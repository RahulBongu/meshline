# Meshline Group Chat Design — **DRAFT (Phase E, design only)**

> **Status:** Design proposal for review. No code. Wire types are drafted in
> `PROTOCOL.md` §6.7 (`GROUP_MESSAGE 0x09`, `GROUP_CONTROL 0x0A`); this note
> pins the semantics those wire bytes must satisfy. Amendments here that change
> bytes on the wire also require a `PROTOCOL.md` amendment and new test vectors.
> Constraints are the approved list (creator-admin v1, 20 members, epoch keys,
> Noise-only invites, tag-first routing); this note resolves how they fit.

## 1. Overview

A group is a shared inbox for up to 20 people, identified by Ed25519 public keys.
One symmetric 32-byte epoch key encrypts every message in that epoch with
ChaCha20-Poly1305 (explicit nonce). Everyone floods and relays normally; only
members holding the epoch key can decrypt. Group membership is announced only
through signed control packets; the group itself is addressed by a derived
8-byte tag, so relays learn nothing about the group.

Bones: `groupId (16)`, `name (≤32 chars)`, `icon` (fruit/avatar index, §6.1.1
of PROTOCOL), `epoch (2-byte, starts at 1)`, `epochKey (32)`, `roster`
(ordered list of Ed25519 pubkeys + display name/avatar each), `admin` = creator
in v1.

## 2. Keys, epochs and membership

- **Group identity:** `groupId = 16 random bytes` (SecureRandom), never reused.
  Groups have no global registry; the roster *is* the membership list.
- **Epochs:** every membership change — add, remove, admin-initiated leave
  handling — **starts a new epoch and generates a fresh 32-byte epoch key**
  (random). Epoch numbers increase monotonically (2 bytes, big-endian) and are
  never reused, even after a key rotation or app restart.
- **No forward secrecy inside an epoch.** Anyone with the epoch key can decrypt
  every message ever sent in that epoch, including past ones captured off the
  air. This is an honest, documented trade-off in v1: a fresh epoch after every
  membership change means a leaver's exposure stops at the epoch boundary,
  but *within* the current epoch a leaver holding the key can still read
  until the admin rotates (§5, honest-limits §8). Ratcheted Sender Keys (per-sender
  key chains, forward secrecy per sender) are a later upgrade and are
  deliberately deferred so the v1 invariant stays simple: *epoch key ⇔ can
  read epoch*.
- **Who generates keys:** the admin generates the epoch key for epoch 1 and for
  every later epoch. Members never generate keys. A member who receives a
  control announcing a new epoch must fetch the new key from the admin
  (next section) before it can decrypt that epoch.

## 3. Invites (Noise-only, never flooded, never in the clear)

- **Channel:** invites travel **only over existing Noise sessions as private
  messages** — `PRIVATE_MESSAGE` packets (§6.3 of PROTOCOL). An invite is never
  flooded as a public/group packet and never appears in the clear.
- **Payload (before Noise encryption, as a structured blob):**
  `groupId(16) | nameLen(1) | name (≤32B) | icon(1) | epoch(2) | epochKey(32) |
  rosterLen(1) | roster( rosterLen × (pubkey(32) ‖ nickLen(1) ‖ nickname ‖ avatar(1)) )`.
  The roster is attached so the invitee gets display info without waiting for
  the next flood of control packets.
- **Accept / decline:** the recipient sees an "invite inbox" entry and must
  **explicitly accept or decline**. On accept, the app stores the group state
  (groupId, epoch, key, roster) in the encrypted DB and starts decrypting +
  relaying that group's traffic. On decline, nothing is stored (only a
  short-lived "declined at" marker so a re-invite can be recognised).
- **Queueing (two different retentions — do not confuse them):** invites are
  held in the **admin's encrypted outbox** (the admin's device only; fields
  `groupId, inviteePubkey, payload, expiresAt = created + 24 h, retryCount,
  nextRetryAt`) and are **retried by the admin until the 24 h expiry** with
  backoff. The mesh relay store-and-forward (Phase 2, P1) that may cache a
  single private packet is separate and shorter — **12 h** — and is held by
  the relays that happened to see the ciphertext, not by the admin. So: the
  *admin* pipeline lives 24 h; any *relay* cache lives 12 h. After the 24 h
  admin expiry the sender surfaces "invite expired, re-invite" and the outbox
  row is deleted.

