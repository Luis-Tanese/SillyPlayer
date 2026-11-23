# SillyPlayer for Android

Android music player built with Kotlin, Jetpack Compose, Room, and Media3 (Android 8.0 / API 26+).

## Building

From the project root:

```bash
python3 scripts/android-bootstrap.py
./scripts/android-build.sh :app:assembleRelease
python3 scripts/android-package.py
```

Run unit tests with:

```bash
./scripts/android-build.sh :app:testDebugUnitTest
```

`scripts/android-bootstrap.py` installs the Android SDK, NDK, CMake, and Gradle under `.deps/`. `scripts/android-native.py` compiles FFmpeg, LAME, TagLib, and OpenSSL for `arm64-v8a` and `armeabi-v7a` and caches them for subsequent builds.

## Installing on a device

Install the `arm64-v8a` APK on 64-bit Android devices (most modern phones) or `armeabi-v7a` on older 32-bit devices.

To install over ADB:

```bash
scripts/android-device.sh devices
scripts/android-device.sh install <path-to-apk>
```

## Notes

- Choose a public audio folder (such as `Music`) in **Settings → Library and storage**. Your songs and a portable SQLite copy of your library are kept there so they survive uninstalling.
- For desktop Wi-Fi sync, enable **Phone sync** in desktop Settings and scan the QR code in Android **Settings → Phone sync**. Both devices need to be on the same local network (TCP port `43817`, UDP auto-discovery port `43818`).

## License

SillyPlayer is licensed under the [GNU GPL v3.0 or later](../../LICENSE). See [NOTICE.md](NOTICE.md) for third-party libraries.
