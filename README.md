# Meshline — offline BLE mesh messenger (Android MVP)

**Working title.** Inspired by BitChat: peers form a Bluetooth Low Energy mesh and relay
messages hop-by-hop with **no internet, no SMS, no accounts, no servers**. Private
messages are Noise-encrypted; public messages are signed but *not* encrypted.

This repository implements **Phase 1 (MVP) for Android only**, in phases A→E. **Phases A–C
are complete in this checkout** (project setup, protocol spec, codec + router +
simulator + BLE transport). Phases D–E are stubs/design below — do not mistake stubs for
working crypto.

## Assumptions (session brief)

- Project location: `Projects/meshline`. Package/applicationId: `com.meshline.app`.
  The PRD uses "Meshline" as a working title and does not name the applicationId.
- Latest SDK available in the dev environment is **36** (`android-36` platform +
  build-tools 36.0.0 installed). `minSdk 26`, `compileSdk 36`, `targetSdk 36`.
- Versions pinned for JDK 17 + Gradle 8.14 + AGP 8.12 + Kotlin 2.1 (see
  `build.gradle.kts`). If your SDK has a newer platform, bump `compileSdk`/`targetSdk`.
- Noise (`noise-java`) is **deferred to Phase D**. Phase A ships only BouncyCastle
  for primitives + SHA-256 in the codec, so the wire format can be frozen and fuzzed
  before any key material exists. Inventing crypto primitives or protocol is out.
- Room entities + KSP are wired in D1 (`storage/ContactsStore.kt`) and encrypted
  with the maintained **`net.zetetic:sqlcipher-android`** artifact. The DB
  passphrase is 32 random bytes wrapped by a Keystore AES-GCM key
  (`security/DbPassphrase.kt`), never derived from a static value.
- BLE service UUID (`a777356e-7ebc-429e-a847-d52eaeeb1331`) and characteristic UUID
  (`cf602b5c-31b5-4b91-aa65-9272bd79cd3f`) were generated once (random v4) and are
  now **frozen** in `transport/BleUuids.kt`. Changing either is a wire break and
  needs a protocol-version bump + new test vectors.

## Phases

| Phase | What | Status in this checkout |
|-------|------|---------------------------|
| **A** | Project setup, `PROTOCOL.md`, `PacketCodec` + unit tests + test vectors | ✅ **Done** |
| **B** | `MeshRouter` + in-memory mesh simulator (loss/latency/duplicate, 30-node flood test) + Noise design gate | ✅ **Done** (signing input TTL-invariant; dedupe stores highest TTL; signature-first ordering; per-connection rate limits; 20-seed storm measurements) |
| **C** | Real BLE transport (dual-role GATT, announce, public chat 2+ phones) + frozen UUIDs | ✅ **Done** (`transport/BleTransport.kt`, `BleUuids.kt`, `service/BleMeshService.kt`) — needs physical phones to validate (see below) |
| **D1** | Identity, profile, QR, contacts (Keystore-backed identity) | ✅ **Done** (Keystore identity + BC primitives, QR strict parser + fuzz, protected tier, contacts DB, My QR / Scan / Contacts screens, ANNOUNCE avatar+signature+interval, router verifier; BLE untouched) |
| **D2** | Noise + private chat + encrypted storage + panic wipe | 🟡 **Protocol + storage layer done** (branch `d2-noise`): Noise state machine (official spec vectors as a gate), explicit transport nonce + 128-nonce replay window, session store in SQLCipher, handshake rate limits + quarantine. **Not wired into BLE or the UI** until the D1 phone test passes. |
| **E** | Group chat (see `GROUPS_DESIGN.md`) | Draft (design only) |
| **F** | Foreground-service polish, permission flow, power modes, UI polish, merged-manifest audit | *(was Phase E; renumbered when groups moved in)* |

## Architecture (kept separate and unit-testable)

