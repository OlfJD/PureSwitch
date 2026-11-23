# PureSwitch – Devlog

## 2026-11-23 – App icon

- Picked from six options: **power symbol, green (#22C55E) on dark (#18181B)**.
- Android: adaptive icon with a vector foreground (`drawable/ic_launcher_foreground.xml`), dark
  background colour, and a monochrome layer for Android 13+ themed icons. PNG icons for Android
  7 (API 24–25) were drawn with the same shapes.
- The white Capacitor splash screen is replaced: dark background with the power symbol
  (`drawable/splash.xml`), and `windowSplashScreenBackground` for Android 12+.
- Browser icon `public/favicon.svg` is the same icon (was Vite's logo).
- Removed the template's Capacitor/robot icon files and the splash PNGs.
- Removed `android:roundIcon`: the OnePlus launcher shows an app's round icon whenever it has
  one, so PureSwitch was a circle among rounded squares. Now the launcher shapes it like the rest.

### Login confirmed
- Connect Smart Life worked on the phone (at work): the keys were saved. Switching still needs
  testing at home.

## 2026-11-23 – Connect Smart Life (option 1 from below)

Goal: scan → tap. *Edit → + Add outlet → **Connect Smart Life***:
1. Enter the **user code** from Smart Life (Me → ⚙ → Account and Security → User Code).
2. Scan the QR code with Smart Life (+ → Scan) and confirm. On a single phone: take a
   screenshot and pick it in Smart Life's scanner via the photo icon.
3. PureSwitch adds every plug / power strip / switch with its name and key, then scans the
   Wi-Fi for their addresses. Lights, sensors etc. are left out.

### How it works
- `TuyaCloud.java`, ported from Tuya's open-source tuya-device-sharing-sdk (MIT) and Home
  Assistant's Tuya integration (Apache 2.0): QR login at `apigw.iotbing.com`, then signed +
  AES-GCM-encrypted requests to the account's Tuya API server for homes → devices (`local_key`).
- It uses Home Assistant's public client ID, because Tuya only offers this login for Home
  Assistant. **Smart Life shows the request as "Home Assistant".**
- The keys go straight from the Java side into the encrypted vault. The web side only gets names,
  device IDs and categories. Right after fetching, the login is ended (`terminal/expire`), and no
  tokens are stored. This is the only internet connection the app ever makes, and only to
  `https://…tuya….com` / `iotbing.com`.
- Outlets that have a key but no address yet show **Not found yet**. They're found automatically
  (scan + key check) whenever the app opens or comes back, so connecting away from home is fine.
- Added `qrcode-generator` (draws the QR code, no network).

### Not yet verified
- Demo mode tested in the browser (QR → 3 plugs added, light skipped → scan). The APK compiles.
  The real Smart Life login hasn't been tried yet.

## 2026-11-23 – Why outlets need a key, and three ways to get it

The goal: **scan → save → tap**, nothing else.

### Why a scan isn't enough
Tuya / Smart Life outlets only accept on/off commands encrypted with their own secret, the
**local key**. The manufacturer builds this in; PureSwitch didn't add it. A scan can see that an
outlet exists (address and device ID), but the outlet never sends its key over Wi-Fi. If it did,
anyone on the network could switch it. Only Tuya's cloud knows the key: it gave the key to the
outlet when it was paired in Smart Life. So the key has to come from somewhere once.

### Options (simplest for the user first)
1. **One-time "Connect Smart Life" step in the app (recommended).** The app shows a QR code, you
   scan it with the Smart Life app, and PureSwitch fetches all keys at once. Home Assistant's Tuya
   integration uses the same method; it needs no developer account and no tinytuya. After that:
   scan, save, tap, all on the home network.
   - Catch: that one step talks to Tuya's servers, so the app is no longer "local network only,
     never online". It's online only while connecting, and the keys stay encrypted on the phone.
   - Has to be built and tested at home with the real outlets.
2. **Keep the app 100 % offline and use tinytuya once on a PC.** The app stays as it is: run the
   tinytuya wizard (needs a free Tuya developer account), then import `devices.json`. Steps are in
   `PHONE_SETUP.md`, section 3. This is the part that's currently too complicated.
3. **Different outlets, e.g. Shelly plugs.** Made for local control: no keys, no cloud. The app
   could find and switch them with a tap, with much simpler code. Requires buying new outlets.

### Status
- The new version ("Needs key" + import note) is installed on the phone and runs. It has no keys
  saved yet (`listKeys` returns none), so no outlet can be switched.
- Not decided yet which option to build. Option 1 is recommended.

## 2026-11-21 – Say why an outlet can't be switched

### The problem
On the phone, scanned outlets kept showing "Set up". Tapping one opened the editor, and saving
changed nothing, so the next tap opened the editor again. The cause: a scan only finds an outlet's
address and device ID. Outlets never send their local key over Wi-Fi, and without the key the app
can't switch them. Nothing on screen said so.

### Fixed
- A row that only lacks its key now says **Needs key** instead of "Set up" (`devices.js`,
  `missing()`).
- The editor shows a note explaining what's missing, with an **Import key file** button.
- The scan result says new outlets also need their key from Import key file.
- Errors such as "Key not saved" now show on rows that aren't set up yet. Before, "Set up" hid them.

### Not yet verified
- Built for the web only. The APK wasn't rebuilt: this PC has no Android SDK. The note doesn't
  show in demo mode, so it hasn't been seen on screen yet.

## 2026-11-20 – Fake devices on the Wi-Fi can't take over an outlet

### The problem
Scanning used to update a saved outlet's address quietly from whatever announced itself. Those
announcements are encrypted with a key that is the same for every Tuya device, so anyone on the
Wi-Fi could fake one. The app would then send that outlet's commands to the fake device. That
cut off the real outlet, and an older (3.3) outlet could be switched later by sending a recorded
command again.

### Fixed
- **Replies must prove the key** (`TuyaDevice.java`): a status reply only counts if it was
  decrypted or signed with the outlet's local key. Plain-text replies are rejected as
  "Not trusted".
- **Addresses are checked before they're used** (`main.js`, `verifyAddress`): every outlet
  remembers the address where it last proved its key (`checkedIp`). Before the first switch
  command to a new address, the app asks that device for its state, and switches only if the
  reply proves the key. A changed address, device ID or key has to be proven again.
- **Scanning** only moves a saved outlet to a new address after the device there proves its key.
  It also keeps the protocol version that already works, so a fake can't force an older one. If
  the check fails, the old address is kept and the scan list shows "Address not confirmed".
- **Browser demo no longer stores keys.** It only remembers that one was entered (`demoKey`).
  Keys saved by older demo versions are removed from browser storage on startup.

### Still true
- With 3.3 outlets, someone who can see the Wi-Fi traffic between phone and plug could record a
  real reply and send it again. On normal home Wi-Fi other devices can't see that traffic, and
  3.4/3.5 outlets aren't affected.

### Not yet verified
- Demo mode was checked in the browser (scan, switch, old-key cleanup). The Java changes haven't
  been compiled yet: there is no Java/Android SDK on this PC.

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
