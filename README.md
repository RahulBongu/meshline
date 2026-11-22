# Meshline

**Meshline** is a complete, privacy-first, decentralized peer-to-peer messaging application for Android. Meshline allows nearby devices to form an autonomous Bluetooth Low Energy (BLE) mesh network that relays messages hop-by-hop with **zero internet, zero cellular infrastructure, zero phone numbers, and zero central servers**.

Private 1-on-1 messages are end-to-end encrypted using the **Noise XX** protocol. Group chats use **ChaCha20-Poly1305 AEAD** with epoch rotation and metadata-resistant group tags. When desired, users can optionally enable wide-area decentralized relays via Nostr without compromising their local offline-first security.

---

## Key Features

- **Offline Bluetooth LE Mesh Networking**:
  - Dual-role BLE GATT server and client (`BleTransport.kt`).
  - Multi-hop message relaying with automatic hop-decrement TTL routing (up to 7 hops).
  - Bloom filter deduplication and per-connection token-bucket rate limiting.
  - MTU negotiation (up to 512 bytes) with seamless chunk fragmentation and reassembly.
- **End-to-End Encrypted Private Chats**:
  - Mutual authentication using `Noise_XX_25519_ChaChaPoly_SHA256` with ephemeral key derivation.
  - 128-nonce sliding replay window for replay attack protection.
  - Honest message state tracking (`QUEUED`, `SENT`, `DELIVERED`, `READ`, `NOT_DELIVERED`) with cryptographic delivery ACKs.
- **Decentralized Cryptographic Group Chats**:
  - 32-byte ephemeral epoch keys with HKDF-SHA256 8-byte metadata-resistant group tags.
  - ChaCha20-Poly1305 AEAD encryption authenticating 18-byte canonical Associated Data (`groupTag || epoch || senderId`).
  - Private group invites securely dispatched over established Noise channels.
- **Topic Channels & IRC Slash Command Engine**:
  - Topic channels (`#general`, `#mesh`, `#emergency`, `#geo-local`) with unread counters.
  - Full IRC command parser: `/help`, `/join`, `/channels`, `/msg`, `/who`, `/nick`, `/block`, `/unblock`, `/clear`, `/slap`.
- **Optional Decentralized Internet Relay**:
  - Optional Nostr WebSocket transport (`InternetRelayTransport.kt`) for wide-area reach.
  - Disabled by default; 100% isolated from the offline BLE mesh to prevent metadata leakage.
- **Encrypted Local Persistence**:
  - SQLCipher v4 encrypted database integrated with Room v7.
  - Hardware-backed Android Keystore AES-256-GCM master key derivation.
  - Configurable message retention (24 Hours, 7 Days, 30 Days) with automated vacuuming.
- **Out-of-Band Trust & Identity**:
  - Self-certifying cryptographic profiles with Ed25519 identity keypairs.
  - Two-tier trust cache: QR-verified Protected contacts vs. Ephemeral peers.
  - Fast contact verification via high-contrast QR generation and CameraX QR scanner.
- **Emergency Panic Wipe**:
  - Armed 3-tap panic button in Settings.
  - Securely shreds SQLCipher database files with random bytes before filesystem deletion.
  - Clears encrypted preferences, wipes Keystore keys, and terminates the application process.
- **Material 3 Design System**:
  - Modern Charcoal & Mint Emerald visual theme (`#121417`, `#00E599`).
  - 4 primary navigation tabs: `Chats` (Unified Direct & Groups), `Nearby` (Live mesh & diagnostics), `Channels`, and `Settings`.
- **Message Reactions, Typing Indicators & Geo Pings**:
  - Emoji reactions on public broadcasts (`REACTION` — signed, broadcast, TTL 7; stored in the encrypted DB).
  - "Typing…" indicators in private chats (`TYPING` — signed, directed to the peer, TTL 1).
  - Opt-in coarse-area geo pings with privacy-safe geohashes (`GEO_PING` — signed, broadcast, TTL 2).

