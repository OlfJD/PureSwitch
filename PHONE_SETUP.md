# Getting PureSwitch onto your phone

## 0. Quick route on the work PC (already set up)

Java 21 is in `%LOCALAPPDATA%\PureSwitchTools\jdk` and the Android SDK in
`%LOCALAPPDATA%\Android\Sdk`, without Android Studio. To rebuild the APK, run this from the
PureSwitch folder in PowerShell:

```powershell
$env:JAVA_HOME = (Get-ChildItem "$env:LOCALAPPDATA\PureSwitchTools\jdk" -Directory)[0].FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
# Java can't open local sockets under this account's temp path, so give it a short folder:
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Users\Public"
npm run build; npx cap sync android; Set-Location android; .\gradlew.bat assembleDebug; Set-Location ..
```

The APK is then at `android\app\build\outputs\apk\debug\app-debug.apk`. Install it as in
section 2 (via cable with `adb install -r <apk>`, or by copying the file to the phone).

## 1. On the PC (needs internet; downloads about 3–5 GB)

1. Install **Node.js** (LTS) and **Android Studio**. Android Studio comes with its own Java,
   so there's nothing else to install.
2. Start Android Studio once and let the setup wizard install the Android SDK (default choices
   are fine). In *Settings → Languages & Frameworks → Android SDK*, make sure **Android 16
   (API 36)** is ticked.
3. In the PureSwitch folder:
   ```
   git pull
   npm install
   npm run android
   ```
   This builds the app and opens the `android/` project in Android Studio.
4. Wait for **Gradle sync** to finish (bottom status bar; the first time takes a while). If it
   asks to upgrade the Android Gradle Plugin, choose **don't upgrade**.
5. *Build → Make Project*. This is the first time the Java code is compiled. If it fails, copy
   the error from the *Build* panel and bring it back here.

## 2. On the phone (Android 7 or newer)

1. *Settings → About phone →* tap **Build number** 7 times to unlock developer options.
2. *Settings → System → Developer options →* turn on **USB debugging**.
3. Plug the phone into the PC, unlock it and allow USB debugging when it asks.
4. In Android Studio pick the phone in the device menu at the top and press the green ▶ **Run**
   button. The app installs and opens. The "Demo mode" note at the bottom should be gone.

*No cable?* Use *Build → Build App Bundle(s) / APK(s) → Build APK(s)*, copy
`android/app/build/outputs/apk/debug/app-debug.apk` to the phone and open it there (allow
"install unknown apps" for your file manager).

## 3. Getting the outlets' local keys (once)

Outlets only accept commands encrypted with their own local key. The easiest way to get the keys
is **tinytuya** on the PC:

1. Install Python, then run `pip install tinytuya`.
2. Create a free account at iot.tuya.com, create a Cloud project, and link your Smart Life app
   account to it (*Devices → Link App Account*, then scan the QR code with Smart Life).
3. Run `python -m tinytuya wizard` and enter the project's Access ID/Secret. It writes
   **`devices.json`**, which holds the keys. Treat this file like a password.
4. Copy `devices.json` to the phone, then in PureSwitch: *Edit → + Add outlet → Import key file*.
   Delete the copy from the phone (and from Downloads) afterwards.

Keys change if you re-pair an outlet in Smart Life. If that happens, run the wizard again.

## 4. First test at home

- Phone and outlets must be on the **same Wi-Fi**. A guest network usually blocks this.
- Close the Smart Life app before scanning (it can hold the ports the scan needs).
- *Edit → + Add outlet → Scan network*: your outlets should appear and get their addresses.
- Tap an outlet. The first tap checks the outlet is genuine, then switches it.
- Press the outlet's own button, then switch apps and come back: the state should update.
- Messages you might see on a row: **Offline** (wrong address / plug off), **Wrong key**,
  **Not trusted** (the device at that address couldn't prove its key), **No reply**.
