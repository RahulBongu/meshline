# Meshline Protocol — v1

> Status: **Draft v1 for Phase D1/E (Step 0 amendments).** (Phase B: signing input TTL-invariant §3.1;
> TTL unauthenticated + highest-TTL dedupe; signature-first router ordering;
> per-connection rate limits. Phase C: BLE service/characteristic UUIDs are
> frozen — see §9. Fixed 32-byte header unchanged. Phase D1/E drafts (§6.1 avatarIndex,
> §6.6 QR, §6.7 groups) are specified here but **not yet coded** (Step 0 is docs only).)
> **Re-plan:** D1 identity/profile/QR/contacts, D2 Noise/private chat/storage/panic wipe,
> E group chat, F foreground/service polish. Noise handshake design is still gated in
> `NOISE_DESIGN.md`; group design is in `GROUPS_DESIGN.md`. This
> document is the single source of truth for the
> wire format. `PacketCodec` implements exactly what is written here; the JSON test
> vectors in `app/src/test/resources/test-vectors.json` are generated from the codec
> and must stay byte-identical to this spec. If they ever disagree, this file wins
> and the codec is fixed.
>
> Design constraints inherited from the PRD and session brief:
> - Binary, **big-endian** everywhere (network order).
> - No INTERNET. Everything travels over one BLE characteristic
>   (Write Without Response + Notify); see §9.
> - Max TTL is **7**. Anything claiming TTL > 7 is invalid.
> - `senderId` / `recipientId` are **8-byte truncated SHA-256** of the sender's /
>   recipient's long-term Ed25519 public key (they double as the ephemeral
>   session identity — see §7). The value `0xFFFFFFFFFFFFFFFF` means "broadcast".
> - Public messages are **signed, not encrypted**. Private messages are
>   Noise-encrypted *payloads* — the outer packet is still a normal Meshline packet.

---

## 1. Packet layout

```
offset  size  field          notes
------  ----  -------------  -----------------------------------------------
0       1     version        MUST be 0x01 for v1.
1       1     type           PacketType code, §2.
2       1     ttl            Hops remaining. 0..7. 0 = do not relay.
3       1     flags          Bitfield, §3.
4       8     timestampMs    Unix epoch milliseconds, big-endian unsigned.
14      2     payloadLen     Big-endian unsigned. Payload bytes that follow.
16      8     senderId       Truncated SHA-256(pubkey)[0..8). Never zero.
24      8     recipientId    Present ONLY if FLAG_HAS_RECIPIENT is set.
..      var   payload        payloadLen bytes.
..      64    signature      Present ONLY if FLAG_HAS_SIGNATURE is set.
```

