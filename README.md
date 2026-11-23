# SillyPlayer

A simple, local-first music player for desktop (Linux & Windows) and Android.

## Features

- Local music library with playlists, custom albums, likes, and queue management
- Online search, playback, and downloads
- Local Wi-Fi phone sync with QR pairing and auto-discovery
- Per-song playback speed (including Nightcore/Daycore), volume boost, and 10-band equalizer
- M3U/M3U8 playlist import & export, batch tag editing, and title cleanup
- Listening stats & insights, sleep timer, and full library ZIP backups
- Desktop Discord Rich Presence & Android Auto / lockscreen controls

## Project layout

- `src/` and `qml/` — Desktop app (C++20 / Qt 6 / libmpv)
- `mobile/android/` — Android app (Kotlin / Jetpack Compose / Media3)
- `assets/` — Icons and artwork
- `scripts/` — Build, test, and packaging scripts
- `tests/` — Desktop regression tests

## Building

### Desktop

Requires CMake 3.22+, Ninja, a C++20 compiler, Qt 6.4+, libmpv, TagLib, libarchive, and OpenSSL (plus X11 headers on Linux).

```bash
./scripts/bootstrap-dev.sh   # optional: installs local SDK into .deps/ on Ubuntu/Debian
./scripts/build.sh
./scripts/run.sh
```

Run tests with `./scripts/test.sh`.

### Android

```bash
./scripts/android-build.sh :app:assembleDebug
```

Run unit tests with `./scripts/android-build.sh :app:testDebugUnitTest`.

## License

SillyPlayer is licensed under the [GNU GPL v3.0 or later](LICENSE). Third-party components retain their respective licenses.
