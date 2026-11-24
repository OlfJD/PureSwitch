# Third-party notices

## tuya-device-sharing-sdk

The Smart Life login in `android/app/src/main/java/com/levi/pureswitch/tuya/TuyaCloud.java` is
ported from Tuya's [tuya-device-sharing-sdk](https://github.com/tuya/tuya-device-sharing-sdk)
(`customerapi.py`, `user.py`).

```
MIT License

Copyright (c) 2023 Tuya

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Home Assistant

The login uses the client ID and schema of Home Assistant's
[Tuya integration](https://github.com/home-assistant/core/tree/dev/homeassistant/components/tuya)
(Apache License 2.0), because Tuya only offers this QR login to Home Assistant. PureSwitch is not
affiliated with or endorsed by Home Assistant.

## qrcode-generator

Draws the login QR code. [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) by
Kazuhiko Arase, MIT License. Its licence is included with the package in `node_modules`.

## Capacitor

The Android app shell. [Capacitor](https://github.com/ionic-team/capacitor), MIT License.