- **Header size (no recipient):** 24 bytes (`4 + 8 + 2 + 8` + 2 spare? — see "size
  check" below).
- **Header size (with recipient):** 32 bytes.
- **Minimum packet size:** 24 bytes (empty payload, broadcast, unsigned).
- **Maximum packet size (enforced by the codec):** `MAX_PACKET_BYTES = 64 * 1024`.
  Anything larger is *split* by fragmentation (§5) before it can be encoded, never
  truncated silently. The BLE layer alone must never see a packet bigger than the
  negotiated MTU payload; it fragments first (§5).

> **Size-check note:** `4 (version+type+ttl+flags) + 8 (timestamp) + 2 (len) + 8 (sender)`
> = **22 bytes**. Adding the always-present 8-byte `recipientId` is *not* optional
> in the fixed layout: v1 keeps the recipient field **always present** at bytes
> 24..32 to keep the header fixed-size and the parser trivial. `FLAG_HAS_RECIPIENT`
> tells you whether the value is meaningful (`!= broadcast`) or whether the packet
> is a broadcast. The table above therefore reads as: bytes 22..24 are part of the
> fixed header (`2 bytes padding`, MUST be zero, reserved for future use). The
> **canonical fixed header is 32 bytes, always**, and the parse offsets are:
>
> ```
> 0  version(1)  1  type(1)  2  ttl(1)  3  flags(1)
> 4  timestampMs(8)   12..14 reserved(2, zero)
> 14 payloadLen(2)    16 senderId(8)    24 recipientId(8)
> 32 payload(payloadLen)   32+len signature(64, iff FLAG_HAS_SIGNATURE)
> ```
>
> This keeps big-endian offsets aligned to the field order in the PRD table while
> giving one fixed header size. The padding bytes are checked on decode: non-zero
> padding is a decode error (fail-closed), so a future v2 can claim them safely.

## 2. Types

| Code   | Name            | Meaning |
|--------|-----------------|---------|
| `0x01` | `ANNOUNCE`      | Peer capability / identity hello. Sent on every new connection and ~every 30 s with jitter. Payload: §6.1. |
| `0x02` | `PUBLIC_MESSAGE`| Nearby broadcast chat text. Signed, **not** encrypted. Payload: UTF-8 text, ≤ 500 chars (§6.2). |
| `0x03` | `PRIVATE_MESSAGE`| One-to-one Noise-AEAD ciphertext. `recipientId` MUST be set and != broadcast. Payload: Noise transport ciphertext (§6.3). |
| `0x04` | `CHANNEL_MESSAGE`| Reserved for Phase 2 (channels, PRD FR-11). Codec accepts and round-trips it; router does not special-case it in MVP. |
| `0x05` | `RECEIPT`       | Delivery / read receipt for a private message. Payload: §6.4. |
| `0x06` | `FRAGMENT`      | One BLE chunk of a larger packet (§5). Codec handles it; the app layer never sees fragments, only reassembled packets. |
| `0x07` | `LEAVE`         | Graceful "I'm going offline". Best-effort; peers also time out announcements. |
| `0x08` | `HANDSHAKE`     | Noise handshake message carried over the mesh. Payload: one Noise handshake message (§6.5). |
| `0x09` | `GROUP_MESSAGE` | *(draft E)* Signed + encrypted group ciphertext. Payload: §6.7. `recipientId` = 8-byte group tag. `HAS_SIGNATURE` set; signature must be a current roster member. |
| `0x0A` | `GROUP_CONTROL` | *(draft E)* Signed group control (leave/rename/roster). Payload: §6.7. `recipientId` = group tag. |
| `0x00` | *(unknown)*     | Reserved / invalid on decode. |
| `0x0B..0xFF` | *(unknown)* | Invalid on decode → drop silently (forward-compatible: unknown types MUST NOT crash the app, only be ignored). Old nodes drop `GROUP_MESSAGE`/`GROUP_CONTROL` as unknown (§6.7). |

Unknown type codes are a **soft error**: the packet is dropped, never relayed,
never delivered, and the receiver stays alive. Version != 1 is a **hard error**:
drop and do not relay (preserves the ability to bump `version` without older
clients relaying new packets they could misinterpret).

## 3. Flags

| Bit    | Name                 | Meaning |
|--------|----------------------|---------|
| bit 0  | `HAS_RECIPIENT`      | Recipient field is meaningful. If clear, `recipientId` MUST be the broadcast value `0xFFFFFFFFFFFFFFFF`. If set, `recipientId` MUST NOT be the broadcast value. |
| bit 1  | `HAS_SIGNATURE`      | Trailing 64-byte Ed25519 signature is present. **Signature covers the entire packet except the TTL byte (offset 2) and except the signature itself.** TTL changes on every relay hop (§8) while the signature must remain valid end-to-end. Canonical signing input (`signingInput`) is the full wire encoding with the TTL byte set to `0x00` and the trailing 64-byte signature stripped (flags byte keeps `HAS_SIGNATURE` set, as on the wire). `PacketCodec.signingInput` implements exactly this; signers sign it, verifiers recompute it after decode. See §3.1. |
| bit 2  | `COMPRESSED`         | Payload is LZ4-compressed (PRD FR-14, P1). **MVP: encoder never sets it, decoder rejects it as UNSUPPORTED_FLAG** (fail-closed until Phase 2 wires it end-to-end so we never silently ship compressed bytes to a peer that would misread them). |
| bit 3  | `FRAGMENTED`         | Reserved. v1 does not set it; fragmentation uses `type == FRAGMENT` instead (§5). Decoder rejects it as UNSUPPORTED_FLAG. |
| bit 4..7 | *(reserved)*       | MUST be zero on encode; MUST NOT be set on decode — a packet with a reserved bit set is invalid. Fail-closed. |

**Who sets the signature bit?** Every `ANNOUNCE`, `PUBLIC_MESSAGE`, `RECEIPT` and
`LEAVE` MUST be signed (`HAS_SIGNATURE = 1`). `PRIVATE_MESSAGE` outer packets are
**unsigned** — the inner Noise ciphertext is already authenticated, and signing the
outer packet would leak a stable long-term identity to every relay that handles it
(metadata minimisation). `FRAGMENT` and `HANDSHAKE` packets are unsigned — fragments
are authenticated when the reassembled packet is verified, and handshake messages
carry their own Noise authentication.

### 3.1 Signature input is TTL-invariant

TTL decrements on **every** relay hop (§8 rule 4). If the signature covered the
TTL byte, every relay would invalidate the signature and the destination could
never verify the origin. Therefore:

```
signingInput = wire bytes of the packet, with byte 2 (TTL) set to 0x00,
               and the trailing 64-byte signature (if present) stripped.
```

All other header bytes (version, type, flags, timestamp, reserved, payloadLen,
senderId, recipientId) and the entire payload **are** covered. The flags byte
keeps `HAS_SIGNATURE` set in the signing input, exactly as on the wire, so a
verifier can recompute the input without special-casing flags.

- **Message ID excludes TTL (and flags, recipientId, signature).** The dedupe
  cache keys on the message ID, so the same packet relayed at TTL 7, then 6,
  then 5, is recognised as one message and is delivered once per node. If TTL
  were part of the ID, every hop would look like a new message and dedupe
  would fail.
- **TTL is unauthenticated.** It is not covered by the signature (§3.1) because
  it changes per hop. Any relay (or attacker) can raise or lower it within the
  hard cap of 7. The damage of inflating TTL is bounded by the cap and by
  dedupe: an inflated copy is still the same message ID, so it cannot cause a
  second local delivery. Instead the dedupe cache stores the **highest TTL
  seen per message ID**; a copy arriving with a higher TTL relays once more
  (without re-delivering), while a copy with a lower/equal TTL is a plain
  duplicate. A copy arriving with TTL 1 first ("TTL pinning") delivers locally
  but never relays; a later higher-TTL copy rescues relaying exactly once.
  TTL *lowering* by a malicious relay cannot be prevented, only bounded —
  the destination may still miss the message, which is the best-effort
  delivery contract.
- **Test vector:** `test-vectors.json` carries the same signed public message at
  TTL 7 and TTL 3 (`public_message_broadcast_signed` / `..._ttl3_low`). Both
  vectors must decode to the same `messageId` and the same
  `PacketCodec.signingInput(...)` bytes, even though their wire bytes differ in
  exactly one byte (offset 2). The `PacketCodecTest` test
  ``same packet at two TTLs shares messageId and signingInput`` pins this.

## 4. Message ID, sender/recipient IDs, and timestamps

### Message ID
```
messageId = SHA-256( senderId(8) ‖ timestampMs(8, BE) ‖ type(1) ‖ payloadLen(2, BE) ‖ payload )
```
- The full 32-byte hash is the ID. The router's dedupe cache stores the full hash
  (or its first 16 bytes if memory-constrained — MVP stores the full 32 bytes in a
  `Set<ByteArray>` via a `LruCache`-style structure; see Phase B).
- Because the payload is inside the hash, two packets with the same sender/timestamp
  but different bodies are distinct messages. A replay of the *same* bytes
  (same ID, same signature) is correctly deduped. This satisfies the PRD definition
  ("hash of senderId + timestamp + type + payload hash") — hashing the length-prefixed
  payload is strictly stronger than hashing `payload hash` alone and is byte-compatible
  for all v1 payloads.
- **TTL, flags, recipientId and signature are not inputs to the hash.** Only
  `senderId ‖ timestamp ‖ type ‖ payloadLen ‖ payload` contribute, so a packet
  relayed with a lower TTL keeps the same message ID (§3.1).

### senderId / recipientId
```
senderId = SHA-256(longTermEd25519PublicKey)[0..8)    // big-endian, 8 bytes
```
- Recipient IDs are computed the same way from the *recipient's* public key.
- An **ephemeral peer ID** may be substituted per session for anti-tracking
  (PRD §12). MVP uses the stable truncated-hash ID; rotation lands with the
  SecurityModule in Phase D and is internal to the node — it changes how `senderId`
  is *chosen*, never how it is *encoded*.

### Timestamps
- `timestampMs` is the sender's wall-clock epoch milliseconds at encode time.
- **Loose validation window (MVP):** reject if `timestampMs == 0`, if
  `timestampMs > now + 24h` (impossible future), or if `timestampMs < now - 7d`
  (replay / stale). The wide past window tolerates clock drift and store-and-forward
  delay; the dedupe cache + signatures are the real replay defence. This is
  documented as a *loose* window in the PRD precisely because phone clocks drift.
- Fragments and receipts use the timestamp of the *original* packet they relate to
  where relevant (§5, §6.4); the encode-time timestamp is still the header field.

## 5. Fragmentation

BLE MTU is negotiated up to 512 bytes, so the usable payload per GATT write is
`MTU - 3`. A packet larger than that MUST be fragmented. Fragmentation is a
**transport concern**: the router speaks in whole `MeshPacket`s, the codec turns
them into `FRAGMENT` packets, and BLE sends each fragment as one GATT write.

### Fragment packet (`type == FRAGMENT`, unsigned, broadcast/directed as the original)
Payload layout (all big-endian):
```
offset  size  field
0       32    originalMessageId   SHA-256 message ID of the unfragmented packet
32      2     index               0-based fragment index
34      2     total               total fragment count (1..1000)
36      var   chunk               slice of the *entire encoded* original packet
```
- The chunk is a slice of the fully-encoded original packet bytes (header + payload
  + signature), **not** of the bare payload. This means reassembly is just
  concatenation in index order, then one normal `decode()` — no special
  header-reconstruction logic, and a tampered fragment corrupts the signature check
  on the reassembled packet.
- `total` fragments are reassembled in order; duplicates are ignored; a missing
  index after **30 s** aborts the reassembly and the partial data is discarded
  (PRD §11.9). Reassembly state is bounded: max 1000 fragments per original packet,
  max 8 concurrent reassemblies per peer, max 64 KiB per reassembly.
- Edge case: a packet that already fits in one BLE write is sent as itself, never as
  a single-fragment `FRAGMENT`. `total == 1` is therefore invalid on decode.
- `index < total` MUST hold; `index >= total` is a decode error.

### Implementation surface (Phase A)
`Fragmentation.split(packetBytes, maxChunkBytes): List<MeshPacket>` and a stateful
`Reassembler(ttlMillis = 30_000)` class with `offer(fragment): ByteArray?` /
`isComplete(messageId)`. Both are pure Kotlin, no Android dependencies, directly
unit-testable.

## 6. Payloads (MVP)

All payloads are opaque bytes at the codec layer; the shapes below are the
contract that higher layers (SecurityModule, MessagingService) must respect.
The codec round-trips any bytes, but a sender that puts the wrong shape in a
known type is a logic bug, and a receiver MUST validate before acting.

### 6.1 ANNOUNCE
```
nicknameLen(1)  nickname UTF-8 (1..32 chars, sanitised, no control chars)
ed25519Pk(32)  sender's signing public key
x25519Pk(32)   sender's key-agreement public key
capabilities(1) bit0 = relayEnabled, bit1 = supportsNoise, bit2 = supportsChannels
avatarIndex(1)  index into the AVATAR TABLE (§6.1.1, draft D1). 0xFF = neutral default.
intervalUnits(1)  sender's current announce interval in 10 s units (draft D1;
                  3 = 30 s, 6 = 60 s, 12 = 120 s). Inside the signed payload.
                  Receivers MUST accept only {3, 6, 12} and reject anything
                  else (droppedInvalid, never cached).
```
**Avatar is cosmetic, not an identity.** The avatarIndex is a display hint only; it
never affects senderId, fingerprint or verification. Receivers MUST ignore values
outside the table (render neutral) and MUST NOT reject the ANNOUNCE for an
unknown avatar. Nickname/keys remain the identity surface.

### 6.1.1 AVATAR TABLE (draft D1, fixed append-only)

| Index | Fruit |
|------|-------|
| 0 | apple |
| 1 | green apple |
| 2 | pear |
| 3 | orange |
| 4 | lemon |
| 5 | banana |
| 6 | watermelon |
| 7 | grapes |
| 8 | strawberry |
| 9 | melon |
| 10 | cherries |
| 11 | peach |
| 12 | pineapple |
| 13 | kiwi |
| 14 | tomato |
| 15 | avocado |

- The table is **fixed and append-only**: never renumber or repurpose an entry.
  Index `0xFF` (and any unassigned value, e.g. 16..254 until assigned) renders a
  neutral default icon on old clients. New entries are appended after 15.
- Deferred (NOT in v1): mango, coconut, blueberries — added at index 16+ **only
  if** the app bundles an offline emoji font (androidx.emoji2 bundled provider;
  never the downloadable-fonts provider, which needs network). Until then these
  fruits MUST NOT be advertised.
- Sent on (re)connect and then roughly every 30 s with jitter. Contains no secrets —
  public keys are public, the IP-style metadata is the senderId (truncated hash).
- Receivers use it to populate the peer list (nickname, hops, signal). Signal
  strength is *not* in the packet; it comes from the BLE scan callback.
- The nickname is **display-only**; identity is the public key (fingerprint =
  SHA-256(ed25519Pk ‖ x25519Pk) hex, displayed in the Peers screen).

### 6.2 PUBLIC_MESSAGE
```
text UTF-8, 1..500 characters (NOT bytes — enforced before encode)
```
- Signed (§3), relayed as broadcast, delivered to every node that sees it.
- The UI MUST label these "public — anyone in range can read this" (PRD §12).

### 6.3 PRIVATE_MESSAGE
```
version(1)   = 1
flags(1)     = 0
nonce(8)     the Noise transport nonce, big-endian
ciphertext   ChaCha20-Poly1305 ciphertext (plaintext + 16-byte tag)
```
- Outer packet: `recipientId` = recipient, `HAS_SIGNATURE` clear (the Noise
  transport authenticates the content).
- The nonce is **explicit on the wire** (§4.1 of NOISE_DESIGN.md): the mesh
  reorders and duplicates packets, so the receiver must know which nonce a
  ciphertext used before it runs the replay window.
- **How the nonce is applied.** Noise's ChaChaPoly uses a 64-bit counter as the
  AEAD nonce, and that counter is *independent of the key*: the key stays
  inside the library's cipher state (which is why the transport state cannot
  be exported — NOISE_DESIGN amendment A2), while the nonce is a value we set
  on the cipher before every operation (`CipherState.setNonce`). The sender
  owns the counter and puts it in the packet; the receiver uses exactly that
  nonce. **Consequence: message N+1 can be decrypted before message N** —
  nothing has to arrive in order, and no key ever has to be reconstructed.
  The library's own counter is a side effect of each operation and is
  overwritten before every use, so our counter is the only authority.
