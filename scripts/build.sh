#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
args=()
if [[ -d .deps/root/usr ]]; then
  prefix="$PWD/.deps/root/usr"
  args+=(-DCMAKE_PREFIX_PATH="$prefix" -DCMAKE_INCLUDE_PATH="$prefix/include" -DCMAKE_LIBRARY_PATH="$prefix/lib/x86_64-linux-gnu")
  export LD_LIBRARY_PATH="$prefix/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
if [[ -d .deps/media ]]; then
  media="$PWD/.deps/media"
  args+=(-DMPV_INCLUDE_DIR="$media/include" -DMPV_LIBRARY="$media/lib/libmpv.so.2")
  export LD_LIBRARY_PATH="$media/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release "${args[@]}"
cmake --build build --parallel "${BUILD_JOBS:-4}"
