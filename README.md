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
- Room entities + the KSP plugin land in Phase D. Phase A declares Room/SQLCipher
  *runtime* dependencies only, no DAOs.
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
| D | Noise handshake, private chat, encrypted storage, panic wipe | Contract only (`security/`, `storage/`) — design in `NOISE_DESIGN.md` (approved with amendments) |
| E | Foreground service polish, permissions flow, power modes, UI polish, merged-manifest audit | Stub (`service/MeshlineService.kt`, placeholder Compose screen) |

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
$env:JAVA_HOME = "C:\Users\rahul\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2" # JDK 17
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd Projects\meshline
.\gradlew.bat :app:testDebugUnitTest          # 33 tests green at time of writing
.\gradlew.bat :app:assembleDebug              # app-debug.apk
.\gradlew.bat :app:lintDebug                  # clean
```

Expected: `PacketCodecTest` 21, `MeshRouterTest` 10, `MeshSimulatorTest` 2 — 33 total,
0 failures. No INTERNET permission on the **merged manifest** or in
`aapt dump permissions app-debug.apk`.

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
`app-debug.apk` on both. Keep both on the same desk first (within 1 m).

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
9. **Report back:** phone models + Android versions, distance, whether
   discovery beat 10 s, which hop counts you saw, and any Logcat tags
   `BleTransport` / `BleMeshService` errors (especially GATT 133 or permission
   denials). That data closes the Phase C AC and seeds Phase E battery tuning.

**Logcat (per phone, replace `<serial>` with `adb devices` output):**
```powershell
adb -s <serial> logcat -s BleTransport BleMeshService
```
Look for: `BleMeshService up as anonXXXX`, `duplicate link closed`,
`isMultipleAdvertisementSupported=...`, and any GATT error codes in the debug
overlay's `last GATT error` line.

**Frozen wire constants (do not change without a version bump):**
service `a777356e-7ebc-429e-a847-d52eaeeb1331`, characteristic
`cf602b5c-31b5-4b91-aa65-9272bd79cd3f`, MTU 512 (payload 509), max connections 6.

## Open issues after Phase C

- **UI send wiring is partial.** `MainActivity` has the composer + Start button;
  `BleMeshService.sendPublic` exists but is not yet bound to the composer's Send
  action with a live router handle — bind it (and the peer list from ANNOUNCE
  nicknames + signal + hops) at the start of Phase D while Phase E polishes UI.
- **Identity is ephemeral in Phase C** (random nickname + random senderId per
  process; placeholder key bytes in ANNOUNCE). Phase D replaces this with
  Keystore-backed Ed25519/X25519 pairs, real signatures, and fingerprint display.
- **Signature verification is permissive until Phase D.** The router already
  *orders* verification before dedupe/rate-limit/relay (PROTOCOL §8) — Phase D
  only injects the key lookup into the verifier hook.

## Compliance notes (PRD §12)

- Public chat is signed, **not encrypted** — the UI labels this (Phase E).
- Meshline is **not an anonymity tool**; metadata (IDs, timing, size) is visible to
  relays. Do not market it for high-risk use until after an independent security audit.
- The v1 Android manifest declares **no INTERNET permission** — a verifiable
  no-network guarantee only if checked on the *merged* manifest.
