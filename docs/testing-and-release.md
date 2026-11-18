# Meshline Testing & Release Verification Guide

This guide documents the automated test suite, physical device testing protocols, build procedures, and release verification standards for **Meshline**.

---

## 1. Automated Unit Test Suite

The test suite runs entirely on the JVM without requiring Android devices or emulators, providing instant feedback on protocol correctness, cryptographic operations, routing algorithms, and command parsing.

### Executing Tests
```powershell
.\gradlew.bat :app:testDebugUnitTest
```

### Test Coverage Highlights
- **`GroupProtocolTest`**: Validates HKDF-SHA256 8-byte group tag derivation, 18-byte associated data construction, ChaCha20-Poly1305 AEAD encryption/decryption roundtrips, rejection of tampered ciphertexts and mismatched AD (epochs/senders), and group invite serialization.
- **`CommandParserTest`**: Validates all IRC slash commands (`/help`, `/join`, `/channels`, `/msg`, `/who`, `/nick`, `/block`, `/unblock`, `/clear`, `/slap`), error formatting for missing parameters, ordinary chat text passthrough, and escaped leading slashes (`//text` $\to$ `/text`).
- **`ChannelProtocolTest`**: Validates `CHANNEL_MESSAGE` (0x04) payload encoding/decoding, topic normalization (`#channel`), unread count tracking, and message deduplication.
- **`NoiseSessionTest` & `NoiseHandshakeTest`**: Validates the 3-message `Noise_XX` handshake pattern, static key authentication, symmetric cipher state initialization, and 128-nonce replay window enforcement.
- **`MeshRouterTest` & `MeshRouterSimulationTest`**: Validates TTL decrementing, duplicate packet suppression, 10 pkt/s rate limiting, signature verification before deduplication, and direct routing shortcuts.
- **`PacketCodecTest` & `VectorsTest`**: Enforces strict big-endian binary packet serialization against frozen test vectors.

---

## 2. Physical Two-Device Testing Protocol

> [!IMPORTANT]
> Bluetooth Low Energy (BLE) peripheral advertising and central scanning **do not function inside Android emulators**. Full radio verification requires two physical Android devices running Android 8.0+ (API 26+).

### Step-by-Step Two-Device Checklist

| Step | Verification Procedure | Expected Result |
|---|---|---|
| **1. Installation** | Install `app-debug.apk` or `app-releaseSmoke.apk` on Device A and Device B. | Clean installation without permission crashes. |
| **2. Permission Grant** | Launch app on both devices. Allow requested Bluetooth and Location (on Android < 12) permissions. | Status indicator displays "● BLE MESH" with green dot. |
| **3. Mesh Announcement** | Tap "Start Mesh (BLE)" on both phones. Observe Nearby tab. | Devices discover each other within ~5–10 seconds. Direct peer count updates to $\ge 1$. |
| **4. Public Broadcast** | On Device A, enter a 500-character message in Nearby tab and tap "Broadcast". | Message appears on Device B showing sender fruit avatar, nickname, and "direct" (0 hops) badge. |
| **5. QR Contact Exchange** | On Device A, tap Settings $\to$ "My QR Code". On Device B, tap "Scan QR Code" and scan Device A's screen. Confirm the contact. | Device A appears in Device B's contacts directory with "✓ verified" badge. Keys added to protected trust tier. |
| **6. 1-on-1 Private E2EE Chat** | On Device B, open verified contact Device A and send a private message. | Outbox progresses from `Queued` $\to$ `Sent` $\to$ `Delivered` $\to$ `Read` upon receipt acknowledgment. |
| **7. Encrypted Group Chat** | On Device A, tap Chats $\to$ Groups $\to$ `+`. Name the group "Alpine Camp", select fruit avatar, check Device B, and tap "Create". | Device B receives a Noise-encrypted group invite card. Tapping "Accept" admits Device B. Messages sent in the group decrypt with epoch 1 keys. |
| **8. Topic Channels** | On Device A, switch to Channels $\to$ `#emergency` and broadcast an alert. | Alert displays in Device B's `#emergency` channel with matching timestamp and sender avatar. |
| **9. Screen-Off & Background** | Lock screen on Device B. Send a message from Device A. | Foreground service stays running; message is received and processed in the background. |
| **10. 3-Tap Emergency Panic Wipe** | In Settings, tap the red "Armed Panic Wipe" button 3 times and confirm. | All keys, contacts, sessions, messages, and SQLCipher database are permanently erased. App resets to fresh identity on next launch. |

---

## 3. Build & Release Artifacts

Meshline supports two optimized Gradle build configurations:

### 3.1 Debug Build
```powershell
.\gradlew.bat :app:assembleDebug
```
- Produces `app-debug.apk` containing universal ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`).
- Optimized for rapid iterative development and testing.

### 3.2 Release Smoke Build
```powershell
.\gradlew.bat :app:assembleReleaseSmoke
```
- Full R8 / ProGuard code shrinking and resource minification enabled.
- Excludes emulator ABIs; produces split APKs for `arm64-v8a` and `armeabi-v7a`.
- Strictly signed with the local debug signing configuration to verify release readiness without manual key setup.
- Size budget: Each resulting release APK is well under the **25 MB limit**.
