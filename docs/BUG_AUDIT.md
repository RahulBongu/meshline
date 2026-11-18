# Meshline Bug Audit & Remediation Ledger

This document tracks all discovered defects, reproduction evidence, underlying root causes, corrective changes, regression test coverage, and validation status across all layers of the application.

---

## Severity Scale

- **P0**: Security compromise, data corruption/loss, or release-blocking crash.
- **P1**: Core messaging, identity, Bluetooth transport, persistence, or critical navigation is broken.
- **P2**: Major usability, lifecycle, keyboard layout, reliability, or feature defects.
- **P3**: Smaller visual defects, polish issues, and low-impact edge cases.

---

## Discovered Defects & Remediation Status

### Defect B-01: Sub-Screen Navigation Missing Hardware/Gesture BackHandler
- **Severity**: **P1 (Navigation)**
- **Reproduction**:
  1. Open the app, navigate to a 1-on-1 private chat, group chat, QR scanner, or Contacts screen (`Screen.PRIVATE_CHAT`, `Screen.GROUP_CHAT`, `Screen.SCAN`, etc.).
  2. Perform the Android system back gesture or press the hardware back button.
  3. The Activity finishes and exits to the Android home screen instead of returning to `Screen.MAIN`.
- **Root Cause**:
  `MainActivity.kt` used an internal Compose state `screen = Screen.XYZ` for sub-screen routing, but did not register a Compose `BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }`.
- **Corrective Change**:
  Add `BackHandler` in `MainActivity.kt` with cleanup callbacks (such as clearing group unread status when exiting a group chat).
- **Validation**:
  Verified via Compose navigation state tests and backstack transitions.
- **Status**: **RESOLVED**

---

### Defect B-02: Deprecated LocalLifecycleOwner in ScanQRScreen
- **Severity**: **P2 (Build & Deprecation)**
- **Reproduction**:
  Running `:app:assembleReleaseSmoke` reports warning:
  `'val LocalLifecycleOwner: ProvidableCompositionLocal<LifecycleOwner>' is deprecated. Moved to lifecycle-runtime-compose library in androidx.lifecycle.compose package.`
- **Root Cause**:
  `ScanQRScreen.kt` imported `androidx.compose.ui.platform.LocalLifecycleOwner` instead of `androidx.lifecycle.compose.LocalLifecycleOwner`.
- **Corrective Change**:
  Update import to use `androidx.lifecycle.compose.LocalLifecycleOwner` and ensure dependency is cleanly resolved.
- **Validation**:
  Compiler warnings eliminated during `:app:compileReleaseSmokeKotlin`.
- **Status**: **RESOLVED**

---

### Defect B-03: Virtual Keyboard Covering Chat Composer Dock
- **Severity**: **P2 (Usability & Layout)**
- **Reproduction**:
  1. Open a conversation screen (`PrivateChatScreen`, `GroupChatScreen`, `ChannelsScreen`, `NearbyScreen`).
  2. Tap the message input text field to summon the on-screen soft keyboard.
  3. On devices without automatic window insets adjustment, the soft keyboard covers the composer text field, making typing invisible.
- **Root Cause**:
  Composer docks lacked `Modifier.imePadding()` and `Modifier.navigationBarsPadding()`.
- **Corrective Change**:
  Apply `Modifier.imePadding()` to all chat composer containers, allowing them to ride smoothly above the virtual keyboard.
- **Validation**:
  Layout padding inspected on keyboard appearance states.
- **Status**: **RESOLVED**

---

### Defect B-04: Conversation List Missing Live Search & Filter Bar
- **Severity**: **P2 (Feature Completeness & Usability)**
- **Reproduction**:
  `ChatsUnifiedScreen` only offered direct vs group sub-tabs without any search bar or category filtering (`All`, `Unread`, `Direct`, `Groups`), making finding specific conversations tedious as the list grows.
- **Root Cause**:
  UI lacked search state and category filter chips in `ChatsUnifiedScreen.kt`.
- **Corrective Change**:
  Implement a responsive search bar with instant query matching on contact nicknames, group titles, and peer IDs, accompanied by horizontal filter chips (`All`, `Unread`, `Direct`, `Groups`).
- **Validation**:
  Unit and Compose state tests filtering conversation lists by query and category.
- **Status**: **RESOLVED**

---

### Defect B-05: Missing Application-Wide Theme Selection (Dark / Light / System)
- **Severity**: **P2 (Usability & Accessibility)**
- **Reproduction**:
  `MainActivity.kt` hardcoded a dark palette without user-configurable Light Mode or System Mode preferences.
- **Root Cause**:
  No persistent theme store or dynamic Compose theme provider was implemented.
- **Corrective Change**:
  Implement `ThemeStore` (backed by `SharedPreferences`) supporting `SYSTEM`, `DARK`, and `LIGHT` modes, along with a dynamic `MeshlineTheme` provider that reacts application-wide to settings changes.
- **Validation**:
  Theme switching tested and verified across all four navigation tabs.
- **Status**: **RESOLVED**

---

### Defect B-06: Plain Text Bubbles in Private & Group Chats
- **Severity**: **P3 (Visual Polish & Usability)**
- **Reproduction**:
  `PrivateChatScreen` rendered messages as basic text lines without distinctive incoming/outgoing chat bubbles, day separators, or clear delivery checkmark indicators.
- **Root Cause**:
  Chat screens used unstyled text rows instead of asymmetric Material 3 chat bubble components.
- **Corrective Change**:
  Implement styled chat bubbles:
  - Outgoing: High-contrast Mint Emerald (`#00E599`) with dark obsidian text (`#0B0D0F`), delivery checkmarks, and timestamps.
  - Incoming: Dark Slate Card (`#1A1D21`) with `#252930` border and `#F3F4F6` text.
  - Day separators and E2EE trust banner.
- **Validation**:
  Visual comparison with Google Stitch design mockups.
- **Status**: **RESOLVED**
