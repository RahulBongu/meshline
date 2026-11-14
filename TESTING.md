# Meshline test day

Everything you need to test Meshline on real phones and emulators, in the order
it should be done. The point of this document is that **you can paste the
results back** and I can read exactly what happened.

- The build helper: [`tools/test-day.ps1`](tools/test-day.ps1)
- The protocol: [`PROTOCOL.md`](PROTOCOL.md)
- The Noise design: [`NOISE_DESIGN.md`](NOISE_DESIGN.md)

---

## 0. What gets built

There are three builds of interest, and they are built **side by side** with
`git worktree` — your checkout is never switched to another branch:

| build | git ref | APK | what it is |
|---|---|---|---|
| Phase C | tag `phase-c-unverified` | `meshline-phase-c.apk` | the public BLE chat, before identities |
| D1 | branch `d1-identity` | `meshline-d1.apk` | identity, QR contacts, signed traffic |
| D2 | branch `d2-noise` | `meshline-d2.apk` | Noise private chat, chat history, panic wipe |

All three are **debug** builds (debug-signed, so they install on any phone
without extra setup) and land in `.\test-apks\`.

```powershell
# Build all three (this takes a while: three full Gradle builds).
.\tools\test-day.ps1 -Build all

# Build one and install it on a phone.
.\tools\test-day.ps1 -Build d2 -Install d2 -Serial R58M1234ABC

# Build, install and run the instrumented tests.
#   WARNING: connectedDebugAndroidTest uninstalls the app and its data.
.\tools\test-day.ps1 -Build d2 -Install d2 -Serial emulator-5554 -RunTests
```

The script needs `JAVA_HOME` (JDK 17) and `ANDROID_HOME`; it finds them the same
way the README does. `adb` comes from `$env:ANDROID_HOME\platform-tools`.

> **The instrumented tests destroy the app's data.** They wipe the identity and
> the contacts (that is the point of the wipe tests). Run them **before** the
> manual tests, or reinstall the APK afterwards and start from a fresh identity.

---

## 1. Emulators (API 26 and API 34) — instrumented tests

BLE does not work on an emulator, so this step is only about the **database,
the migrations and the code paths that do not need a radio**.

```powershell
# Start an API 26 and an API 34 emulator, then:
.\tools\test-day.ps1 -Build d2 -Install d2 -Serial emulator-5554 -RunTests
```

The instrumented suite covers: the SQLCipher open/reopen with the wrong
passphrase, the identity store, the secure-storage reset notice, the D2 stores
and the **Room migration chain v1 → v6** (the contacts and the received
messages must survive it).

**Results — step 1**

| emulator | Android version | instrumented tests | result |
|---|---|---|---|
| API 26 | 8.0 | | pass / fail |
| API 34 | 14 | | pass / fail |

---

## 2. Two phones — Phase C build

Install `meshline-phase-c.apk` on **both** phones. Both phones must run the
**same** build.

1. Start the mesh on both, phones within 1 m of each other.
2. Note how long the peers take to appear.
3. Send a public message each way.
4. Check Logcat: `adb -s <serial> logcat -s BleTransport BleMeshService`.

**Results — step 2**

| phone | model | Android version | discovery time | MTU | GATT errors | first message delivered? |
|---|---|---|---|---|---|---|
| A | | | | | | |
| B | | | | | | |

---

## 3. Two phones — D1 build

Install `meshline-d1.apk` on both phones (**uninstall the Phase C build
first**: the signatures are the same, so an install over the top is fine, but
the identities are not — a fresh start is cleaner).

1. Start the mesh on both. The 4-hex id under your nickname must differ.
2. Send a public message each way.
3. **My QR** on A → scan it on B → confirm → the verified badge appears.
4. Scan B's QR on A → confirm → the badge appears the other way.
5. Force-stop and relaunch one phone: the same id, the contacts still there.

**Results — step 3**

| phone | model | Android version | discovery time | MTU | GATT errors | first message delivered? | QR both ways? |
|---|---|---|---|---|---|---|---|
| A | | | | | | | |
| B | | | | | | | |

---

## 4. Two phones — D2 build (the interesting one)

Install `meshline-d2.apk` on both phones. **Same build on both.**

1. Start the mesh on both.
2. **Private (encrypted)** tab → tap the other phone → type a message → **Send
   (encrypted)**.
   - The state must go **Queued → Sent → Delivered** (and **Read** when the
     other phone opens the chat).
3. **Not delivered + Resend:** on A, send a message to a phone that is *not*
   there (or turn B's screen off / move it out of range) and wait. The message
   must end as **Not delivered** (never "Sent" forever), and **Resend** must
   put it back on the wire.
4. **Retention:** Settings → Message retention → *1 day* (or *7 days*) → the
   history is purged by the periodic tick. Set it back to *Off* to keep
   everything.
5. **Clear chat:** in a private chat → **Clear chat** → the history of that
   conversation is gone (the other conversations are not).
6. **Chat history persists:** force-stop the app and relaunch it → the chat
   history is still there (the history lives in the encrypted database, not in
   memory).
7. **QR both ways** (as in step 3) → the verified badge appears.
8. **Wipe:** on A, *Nearby* tab → *Danger zone* → tap **Panic wipe** three
   times → confirm. A gets a **new** identity. B must show the **key-change
   warning** when A comes back; accept it (or reject it → A is blocked).
   Then scan A's **new** QR on B → the verified badge is back and the chat works
   again.
9. **Permission deny (the crash check):** revoke Meshline's *Nearby devices*
   permission in system settings (or *Location* on Android 11) while the app
   is closed, reopen it and tap **Start mesh**: the app must **not** crash — a
   red `MESH NOT STARTED — …` banner appears instead (the exact wording
   depends on the Android version). Then grant the permission again and tap
   **Start mesh**: the banner clears and the mesh comes up.

**Results — step 4**

| phone | model | Android version | discovery time | MTU | GATT errors | first message delivered? | Delivered state seen? | QR both ways? | wipe test |
|---|---|---|---|---|---|---|---|---|---|
| A | | | | | | | | | |
| B | | | | | | | | | |

Tick the boxes that passed:

- [ ] A → B private message reached **Delivered**
- [ ] B → A private message reached **Delivered**
- [ ] **Read** appeared on the sender
- [ ] a message with no peer ended as **Not delivered** (not "Sent")
- [ ] **Resend** put that message back on the wire and it was delivered
- [ ] retention (1 day / 7 days) purged the history
- [ ] **Clear chat** emptied one conversation only
- [ ] the chat history survived a force-stop and relaunch
- [ ] QR verified both ways, badge shown
- [ ] the panic wipe gave a new identity and the peer saw the key-change warning
- [ ] denying a Bluetooth permission showed the red banner instead of crashing; grant + Start mesh recovered
- [ ] accepting the new key restored the chat (and the badge came back only after a new QR scan)
- [ ] Logcat shows **no keys, no fingerprints and no message text**

---

## Notes

- **The emulator step first.** `connectedDebugAndroidTest` uninstalls the app
  and wipes its data; doing it afterwards would undo your manual setup.
- **Same build on both phones.** A D1 phone and a D2 phone cannot talk: the
  private chat needs both sides to speak the same protocol.
- **BLE realities:** discovery under 10 s, an MTU of 247 or more, and *GATT 133*
  (a stale connection) are normal on Android and handled by the transport —
  note how often they happen, that is the data I need for the Phase F polish.
- If something fails, the Logcat of **both** phones is the most useful thing
  you can paste back:
  ```powershell
  adb -s <serial> logcat -s BleTransport BleMeshService
  ```
