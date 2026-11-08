# Meshline Protocol — v1

> Status: **Draft v1 for Phase C.** (Phase B: signing input TTL-invariant §3.1;
> TTL unauthenticated + highest-TTL dedupe; signature-first router ordering;
> per-connection rate limits. Phase C: BLE service/characteristic UUIDs are
> frozen — see §9. Fixed 32-byte header unchanged. Noise handshake design is
> gated in `NOISE_DESIGN.md` and is not part of the wire spec yet.) This
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
| `0x00` | *(unknown)*     | Reserved / invalid on decode. |
| `0x09..0xFF` | *(unknown)* | Invalid on decode → drop silently (forward-compatible: unknown types MUST NOT crash the app, only be ignored). |

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
```
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
ciphertext = Noise transport message bytes (Noise_XX_25519_ChaChaPoly_SHA256)
```
- Outer packet: `recipientId` = recipient, `HAS_SIGNATURE` clear.
- Re-key after the Noise library's recommended message count / interval
  (Phase D). Message states: Queued → Sent → Delivered → Read (PRD §13).

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
- `HANDSHAKE`: payload = one raw Noise handshake message (length is implicit in
  the Noise spec — no length prefix here; the Noise library parses it).
  Unsigned, directed. Relayed like a private message so the handshake can
  traverse multi-hop paths. Handshake packets are rate-limited harder than any
  other type (§8).

## 7. Identity & keys (summary — details in Phase D)

- On first launch the `SecurityModule` generates:
  - **X25519** (Curve25519) key pair for key agreement (Noise uses this), and
  - **Ed25519** key pair for signing.
- Private keys live in the **Android Keystore** (non-exportable where the hardware
  supports it); they are never in the Room database and never in a packet.
- `senderId` = truncated SHA-256 of the *Ed25519* public key (identity anchor).
- **Fingerprint** shown in the UI = `SHA-256(ed25519Pk ‖ x25519Pk)` as 64 hex chars,
  displayed in groups of 4. Verification by QR / in-person compare (PRD FR-13, P1)
  is *not* in MVP; MVP shows the fingerprint and marks TOFU (trust-on-first-use)
  honestly.

## 8. Router rules (normative, implemented in Phase B)

1. **Validate:** version == 1, `payloadLen` matches actual remaining bytes,
   TTL ≤ 7, timestamp inside the §4 window, no reserved flag bits, no unsupported
   flags. Invalid → drop, never relay.
   **Signature verification runs before dedupe insertion, rate-limit
   accounting and relay (Phase D ordering rule).** A packet whose signature
   fails verification (or is missing where §3 requires one) is dropped and
   **never inserted into the dedupe cache** and never charged to a rate-limit
   bucket. Until Phase D wires real verification, the router's verifier is
   permissive (`{ true }`) — the *placement* of the check is pinned now so
   Phase D only injects the key lookup.
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
8. **Announce cadence:** on connect + ~30 s ± jitter, so offline peers time out
   in ~90 s without generating traffic storms.

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