```
UI (activity/compose)
  -> ViewModels (viewmodel/ViewModels.kt)
    -> MessagingService (Phase B)
      -> MeshRouter (router/MeshRouter.kt, Phase B)
        -> PacketCodec (protocol/, Phase A ✅ — pure JVM)
          -> Transport (interface: transport/Transport.kt; impls: simulator B, BLE C)
SecurityModule (security/, Phase D)   Storage (storage/, Phase D)
```

The codec and router (`protocol/`, `router/`) have **no Android imports**. BLE, UI
and crypto layers depend on them, never the other way around. The in-memory
simulator (Phase B) reuses the exact same codec and router. `BleTransport` is the
only module that touches `BluetoothGatt*` APIs; it implements `transport/Transport.kt`,
so Phase C keeps the layering.

## How to run Phase A + B tests (JVM) and Phase C test (physical phones)

### Prerequisites

- **Unit tests (Phases A + B): pure JVM.** No emulator or phone is needed and BLE
  is not involved. You need JDK 17 and the Android SDK (for the Gradle Android plugin).
- **Phase C (BLE): physical Android devices only.** BLE does not work on
  emulators. Discovery latency (<10 s), multi-hop delivery, and the 4%/2% battery
  targets can only be verified on real hardware — see `PROTOCOL.md` §9 and the PRD.

### JVM commands

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\jdk-17" # JDK 17 (your local path, never commit)
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd meshline
.\gradlew.bat :app:testDebugUnitTest          # test suite (see below for counts)
.\gradlew.bat :app:assembleDebug              # app-universal-debug.apk (universal, all ABIs)
.\gradlew.bat :app:assembleRelease            # per-ABI release APKs (minified, unsigned)
.\gradlew.bat :app:assembleReleaseSmoke       # release-shaped, signed with the DEBUG keystore
.\gradlew.bat :app:lintDebug                  # clean
```

Debug output is `app/build/outputs/apk/debug/app-universal-debug.apk` (one
universal APK with all four ABIs, so it also installs on an x86_64 emulator).
Release output is `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`
and `app-armeabi-v7a-release-unsigned.apk` (ABI splits, no universal APK).
`releaseSmoke` is minified/shrunk exactly like release but signed with the local
debug keystore for on-phone smoke testing:
`app/build/outputs/apk/releaseSmoke/app-arm64-v8a-releaseSmoke.apk` (and the
`armeabi-v7a` variant). **Never distribute a `releaseSmoke` APK**; the real
`release` artifact stays unsigned until you supply a real key.

Which outputs are produced is decided **per build type**, not per command line,
so `.\gradlew.bat :app:assembleDebug :app:assembleReleaseSmoke` in one
invocation still gives you the universal debug APK:

| Build type | Outputs | Purpose |
|---|---|---|
| `debug` | 1 universal APK (arm64-v8a, armeabi-v7a, x86_64, x86) | emulator UI/DB tests, daily dev |
| `release` | `arm64-v8a` + `armeabi-v7a` splits, unsigned | real distribution (needs your key) |
| `releaseSmoke` | `arm64-v8a` + `armeabi-v7a` splits, debug-key-signed | release smoke test on a phone |

Expected (D1): `PacketCodecTest` 21, `MeshRouterTest` 12, `MeshSimulatorTest` 2,
`KeyKnowledgeTest` 12, `KeyCacheTest` 6, `AnnounceVerifierTest` 5,
`IdentityTest` 1, `QrPayloadTest` 4, `BleTransportTest` 5,
`HoldingBufferTest` 4, `SampleQrGeneratorTest` 1 — **73 tests, 0 failures**. No
INTERNET permission on the **merged manifest** or in
`aapt dump permissions app-universal-debug.apk`.

On the `d2-noise` branch add: `NoiseVectorsTest` 1, `NoiseSessionTest` 8,
`NoiseNonceOrderingTest` 6, `ReplayWindowTest` 6, `HandshakeRateLimiterTest` 9,
`SessionCodecTest` 4, `SessionStoreTest` 2, `OutboxStoreTest` 2 —
**111 tests, 0 failures** in total.

> **Instrumented tests destroy app data.** `:app:connectedDebugAndroidTest`
> typically uninstalls the app (and its data) after the run, and the
> Keystore/DB wipe tests (`IdentityStoreInstrumentedTest`,
> `DbPassphraseInstrumentedTest`, `DatabaseInstrumentedTest`) deliberately
> destroy the app's identity and contacts. Run instrumented tests **before** any
> manual phone testing, then reinstall the APK and start with a fresh
> identity/contacts. They require a connected device:
> `.\gradlew.bat :app:connectedDebugAndroidTest`.

### Dev-only sample QR (test one phone against a laptop screen)

The JVM test source set contains a generator that writes a scannable QR for a
throwaway identity:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*SampleQrGeneratorTest*"
# app/build/reports/sample-contact-qr.png
```

