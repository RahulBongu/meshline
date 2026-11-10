# Meshline Noise design note — **PROPOSAL, awaiting review (Phase D gate)**

> **Status (D2, protocol + storage level):** the design was **approved with
> amendments** and the **protocol and storage layers are now implemented** on
> branch `d2-noise`: the Noise state machine, the explicit transport nonce and
> the 128-nonce replay window, the session store and the handshake rate limits
> are coded and tested against the simulator. What is **not** done yet is the
> wiring into `BleTransport` and the UI — that waits for the D1 phone test to
> pass, so the D2 code is currently reachable only from the tests.
>
> **Amendment A2 (D2, library limitation):** §6.1 says a session resumes in
> `ESTABLISHED` across a restart because the Noise transport cipher state is
> serialised into Room. `noise-java` 0.1.1 **cannot export that state**:
> `HandshakeState` has no key getter, `CipherState` only accepts a key, and
> `SymmetricState` (which computes the transport keys) is a package-private
> class. We do not reach into the library's internals to fish the keys out.
> Instead:
>   - the `noise_sessions` table keeps the identity binding, the state, the
>     nonce counters and the replay window — everything the library allows —
>     and has a `transportKey` column ready for a library that can export the
>     material;
>   - a session restored from the store is treated as **session loss** and
>     **silently re-handshakes**, which is exactly the recovery path §6.1
>     already specifies for a stale or rotated session;
>   - ephemeral private keys are still never persisted (§3.5) — unchanged.
>
> Consequence for the product: after an app restart the peer sees one extra
> handshake round, not a resumed session. The wire format, the handshake, the
> nonce window and the store are unaffected.
>
> **Amendment A3 (D2, wire):** the handshake payload carries a small Meshline
> envelope (version, flags, **step**, Noise length, Noise message, optional
> Ed25519 binding trailer) instead of "one raw Noise message" (§2). The
> `step` byte is needed because a Noise read that fails **destroys the
> handshake state machine** — the transcript hash is mixed before the tag
> check — so the receiver must never guess which message it received. A wrong
> step can only make the read fail (the packet is then rejected as an invalid
> handshake); it can never make a bad message look good. See PROTOCOL.md §6.5.
>
> **Original status text:** design proposal. Phase D should not start until
> this note is reviewed. It pins the decisions that are hard to change
> after code lands: the handshake pattern, how a handshake crosses a multi-hop
> BLE mesh, when sessions re-key, and how handshake rate limits are enforced.
> All cryptographic primitives and the Noise framework itself come from
> reviewed libraries (see §1). We never invent primitives or protocol.
>
> **Amendment (amendment A):** approved with amendments. Decisions folded in:
> handshakes use normal fragmentation (§3.6); blocked peers are dropped before
> budget accounting (§5); silent re-handshake on session loss (§4); transport
> messages carry an explicit nonce with a replay window (§4.1); the handshake
> binds the Noise static key to the Ed25519 identity and checks the senderId
> (§7.1); handshake rate limits/quarantine are per connection (§5); a changed
> key for a verified contact demands explicit user acceptance (§7.2); sessions
> persist encrypted across restarts with store-and-forward semantics (§6.1); the
> `noise-java` version is pinned and official test vectors are run (§9).

## 1. Library and primitive choices

| Concern | Choice | Why |
|---------|--------|-----|
| Noise framework | `noise-java` **pinned to `org.signal.forks:noise-java:0.1.1`** (Maven Central, Signal Foundation fork — see §9(f)) | Explicit Noise API over `Noise_XX_25519_ChaChaPoly_SHA256`; no hand-rolled handshake. Signal's fork is chosen over the older `com.github.auties00:noise-java:1.2` for audit pedigree. |
| X25519 key agreement | BouncyCastle X25519 (already on classpath) via the Noise library | Noise_XX needs X25519; BC is audited for this primitive |
| Ed25519 signing | BouncyCastle Ed25519 | Signs outer packets (PROTOCOL.md §3.1). Separate from X25519 — two long-term key pairs, as the PRD requires |
| Transport AEAD | ChaChaPoly via the Noise session (`ChaChaPoly_SHA256`), not raw BC | Noise manages the ratchet/re-key internally; using raw BC alongside would double-book key scheduling |
| Key storage | Android Keystore (non-exportable where supported) | Private keys never in Room (PROTOCOL.md §7). Fingerprint = `SHA-256(ed25519Pk ‖ x25519Pk)` |

