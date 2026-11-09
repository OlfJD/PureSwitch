# PureSwitch – Devlog

## 2026-11-08 – Android app (Capacitor) + real LAN control

### Setup
- Capacitor 8.6 added; Android project in `android/` (app id `com.levi.pureswitch`).
- `npm run android` = build the web app, copy it into `android/`, open Android Studio.
- Gradle wrapper bumped 8.14.3 → 9.1.0: Android Studio's bundled Java 25 can't run Gradle 8.x
  ("Unsupported class file major version 69").

### Native plugin `TuyaLan` (`android/app/src/main/java/com/levi/pureswitch/tuya/`)
- `TuyaDevice`: direct TCP 6668 connection to a plug. Protocols 3.3 (AES-ECB), 3.4 (session key
  + HMAC-SHA256) and 3.5 (session key + AES-GCM, 6699 frames). Unknown version → tries 3.3, 3.4,
  3.5 and the app remembers which worked. Switch is data point `1`.
- `TuyaDiscovery`: listens on UDP 6666/6667/7000 for plug broadcasts (and asks 3.5 plugs to
  announce themselves); holds a Wi-Fi multicast lock while scanning.
- `KeyVault`: local keys encrypted with an AES-GCM key kept in the Android Keystore. The web side
  only ever sends keys in; they are never read back out to it.
- `LocalNet`: private-IPv4 check; every connection and scan result goes through it.

### App changes
- On the phone, keys are stored per outlet in the vault (keys found in old saved data are moved
  there on first launch). The key field shows "Saved (leave empty to keep)" instead of the key.
- Key must be 16 characters (form + import).
- On open / when the app comes back to the foreground, the real on/off state of each outlet is
  read from the plug.
- Error text from the plugin is shown on the row: Offline, No reply, Wrong key, Refused, No key.

### Security
- Android backups and device-to-device transfer are off (`allowBackup=false`,
  `data_extraction_rules.xml`), so the outlet list and encrypted keys never leave the phone.
- Permissions: network access (Android calls it INTERNET), Wi-Fi state, multicast (for scanning).
  No location, no other permissions.

### Not yet verified
- Nothing has run on a real phone or real plugs yet — the Android SDK wasn't installed, so the APK
  hasn't been built. Only the protocol code was compile-checked.

## 2026-11-08 – All on / All off

- Two buttons above the list: **All on** and **All off**. Shown when 2+ outlets are set up, hidden
  in Edit mode, greyed out when there's nothing to do.
- All off with protected outlets on asks first: *Turn off all*, *Skip protected* (turns off
  everything else) or *Cancel*. If only protected outlets are on, the middle choice is left out.
  With nothing protected on it just turns everything off.
- Outlets switch in parallel; each shows its own "…" / error like a single tap does.
- The confirm sheet now supports an optional third choice and stacks its buttons vertically.

## 2026-11-08 – Finding outlets (demo) + groundwork for Android

The outlets are Tuya / Smart Life. Everything below runs in demo mode in the browser; the Android
app will swap in a native `TuyaLan` Capacitor plugin behind the same functions.

- **`src/devices.js`** is now the only place that talks to outlets: `discover()`, `setPower()`,
  `isReady()`, `isLocalIPv4()`. In the browser it uses a demo backend (3 fake outlets, switches
  always succeed). A "Demo mode" note shows at the bottom of the screen.
- **Add outlet** (in Edit mode) opens a sheet with three choices:
  - *Scan network*: lists outlets found on the Wi-Fi; tap Add to create one with its IP and device
    ID filled in. Outlets you already have are marked "Added" and their IP is refreshed if the
    router gave them a new one.
  - *Import key file*: reads a tinytuya `devices.json` locally (never uploaded). Adds plugs, power
    strips and switches; updates keys of ones you already have (keeps your names); skips lights,
    sensors etc.; drops any non-local IP.
  - *Enter manually*: the old edit form.
- Switching shows "…" while it's in progress and a short error on the row if the outlet can't be
  reached (outlet stays in its old state).
- On the real app, an outlet missing its IP, ID or key shows "Set up" and opens its editor.
- Outlets now remember their Tuya protocol `version` (from a scan or import).
- Fixed: elements with the `hidden` attribute could be forced visible by other CSS.

## 2026-11-08 – Safety lock is back + local-only lockdown

### Safety lock
- Per-outlet "Ask before turning off" switch in the edit dialog (stored as `safetyLock`, same
  field as the old version, so old settings carry over).
- Protected outlets show a small lock next to their name. Turning one off asks first; Cancel is
  focused by default, and Escape or tapping outside counts as "no". Turning on never asks.
- Remove now uses the same in-app confirm instead of the browser's `confirm()` popup (which
  looks out of place in an Android app).

### Local network only
- Outlet IPs must be private LAN addresses (192.168.x.x, 10.x.x.x, 172.16–31.x.x); anything
  else can't be saved.
- Content-Security-Policy in `index.html`: the page can only load from / talk to its own origin.
  No third-party scripts, fonts or outside requests.
- `vite.config.js` pins the dev server to `localhost`, so it isn't reachable from other devices.

### Fixed
- Tapping the padding inside a dialog no longer closes it as if you'd tapped outside.

## 2026-11-07 – Minimal redesign

Rewrote the UI to do one thing: switch outlets on and off.

- Main screen is just a list of outlets; tap a row to toggle it (green = on).
- "Edit" in the top right shows "+ Add outlet"; tapping an outlet there opens a small dialog for
  name, connection details (IP / device ID / local key, collapsed) and Remove.
- Removed: phone frame & desktop preview bar, view modes, safety locks and their confirm dialogs,
  "Turn on/off all" buttons, sounds, toasts, the demo outlets, Google Fonts and the `lucide`
  dependency.
- Storage key is unchanged (`pureswitch_devices_v1`), so existing outlets carry over.
  Light/dark follows the system theme.

## 2026-11-04 – Code review & bug-fix pass

### Fixed
- **Deleted outlets came back.** `loadOutlets()` treated an empty saved list as "nothing saved" and
  restored the four demo outlets, so removing every outlet undid itself on reload.
- **Unescaped user text in HTML.** Toast messages (which include the outlet name) and the IP tag
  were inserted with `innerHTML` without escaping.
- **Toast and footer icons rendered at 24 px.** Lucide replaces `<i data-lucide>` with `<svg>`, but
  `.toast i` / `.app-footer-tip i` only targeted the `<i>`, so the sizing never applied.
- **Outlet cards wrapped badly.** The safety badge dropped onto its own line and "ON • 230V" split
  over two lines. Title/meta rows no longer wrap; long names and IPs truncate with an ellipsis.
- "Turn Off Others, Keep Protected ON" gave no feedback when only protected outlets were on.

### Improved
- Sound toggle and Phone/Window view mode are now remembered (`SETTINGS_KEY` existed but was unused).
- New outlets start with an empty IP instead of an invented `192.168.1.1xx` address.
- IP field validates IPv4 format.
- Header action/view-button icons sized to match their labels.
- Added favicon link and a `prefers-reduced-motion` rule for the pulsing/bouncing animations.
- `saveOutlets()` no longer throws if localStorage is unavailable.

### Notes
- `src/counter.js`, `src/assets/*` and `public/icons.svg` are unused Vite template files (left in place).