It is never packaged into any APK (debug/release/releaseSmoke). Scan the PNG from
the laptop screen with one phone; the contact is intentionally never online.

## Protocol in one page (full spec: [`PROTOCOL.md`](PROTOCOL.md))

Binary, big-endian, 32-byte fixed header:

```
version(1)=1 | type(1) | ttl(1) ≤7 | flags(1) | timestampMs(8) | reserved(2)=0
| payloadLen(2 BE) | senderId(8) | recipientId(8, broadcast = 0xFF*8)
| payload | signature(64, iff HAS_SIGNATURE)
```

Types: `ANNOUNCE 0x01`, `PUBLIC_MESSAGE 0x02`, `PRIVATE_MESSAGE 0x03`,
`CHANNEL_MESSAGE 0x04`, `RECEIPT 0x05`, `FRAGMENT 0x06`, `LEAVE 0x07`,
`HANDSHAKE 0x08`. Flags: `HAS_RECIPIENT 0x01`, `HAS_SIGNATURE 0x02`;
`COMPRESSED`/`FRAGMENTED` are rejected in MVP (fail-closed); reserved bits must be 0.

Message ID = `SHA-256(senderId ‖ timestamp BE ‖ type ‖ payloadLen BE ‖ payload)`.
Sender/recipient IDs are 8-byte truncated SHA-256 of a 32-byte Ed25519 public key.

Worked example (test vector `public_message_broadcast_unsigned`, payload `"hi"`,
timestamp 1700000000000, sender `0102030405060708`):

```
01020700 0000018BCFE56800 0000 0002 0102030405060708 FFFFFFFFFFFFFFFF 6869
```

Fragmentation (§5 of the spec): chunks of the *entire encoded packet* inside
`FRAGMENT` payloads (`messageId(32) ‖ index(2) ‖ total(2) ‖ chunk`), reassembled
in order with a 30 s timeout, then decoded once (signature verified there).

## Physical-phone test — Phase C (you do this, I cannot run BLE here)

**You need:** 2 physical phones (any brands; ideally different vendors), both
Android 8.0+, both with Bluetooth LE and location services on. Install the same
`app-universal-debug.apk` on both. Keep both on the same desk first (within 1 m).

1. **Permissions.** On each phone: Settings → Apps → Meshline → Permissions.
   Grant Nearby devices / Bluetooth (and Location on Android ≤ 11). Deny
   Notifications once to confirm the app still runs (notification returns in Phase E).
2. **Discovery (<10 s AC).** Open Meshline on both, tap **Start mesh (BLE)**.
   Note the clock. Each phone should log `BleMeshService up as anonXXXX`
   (Logcat tag `BleMeshService`). Within ~10 seconds each should find the other
   (the ANNOUNCE exchange happens over GATT). If nothing appears, check that
   both Bluetooth radios are on, distance < 10 m, and no other app is hogging
   the radio.