- **Associated data (normative):** the 32-byte packet header with the **TTL
  byte zeroed** (relays decrement it, so the ciphertext must survive any
  number of hops) and the **payload length zeroed** (the payload *is* the
  ciphertext and cannot be part of its own associated data; a truncated
  ciphertext fails the Poly1305 tag check anyway). Everything else — version,
  type, flags, timestamp, sender, recipient — is bound, so a ciphertext cannot
  be replayed under a different sender, a different recipient or a different
  timestamp.
- The receiver runs a **128-nonce replay window** per direction before it
  decrypts: a nonce below the window is a replay (dropped and counted), a
  nonce more than 128 ahead of the expectation is refused as a possible gap
  attack, a nonce inside the window is accepted exactly once.
- **Authenticate first (normative).** The order is: **decrypt (verify the AEAD
  tag) → then judge the nonce.** The replay window and the drift/re-handshake
  logic may only ever act on an **authenticated** message.
  - A **forged** packet (bad tag) is dropped **silently**: it does not move the
    replay window, does not count as a violation, does not change the session
    and does **not** ask for a re-handshake. Without this, anyone could force
    a re-handshake (and burn the handshake budget) with a single made-up
    packet.
  - A **genuine** message whose nonce is more than 128 ahead of the expectation
    is the only thing that triggers the clean re-handshake of §6.1 — our state
    and the peer's have drifted, and the message is authenticated, so we can
    trust that it really came from that peer.
  - Because the associated data binds the timestamp, a ciphertext **re-wrapped
    in a fresh outer packet** (a new timestamp, a new message id) no longer
    authenticates: the AEAD catches that replay. The replay window remains
    the second line of defence and the drift detector.
- Re-key after the Noise library's recommended message count / interval
  (Phase D). Meshline's own bound is **1 000 messages per direction**, plus
  24 h of session age and 7 days since the first transport message.
- Message states: Queued → Sent → Delivered → Read (PRD §13).

### 6.4 RECEIPT
```
receiptKind(1)   1 = DELIVERED, 2 = READ
messageId(32)   the private-message ID being acknowledged
```
- Signed, directed (`recipientId` = original sender). Relays forward it like any
  other directed packet; the dedupe cache applies.

### 6.5 LEAVE / HANDSHAKE
- `LEAVE`: payload = 8 bytes, the sender's `senderId` (redundant with the header,
  kept so a parser that only reads the payload can still log who left).
  Signed, broadcast. Receivers mark the peer offline immediately instead of
  waiting for the announce timeout.
- `HANDSHAKE`: the Noise handshake message plus a small Meshline envelope.
  Unsigned, directed. Relayed like a private message so the handshake can
  traverse multi-hop paths. Handshake packets are rate-limited harder than any
  other type (§8).

```
version(1)   = 1
flags(1)     bit 0 = an Ed25519 binding trailer follows
step(1)      1 = "-> e"        (initiator's first message)
             2 = "<- e, ee, s, es" (responder's answer)
             3 = "-> s, se"    (initiator's final message)
noiseLen(2)  length of the Noise message, big-endian
noise        the raw Noise handshake message
binding      ed25519Pk(32), present when flags bit 0 is set
```

