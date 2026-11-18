# Meshline Mobile Messenger — Complete UI Design System
## Premium Light & Dark Design via Google Stitch MCP

**Design Source of Truth:** Google Stitch MCP Project `Meshline — Premium Messaging UI` (`projects/13815013391327039542`)  
**Design Systems:** Light Mode (`assets/bb624ca0c7ac4c8087e8377baf4677a5`) & Dark Mode (`assets/090df621b80b4de18b978a5859105347`)  
**Target Platform:** Native Android (Jetpack Compose / Material 3)  
**Supported Appearance Modes:** `System default`, `Light`, and `Dark` (fully persistent via `ThemeStore`)

---

## 1. Brand Narrative & Visual Identity

Meshline transforms decentralized, offline-first peer-to-peer messaging into a premium, approachable consumer experience that balances the immediate conversational utility of **WhatsApp** with the visual restraint, editorial composure, and refined micro-typography of **Instagram**.

### Core Design Philosophy: Consumer First, No Hacker Aesthetic
- **Elimination of Neon Green Hacker Aesthetic:** The excessive neon-green-on-black terminal look has been completely replaced with a sophisticated neutral foundation and restrained indigo/periwinkle brand accents.
- **Strict Semantic Status Color Role:** Green is strictly a semantic status color (`#10B981` in light, `#34D399` in dark) reserved for online presence, live audio indicators, and successful cryptographic handshake delivery. It is **never** used as an abrasive background or primary brand accent.
- **Three Persistent Appearance Modes:** Users can seamlessly choose between **System default**, **Light**, and **Dark** in Settings. Preferences are persisted across app restarts via `SharedPreferences`.

---

## 2. Color System & Design Tokens

### 2.1 Refined Light Palette (Daylight & Clarity)

| Token Name | Compose Token | Hex Code | Purpose |
| :--- | :--- | :--- | :--- |
| `Canvas / Background` | `colors.background` | `#F6F7FB` | Crisp, luminous cool light gray ground canvas |
| `Primary Surface` | `colors.surface` | `#FFFFFF` | Pure white top app bars, incoming chat cards, dialogs |
| `Surface Elevated` | `colors.surfaceElevated` | `#FFFFFF` | Layered cards and action sheets |
| `Surface Container Low` | `colors.surfaceContainerLow` | `#F0F2F8` | Recessed search fields, message input dock, inactive pills |
| `Border / Divider` | `colors.border` | `#E3E7EF` | Delicate 1px structural outlines, dividers, chat delimiters |
| `Brand Primary Accent` | `colors.primary` | `#5368C8` | Refined Royal Indigo for buttons, active pills, outgoing bubbles |
| `On-Primary Text` | `colors.onPrimary` | `#FFFFFF` | Pure white text/icons on primary accent surfaces |
| `Soft Accent Container`| `colors.primarySoft` | `#E9EDFF` | Contextual chip backgrounds, active filter pills, unread tags |
| `Secondary Text` | `colors.secondary` | `#687386` | Muted slate for subtitles, metadata, secondary captions |
| `Primary Text` | `colors.textPrimary` | `#1B2028` | Deep charcoal for optimal reading comfort and contrast |
| `Secondary Text` | `colors.textSecondary` | `#687386` | Timestamps, read receipts, peer IDs, delivery status labels |
| `Muted Text` | `colors.textMuted` | `#9AA4B2` | Placeholders, inactive tab labels, empty states |
| `Status Connected` | `colors.statusConnected` | `#10B981` | Natural emerald green strictly for online presence |
| `Status Warning` | `colors.statusWarning` | `#F59E0B` | Semantic amber for pending handshakes / warnings |
| `Danger / Panic` | `colors.danger` | `#EF4444` | Semantic red for emergency panic wipe and key rejections |
| `Outgoing Bubble` | `colors.bubbleOutgoing` | `#5368C8` | Royal Indigo outgoing message container |
| `Outgoing Bubble Text`| `colors.bubbleOutgoingText` | `#FFFFFF` | High-contrast white text on outgoing bubble |
| `Incoming Bubble` | `colors.bubbleIncoming` | `#FFFFFF` | Crisp white incoming message container |
| `Incoming Bubble Text`| `colors.bubbleIncomingText` | `#1B2028` | Deep charcoal text on incoming bubble |

### 2.2 Refined Dark Palette (Nighttime & OLED Composure)

