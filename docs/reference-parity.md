# Meshline System Capabilities & Technical Specification Ledger

This document details the complete technical architecture, security guarantees, wire formats, and operational specifications of **Meshline**.

---

## 1. System Capability Matrix

| Capability | Standard Mobile Messenger | Meshline Implementation | Architectural Advantage |
|---|---|---|---|
| **Radio Transport** | Centralized Wi-Fi / Cellular only | Native Dual-Role Bluetooth Low Energy (`BleTransport.kt`) | 100% offline-first communication without cellular towers or internet access |
| **Mesh Routing** | None (client-server) | Multi-hop flood/relay with TTL (`MeshRouter.kt`) | Automatic packet forwarding through intermediary devices up to 7 hops |
| **Protocol Invariants** | Proprietary API endpoints | Frozen Wire UUIDs: Service `a777356e-7ebc-429e-a847-d52eaeeb1331`, Char `cf602b5c-31b5-4b91-aa65-9272bd79cd3f` | Deterministic binary framing; robust against network fragmentation |
| **Identity & Trust** | Phone numbers, SMS, emails | Hardware-backed Android Keystore Ed25519 + X25519 (`AndroidKeystoreIdentityStore.kt`) | Zero accounts, zero phone numbers, zero tracking; identity stays in secure enclave |
| **Trust Model** | Central directory server | Strict Two-Tier KeyCache (`KeyCache.kt`): Protected (QR-verified) vs Ephemeral (Announce-learned) | Defense against impersonation and Sybil attacks via out-of-band verification |
| **1-on-1 E2EE** | TLS to server or server-assisted E2EE | `Noise_XX_25519_ChaChaPoly_SHA256` via `noise-java` with 128-nonce anti-replay bitmask window | End-to-end mutual authentication directly between peers without third-party servers |
| **Outbox & Retries** | Cloud-dependent synchronization | Persistent SQLCipher Outbox (`Outbox.kt`), retries at 1s/2s/4s/8s/16s/30s | Guaranteed delivery state tracking with cryptographic delivery receipts |
| **Group Messaging** | Server-fanout chat rooms | Cryptographic Epoch Groups (`GroupManager.kt`): 32-byte epoch keys, 8-byte HKDF tag, ChaCha20-Poly1305 AEAD, 18-byte AD | Fully decentralized group chats; metadata-resistant group tags and tamper-proof payloads |
| **Group Invites** | Cleartext link sharing | Noise-Encrypted Private Invites (`SecureChannel.kt` with `MGRP` magic header) | Group epoch keys delivered securely over private E2EE channels |
| **Topic Channels** | Server-hosted public channels | Distributed topic channels (`#general`, `#mesh`, `#emergency`, `#geo-local`) | Localized community broadcasts without centralized administration |
| **Command Engine** | None | Full IRC Slash Command Engine (`CommandParser.kt`): `/help`, `/join`, `/channels`, `/msg`, `/who`, `/nick`, `/block`, `/unblock`, `/clear`, `/slap` | Rapid, keyboard-friendly command execution across all chat surfaces |
| **Contact Exchange** | Central username search | CameraX + ZXing QR Code Scanner (`ScanQRScreen.kt`), self-certifying payload with fingerprints | Instant physical verification without exposing personal identifiers |
| **Emergency Data Wipe** | Account deletion request | 3-Tap Armed Panic Wipe (`PanicWiper.kt`): Database shredded with random bytes, keys wiped, process killed | Immediate, irrevocable data destruction under physical distress |
| **Database Encryption** | Plaintext SQLite or standard filesystem | SQLCipher v4 encrypted Room v7 (`MeshlineDatabase.kt`) with hardware-wrapped passphrase | Full AES-256 at-rest encryption for all messages, contacts, and group keys |
| **Wide-Area Relay** | Monolithic server architecture | Optional Nostr Relay Transport (`InternetRelayTransport.kt`) with explicit toggle | Optional global reach over decentralized relays; defaults to 100% offline BLE radio |
| **Private Media (Voice Notes, Images)** | None in plain messengers | Voice notes (AAC) and small images (JPEG) in private chats (Features 4) | Sent over Noise XX AEAD like private messages; stored in the encrypted `messages` table (`media`, `mediaType` columns) |
| **Reply-to Quoting** | None in plain messengers | Quoted replies in private chats (Feature 5) | Quote stored on the message row (`parentIdHex`) |
| **Local Message Search** | None in plain messengers | Search the private chat history by text (Feature 6) | In-memory, case-insensitive, over encrypted message rows |
| **Message Reactions** | None in plain messengers | Emoji reactions on public broadcasts and topic channel messages (`REACTION` packet: signed, broadcast, TTL 7) | Reactions ride the existing broadcast path; per-sender rows stored in the SQLCipher DB (`message_reactions`) |
| **Typing Indicators** | Rare in offline messengers | Directed, signed, TTL-1 "typing" packets (`TYPING`) for private DMs | Privacy-safe: addressed to one peer, never relayed, expires in seconds |
| **Geo Pings** | None in offline messengers | Signed, broadcast, TTL-2 geo beacons (`GEO_PING`) carrying a privacy-safe geohash | Disclosure boundary is the geohash cell (a few km); opt-in by location |
| **Group Member Management** | None in plain messengers | Admins remove members and transfer admin rights (`GROUP_CONTROL` kinds 4/5, Feature 6); every roster change rotates the epoch + key | Removed members and transferred-away admins are notified via the broadcast control; out-of-admin cannot touch membership |
| **Group Roster Display** | None in plain messengers | MEMBERS panel in the group chat header (nicknames, "You" marker, 👑 admin badge, Transfer/Remove for admins, Feature 7) | Admin actions broadcast the control to all members |
| **Nearby Peer Roster** | None in plain messengers | Tapable NEARBY PEERS roster on the Nearby screen (nickname, signal strength, hops, 💬 chat button, Feature 8) | Data from the ANNOUNCE nicknames + router telemetry (RSSI/hops) |
| **Background Notifications** | None in offline messengers | "New message from …" notifications for private and group messages (Feature 9) | Posts to the "New Messages" channel; previews are single-line, messages are never read back |
| **Persistent Groups & Channels** | None in plain messengers | Groups (epoch key, roster, unread), group messages and channel history survive a restart (DB v10) | Write-through to the encrypted `groups` / `group_messages` / `channel_messages` tables; single-consumer ordered queue; retention sweep; panic-wipe clears all three |
| **UI Design System** | Standard OS styles | Coral-DeepLavender Rx theme (`#FAF7F2` cream, `#FF5733` coral accent, `#C4B5E0` incoming bubbles), 4 main tabs, unread badges | Optimized for high contrast, low cognitive load, and a distinctive warm identity |