- **Why the envelope:** the Noise library parses a handshake message from its
  own framing, but the mesh needs three things around it: a version, the
  identity binding, and — importantly — **which step** the message is. A Noise
  read that fails *destroys the handshake state machine* (the transcript hash
  is mixed before the tag check), so the receiver must never guess which
  message it got. The `step` byte says exactly which message to feed in. The
  step byte is not secret and not authenticated: a wrong step only makes the
  Noise read fail (the packet is then rejected and counted as an invalid
  handshake), it can never make a bad message look good.
- **Binding trailer (§7.1 of NOISE_DESIGN.md):** the *first* message of each
  side carries the signer's long-term Ed25519 public key. The receiver checks
  that `SHA-256(ed25519Pk)[0..8)` equals the outer packet's `senderId`; a
  mismatch is an impersonation attempt (the handshake is aborted and the
  connection is quarantined). The strong check is the X25519 key the Noise
  handshake itself reveals: it must equal the key already announced for that
  `senderId`, otherwise the peer is impersonating somebody.
- **Prologue:** both sides use the same prologue
  (`"Meshline-D2-NoiseXX-v1"`), which is hashed into the transcript. It is a
  wire constant — changing it invalidates every session.
- **Pattern:** `Noise_XX_25519_ChaChaPoly_SHA256`, implemented by the pinned
  `org.signal.forks:noise-java:0.1.1`. The official Noise test vectors for
  that pattern are run as a unit test (`NoiseVectorsTest`) — a non-conformant
  implementation cannot ship.

### 6.6 QR payload (draft D1, not coded in Step 0)

Personal QR codes (public data only — **never private keys**) let a friend add you
as a verified contact in person. Format:

```
version(1) = 1
ed25519Pk = 32 bytes
x25519Pk = 32 bytes
avatarIndex(1) = AVATAR TABLE index (§6.1.1) or 0xFF for neutral
nickLen(1) = nickname length 1..24 (UTF-8 bytes, sanitised per §6.1)
nickname = nickLen bytes UTF-8
checksum(4) = SHA-256(all preceding bytes)[0..4)
```

- On-wire form: base64url **without padding**, prefixed `meshline://c/`
  (e.g. `meshline://c/AQID...`).
- The fingerprint shown in the UI is always `SHA-256(ed25519Pk ‖ x25519Pk)`.
- **Strict parser** (fail-closed, fuzz-tested in D1): reject wrong version,
  wrong lengths, bad checksum, unsanitised nickname (control chars, >24 bytes,
  empty), or trailing bytes. The scanner must never auto-add a contact — the
  user confirms on the scan-confirmation screen (“Add verified contact?”).
- Display identity in chat is `“<fruit> <nickname> - <4-hex fingerprint>”`
  (e.g. `strawberry Rahul - 7F3A`; the 4 hex chars are the first 4 chars of the
  fingerprint hex). Only QR-verified contacts get a verified badge.

### 6.7 Group packets (draft E, design in GROUPS_DESIGN.md)

Groups are E2E-encrypted with one shared symmetric key per epoch
(ChaCha20-Poly1305, explicit nonce). Two new wire types carry them; full
semantics are in `GROUPS_DESIGN.md`.

- `GROUP_MESSAGE (0x09)`: `recipientId` = **8-byte group tag** (see below),
  **`HAS_SIGNATURE` set; a valid Ed25519 signature from a current roster
  member is mandatory** — a shared epoch key alone cannot authenticate the
  sender, so packets without a verifiable roster-member signature MUST be
  rejected (dropped, never cached) before dedupe/rate-limit/relay (§8).
  Payload (encrypted, §6.7.1): `epoch(2 BE) | nonce(8) | ciphertext(N)`.
  Plaintext (before AEAD): the 500-char-limit group text; the sender is
  resolved locally from the roster (never trusted from the wire). AEAD
  associated data is **`groupTag(8) ‖ epoch(2 BE) ‖ senderId(8)`** — both sides
  MUST compute it identically (see §6.7.1). Only members holding the epoch
  key can decrypt; everyone else drops at the tag check.
- `GROUP_CONTROL (0x0A)`: `recipientId` = group tag, **signed** (§3.1, TTL-
  invariant). Payload (NOT encrypted — roster is semi-public, signature is the
  authority): `epoch(2) | kind(1) | nameLen(1) | name | roster(…)` where
  `kind` = 1 leave, 2 rename, 3 roster-update. Roster entries list member
  Ed25519 pubkeys (≤20). Only the admin may change membership; control
  signature + admin identity are verified before any action.
- **Group tag:** `HKDF(epochKey, info="meshline-group-tag", len=8)` (HKDF-SHA256
  per RFC 5869). Relays see only the tag, so cross-group traffic cannot be
  linked; the tag **changes every epoch**, so epochs cannot be linked either.
  Members check the tag first (a fixed set of 8-byte compares) and drop other
  groups' traffic **before any decryption**.
- **Unknown types (forward compatibility):** after signature verification,
  the router **relays** unknown packet types instead of dropping them (§2,
  §8 rule 1a). A node that sees `0x09`/`0x0A` (or any future type) it does not
  understand still forwards it, provided the signature verifies against a
  key it already knows (peer/roster pin for `senderId`). Without a known key
  the packet cannot be verified and is dropped. Pre-E nodes therefore relay
  group traffic they cannot read — groups are invisible to old *clients* but
  not opaque to old *relays*. Old nodes never decrypt group traffic.
- **Invites** do NOT use either of these types: they travel only as Noise
  `PRIVATE_MESSAGE`s (§6.3) with payload `group invite (groupId, name, icon,
  epoch, epoch key, roster)`. See `GROUPS_DESIGN.md` §3.

#### 6.7.1 AEAD associated data (draft E, normative when implemented)

