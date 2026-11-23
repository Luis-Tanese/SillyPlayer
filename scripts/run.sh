#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
export SILLYPLAYER_TOOL_DIR="$PWD/.deps/tools"
if [[ -d .deps/root/usr ]]; then
  export LD_LIBRARY_PATH="$PWD/.deps/root/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export QML2_IMPORT_PATH="$PWD/.deps/root/usr/lib/x86_64-linux-gnu/qt6/qml"
  export QT_PLUGIN_PATH="$PWD/.deps/root/usr/lib/x86_64-linux-gnu/qt6/plugins:/usr/lib/x86_64-linux-gnu/qt6/plugins"
fi
if [[ -d .deps/media/lib ]]; then
  export LD_LIBRARY_PATH="$PWD/.deps/media/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export PATH="$PWD/.deps/media/bin:$PATH"
fi
exec ./build/sillyplayer "$@"