## 4. Wire format (draft — details in PROTOCOL.md §6.7)

- **GROUP_MESSAGE (`0x09`), outer:** `recipientId` = 8-byte group tag (§4.1),
  **`HAS_SIGNATURE` set — a valid Ed25519 signature from a *current* roster
  member is mandatory**. A shared epoch key alone cannot authenticate the
  sender, so a packet whose signature is missing, invalid, or from a
  non-roster (or removed) key MUST be rejected before dedupe/rate-limit/
  relay. Payload (encrypted): `epoch(2) | nonce(8) | ciphertext`. The AEAD
  **associated data** is the 18 bytes `groupTag(8) ‖ epoch(2) ‖ senderId(8)`
  (see PROTOCOL §6.7.1). The ciphertext covers the group text (500-char
  limit); the sender is resolved locally from the header `senderId` +
  roster, never from the plaintext.
- **GROUP_CONTROL (`0x0A`), outer:** `recipientId` = group tag, `HAS_SIGNATURE`
  set (§3.1 TTL-invariant). Payload is **not encrypted**:
  `epoch(2) | kind(1) | nameLen(1) | name | roster`. `kind`: 1=leave, 2=rename,
  3=roster-update. The signature's key must be the admin's (§5) or the packet
  is dropped.
- **Epoch/tag derivation:** the recipient's router checks the tag **first**,
  before any decryption. Only a matching tag reaches the group decrypt path;
  a non-member has no tag in its local set and drops the packet as ordinary
  "not for me" traffic.
- **Group tag (unlinkability):** `tag = HKDF-SHA256(epochKey,
  info = "meshline-group-tag", length = 8)` (RFC 5869). The tag is a
  pseudorandom function of the epoch key, so relays cannot tell which group a
  packet belongs to, the tag reveals nothing about the key beyond the standard
  HKDF pseudorandomness assumption, and the tag **rotates every epoch**, so
  epochs are unlinkable too. A 64-bit tag carries a negligible collision risk
  for ≤20-member groups, and a collision only wastes one wrong decryption
  attempt.
- **Old nodes / forward compatibility (§6.7):** before Phase E, `0x09`/`0x0A`
  are unknown types, but per PROTOCOL §8 rule 1a a node **relays** an unknown
  type after verifying its signature (key pinned for `senderId`). Group
  traffic is invisible to old *clients* (they never decrypt it) while old
  relays can still forward it once the signature checks out.

## 5. Ordering, late messages and replays

- **Nonce:** every group message carries an explicit **8-byte random nonce**,
  fresh per message (never reused under the same epoch key). Receivers keep a
  **per-sender nonce replay window** (128, as in private chat). AEAD failures
  are dropped silently.
- **Out-of-order epoch arrival:** a `GROUP_MESSAGE` for `epoch e+1` may
  arrive before its key. Receivers hold it in a **bounded short buffer**
  (at most 4 pending messages per group, 5-second lifetime, then drop) and
  decrypt once the key arrives. Old epoch keys are kept only as long as late
  messages for that epoch need them (bounded: at most the current + previous
  epoch keys per group, keys are deleted after the buffer lifetime).
- **Rate limits:** per-sender per-group token bucket (starter: 20 messages /
  10 s per sender per group; 5 group-control / min per admin). Over-limit =
  drop + count. Blocked members are hidden locally by the UI exactly like
  blocked private peers (their messages never render; relaying continues
  regardless).

## 6. Membership control (admin authority)

- **Only the admin may change membership** (add/remove/rename). Every
  `GROUP_CONTROL` signature and the signer's identity (derived senderId from
  the signed packet key) are verified against the roster's pinned admin key
  before any state changes.
- If a control arrives signed by a non-admin, it is dropped and counted as a
  protocol violation on that connection (surfaced in mesh status at verbose).
- **Admin gone = roster frozen.** If the admin leaves or is unreachable, the
  roster cannot change until a replacement admin is chosen out-of-band (v1 has
  no admin-transfer command). Members should be told in the group info screen
  that the roster looks stale.