```
ad = groupTag(8) ‖ epoch(2 BE) ‖ senderId(8)   // 18 bytes
```
- `groupTag` is the packet's `recipientId` (8 bytes, §6.7).
- `epoch` is the 2-byte big-endian epoch in the payload header.
- `senderId` is header bytes 16..24 (truncated hash of the sender's Ed25519 key).
Both signer and receiver MUST compute `ad` identically. Tampering with tag,
epoch or senderId breaks AEAD even if the ciphertext is untouched.

#### 6.7.2 Q: does a GROUP_MESSAGE payload decrypt to a JOINED inner packet?

No. `GROUP_MESSAGE` is a *single* outer packet whose payload is AEAD
ciphertext. There is no inner MeshPacket; the plaintext is the group text
plus the sender reference. Decryption happens exactly once per delivery
(after tag + epoch-key lookup), and replays are caught by the per-sender
nonce window (see `GROUPS_DESIGN.md` §5).

## 7. Identity & keys (summary — details in Phase D)

- On first launch the `SecurityModule` generates:
  - **X25519** (Curve25519) key pair for key agreement (Noise uses this), and
  - **Ed25519** key pair for signing.
- Raw Ed25519/X25519 private keys **cannot be imported into the Android Keystore**
  on minSdk 26. They are therefore encrypted with a non-exportable Keystore
  AES-256-GCM key (`meshline-key-wrap`, one random IV per key) and only the
  ciphertext is kept in SharedPreferences; keys never enter Room and never enter
  a packet. Panic wipe deletes both the ciphertext and the wrap key.
- The **SQLCipher passphrase** is 32 bytes from `SecureRandom`, generated once,
  wrapped by a second Keystore key (`meshline-db-wrap`). It is never derived from
  a PIN, nickname, device id, or any static value, and is never logged.
- **Backup is disabled**: `android:allowBackup="false"` plus
  `data_extraction_rules.xml` excluding every domain (`database`, `sharedpref`,
  `file`, `root`) from both cloud backup and device transfer, so identity keys,
  contacts, and messages cannot leave the device through backup.
- **Secure-storage reset (D1, fail-closed):** if the Keystore wrap key is
  missing/invalidated (OS upgrade, OEM key loss) or the SQLCipher database
  cannot be opened/decrypted, the app MUST recover without crashing:
  1. discard the unusable wrap-key entry and generate a **fresh Ed25519+X25519
     identity** (new `senderId`/fingerprint),
  2. wipe the wrapped DB key and `meshline.db*` files, so contacts start empty,
  3. record a one-shot `SecureStorageStatus` reason (`KEYSTORE_INVALIDATED` or
     `DB_UNREADABLE`) and show a clear **“secure storage was reset”** dialog the
     next time the UI opens.

  Peers will observe a **key change** (new senderId/fingerprint) after an
  identity reset; D2 adds the explicit key-change warning for pinned contacts.
  A DB-only reset keeps the identity and only empties contacts. The app never
  silently presents an undecryptable store as empty without this notice.
- `senderId` = truncated SHA-256 of the *Ed25519* public key (identity anchor).
- **Secure sessions (D2, protocol + storage level):** established Noise sessions
  and the peer records they carry are stored in the `noise_sessions` table of
  the same SQLCipher-encrypted database (so the rows are ciphertext at rest
  under the Keystore-wrapped DB key). **Ephemeral private keys are never
  stored** — only the derived session state, the identity binding, the nonce
  counters and the replay window. The table was added in DB version 2 with a
  non-destructive `MIGRATION_1_2` (contacts survive the upgrade).
  - **The `transportKey` column is never written.** `noise-java` 0.1.1 exposes
    no way to export the Noise transport key material (`HandshakeState` has no
    key getter and `SymmetricState` is package-private), so there is nothing
    meaningful to store — writing key material the app cannot use would only
    add a second secret to erase in a panic wipe. The `SessionStore` mapping
    always writes NULL, even when a snapshot carries a key; the column exists
    only so a future library with an export path needs no schema change.
  - **Limitation (D2, documented):** because the transport cipher state
    cannot be written down, a session restored from the store **re-handshakes
    silently** — the same recovery path NOISE_DESIGN.md §6.1 prescribes for
    session loss — instead of resuming in place.
- **Store-and-forward outbox (D2, FR-12):** the `outbox` table (DB version 3,
  `MIGRATION_2_3`) holds the **plaintext** of private messages that wait for
  a session, protected by the same SQLCipher database. The message is
  **encrypted at send time**, under the session that is live then — so it
  never carries a stale nonce and never belongs to a session that no longer
  exists. **Relays never cache private ciphertext**: a relay forwards a
  private message and forgets it; store-and-forward for private messages is
  sender-side only. A queued message waits at most **12 h** and is then
  dropped. User-visible states: **Queued** (in the outbox, nothing on the
  wire) → **Sent** (encrypted under the live session and handed to the router)
  → **Delivered** / **Read** (receipts, §6.4). A message queued while the
  peer is away shows *Queued* until the silent re-handshake completes, then
  flips to *Sent*.
- **Chat history (D2, FR-15):** the `messages` table (DB version 6,
  `MIGRATION_5_6`) is the **history** — both directions — while the outbox
  tracks **delivery only**. The retention is a setting: **off (keep
  everything, the default) / 1 day / 7 days**; the service purges the expired
  rows with its periodic tick. **"Clear chat"** empties one conversation (and
  cancels its pending sends) whatever the retention is.
  - A message that nobody ever acknowledged ends as **Not delivered** — never
    as "Sent". It can be sent again: **Resend** creates a fresh outbox entry
    with the same application-level id, so the receiver still shows it once.
- **Handshake tiers (D2, FR-12 review):** the reserved half of the global
  handshake budget is split: a QR-verified contact may take
  **60 X25519 operations / minute** (per sender), a **direct** neighbour that
  has delivered a verified ANNOUNCE may take **30** (per **connection** — one
  link cannot use more than one allowance, however many identities it
  announces), and everybody else only the general half.
  A **direct** neighbour is protected **only after** it has delivered a
  verified ANNOUNCE, and only when the handshake arrived **straight** from it:
  a handshake that was relayed uses the general pool only, so a peer cannot
  launder its budget through a friendly relay. One peer can therefore never
  starve a contact's handshake.
- **Panic wipe (§7):** erases the identity (and its Keystore alias), the
  contacts, the Noise sessions, the outbox, the chat history, the receiver's
  seen ids, the preferences and the database file.
- **Receiver-side dedupe (D2):** the application-level message ids the
  receiver has shown are persisted in the `seen_message_ids` table (DB version
  4, `MIGRATION_3_4`) with a **30-day retention** — longer than the sender's
  12 h outbox expiry. A retry that arrives after a process restart is
  acknowledged again but **not shown twice**.
- **Fingerprint** shown in the UI = `SHA-256(ed25519Pk ‖ x25519Pk)` as 64 hex chars,
  displayed in groups of 4. Verification by QR / in-person compare (PRD FR-13, P1)
  is *not* in MVP; MVP shows the fingerprint and marks TOFU (trust-on-first-use)
  honestly.
- **4-hex short form is for disambiguation only** (16 bits — forgeable in
  seconds). Chat display (`“<fruit> <nickname> - 7F3A”`) uses the short form to
  tell duplicate nicks/fruits apart, but **only the QR-verified badge or a full
  fingerprint comparison conveys trust**. Never treat a matching 4-hex prefix as
  verified.

## 8. Router rules (normative, implemented in Phase B)

1. **Validate:** version == 1, `payloadLen` matches actual remaining bytes,
   TTL ≤ 7, timestamp inside the §4 window, no reserved flag bits, no unsupported
   flags. Invalid → drop, never relay.
   **Signature verification runs before dedupe insertion, rate-limit
   accounting and relay (Phase D ordering rule).** A packet whose signature
   fails verification (or is missing where §3 requires one) is dropped and
   **never inserted into the dedupe cache** and never charged to a rate-limit
   bucket. D1 wires the production verifier (`AnnounceVerifier` + two-tier
   `KeyCache`); there is no permissive fallback in the app. JVM simulator
   tests may inject a permissive verifier, but that verifier is test-only and
   never shipped.

1a. **Forward compatibility:** after signature verification, the router
    **relays unknown packet types** instead of dropping them (§2). A packet of
    an unknown type is relayed iff its signature verifies against a key the
    node already pins for that `senderId` (peer store / group roster). If the
    key is unknown or the signature fails, the packet is dropped. Nodes never
    deliver unknown types locally.

#### 8.1 How a relay learns a sender's key (design D1, chosen: Candidate A)

**Choice: Candidate A — flood ANNOUNCE (jittered, rate-limited, own TTL).**
**Candidate B (attach the 32-byte key to a sender's first packets) is rejected
for v1.** Justification: A reuses the existing ANNOUNCE/discovery machinery
(no new flag, no per-packet 32-byte tax), is self-certifying (below), degrades
gracefully on old nodes (they already flood ANNOUNCE), and separates identity
discovery (ANNOUNCE) from data (everything else). B would tax the first N
packets of every sender forever (32×N bytes per sender per node) and still
need a cache for the N+1th packet — the cache is unavoidable either way.

- **Self-certifying:** an ANNOUNCE is accepted iff it is structurally valid,
  rate-limited (§7) and `SHA-256(ed25519Pk)[0..8) == header.senderId`. That
  check binds the announced key to the claimed identity without prior trust.
  Only then is the (senderId, ed25519Pk) pair inserted into the key cache.
  A failed check drops the ANNOUNCE and never caches it.
  **Signature (amended):** an ANNOUNCE **must** carry a valid Ed25519 signature
  (`HAS_SIGNATURE` set) covering `nickname ‖ avatarIndex ‖ x25519Pk ‖
  capabilities` *and* the header fields per §3.1 `signingInput` (TTL excluded).
  Verification uses the **announced edPk itself** (key is in payload) and the
  hash check above — it requires no prior trust — but it MUST happen **before**
  the ANNOUNCE is cached, relayed, or shown in the UI. A payload tampered
  after signing (nickname, avatar, x25519 key, capabilities, or any header
  byte except TTL) fails `Ed25519.verify` and is dropped, never cached.
  ANNOUNCEs with missing signatures, mismatched hashes or bad signatures are
  `droppedInvalid` **and** `droppedBadSignature`, not `droppedDuplicate`. An
  ANNOUNCE that is cryptographically valid but rejected by the learning budget
  is counted as `droppedBudget` and is **not** counted as invalid (see §8.1
  counter table).
- **Flood:** ANNOUNCE uses TTL 7 (same cap as data, so keys travel as far as
  data will), jitter 10–200 ms per hop, adaptive relay probability (rule 6),
  and its own rate limit (≤ 5/10 s per sender and per connection, same bucket
  shape as HANDSHAKE). Cadence is on connect + every ~30 s (see §8.2 for the
  interval choice from simulator numbers).
- **Key cache — two tiers (amended):**
  - **Protected tier** — QR-verified contacts + currently connected neighbours
    (and self). Cap **100 entries**, **never expired/evicted by unauthenticated
    (unverified) ANNOUNCEs**; entries leave only on block, disconnect timeout,
    or panic wipe. A sustained flood of fake (self-consistent, even signed by
    the attacker) identities **cannot** evict protected answers.
  - **Untrusted tier** — all other verified ANNOUNCEs. **500-entry LRU**,
    **420 s (7 min) expiry** (3 × 120 s slowest cadence + 60 s slack; §8 rule 8)
    sliding on last verified ANNOUNCE (≈25 KB). Expired/LRU-evicted → treated
    as unknown. An attacker can plant at most 500 untrusted entries; real peers
    re-teach instantly on their next ANNOUNCE (not starved).
  - **Per-connection budgets (amended):** cold-start replay (N = 20) consumed
    the whole old **20/5-min** budget, so a node with ONE link into a 50-node
    mesh could learn at most 20 of the other 49. The retuned split is:
    - **Replay allowance: 49** new identities per connection per window
      (covers “learn everyone through one bridge”), and
    - **Normal budget: 50** new identities per connection per window for
      non-replay discovery (the old 20 starved hub-link learning in degree-6;
      the coverage probe below showed 67.8% at 20, so it was raised).
  - **Global cap (amended):** new untrusted identities are capped globally at
    **100 per 5-min window**, independent of connection count. Per-connection
    buckets reset on reconnect, so without this an attacker cycling
    connections could plant unlimited identities. A larger allowance is
    permitted **only while the cache is nearly empty** (cold start):
    while untrusted size < 20 the global cap is **149** (100 + 49 replay) so a
    fresh node can learn everyone through one bridge. Once warm, the 100 cap
    binds. Test: an attacker that reconnects repeatedly cannot exceed the
    global cap (see `KeyKnowledgeTest` global-cap test).
  - **Worst-case churn (corrected):** insertion rate can be **(49 + 50) × 6 =
    594 per 5 min**; over the 7-min expiry the insertions that survive is
    `594 × (7/5)` ≈ **832**, which exceeds the 500-entry LRU cap. At the cap
    the **oldest untrusted entry is evicted** (LRU); the protected tier is
    never affected, and the **global 100/149 cap is the real binder**
    (594 is the per-connection arithmetic, 100 is what lands). Real peers
    re-teach instantly on their next ANNOUNCE. Starting values only —
    phone-validated.
- **Packets from unknown senders — bounded holding buffer (amended, D1):** a
  packet whose sender key is not cached is **held for a short while** instead
  of being dropped on the spot, because in a mesh the sender's ANNOUNCE and
  its first data packet routinely race each other (the ANNOUNCE has one more
  hop to travel, or the relay was busy). The buffer is strictly bounded:

  | Bound | Value | Rule |
  |---|---|---|
  | Packets held **in total** | **32** | the whole buffer, per node |
  | Packets held **per connection** | **8** | one chatty neighbour cannot fill it |
  | **Hold time** | **3 s** | after that the packet is dropped |

  A held packet is **released** as soon as the matching **verified ANNOUNCE**
  arrives (that ANNOUNCE teaches the sender's key); the released packet then
  goes through the normal path again (verification → dedupe → rate limit →
  delivery → relay). A held packet is **not** relayed while it waits, and it
  is never cached. Packets are keyed by message id, so a duplicate copy of a
  held packet is ignored rather than queued twice.

  Anything that does not fit (buffer full) is dropped immediately and counted
  as `droppedBufferFull` — **an unknown-sender flood must never be able to
  delay or displace legitimate traffic**, and it must never grow the buffer
  beyond 32/8. The only exception to "unknown senders are never trusted" is
  an ANNOUNCE that passes the self-certifying check (it *is* the teacher); a
  Noise HANDSHAKE that passes §7.1 of NOISE_DESIGN.md may also teach its
  binding once D2 lands. Everything else (PUBLIC, PRIVATE, GROUP_*, unknown
  types) from an unknown `senderId` is held (not delivered, not relayed) and
  is dropped even if its signature would otherwise verify against a key the
  node does not hold.
- **Sender settle window (new, D1):** after linking a **new** neighbour, the
  sender waits **2 s** before its **first data packet** to that neighbour
  (ANNOUNCE and HANDSHAKE are never delayed). The neighbour needs the
  sender's ANNOUNCE before it can verify the sender's data, and the ANNOUNCE
  travels one hop less than the data. The window is per connection: it starts
  when the peer is first seen, is skipped for peers that were already linked,
  and restarts after a disconnect. Only *originated* data waits — a relayed
  packet keeps the **origin's** signature, so the relay's key is not what the
  receiver needs. `MeshRouter` implements this with a wall-clock timer
  (`RouterConfig.newPeerSettleMs`, default 2 000 ms) over the transport's
  connection view; the mesh neighbour view used for routing is unchanged.
- **Signature counters (normative names in `MeshStats`):**

  | Counter | Meaning |
  |---|---|
  | `droppedUnknownKey` | A held packet **timed out** (3 s) without its sender's ANNOUNCE arriving. Also increments `droppedInvalid`. This is the **only** place the counter moves: a packet that is held and later released is not a drop at all. |
  | `droppedBadSignature` | Key was known but Ed25519 verification failed, or a type that §3 requires to be signed arrived unsigned. Also increments `droppedInvalid`. |
  | `droppedBudget` | ANNOUNCE is structurally valid and correctly signed, but the `KeyCache` per-connection or global learning budget rejected it. **Not** invalid and **not** charged to `droppedInvalid`; a later announce can still teach the key. |
  | `droppedBufferFull` | The holding buffer was full (32 total / 8 per connection) when the packet arrived, so it was dropped instead of held. Also increments `droppedInvalid`. |

  Operators use the split to distinguish "the mesh has not converged yet"
  (`droppedUnknownKey` — a timeout, i.e. the key never arrived), "someone is
  forging/corrupting" (`droppedBadSignature`), "a flood is being throttled"
  (`droppedBudget`) and "the buffer was full" (`droppedBufferFull`). The
  holding buffer also exposes `heldTotal` (packets ever held) and
  `releasedPending` (packets released after their ANNOUNCE arrived): in a
  healthy mesh `heldTotal > releasedPending > 0` right after a join, and the
  difference is what times out.
- **Simulator model (D1 test-only, not production):** nodes start knowing
  **only direct neighbours** (via the adjacency map); all other keys are
  learned exclusively by observing verified ANNOUNCE floods. Measurements in
  `MeshSimulatorTest` / `KeyKnowledgeTest` are the evidence for §8.2.
2. **Deduplicate with TTL upgrade:** the LRU cache (cap 5,000 IDs **or**
   1 hour, whichever comes first) stores the **highest TTL seen per message
   ID**. A copy whose TTL is ≤ the stored max is a duplicate: drop. A copy
   whose TTL is **higher** (§3.1 pinning defence) relays once more —
   rate-limited as usual — **without re-delivering** locally. Local sends mark
   the origin's TTL so its own copies loop as duplicates.
3. **Deliver locally** if broadcast or `recipientId == ownId`. Still relay (rule 4)
   unless the packet is a receipt for us.
4. **Relay:** if `ttl > 1` and we didn't originate it, decrement TTL, wait a
   **random jitter 10–200 ms**, then forward to every connected peer **except the
   source** of this copy.
5. **Direct, don't flood:** if the recipient is a *direct neighbour*, send only to
   them and stop (no broadcast).
6. **Adaptive relay probability:** with many neighbours, drop some relays
   probabilistically to avoid broadcast storms. MVP formula (Phase B):
   `relayProbability = 1.0` for ≤ 4 neighbours, then `4.0 / neighbourCount`,
   floored at `0.25`. Target metric: no storm at 30 nodes in one room.
7. **Rate-limit:** per sender **and** per connection (the immediate neighbour
   the packet arrived on), token-bucket (MVP starter: 20 packets / 10 s for
   public, 5 / 10 s for handshake, receipts 30 / 10 s). Both buckets must
   allow; `FRAGMENT` chunks are bounded by reassembly limits instead.
   Over-limit → drop + count. Max packet size = 64 KiB (§1) — larger packets
   are fragmented, never sent whole. Blocked peers are dropped **before**
   budget accounting (Phase D, see `NOISE_DESIGN.md` §5).
8. **Announce cadence (adaptive with hysteresis, from §8.2/§8.4):** on connect +
   adaptive periodic cadence — **30 s / 60 s / 120 s** bands (§8.4) plus ±10 s
   jitter. Receivers set peer/key expiry to **3 × senderInterval + 30 s jitter
   slack** using the sender's `intervalUnits` byte (§6.1): 30 s band → 120 s
   expiry, 60 s → 210 s, 120 s → **390–420 s**. The untrusted-tier expiry is
   therefore **420 s (7 min)**, not the old 300 s — it must outlive the
   slowest sender's three cadences. Cadence has hysteresis (switch *up* at
   33 known nodes, switch *down* at 27) so it cannot flap around a threshold
   (see §8.4). Peer timeout = 3 missed cadences by band. ANNOUNCE has its own
   rate limit and TTL 7 (see §8.1).

#### 8.2 Announce interval (from simulator measurements)

`KeyKnowledgeTest` (30 nodes, 20 seeds, key-knowledge model §8.1):
- **Fully-connected:** delivery **1.000**, data flood **232** linkTx mean, one
  full ANNOUNCE round (all 30 nodes flood once) **7,121** linkTx.
  → ~**14,242/min** at 30 s cadence, ~**7,121/min** at 60 s, ~3,560/min at 120 s.
- **Random degree ~6:** delivery **0.991**, data flood **952** linkTx mean,
  one ANNOUNCE round **3,125** linkTx.
  → ~**6,250/min** at 30 s, **3,125/min** at 60 s.
- **20-node group flood (10 members / 10 non-member relays):** members
  **9/9 delivered**, non-members relayed normally (130 relay forwards total),
  per-node relay bound holds.

**Measurement method:** an ANNOUNCE round is measured **to quiescence** — the
test waits until the virtual network stops transmitting (300 ms quiet, 10 s
cap) instead of sleeping a fixed 500 ms. A fixed sleep truncates the wave:
at 500 ms only ~70% of the round has been transmitted and 10–27 of 30 nodes
have the sender's key, so the data packet is dropped as *unknown-sender*
rather than proving the key-knowledge path. Measuring a settled wave is what
makes the delivery numbers above reproducible; the earlier 2,276-linkTx /
0.969-coverage figures were measured on a truncated wave (see §8.3).

**Chosen cadence: every ~60 s ± 10 s jitter** (on connect + periodic, adaptive
± hysteresis per rule 8), peer timeout **180 s** (3 missed cadences). Compared
with 30 s this halves the announce overhead (≈7k linkTx/min FC, ≈3.1k/min
degree-6 at 30 nodes) while keeping the 420 s cache warm across **three
slowest** cadences. Interval values are sent as `intervalUnits` (§6.1) and
are accepted only as **{3, 6, 12}**. A 120 s cadence would halve overhead
again but leaves only 2–3 refreshes per cache lifetime and risks timeout on
lossy links, so 60 s (adaptive) is the current balance. The interval is
behaviour-only (no wire change beyond the one byte).
**Correction (D1):** the previously recorded "degree-6 bytes/s *fell*
(590 → 422 B/s)" and the "announce round's linkTx fell 27% (3,125 → 2,276)"
were a **measurement artifact**, not a saving: the 2,276 figure was taken
500 ms after the wave started, so it counted only part of the round. A settled
round is **3,125 linkTx**, i.e. the original figure. The signed ANNOUNCE +
per-connection new-identity budget do suppress redundant relay copies, but
they do not change the round's size this much.
**Amendment (starting values, phone-validated later): adaptive interval that
grows with known-node count** — ≤10 known → 30 s, 11–30 → 60 s, >30 → 120 s
(see §8.4). §8.2 numbers are the degree-6 decision case; the top line is the
plain 60 s baseline before adaptation.

#### 8.3 Cold start: cached-ANNOUNCE replay on a new link (amended)

When a fresh BLE link to a neighbour comes up, that neighbour **replays up to
N = 20** of its cached verified ANNOUNCEs to the newcomer (oldest-first is
fine; newest-first also acceptable — the newcomer only needs a spanning set).
The replay is **rate-limited** (same ANNOUNCE bucket, ≤5/10 s per connection)
and **fragmented as needed** (normal §5/MTU path — each replayed ANNOUNCE is
a whole packet). Rationale: a late joiner connected to one neighbour can then
verify traffic from non-neighbours without waiting for the next full periodic
flood. The simulator models this: a late joiner starts empty, receives N
replays, then a non-neighbour flood verifies on first arrival (see
`KeyKnowledgeTest` late-joiner probe: time-to-first-verified-delivery).

**Known transient (degree-6, first round):** an early `KeyKnowledgeTest`
measured **0.969 key coverage after one announce wave** (843/870 ordered
pairs). This is *not* a correctness bug: adaptive relay suppression
legitimately drops ~3% of one-wave announces. After three waves (the real
60 s cadence) coverage was **1.000** in every toggle config — the overhead
drop is fewer redundant copies, not lost keys. The 0.969 number is recorded
here so future re-measures do not mistake it for a regression.
**Correction (D1):** the 0.969 figure was measured on a wave that was still
in flight (the test slept a fixed 500 ms, then counted). Measured **to
quiescence**, one wave reaches **870/870 pairs (1.000)** and the origin's key
is known by **30/30** nodes in 17 of 20 seeds (29/30 in 3 seeds), so delivery
is **0.991** (§8.2). The residual gap is not missing keys but adaptive
suppression on the *data* flood: a node that learned the key can still miss
the packet itself (27–29 of 29 delivered in the worst seeds).

#### 8.4 Announce overhead in bytes/s/node (degree-6 decision case)

One ANNOUNCE on the wire is **~170 bytes** (32 header + ~70 payload + 64
signature, avatar included). One full round (30 announces, degree ~6) is
**3,125 link transmissions** (measured, wave measured to quiescence) ≈ 528 KB
moved per round (`3,125 × 169 B`). Per node (TX + RX = 2 × moved ÷ 30 nodes):

| cadence | per node per min | per node per sec |
|---------|------------------|-------------------|
| 30 s | ~69 KB | **~1,175 B/s** |
| 60 s | ~34 KB | **~590 B/s** |
| 120 s | ~17 KB | **~295 B/s** |

At BLE LE-1M rates (~0.7 Mb/s usable) 590 B/s is a **~0.7% radio duty-cycle
unvalidated estimate** — duty cycle is not battery drain. It says nothing
about the PRD battery target (**≤4 %/hour screen-on**) without radio power,
CPU wake and display measurements. In a fully-connected room the rate is
~2.3× higher (≈1,340 B/s at 60 s). ANNOUNCE overhead *may* not be the
battery bottleneck; adaptive spacing is therefore chosen for *storm
hygiene*, not battery rescue, until measured.
The adaptive schedule (switch up at 33 known, down at 27; bands 30/60/120 s)
roughly equalises per-node bytes/s as the mesh grows.
**Correction (D1):** these figures were previously ~37% lower (2,276 linkTx,
~420 B/s at 60 s) because the round was measured 500 ms after it started; see
§8.2. The battery band (**≤4 %/hour**) is unaffected in direction but the
60 s cadence now costs ~590 B/s per node, so the **30 s band (≈1,175 B/s) is
the one to keep off** until a phone measures real drain.

## 9. BLE transport expectations (Phase C — normative for the codec)

- One 128-bit service UUID and one characteristic (
  `Write Without Response` + `Notify`) shared by every install.
  **Service UUID (frozen):** `a777356e-7ebc-429e-a847-d52eaeeb1331`
  **Characteristic UUID (frozen):** `cf602b5c-31b5-4b91-aa65-9272bd79cd3f`.
  Both are random v4 UUIDs generated once in Phase C and hard-coded in
  `BleUuids.kt`. Changing either is a wire break — it needs a version bump
  and new test vectors.
- **Raw chunking is link-layer framing only, applied per hop.** When the
  negotiated MTU is too small for a MeshPacket FRAGMENT (payload < 95 bytes,
  e.g. MTU 23), `BleTransport` slices the *entire encoded packet* into raw
  chunks (`magic 0x7F | totalLen(2) | index(2) | total(2) | data`, 7 bytes
  overhead). The receiver reassembles the raw bytes *before* the router
  decodes them, so chunking is invisible to the mesh: it never changes the
  packet bytes, hence **never affects signatures (§3.1), message IDs (§4),
  TTL, flags or payloads**. Each hop re-chunks independently for its own
  connection's MTU — a packet that was raw-chunked on hop 1 is a whole
  packet again on hop 2.
- **Magic collision:** the chunk magic `0x7F` can never be mistaken for a
  MeshPacket because a valid packet's first byte is `version = 0x01`;
  any other version byte is already a hard decode error (§2), and `0x7F`
  is reserved for this framing.
- Advertise the service UUID, scan with a **service-UUID filter** only.
  Peers are identified *by the ANNOUNCE packet after connecting*, never by
  advertisement bytes (iOS cannot put arbitrary data in an advert, and the
  advert also leaks less metadata).
- Dual role: every node is GATT server **and** GATT client simultaneously.
  Concurrency cap starts at **6** connections, configurable. The lower
  `senderId` (unsigned byte compare) acts as central to avoid double-connects.
- Request MTU 512; payload per write = MTU − 3. Fragment via §5.
- GATT error 133 → retry with backoff and a fresh connection. Scan restarts are
  throttled (≤ a few per 30 s) so scanning is a long-lived, tuned session.
- Foreground service type `connectedDevice` with a persistent notification
  (Phase E). BLE does not work on emulators — all radio behaviour is
  **physical-device only** (see README).

## 10. Security notes and honest limits

- **Public = signed, not encrypted.** Anyone with a BLE radio in range can read it.
  The UI says so on the Nearby screen. Public messages are *not* private.
- **Private = Noise AEAD.** Metadata (timing, size, sender/recipient IDs, packet
  count) is visible to relays and to anyone sniffing BLE. Payloads are not.
- **Meshline is not an anonymity tool** (PRD §12). Do not market it for high-risk
  use until it has had an independent security audit, and say so in the app.
- **Group traffic is linkable within an epoch (draft E honest limit):**
  `senderId` is in every header and the epoch tag is in every `recipientId`,
  so a passive observer who sees a flood can infer *which senderIds talk to
  which epoch tag* — i.e. group membership and who-talks-together within an
  epoch — even though it cannot read the text. The tag rotates each epoch
  (epochs are unlinkable) but traffic *inside* one epoch is correlatable.
  The app MUST disclose this in group UI (see `GROUPS_DESIGN.md` §8).
- **Known limitation — stable sender IDs (documented, design unchanged):**
  `senderId` derives from the long-term Ed25519 public key (§4, §7), so it is
  **stable and linkable across sessions and devices that share the key**. Any
  peer or relay that sees packets from the same senderId can link those packets
  to the same identity over time, even in different places and at different
  times. Per-session ephemeral IDs would reduce this (§4) but are **not
  implemented** — the MVP deliberately keeps the stable ID so dedupe, block
  lists and fingerprint display are simple and testable. This must be disclosed
  in the app's onboarding/limits screen (Phase E) and reconsidered after the
  Phase D security review. Do not claim unlinkability you do not have.
- Crypto primitives: **never invented here.** MVP uses BouncyCastle for
  Ed25519/X25519/ChaCha20-Poly1305 and a reviewed Noise implementation
  (Phase D) for the handshake. The codec itself does no crypto except hashing
  (SHA-256 via the platform / BouncyCastle) — signature *verification* is the
  SecurityModule's job, not the codec's.
- Parser robustness: the decoder is **fail-closed** on every malformed input
  (wrong version, length mismatch, trailing garbage, oversized payload, bad
  timestamp, reserved flags). Fuzzing the parser is acceptance-gated before launch
  (PRD §17).

## Known issues / Phase E hardening (documented, not implemented in Phase C)

- **Connection-slot exhaustion.** The connection cap is 6 (`BleUuids.MAX_CONNECTIONS`).
  A device can hold all 6 connections open without ever delivering a valid
  ANNOUNCE, starving legitimate peers (an unauthenticated, low-cost denial of
  service: BLE connects are cheap, slots are not). *Planned mitigation
  (Phase E, behaviour-only — no wire change):* close any connection that has
  not delivered a valid ANNOUNCE within ~10 s of connecting, and when the cap
  is reached prefer evicting the unauthenticated/idle link over an established
  (Announce-verified) one. Eviction policy and the exact timeout are tuned in
  Phase E against field measurements; the wire protocol is unaffected, so this
  does not need a version bump.

## 11. Change policy

- Any change to §§1–6 that alters bytes on the wire = **protocol v2**, bump
  `version` to `0x02`, and ship with new test vectors. Receivers that don't
  understand v2 drop packets (hard error, §2).
- Behaviour-only changes (§8 router tuning, §9 transport) do not bump the version
  but must update this file and the README test instructions.
- Test vectors are the executable truth: every vector in
  `test-vectors.json` must decode to the packet described, and re-encoding the
  decoded packet must reproduce the exact bytes. Round-trip is the invariant.
