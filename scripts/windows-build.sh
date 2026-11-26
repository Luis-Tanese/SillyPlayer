#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
project_dir="$PWD"
export LD_LIBRARY_PATH="$project_dir/.deps/root/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
mpv_inc="$project_dir/.deps/windows/native/include"
mpv_lib="$project_dir/.deps/windows/native/lib/libmpv.dll.a"
if [[ ! -f "$mpv_lib" ]]; then
 mpv_inc="$project_dir/.deps/windows/mpv/include"
 mpv_lib="$project_dir/.deps/windows/mpv/libmpv.dll.a"
fi
cmake -S . -B build/windows -G Ninja \
 -DCMAKE_TOOLCHAIN_FILE="$project_dir/cmake/windows-mingw.cmake" \
 -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF \
 -DCMAKE_PREFIX_PATH="$project_dir/.deps/windows/qt/6.4.2/mingw_64;$project_dir/.deps/windows/native" \
 -DQT_HOST_PATH="$project_dir/.deps/root/usr" \
 -DQT_HOST_PATH_CMAKE_DIR="$project_dir/.deps/root/usr/lib/x86_64-linux-gnu/cmake" \
 -DMPV_INCLUDE_DIR="$mpv_inc" \
 -DMPV_LIBRARY="$mpv_lib" \
 -DOPENSSL_INCLUDE_DIR="$project_dir/.deps/windows/msys/mingw64/include" \
 -DOPENSSL_CRYPTO_LIBRARY="$project_dir/.deps/windows/msys/mingw64/lib/libcrypto.dll.a"
cmake --build build/windows --target sillyplayer --parallel "${BUILD_JOBS:-3}"