---

## Architectural Layers

Meshline is structured into strictly isolated, independently testable layers:

```
+---------------------------------------------------------------------------------------+
|                                    PRESENTATION                                       |
|  MainActivity.kt - 4-Tab Scaffold (Chats | Nearby | Channels | Settings)              |
|  ChatsUnifiedScreen  |  NearbyScreen  |  ChannelsScreen  |  ProfileAndSettingsScreen  |
+---------------------------------------------------------------------------------------+
                                          | StateFlow / Intents
+---------------------------------------------------------------------------------------+
|                                 APPLICATION SERVICE                                   |
|  BleMeshService.kt - Foreground Service (Foreground Service Type: connectedDevice)    |
|  Peer Profile Cache  |  Diagnostics & Metrics  |  Intent Dispatcher & Lifecycle Scope |
+---------------------------------------------------------------------------------------+
              |                                          |
+-------------v-------------------------+      +---------v------------------------------+
|            DOMAIN SERVICES            |      |            PERSISTENCE (Encrypted)     |
| - SecureChannel.kt (Noise XX 1-on-1)  |      | - MeshlineDatabase v7 (SQLCipher v4)   |
| - GroupManager.kt (Epoch AEAD Groups) |<---->| - MessageStore, ContactsRepository     |
| - ChannelManager.kt (Topic Channels)  |      | - GroupEntities & DAOs (Groups, Msgs)  |
| - CommandParser.kt (Slash Commands)   |      | - RetentionStore & PanicWiper          |
+---------------------------------------+      +----------------------------------------+
              | Signed MeshPackets
+-------------v-------------------------------------------------------------------------+
|                                    ROUTING LAYER                                      |
|  MeshRouter.kt                                                                        |
|  - Managed Flooding & Next-Hop Forwarding (Max 7 hops)                                |
|  - RateLimiter (Token bucket per peer: 10 pkts burst / 2 pkts/sec sustained)         |
|  - BloomFilter & LRU Seen Cache (1,024 packets deduplication)                         |
|  - PacketSigner & Signature Verification (Ed25519)                                    |
+---------------------------------------------------------------------------------------+
              | FragmentPackets
+-------------v-------------------------------------------------------------------------+
|                               TRANSPORTS & PACKET ENGINE                              |
|  - PacketCodec.kt (Fixed Header: Magic 0x4D4C, Type, Flags, TTL, Seq, ID, Recipient) |
|  - FragmentEngine.kt (MTU Fragmentation & Reassembly)                                 |
|  - BleTransport.kt (Dual-Role GATT Server + Client)                                   |
|  - InternetRelayTransport.kt (Optional Nostr WebSocket Transport)                     |
+---------------------------------------------------------------------------------------+
```

The core protocol codec (`protocol/`) and router (`router/`) have **zero Android platform dependencies**, enabling fast JVM unit testing and simulation.

---

## Wire Protocol & Frozen Constants

Meshline packets use a canonical fixed 20-byte binary header followed by payload and optional 64-byte Ed25519 signature:

- **BLE Service UUID**: `a777356e-7ebc-429e-a847-d52eaeeb1331`
- **BLE Characteristic UUID**: `cf602b5c-31b5-4b91-aa65-9272bd79cd3f`
- **Protocol Magic**: `0x4D4C` ("ML" in ASCII)

### Binary Header Format

```
Offset  Size  Field          Description
0       2     magic          0x4D4C ("ML")
2       1     type           PacketType (0x01..0x0A)
3       1     flags          Bit 0: HAS_RECIPIENT (0x01), Bit 1: HAS_SIGNATURE (0x02)
4       1     ttl            Hop limit (default 7, decremented at each forward)
5       1     reserved       Reserved (0x00)
6       2     seq            Monotonic big-endian sequence number
8       8     sender_id      First 8 bytes of SHA-256(Ed25519 public key)
16      8     recipient_id   Broadcast ID (0x00..00), Peer ID, or 8-byte Group Tag
24      N     payload        Packet payload bytes
24+N    64    signature      Optional Ed25519 signature over header || payload
```

