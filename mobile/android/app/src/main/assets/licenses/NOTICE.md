# Third-party software

SillyPlayer for Android is licensed under the GNU GPL v3.0 or later. Third-party libraries retain their respective licenses; full license texts are bundled in `assets/licenses/`.

| Component | Version / source | License |
|---|---|---|
| AndroidX, Compose, Room, DataStore, Media3, WorkManager, CameraX | Versions in `app/build.gradle.kts` | Apache 2.0 |
| Kotlin, coroutines, serialization | Versions in `app/build.gradle.kts` | Apache 2.0 |
| NewPipeExtractor | [v0.26.5](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5) | GPL 3.0 or later |
| Rhino | [1.8.1](https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release) | MPL 2.0 |
| OkHttp / Okio | [4.12.0](https://github.com/square/okhttp/tree/parent-4.12.0) | Apache 2.0 |
| Coil | [3.3.0](https://github.com/coil-kt/coil/tree/3.3.0) | Apache 2.0 |
| ZXing | [3.5.3](https://github.com/zxing/zxing/tree/zxing-3.5.3) | Apache 2.0 |
| FFmpeg | [6.1.5](https://ffmpeg.org/releases/ffmpeg-6.1.5.tar.xz) | LGPL 2.1 or later |
| LAME | [3.100](https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz) | LGPL 2.0 or later |
| TagLib | [1.13.1](https://github.com/taglib/taglib/tree/v1.13.1) | LGPL 2.1 or MPL 1.1 |
| OpenSSL | [3.5.4](https://github.com/openssl/openssl/tree/openssl-3.5.4) | Apache 2.0 |
| nlohmann/json | [3.12.0](https://github.com/nlohmann/json/tree/v3.12.0) | MIT |
| Lucide icons | [0.468.0](https://github.com/lucide-icons/lucide/tree/0.468.0) | ISC |

Native libraries are statically linked into `libsillymedia.so` (`libc++_shared.so` comes from the Android NDK). Exact pinned sources, SHA-256 checksums, and build flags are in `scripts/android-native.py`; the FFmpeg TLS hostname verification patch is in `mobile/android/native/ffmpeg-hostname.patch`.