- **Leaver reads until rotation.** A departing member keeps the current epoch
  key, so it can keep reading the trailing traffic in that epoch; only the
  next epoch (new key) cuts them off. This is inherent to shared-key groups
  and is disclosed in the UI.

## 7. Delivery semantics (honest)

- **Best-effort flood, no per-member receipts in v1.** There is no Delivered/Read
  marking for group messages — the sender cannot know who received what.
- **No history recovery.** A message sent while a member is out of range is
  **not** recovered when they return. Late joiners receive **no history** —
  only traffic sent after they accept the invite.
- The UI must label group chat with this explicitly (draft copy in §9).

## 8. Threat model and honest limits

| Threat | Mitigation | Residual risk |
|--------|-----------|---------------|
| Passive relay sniffing group text | AEAD + tag-first routing | Timing/size/group-tag presence visible |
| Observer inferring membership / who-talks-together | — (headers are in the clear) | **senderIds + epoch tag let an observer infer group membership and who-talks-together within an epoch** (graph analysis) |
| Removed member reading new epochs | New epoch key per membership change | Reads until rotation (honest limit §6) |
| Removed member reading old epochs | — (epoch key already shared) | **All past traffic in that epoch is readable** (no FS in epoch) |
| Impersonation (forged control) | Admin signature + roster pin | TOFU until roster verified out-of-band |
| Malicious relay replaying | 8-byte nonce + replay window + dedupe | Drop only, no attribution |
| Flooding / tag learning | Rate limits, tag unlinkability | Flooding accepted; tag rotation limits learning |

**Honest limits (must appear in the app):**
1. **Sender IDs plus the epoch tag let a passive observer infer group
   membership and who talks together within an epoch** — do not claim traffic
   analysis resistance.
2. No forward secrecy inside an epoch (see §2).
3. No delivery/read receipts; no history recovery; no late-joiner history.
4. The group tag is visible to relays (they can count group-looking packets).
5. A leaver can read until the next epoch; `Rotate key` is the admin's only
   remedy.
6. This is **not** an anonymity tool (same disclosure as PROTOCOL §10): senderIds
   are stable and linkable, and group traffic is linkable at the tag level.

## 9. UI (Chats screen) and copy

- **Chats** top-level tabs: **Nearby** (public, §6.2), **Private** (contacts /
  nearby peers), **Groups**.
- **Create Group:** name (≤32 chars), fruit icon picker (AVATAR TABLE),
  member picker (verified contacts first; unverified with a "not verified"
  hint), max 20. Creates epoch 1 and sends Noise invites.
- **Group chat:** each message shows the sender's **fruit + nickname**
  (resolved from the roster, never from a peer-supplied blob). Group info
  screen: members + admin badge, add/remove (admin only), **Leave**, invite
  inbox (accept/decline, expiry countdown).
- **Statutory copy:** *"Group messages are end-to-end encrypted with a shared
  key. Everyone in the group can read them. New members don't see old
  messages, and nobody sees delivery receipts. This is not anonymous."*

## 10. Tests (Phase E acceptance)

1. **20-node simulator group flood with non-member relays:** flood the tag;
   every member decrypts, every non-member drops at the tag check without
   spending a decrypt attempt; relay counts bounded (same ≤29 relay-forward
   rule as the 30-node flood).
2. **Removed member cannot decrypt the next epoch:** after a roster-update
   control (new epoch + new key), the ex-member's decryption of the next
   `GROUP_MESSAGE` fails; a current member's succeeds.
3. **Out-of-order key arrival:** `epoch e+1` message arrives before its key
   (buffered), key arrives via the following control, message then decrypts.
4. **Replay:** duplicate ciphertext (fresh outer header, same nonce) is
   dropped by the nonce window even though the outer dedupe would pass it.
5. **Non-admin roster change rejected:** a roster-update control signed by a
   non-admin changes nothing.
6. **Tag-collision handling:** two groups whose tags collide at 64 bits (test
   harness forces the collision) do not cross-deliver; the member of one
   group never decrypts the other's traffic even if it tries.

---

**Review gate:** approve, amend, or reject before Phase E implementation
begins. Wire changes need a `PROTOCOL.md` amendment + vectors; pure UX/copy
changes need only a README note.