### Packet Types

- `0x01`: `ANNOUNCE` — Public profile broadcast (Ed25519 key, display nickname, avatar).
- `0x02`: `PUBLIC_CHAT` — Plaintext unencrypted public mesh broadcast.
- `0x03`: `DIRECT_MESSAGE` — Noise XX encrypted 1-on-1 payload.
- `0x04`: `DELIVERY_ACK` — Cryptographic delivery acknowledgment.
- `0x05`: `READ_RECEIPT` — Explicit read receipt.
- `0x06`: `FRAGMENT` — MTU fragmentation packet for constrained links.
- `0x07`: `KEY_EXCHANGE` — Noise XX handshake message (`XX_MSG_1`, `XX_MSG_2`, `XX_MSG_3`).
- `0x08`: `CHANNEL_MESSAGE` — Topic channel message (`#general`, `#mesh`, etc.).
- `0x09`: `GROUP_MESSAGE` — ChaCha20-Poly1305 AEAD encrypted group chat payload.
- `0x0A`: `GROUP_CONTROL` — Group control payload (epoch rotation, member updates).
- `0x0B`: `REACTION` — Broadcast emoji reaction on a public broadcast message (signed, broadcast, TTL 7).
- `0x0C`: `TYPING` — Directed "typing…" indicator (signed, directed to the peer, TTL 1).
- `0x0D`: `GEO_PING` — Broadcast privacy-safe geo beacon with a geohash (signed, broadcast, TTL 2).

---

## Build & Test Instructions

### Prerequisites

- **Java**: JDK 17
- **Android SDK**: `compileSdk 36`, `targetSdk 36`, `minSdk 26`
- **Build Tools**: Android Build Tools 36.0.0, Gradle 8.14, AGP 8.12

### Building from Command Line

```powershell
# Run the complete unit test suite (150+ unit tests across 13 suites)
.\gradlew.bat :app:testDebugUnitTest

# Build universal debug APK (all ABIs, installs on devices & emulators)
.\gradlew.bat :app:assembleDebug

# Build release smoke APK (minified with R8, resource shrunk, signed with local key)
.\gradlew.bat :app:assembleReleaseSmoke

# Build unsigned release APK (ABI splits)
.\gradlew.bat :app:assembleRelease
```

---

## Build Artifacts

| Target Artifact | Build Type | Output Path | File Size |
| :--- | :--- | :--- | :--- |
| **Universal Debug APK** | Debug | `app/build/outputs/apk/debug/app-universal-debug.apk` | **23.15 MB** |
| **ReleaseSmoke ARM64** | Release (Smoke Key) | `app/build/outputs/apk/releaseSmoke/app-arm64-v8a-releaseSmoke.apk` | **5.57 MB** |
| **ReleaseSmoke ARMv7** | Release (Smoke Key) | `app/build/outputs/apk/releaseSmoke/app-armeabi-v7a-releaseSmoke.apk` | **5.22 MB** |
| **Release Unsigned ARM64** | Release (Unsigned) | `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk` | **5.41 MB** |

---

## Documentation

Detailed architectural and technical documents are available in the [`docs/`](docs/) directory:

- [docs/architecture.md](docs/architecture.md): Full system architecture and component interactions.
- [docs/reference-parity.md](docs/reference-parity.md): Technical capability ledger, protocol invariants, and delivery states.
- [docs/security-threat-model.md](docs/security-threat-model.md): Threat modeling, cryptographic guarantees, and adversary analysis.
- [docs/testing-and-release.md](docs/testing-and-release.md): Testing methodology, physical device verification checklist, and release preparation.

---

## License

This project is licensed under the terms of the Apache License, Version 2.0.
