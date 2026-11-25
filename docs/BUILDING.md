# Building PureSwitch

PureSwitch is a small web app (`index.html`, `src/`) inside an Android shell made with
[Capacitor](https://capacitorjs.com). Everything that talks to outlets is native Java in
`android/app/src/main/java/com/levi/pureswitch/tuya/`.

## Try it in the browser

```bash
npm install
npm run dev
```

Opens on `http://localhost:5173` in **demo mode**: three made-up outlets, every switch succeeds,
Connect Smart Life is simulated. Good for working on the interface. The dev server only listens on
your own PC.

## Build the Android app with Android Studio

1. Install **Node.js** (LTS) and **Android Studio**. Let Android Studio's setup wizard install the
   Android SDK, and tick **Android 16 (API 36)** under *Settings → Languages & Frameworks →
   Android SDK*.
2. `npm install`, then `npm run android`. This builds the web app, copies it into `android/` and
   opens the project in Android Studio.
3. Wait for Gradle sync. If it offers to upgrade the Android Gradle Plugin, don't.
4. Turn on **USB debugging** on the phone (*About phone →* tap *Build number* 7 times, then
   *Developer options → USB debugging*), plug it in, pick it in the device menu and press ▶ Run.

## Build without Android Studio

You only need Java 21 (e.g. [Temurin](https://adoptium.net)) and the Android
[command-line tools](https://developer.android.com/studio#command-line-tools-only). Install
`platform-tools` with `sdkmanager`; Gradle downloads the rest during the first build.

```powershell
$env:JAVA_HOME = "<path to JDK 21>"
$env:ANDROID_HOME = "<path to Android SDK>"
npm run build
npx cap sync android
cd android
.\gradlew.bat assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
```

Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

If Java fails with *Unable to establish loopback connection*, its temp folder path is too long or
unusual for Windows. Point it at a short folder:
`$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Users\Public"`.

## Release

Release builds are signed with a key that is never committed. Put a `keystore.properties` file
in `android/` (or set `PURESWITCH_KEYSTORE_PROPERTIES` to its path):

```properties
storeFile=C:/path/to/pureswitch-release.jks
storePassword=...
keyAlias=pureswitch
keyPassword=...
```

Then:

1. `npm test` passes.
2. Bump `versionCode` and `versionName` in `android/app/build.gradle`.
3. `.\gradlew.bat assembleRelease`, and rename `app/build/outputs/apk/release/app-release.apk` to
   `PureSwitch.apk` (no version in the name, so the README's `releases/latest/download/…` link
   keeps working).
4. Install **that exact file** on a phone that has the previous release and check it starts and
   switches.
5. Get its checksum (`Get-FileHash PureSwitch.apk -Algorithm SHA256`) and put it in the release
   notes, so people can check their download.
6. Attach it to a GitHub release tagged `v<versionName>`.

Keep the key and its passwords backed up. An update only installs over an existing install when it
is signed with the same key.