---

## 2. Protocol Interoperability & Wire Invariants

### 2.1 Wire Packet Framing
Meshline maintains a strict canonical binary header:
```
Offset  Size  Field          Description
0       2     magic          Fixed 0x4D4C ("ML" in ASCII)
2       1     type           PacketType (0x01..0x0A)
3       1     flags          Bit 0: HAS_RECIPIENT (0x01), Bit 1: HAS_SIGNATURE (0x02)
4       1     ttl            Hop limit (default 7, decremented at each forward)
5       1     reserved       Reserved (0x00)
6       2     seq            Monotonic big-endian sequence number
8       8     sender_id      First 8 bytes of SHA-256(Ed25519 public key)
16      8     recipient_id   Broadcast ID (0x00..00), Peer ID, or 8-byte Group Tag
24      N     payload        Raw packet payload bytes
24+N    64    signature      Optional Ed25519 signature over signingInput(packet)
```

### 2.2 Wire Security Invariants
1. **Signature Verification before Deduplication**:
   Every broadcast packet (`ANNOUNCE`, `PUBLIC_CHAT`, `CHANNEL_MESSAGE`, `GROUP_MESSAGE`) MUST have bit 1 of `flags` set. The router verifies the Ed25519 signature **before** checking the 256-entry deduplication LRU table. An unverified or invalid signature is dropped immediately and NEVER forwarded, preventing denial-of-service relay amplification.
2. **Deterministic Sender ID**:
   Sender IDs are strictly the first 8 bytes of `SHA-256(ed25519_public_key)`. Self-certifying `ANNOUNCE` packets enforce this invariant.
3. **18-Byte Associated Data for Group Messages**:
   Group payloads encrypted with ChaCha20-Poly1305 authenticate the 18-byte AD:
   $$\text{AD} = \text{groupTag (8 bytes)} \parallel \text{epoch (2 bytes BE)} \parallel \text{senderId (8 bytes)}$$
   Any packet replayed into a different epoch, a different group, or spoofed under another sender's ID fails Poly1305 authentication immediately.
4. **Noise Replay Prevention**:
   Noise transport messages maintain a 128-nonce bitmask sliding replay window. Replayed ciphertexts or out-of-order deliveries beyond 128 nonces are rejected.

---

## 3. Honest Delivery States

Unlike mock applications that simulate immediate green checkmarks, Meshline maintains honest state machines:
- **`QUEUED`**: The message is stored in SQLCipher; the device is waiting for a direct or routed link to complete the Noise XX handshake.
- **`SENT`**: The packet was handed to the BLE mesh transport.
- **`DELIVERED`**: The destination peer decrypted the envelope and returned a signed `DELIVERY_ACK` (`0x04`) packet.
- **`READ`**: The destination peer actively opened the chat conversation (`READ_RECEIPT` `0x05`).
- **`NOT_DELIVERED`**: Retries expired after the full backoff cycle without acknowledgment. The user can tap `[Resend]` to re-queue.

---

## 4. Architectural Summary

Meshline separates transport, routing, cryptography, presentation, and persistence into strictly isolated layers:
- Core routing (`MeshRouter.kt`) and crypto payloads (`GroupPayloads.kt`, `CommandParser.kt`) are pure Kotlin with zero Android dependencies, enabling thorough JVM unit testing.
- UI components collect strictly typed `StateFlow` primitives without holding service or hardware references.