**Explicit non-goals:** No custom DH, no custom AEAD, no custom handshake.
If `noise-java` cannot run on minSdk 26 with its BouncyCastle backend, Phase D
must re-evaluate the *library* (e.g. switch to `noise-c` via a reviewed binding)
and re-review this note — not hand-roll the pattern.

## 2. Handshake pattern: `Noise_XX_25519_ChaChaPoly_SHA256`

- **Pattern:** XX (mutual static authentication, hidden static keys until the
  second message). Both initiator and responder prove possession of their
  long-term X25519 identity keys to each other during the handshake.
- **Static keys:** each node holds **one** long-term X25519 key pair (§1).
  Noise learns the peer's static key from the handshake; the SecurityModule
  records `(senderId → x25519Pk, ed25519Pk, fingerprint)` in Room on first
  successful handshake (TOFU). The fingerprint UI (Peers screen) shows this.
- **Pre-messages / PSK:** none in MVP. There is no out-of-band pre-key
  distribution; a node that has never seen a peer starts a fresh XX handshake
  over the mesh (§3). (Identity verification by QR is P1 — FR-13.)
- **Payloads:** the Noise `XX` handshake messages `-> e`, `<- e, ee, s, es`,
  `-> s, se` are carried as `HANDSHAKE` packet payloads (PROTOCOL.md §6.5).
  Our outer packet sets `recipientId`, leaves `HAS_SIGNATURE` clear (Noise
  authenticates these), and is relayed like data (§3).

### Message flow (initiator = newer session)

```
A (initiator, holds Queued private message for B, no live session)
  | HANDSHAKE(m1: -> e) .................>
  |                                     B creates/resumes session, stores e
  | <................. HANDSHAKE(m2: <- e, ee) — B also proves static s later
  | HANDSHAKE(m3: -> s, se) ...........>
  |  (A now has transport keys; B has them after m3 too — both sides
  |   can encrypt from m3 onward; A treats outgoing as transport after m3)
  | PRIVATE_MESSAGE(Noise transport ciphertext) -->
  | <------------ PRIVATE_MESSAGE(...) ----------
  | RECEIPT(DELIVERED -> A) ....>  (plain signed receipt, §6.4)
```

Replies usually arrive out of order over BLE. Sessions must be state-machine-
driven, not linear (see §4).

## 3. Multi-hop handshakes (the hard part)

A handshake is **three mesh-routed datagrams**, not a connection:

