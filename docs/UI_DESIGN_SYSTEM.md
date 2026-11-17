# Meshline Mobile Messenger — UI Design System

**Design Source of Truth:** Google Stitch MCP Project `projects/2058343025590670872`  
**Design System Asset:** `assets/ec6d8c0eaa624c8db4eb0bf5a2fac2bd`  
**Target Platform:** Native Android (Jetpack Compose / Material 3)

---

## 1. Brand Narrative & Visual Identity

Meshline fuses the effortless, high-density conversation navigation of **WhatsApp** with the confident visual polish, refined typography, and tactile delight of **Instagram**.

- **Atmosphere:** Deep obsidian-charcoal surfaces (`#121417` / `#111316`) designed specifically for OLED contrast, minimal eye strain, and infinite spatial depth.
- **Accent Rhythm:** A vibrant **Mint Emerald** (`#00E599`) focal point reserved exclusively for active intent: outgoing message bubbles, unread counters, primary CTA buttons, and verified encryption status.
- **Surfaces & Tactility:** Layers constructed through chromatic separation and 1px hairline borders (`#252930`) rather than high-spread artificial drop shadows.

---

## 2. Color System & Design Tokens

### 2.1 Dark Palette (Default OLED)

| Token Name | Hex Code | Purpose |
| :--- | :--- | :--- |
| `Canvas / Background` | `#121417` | Screen background base layer |
| `Surface Container Low` | `#16191D` | Search bar fill, list item backings |
| `Surface Card / Incoming Bubble` | `#1A1D21` | Message cards, incoming message bubbles |
| `Surface Elevated / Header / Sheets` | `#22272E` | Top app bars, bottom composer docks, modal sheets |
| `Border / Hairline Outline` | `#252930` | 1px perimeter outlines, dividers, chip borders |
| `Primary Accent (Mint Emerald)` | `#00E599` | Primary FAB, outgoing bubbles, unread pills, active tabs |
| `Primary On-Accent (Obsidian)` | `#0B0D0F` | Text and icons rendered inside `#00E599` surfaces |
| `Secondary Accent (Deep Mint)` | `#10B981` | Verified peer shields, secondary encryption badges |
| `Text Primary` | `#F3F4F6` | Message titles, incoming chat text, primary headings |
| `Text Secondary` | `#9CA3AF` | Subtitles, timestamps, peer IDs, delivery status labels |
| `Text Muted` | `#6B7280` | Placeholders, inactive tab labels, empty state captions |
| `Danger / Panic` | `#EF4444` | Emergency panic wipe button, blocked peer indicators |

### 2.2 Light Palette (Complementary High-Contrast Mode)

| Token Name | Hex Code | Purpose |
| :--- | :--- | :--- |
| `Canvas / Background` | `#F8FAFC` | Light mode background |
| `Surface Card / Incoming Bubble` | `#FFFFFF` | Incoming chat bubbles, list cards |
| `Surface Elevated / Header` | `#FFFFFF` | App bar, bottom navigation with subtle divider |
| `Border / Outline` | `#E2E8F0` | Dividers and card borders |
| `Primary Accent (Mint Emerald)` | `#059669` | High-contrast mint emerald for daylight readability |
| `Primary On-Accent` | `#FFFFFF` | Text on primary accent buttons |
| `Text Primary` | `#0F172A` | Primary text and headings |
| `Text Secondary` | `#475569` | Subtitles, timestamps |
| `Text Muted` | `#94A3B8` | Placeholders and inactive states |

---

## 3. Typography Hierarchy

| Level | Font Family | Size | Weight | Line Height | Usage |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `Display Large` | Plus Jakarta Sans | 32sp | Bold (700) | 38sp | Hero counters, onboarding headers |
| `Headline Large` | Plus Jakarta Sans | 24sp | Semi-Bold (600) | 30sp | Screen titles ('Meshline', 'Nearby') |
| `Headline Medium`| Plus Jakarta Sans | 20sp | Semi-Bold (600) | 26sp | Dialog headers, section titles |
| `Title Small` | Plus Jakarta Sans | 16sp | Semi-Bold (600) | 22sp | Conversation partner names, group names |
| `Body Large` | Geist / Inter | 16sp | Regular (400) | 24sp | Message bubble body text |
| `Body Medium` | Geist / Inter | 14sp | Regular (400) | 20sp | Conversation list previews, descriptions |
| `Body Small` | Geist / Inter | 13sp | Regular (400) | 18sp | Diagnostic logs, peer hop telemetry |
| `Label Medium` | Geist / Inter | 12sp | Medium (500) | 16sp | Timestamps, channel pill chips |
| `Label Extra Small` | Geist / Inter | 10sp | Semi-Bold (600) | 14sp | Unread badge counters, status indicators |

---

## 4. Spacing, Shapes, and Radii

- **Grid Baseline:** 4dp baseline rhythm.
- **Spacing Scale:**
  - `space-xs`: `4dp` (message clumping from same sender)
  - `space-sm`: `8dp` (badge padding, avatar to text gap)
  - `space-md`: `12dp` (message transition between different senders)
  - `space-lg`: `16dp` (screen horizontal margins, card padding)
  - `space-xl`: `24dp` (section vertical separation)
- **Corner Radii:**
  - `Full Pill (9999dp)`: Search bar, filter chips, CTA buttons, unread badges.
  - `Bubble Outgoing`: `18dp` with bottom-right anchor corner tapered to `4dp`.
  - `Bubble Incoming`: `18dp` with bottom-left anchor corner tapered to `4dp`.
  - `Cards`: `16dp` rounded corners with 1dp border `#252930`.

---

## 5. Component Specifications

### 5.1 Conversation Inbox Item
- Left: 48dp circular avatar with fallback fruit/identicon and optional 10dp emerald online/mesh dot.
- Center Column:
  - Top Row: Contact Name (Semi-Bold 16sp) + Verified Shield badge if QR-verified.
  - Bottom Row: Delivery status icon (Double checkmark in mint if delivered/read, single if sent) + Message preview (truncated to 1 line).
- Right Column: Timestamp (Label 12sp) + Unread Pill (vibrant `#00E599` badge with `#0B0D0F` text).

### 5.2 Message Bubbles & Delivery States
- **Incoming**: Solid `#1A1D21` fill with 1dp border in `#252930`. White primary text. Timestamp and sender nickname inside bubble.
- **Outgoing**: Vibrant `#00E599` fill. Dark obsidian `#0B0D0F` typography.
- **Delivery Icons**:
  - `QUEUED`: Hollow clock / circle icon.
  - `SENT`: Single checkmark.
  - `DELIVERED`: Double checkmarks in muted obsidian.
  - `READ`: Double checkmarks in dark accent.
  - `NOT_DELIVERED`: Small red alert indicator with tap-to-retry.

### 5.3 Bottom Navigation & Action Dock
- Fixed 4-tab bottom navigation with glassmorphism surface `#1A1D21` and top hairline border `#252930`.
- Tabs: `Chats`, `Nearby`, `Channels`, `Settings`.
- Active tab uses `#00E599` icon tint and text label. Inactive tabs use `#9CA3AF`.

### 5.4 Nearby Mesh Radar
- Tactile hero card displaying real-time peer count, BLE transmission mode, and hop limit.
- Radar pulse circle with reachable peer dots showing signal RSSI.
- Live public broadcast stream below the hero card.