| Token Name | Compose Token | Hex Code | Purpose |
| :--- | :--- | :--- | :--- |
| `Canvas / Background` | `colors.background` | `#11141A` | Deep cool night charcoal canvas (no harsh ink-black) |
| `Primary Surface` | `colors.surface` | `#1A1F28` | Incoming message cards, list containers, nav bar backing |
| `Surface Elevated` | `colors.surfaceElevated` | `#222834` | Dynamic popovers, bottom action sheets, modal overlays |
| `Surface Container Low` | `colors.surfaceContainerLow` | `#181C24` | Sunken search fields, composer input docks |
| `Border / Divider` | `colors.border` | `#303746` | Low-contrast 1px boundary lines preventing optical bleeding |
| `Brand Primary Accent` | `colors.primary` | `#A4B2FF` | Refined Soft Periwinkle-Indigo for active tabs, CTAs, tags |
| `On-Primary Text` | `colors.onPrimary` | `#11141A` | Deep night charcoal text on periwinkle primary buttons |
| `Soft Accent Container`| `colors.primarySoft` | `#282F49` | Subdued indigo tint container for outgoing bubbles, active pills |
| `Secondary Text` | `colors.secondary` | `#A0A8B7` | Soft cool gray for subtitles, secondary metadata |
| `Primary Text` | `colors.textPrimary` | `#F1F3F8` | Crisp light text delivering comfortable reading contrast |
| `Secondary Text` | `colors.textSecondary` | `#A0A8B7` | Timestamps, read receipts, peer IDs, delivery status labels |
| `Muted Text` | `colors.textMuted` | `#6E788B` | Placeholders, inactive tab labels, empty states |
| `Status Connected` | `colors.statusConnected` | `#34D399` | Semantic emerald green dot strictly for online peer presence |
| `Status Warning` | `colors.statusWarning` | `#FBBF24` | Semantic amber for pending handshakes / warnings |
| `Danger / Panic` | `colors.danger` | `#F87171` | Semantic red for emergency panic wipe and key rejections |
| `Outgoing Bubble` | `colors.bubbleOutgoing` | `#282F49` | Deep indigo tint container for outgoing messages |
| `Outgoing Bubble Text`| `colors.bubbleOutgoingText` | `#F1F3F8` | Crisp light text on outgoing bubble |
| `Incoming Bubble` | `colors.bubbleIncoming` | `#1A1F28` | Slate container with 1px `#303746` border for incoming |
| `Incoming Bubble Text`| `colors.bubbleIncomingText` | `#F1F3F8` | Crisp light text on incoming bubble |

---

## 3. Typography Hierarchy

The typographic scale uses **Inter** / **Plus Jakarta Sans** with clean geometric precision and humanistic proportions:

| Level | Size | Weight | Line Height | Usage |
| :--- | :--- | :--- | :--- | :--- |
| `Display Large` | 32sp | Bold (700) | 40sp | Hero stats, large identity headers |
| `Headline Large` | 26sp | Semi-Bold (600) | 34sp | Top screen headers ('Meshline', 'Nearby') |
| `Headline Medium`| 20sp | Semi-Bold (600) | 28sp | Dialog titles, section headers |
| `Title Medium` | 16sp | Semi-Bold (600) | 22sp | Conversation partner names, group names |
| `Body Large` | 16sp | Regular (400) | 24sp | Primary message bubble text |
| `Body Medium` | 14sp | Regular (400) | 20sp | Conversation list previews, settings copy |
| `Body Small` | 13sp | Regular (400) | 18sp | Diagnostic telemetry, peer hop routing |
| `Label Medium` | 12sp | Medium (500) | 16sp | Timestamps, channel pill chips |
| `Label Small` | 11sp | Semi-Bold (600) | 14sp | Unread badge counts, mesh status indicators |

---

## 4. Layout, Elevation, and Geometry

- **Grid Baseline:** 4dp / 8dp fluid mobile layout with 16dp horizontal screen padding.
- **Depth without Heavy Shadows:** Tonal stepping (`background` -> `surface` -> `surfaceElevated`) coupled with 1px hairline structural borders (`colors.border`).
- **Corner Radii:**
  - `Pill (9999dp)`: Search input, filter chips, CTA buttons, unread counters, status pills.
  - `Message Outgoing`: `18dp` with bottom-right anchor corner tapered to `4dp`.
  - `Message Incoming`: `18dp` with bottom-left anchor corner tapered to `4dp`.
  - `Cards & Dialogs`: `14dp` to `18dp` with subtle 1px border.
  - `Avatars`: Full 50% circular masks with 10–12dp semantic presence dot indicator masked by 2dp surface border.

---

## 5. Screen Component Breakdown

1. **Top Bar & Mesh Status Indicator:**
   - Bold title 'Meshline' paired with a compact pill chip: `Mesh Active • X Online` with a green semantic status dot (`#10B981` / `#34D399`) and soft indigo background (`colors.primarySoft`).
2. **Search & Filter Row:**
   - Pill-shaped search bar with muted slate icon and placeholder.
   - Filter chips: `All`, `Unread`, `Direct`, `Groups`, `Nearby Mesh` with `colors.primarySoft` background and `colors.primary` border when active.
3. **Conversation List Items:**
   - 50dp circular avatar with fallback fruit/identicon and online presence pip.
   - Name in `titleMedium` (`colors.textPrimary`), QR verification checkmark (`colors.statusConnected`).
   - Single-line truncated preview (`colors.textSecondary`).
   - Trailing unread badge in `colors.primary` with `colors.onPrimary` text.
4. **Docked Bottom Navigation Bar:**
   - Docked on `colors.surface` with hairline top border (`colors.border`).
   - Selected items highlight with `colors.primary` text/icon and `colors.primarySoft` indicator.
   - Semantic green badge for active nearby peers.
5. **Appearance Selector (Settings):**
   - 3-segment pill selector: `System`, `Light`, `Dark`.
   - Selected segment highlights with `colors.primarySoft` fill and `colors.primary` border.