1. **Routing:** `HANDSHAKE` packets are directed (`recipientId` set, unsigned,
   TTL up to 7, dedupe on the handshake's own messageId) and are relayed
   hop-by-hop exactly like `PRIVATE_MESSAGE`. Relays never inspect Noise
   payloads; they only decrement TTL and dedupe. This means a 3-hop handshake
   costs 3 hops × 3 messages ≈ 9 link transmissions, plus retransmits on loss.
2. **Noisy ordering:** link loss, latency spread (simulator-configurable,
   BLE-real ~10s of ms) and 10–200 ms relay jitter can reorder or drop
   handshake steps. The session state machine (§4) tolerates out-of-order
   arrivals by buffering one step ahead and by retransmitting the last sent
   handshake message on a timer.
3. **Retransmit & timeouts:** the initiator retransmits its next handshake
   message every **2 s**, up to 5 times, then expires the attempt and surfaces
   "couldn't establish secure session" to the UI (message stays Queued).
   The responder retransmits step 2 on the same schedule when it has sent it
   and step 3 has not arrived. Duplicate arrivals of the same handshake step
   are idempotent: the Noise library is re-entered only in the correct state;
   otherwise the packet is a dedupe/rate-limit drop (§5).
4. **Concurrent initiators:** if A and B both start a handshake at once
   (both have Queued messages), the node with the **lower senderId (unsigned
   byte compare)** wins and its session is used; the other node abandons its
   attempt and adopts the winner's session. This deterministic tie-break
   avoids session forks and double key schedules. Both sides log the decision
   at verbose level for field debugging.
5. **Persistence across relay reboots:** a half-finished handshake survives
   an app restart only as "session absent". Noise ephemeral keys are **never**
   persisted; a restart means a fresh handshake (§6). This is intentional —
   persisted ephemeral state would weaken forward secrecy for little gain.
6. **Fragmentation:** handshake messages use **normal fragmentation** (the
   same `FRAGMENT`/reassembly path as any packet larger than MTU−3, PROTOCOL §5).
   No special handshake fragmentation exists. The per-hop TTL and dedupe rules
   apply per fragment exactly as for data.

### What relays see

Handshake packets leak `(senderId, recipientId, timestamp, size)` to every
relay, like any directed packet. Noise hides the *contents* (ephemerals and
bound keys), not the fact a handshake is happening. The app must not claim
handshake traffic is undetectable.

## 4. Session state machine (per peer)

Persisted in Room (encrypted, §6); in-memory mirror used while running:

```
ABSENT / INITIATING(m1 sent, awaiting m2)
  → RESP_AWAIT(m2 processed, awaiting m3; we are responder)
  → ESTABLISHED (transport keys live, messages flow, re-key timers armed)
  → CLOSED (peer sent LEAVE, or >15 min without any activity and no Queued msgs)
```

- **ABSENT:** no session expected; a received `m1` while ABSENT moves to
  INITIATING-RESPONDER (process m1, emit m2).
- **INITIATING:** we sent `m1`; a duplicate `m1` from the same peer is ignored
  (or triggers a fresh `m2` if it contains a new ephemeral — see below).
- **RESP_AWAIT / ESTABLISHED:** transport ciphertext is accepted only in
  ESTABLISHED. Anything else is dropped and counted as a protocol violation
  (useful spam signal, §5).
- **Transport message nonces + replay window (§4.1):** see below. The outer
  Meshline messageId is not a nonce; the Noise session's cipher nonce is.

### 4.1 Transport nonces and the replay window

The mesh loses, reorders and duplicates packets, so transport messages carry an
**explicit, AEAD-authenticated nonce** (the Noise session's cipher nonce) and a
**replay window** on the receiver:

- **Window size:** 128 nonces (the standard AEAD "skip-list" window — large
  enough to absorb BLE reorder + duplicate + late arrivals, small enough to
  bound the memory of a DHT-pressure attacker). Window state is per direction:
  `nextExpected` plus a 128-bit bitmap of received future nonces.
- **Acceptance:** a ciphertext whose nonce is `nextExpected` is accepted and
  the window advances (sliding past any gaps that later fill). A nonce inside
  the window that was already seen is a replay (drop, count it). A nonce
  beyond `nextExpected + 128` is too far ahead — drop and count as protocol
  violation (possible forging/gap attack). A nonce older than the window is a
  replay (drop, count).
- **Interaction with dedupe:** the outer dedupe cache and the replay window
  are independent. Dedupe stops the *same packet bytes* being processed
  twice; the replay window stops a *different packet* (fresh outer header)
  that reuses an inner nonce from being decrypted twice. Both are required
  because an attacker (or BLE) can re-wrap a ciphertext in a fresh outer
  packet with a new timestamp.
- **Nonce persistence:** the window is in-memory only and resets on restart;
  after a restart the session is re-established (§6.1), so a pre-restart
  window is never trusted across a session boundary.

## 5. Handshake rate limits (aggressive by design)

Handshakes are the cheapest amplification in the protocol (3× fan-out
multiplied by mesh relay fan-out), so they get the strictest limiter in
PROTOCOL.md §8 rule 7. **Buckets and quarantine are per connection**
(the immediate neighbour the packet arrived on), not per claimed `senderId` —
a claimed header field is untrusted until the Noise static key is verified
(§7.1), so a spoofed senderId must not be able to consume another peer's
budget or quarantine an innocent peer.

| Bucket | Limit (Phase D starting point) |
|--------|-------------------------------|
| Per connection | **5 handshake packets / 10 s**; **20 handshake starts / hour**; cooldown **60 s** after a successful handshake before a *new* start is allowed |
| Per node | **30 handshake packets / 10 s** accepted; beyond that, drop silently |
| Invalid handshake | **3 strikes in 5 min** per connection → 10 min handshake-quarantine for that connection |

Over-limit handshakes are **dropped silently** (no error reply — errors are
amplifiers). Repeatedly-invalid handshakes (e.g. correct outer packet, garbage
Noise bytes) increment a separate "protocol violations" counter per connection;
>10 in an hour is surfaced on the mesh-status screen as a health hint, never as
an accusation. **Blocked peers are dropped before budget accounting** (the
block check runs first; a blocked peer burns no per-connection or per-node
handshake budget), consistent with PROTOCOL.md §8. These are MVP starter
values; Phase D field tests (30 nodes, crowded room) may retune them, but any
change must be justified by measured relay-load numbers (simulator + field
counts), not by assumption.

## 6. Re-keying and forward secrecy

Noise gives forward secrecy per session (`ee` + ephemeral ratchet), but
long-lived transport keys still need limits:

- **Message count:** re-key (new Noise handshake, initiator = the node that
  just re-keyed) after **1,000 transport messages** per direction or after
  **2^14** however the library's recommended limit lands — Phase D uses the
  library's own `MAX` counter as the source of truth and adds a Meshline cap
  of 1,000 as an belt-and-braces bound. Both bounds trigger the same action.
- **Time:** re-key after **24 h** of session age, or after **7 days** of
  wall-clock since the session's first transport message — whichever comes
  first. The *first* peer to notice starts the re-handshake; the other adopts
  it via the tie-break in §3.4.
- **Key erasure:** when a session is replaced, old transport cipher states are
  wiped from memory immediately and ephemeral private keys are never written
  to disk. Room keeps the *session-snapshot* note in §6.1; in the MVP the
  snapshot is **cipher state** (see §6.1) rather than only fingerprints.

### 6.1 Session persistence across restarts and store-and-forward

**Sessions persist (encrypted) across app restarts.** On ESTABLISHED, the
SecurityModule serialises the Noise session's transport + handshake cipher
states (chaining keys, counters, replay window) into Room under SQLCipher — the
database key itself is wrapped by the Android Keystore, so the ciphertext on
disk is useless without the device key. Ephemeral *private* keys are still
never written; only the session's derived cipher state is stored.

- **Restart behaviour:** on boot, sessions are reloaded from Room and resume
  in ESTABLISHED without a new handshake. Half-finished handshakes (§3.5)
  are *not* persisted and restart as ABSENT (fresh handshake).
- **Silent re-handshake on session loss:** if a delivered transport packet's
  AEAD verification fails (stale/rotated state, peer upgraded, counter drift
  beyond the replay window), the node **silently starts a fresh XX handshake**
  (no user-visible error) and queues the *new* messages, rather than dropping
  the conversation or surfacing a cryptographic error to the user. The peer
  sees a normal re-handshake (rate-limited as usual, §5). Repeated failures
  (>5 in an hour) switch to the honest "couldn't establish session" UX.
- **Interaction with store-and-forward (P1, FR-12):** MVP does not yet
  store-and-forward private messages to absent peers. When it lands, queued
  *ciphertexts* (already transport-encrypted under the persisted session) are
  cached in the outbox for up to 12 h (configurable) and replayed on
  reconnection without a new handshake, provided the session has not been
  re-keyed in the meantime. If the nonce window has moved past a cached
  message, that message is re-encrypted under the live session or marked
  expired — never sent with a stale nonce.
- **Panic wipe (§7)** erases all sessions, all peer fingerprints, the outbox
  (Queued messages), and both long-term key pairs. The device returns to
  "never seen anyone" with a fresh random nickname.

## 7. Key storage and identity

- **Generation:** first launch, on the UI thread's first frame: generate
  X25519 + Ed25519 pairs. Private keys in Android Keystore
  (`KeyGenParameterSpec`, non-exportable, `USER_AUTHENTICATION_REQUIRED=false`
  for background relay, `STRONG_BOX_BACKED` where available — do not fail the
  app if StrongBox is absent).
- **senderId** = `SHA-256(ed25519Pk)[0..8)` — the same derivation Phase A/B
  already uses. Handshake transcripts bind the X25519 static key; the outer
  packets' senderIds bind the Ed25519 identity. The SecurityModule must check
  that the handshake's claimed static keys **match** the keys bound to that
  senderId (stored on first ANNOUNCE) — a mismatch aborts the handshake and
  quarantines the sender (impersonation defence, TOFU).

### 7.1 Binding the Noise static key to the Ed25519 identity

The handshake **payload** (not just the Noise transcript) binds the two
long-term identities together. The first `HANDSHAKE` message from each side
carries a small binding trailer after the Noise bytes:

```
ed25519Pk(32)  the signer's long-term Ed25519 public key
```

On receiving it, the SecurityModule checks **`SHA-256(ed25519Pk)[0..8)` equals
the outer packet's `senderId` header** before trusting any rate-limit or
quarantine accounting that names that sender. A mismatch is an impostor
attempt: the handshake aborts, the packet is counted as a protocol violation
on that *connection* (§5), and nothing is stored under the claimed identity.
This closes the gap where a peer with a valid X25519 key but a different
Ed25519 identity could otherwise claim the victim's senderId.

### 7.2 Changed key for a known (verified) contact

If a contact has been **verified** (fingerprint pinned by QR/in-person, P1)
and a later handshake presents a *different* Ed25519/X25519 key pair for the
same senderId, the app must:

1. **Show a prominent warning** ("Identity changed — possible impersonation" +
   old vs. new fingerprint, no silent trust).
2. **Require explicit user acceptance** before any new session is used for
   private chat; until then the contact's private traffic is held (Queued) and
   public traffic shows the warning badge.
3. The old fingerprint stays displayed as "previously verified" until the user
   accepts or blocks; a panic-wipe session is the only auto-acceptance path.

An unverified (TOFU) contact whose key changes produces a quieter notice
("New fingerprint — was this expected?") but the same explicit-accept gate for
private chat.

## 8. Queued / Sent / Delivered / Read states and receipts

- An outgoing private message enters **Queued** until a session is
  ESTABLISHED and the ciphertext is handed to the router. Then **Sent**
  ("Sent to mesh" — the honest label; BLE offers no delivery guarantee).
- The destination encrypts a signed `RECEIPT(DELIVERED)` (PROTOCOL.md §6.4)
  back to the sender when the plaintext is accepted by the UI layer; the
  sender marks the message **Delivered**. **Read** is a separate receipt the
  UI sends when the message is actually opened. Public messages never get
  receipts (they have no addressed destination).
- Receipts are routed as ordinary directed packets; if the receipt's sender
  is no longer reachable, it simply times out with the message stuck at
  Sent/Delivered — the UI must say so rather than spin forever.

## 9. What Phase D must still decide (open before code)

1. **Version-pinned library + official vectors.** Phase D pins
   **`org.signal.forks:noise-java:0.1.1`** in the version catalog. (Checked
   against Maven Central metadata at this amendment's writing: the Signal
   fork publishes `0.1.0` and `0.1.1`; `0.1.1` is the release. A different
   coordinate or host for the same upstream fork must be documented here
   before use.) Before any app code, a JVM unit test runs the **official Noise
   test vectors for `Noise_XX_25519_ChaChaPoly_SHA256`** (readme-vectors
   from the Noise spec, 3-message exchange) through `noise-java` and asserts
   byte-exact handshake messages and transport ciphertext. Failing vectors
   block Phase D — a non-conformant Noise implementation cannot be shipped
   even behind the SecurityModule interface.
2. **Backend check on minSdk 26** (BouncyCastle backend, Android 14 pacer).
   If blocked, choose the fallback (mock first behind the same interface) and
   re-review.
3. **Handshake cost over 3 hops at 512-byte MTU:** three `HANDSHAKE` packets
   ≈ (32+48+16+16+64) bytes typical — verify against `noise-java` message
   sizes and fragment counts in the simulator *before* wiring BLE.
4. **Recovery UX** for "session couldn't be established after 5 retries":
   honest copy ("BLE is best-effort — try moving closer"), no spoofed errors
   (the *silent re-handshake* in §6.1 covers transient loss; only repeated
   failures reach this UI).

---

**Review gate:** approve, amend, or reject this design before Phase D
implementation begins. Amendments to §§3–6 that change on-the-wire behaviour
(handshake count, packet mapping, re-key thresholds that alter traffic)
also require a PROTOCOL.md amendment and new test vectors (§11 change
policy). Threshold retunes that do not touch the wire (rate limits only)
are behaviour-only changes and need a PROTOCOL.md note, not a version bump.