3. **Public message (2-phone).** Type `hello from phone A` on phone A, send.
   Phone B's Nearby feed should show it within a couple of seconds. Reverse
   the direction. (The public composer in this build is a Phase C placeholder
   bound to `BleMeshService.sendPublic`; private-chat delivery and read
   receipts are Phase D.)
4. **500-char message both ways (fragmentation).** Paste a 500-character string
   (the composer max) on phone A, send; confirm phone B receives it intact
   (fragmentation + reassembly). Repeat B → A. The debug overlay should show
   the received TTL/hops.
5. **Multi-hop (3-phone).** Put phone A and C > 15 m apart (through a wall if
   you can). Phone B in the middle, within 10 m of both. Send from A → C must
   still arrive via B (B's router relays). This is the user AC: "a third phone
   out of range of the first can receive via the middle phone."
6. **Screen off (5 min), then send.** On phone A, lock the screen for 5 minutes
   with the mesh running (the foreground service must keep BLE alive). Unlock
   and send — confirm delivery and that the debug overlay still shows peers.
7. **Deny Bluetooth permission path.** Uninstall/reinstall, deny the Bluetooth
   permission prompt (don't grant). Confirm: no crash, clear on-screen message
   / log (`Missing BLE runtime permissions`), and the app stays usable offline
   (no peers expected). Then grant and repeat step 2.
8. **Kill/relaunch.** Force-stop the app on phone B, relaunch, and confirm
   neighbours reappear within ~10 s (fresh ANNOUNCE on reconnect).
9. **Battery (Phase F gate, screen-off, 30 min, 2 phones).** On each phone:
   record battery % (`Settings → Battery`) with the mesh idle in the foreground
   service, lock the screen for 30 minutes, unlock and record % again.
   Compute **% per hour** (and mAh if available: `adb shell dumpsys battery`
   before/after). Do this twice: (a) no traffic, (b) one public message every
   5 min. Report both numbers — duty-cycle maths is not battery drain.
10. **Report back:** phone models + Android versions, distance, whether
    discovery beat 10 s, which hop counts you saw, and any Logcat tags
    `BleTransport` / `BleMeshService` errors (especially GATT 133 or permission
    denials). That data closes the Phase C AC and seeds Phase E battery tuning.

**Manual scan checklist (D1, on phones):**
- **Integrator check:** `ScanQRScreen` is a real CameraX + ZXing-core implementation
  (not a placeholder): `ImageAnalysis` + `MultiFormatReader` with the `QR_CODE` hint
  only, no other formats, and rotation from `ImageProxy.imageInfo.rotationDegrees`.
- Try a QR code in **dim light**, under **screen glare**, printed large, and
  **scanning another phone's screen**. A malformed or foreign (non-Meshline) QR
  must show the error state, never crash.
- **Reboot + update:** reboot each phone and confirm the *same* fingerprint
  survives; update the APK (retained data) and confirm contacts/history survive.
  If the OS invalidates the Keystore key after an upgrade, load must fail closed
  (regenerate identity) rather than crash — report the OEM behaviour.

**Battery (Phase F gate, screen-off, 30 min, 2 phones).** On each phone:
record battery % (`Settings → Battery`) with the mesh idle in the foreground
service, lock the screen for 30 minutes, unlock and record % again.
Compute **% per hour** (and mAh if available: `adb shell dumpsys battery`
before/after). Do this twice: (a) no traffic, (b) one public message every
5 min. Report both numbers — **duty cycle is not battery drain**.

**D1 phone checklist (run after installing the `d1-identity` build):**
1. Two phones on this branch discover each other — signed ANNOUNCEs verify
   (debug overlay shows `ttl` + `hops`; `droppedUnknownKey` stays low). The
   overlay also shows `held` / `released` / `bufFull`: right after a join
   `held` rises and `released` catches up — a packet that is held and then
   released was **not** lost.
2. Public messages still flow between the two phones, and each is **signed and
   verified** (Logcat shows no `droppedInvalid` spike on valid traffic).
3. **QR add-contact in both directions:** phone A shows My Profile QR, phone B
   scans it → scan-confirmation → verified badge appears. Swap roles.

**Logcat (per phone, replace `<serial>` with `adb devices` output):**
```powershell
adb -s <serial> logcat -s BleTransport BleMeshService
```
Look for: `BleMeshService up`, `duplicate link closed`,
`isMultipleAdvertisementSupported=...`, and any GATT error codes in the debug
overlay's `last GATT error` line.

**Frozen wire constants (do not change without a version bump):**
service `a777356e-7ebc-429e-a847-d52eaeeb1331`, characteristic
`cf602b5c-31b5-4b91-aa65-9272bd79cd3f`, MTU 512 (payload 509), max connections 6.

## D1 integration status (wired, awaiting phone verification)

D1 is wired into the running app:
- `BleMeshService` loads the Keystore identity, signs ANNOUNCE and
  PUBLIC_MESSAGE, verifies every incoming packet through `AnnounceVerifier` +
  the two-tier `KeyCache`, preloads QR-verified contacts into the protected
  tier, and runs as a connected-device foreground service.
- `MainActivity` navigates Home / My QR / Scan / Contacts / Settings, asks for
  camera permission only when **Scan** is tapped, and sends the public composer
  text through `ACTION_SEND_PUBLIC`.
- Room + SQLCipher opens through `SupportOpenHelperFactory`; QR-added contacts
  persist across restart.
- If the Keystore wrap key is invalidated or the DB cannot be decrypted, the app
  fails closed: it creates a new identity / empties contacts and shows the
  **“secure storage was reset”** dialog. Peers see a new key (D2 adds the
  key-change warning).
- A packet from a sender whose key is not known yet is **held** (32 total / 8
  per connection / 3 s) and released when that sender's verified ANNOUNCE
  arrives, instead of being dropped on the spot. `droppedUnknownKey` counts
  **only** held packets that timed out; a full buffer is counted separately as
  `droppedBufferFull`, so an unknown-sender flood cannot delay real traffic.
- After linking a **new** peer, the sender waits **2 s** before its first data
  packet to it, so the peer has learned the sender's key from the ANNOUNCE
  first.

The D1 phone checklist above is the gate. **D2 does not start until it passes.**

### Release smoke-test checklist (run on the release APK)

1. Install `app-arm64-v8a-releaseSmoke.apk` (or the `armeabi-v7a` variant). It
   is minified/shrunk like release but signed with the local debug keystore, so
   no manual signing is needed. **Never distribute this APK.**
2. Launch: the contacts DB opens (no crash); Home shows `fruit nickname - 4hex`.
   Confirm the merged manifest has **no INTERNET** permission.
3. **My QR** renders; on phone B tap **Scan** → camera permission prompt appears
   only now → scan → confirmation → verified contact appears. With only one
   phone, scan the sample PNG on your laptop screen instead — see
   “Dev-only sample QR” above.
4. Force-stop and relaunch: same fingerprint, contact still listed.
5. Send a public message between the two phones; both show it (release strips
   `Log.d`, so use the debug build if you want the `BleMeshService up` line).
6. Optional destructive drill: clear the app's data / Keystore entry and relaunch
   → “secure storage was reset” dialog, **new** 4-hex id, empty contacts, no crash.

### Release artifact size (per ABI, budget < 25 MB)

Measured with `:app:assembleRelease` (minified, resource-shrunk, unsigned) on
2026-11-10:

| Artifact | Bytes | MiB | Budget |
|---|---:|---:|---|
| `app-arm64-v8a-release-unsigned.apk` | 5,673,688 | 5.41 | < 25 MB |
| `app-armeabi-v7a-release-unsigned.apk` | 5,311,786 | 5.07 | < 25 MB |
| `app-arm64-v8a-releaseSmoke.apk` (debug-key-signed) | 5,682,204 | 5.42 | < 25 MB |
| `app-armeabi-v7a-releaseSmoke.apk` (debug-key-signed) | 5,320,302 | 5.07 | < 25 MB |

Each release/releaseSmoke APK contains exactly one ABI and
`lib/.../libsqlcipher.so`, and none of them declares
`android.permission.INTERNET`. The `releaseSmoke` APKs are signed with the
local **debug** certificate (`CN=Android Debug`) — they are for the on-phone
smoke test only, never for distribution. The universal debug APK is
24,273,208 bytes (23.15 MiB) because it carries all four ABIs for
emulator/UI testing; it is not a release artifact. (The `d2-noise` branch
adds ~63 KB of `noise-java` on top of the D1 sizes.)

## D2 integration status (protocol + storage only, branch `d2-noise`)

The D2 layer is **coded and tested, but not wired in**: nothing in
`BleTransport`, `BleMeshService` or the UI calls it yet, so the shipping app
behaves exactly like `d1-identity` until the D1 phone test passes.

What exists (all pure JVM, all simulator-tested):
- **Noise state machine** (`noise/NoiseHandshake.kt`) — `Noise_XX_25519_ChaChaPoly_SHA256`
  over the pinned `org.signal.forks:noise-java:0.1.1`. The **official Noise
  test vectors** for that pattern are a test gate (`NoiseVectorsTest`): the
  handshake messages, the transcript hash and the transport ciphertexts must
  be byte-exact.
- **Transport nonce + replay window** (`noise/TransportCipher.kt`,
  `noise/ReplayWindow.kt`) — the nonce is explicit on the wire, and a
  128-nonce sliding window per direction refuses replays and nonces that are
  too far ahead.
- **Session store** (`storage/NoiseSessionStore.kt`) — the `noise_sessions`
  table in the SQLCipher database (DB version 2, non-destructive migration),
  plus a versioned snapshot codec.
- **Handshake rate limits and quarantine** (`noise/HandshakeRateLimiter.kt`) —
  per *connection* (never per claimed `senderId`): 5 handshake packets / 10 s,
  20 starts / hour, 60 s cooldown after a success, 30 packets / 10 s per node,
  3 invalid strikes in 5 min → 10 min quarantine.
- **Secure-channel engine** (`noise/SessionManager.kt`) — the per-peer session
  state machine, the identity binding check (Ed25519 trailer ↔ `senderId`) and
  the impersonation defence (the X25519 key the handshake reveals must match
  the announced one), the concurrent-handshake tie-break (lower `senderId`
  wins) and the silent re-handshake on session loss.
- **Store-and-forward outbox** (`storage/OutboxStore.kt`) — the sender's outbox
  stores the **plaintext** (protected by SQLCipher) and encrypts **at send
  time** under the session that is live then, so a queued message never
  carries a stale nonce and never belongs to a session that no longer exists.
  Relays never cache private ciphertext. User-visible states: **Queued** →
  **Sent** (after the silent re-handshake) → **Delivered** / **Read**.
- **Global handshake work budget** (`noise/HandshakeRateLimiter.kt`) — 300
  X25519 operations per minute across *all* connections, because the
  per-connection buckets reset when a peer reconnects under a new BLE address.

Wire format: `PROTOCOL.md` §6.3 and §6.5. Design and its amendments:
`NOISE_DESIGN.md`.

**Known limitation:** `noise-java` 0.1.1 cannot export the Noise transport
keys, so a session cannot be resumed byte-for-byte after a restart — a
restored session **re-handshakes silently** instead (see `NOISE_DESIGN.md`
amendment A2). Everything else in the store works as designed. The
`noise_sessions.transportKey` column is **never written** (the store's
mapping always stores NULL), so there is no key material on disk that the app
cannot use — and no second secret for a panic wipe to chase.

### Review round 2 (D2)

Three more review items were closed on this branch:

1. **Authenticate before acting.** The AEAD tag is now verified **before** the
   replay window or any drift logic may act. A forged packet (bad tag) is
   dropped silently: it does not move the replay window, is not counted as a
   violation, does not change the session and does **not** ask for a
   re-handshake — otherwise anyone could force one with a single made-up
   packet. Only a **genuine, authenticated** message whose nonce is beyond
   the window triggers the clean re-handshake. (This also means a ciphertext
   re-wrapped in a fresh outer packet fails authentication rather than
   reaching the replay window — the AEAD catches it, the window is the second
   line of defence.)
2. **Outbox reliability.** A row stays in the outbox after *Sent* until a
   signed receipt (Delivered) or the 12 h expiry; retries use an exponential
   backoff and are **re-encrypted** under the live session with a **fresh
   nonce** each time, while an **application-level message id** (inside the
   ciphertext) makes the receiver show the message exactly once. The receipt
   is idempotent, so a repeat is acknowledged again. Proven by a simulator
   test with **30 % link loss**: every one of 20 messages is delivered exactly
   once.
3. **Reserved handshake budget.** Half of the global X25519 budget
   (150 operations / minute) is reserved for senders in the protected key
   tier (QR contacts, connected neighbours), so junk handshakes cannot starve
   real friends.

### Review round 1 (D2)

Four review items were closed on this branch:

1. **Explicit nonces with no key export.** The ChaChaPoly nonce is the 64-bit
   counter, which is *independent of the key*: we own it and set it on the live
   cipher state (`CipherState.setNonce`) before every operation. The key never
   leaves the library object, so the missing export does not affect the nonce
   design — and the receiver can decrypt **N+1 before N**.
   `NoiseNonceOrderingTest` proves it: out-of-order decryption, a duplicate
   refused, a gap of 100 lost messages tolerated inside the 128-nonce window,
   and a gap beyond it forcing a clean re-handshake.
2. **Store-and-forward under non-persistent sessions.** The outbox stores
   **plaintext** (SQLCipher-protected) and encrypts **at send time** under the
   live session; relays never cache private ciphertext. `NOISE_DESIGN.md` §6.1
   (FR-12) and `PROTOCOL.md` §7 were updated, with the user-visible states
   (Queued → Sent after the re-handshake → Delivered → Read).
3. **Global handshake work budget.** 300 X25519 operations per minute across
   all connections, because the per-connection buckets reset when a peer
   reconnects under a new BLE address.
4. **The transport key column is never written** — see above.

## Open issues after D1

- **Foreground-service permission gate (Phase F).** The service now fails soft
  (no crash) if Android rejects the FGS start, but the UI should explain which
  Bluetooth permission is missing instead of silently stopping.
- **Connection-slot exhaustion (Phase F).** Six GATT slots can be consumed by
  peers that never send a valid ANNOUNCE; planned mitigation is to close
  non-Announced connections after ~10 s.
- **Adaptive announce cadence (Phase E/F).** D1 announces at a fixed 30 s
  (`intervalUnits = 3`). The 30/60/120 s hysteresis rule (§8.4) is specified but
  not yet driven by known-node count.
- **Neighbour set is not wired into the router yet.** `neighboursProvider`
  returns an empty set, so D1 always floods instead of using the direct-send
  shortcut and adaptive relay probability. Correct, but more expensive at scale.
- **D2+**: Noise private chat, encrypted message storage, panic wipe, groups.

## Compliance notes (PRD §12)

- Public chat is signed, **not encrypted** — the UI labels this (Phase E).
- Meshline is **not an anonymity tool**; metadata (IDs, timing, size) is visible to
  relays. Do not market it for high-risk use until after an independent security audit.
- The v1 Android manifest declares **no INTERNET permission** — a verifiable
  no-network guarantee only if checked on the *merged* manifest.
