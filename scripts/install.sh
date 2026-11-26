#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
install_root="${SILLYPLAYER_INSTALL_ROOT:-$HOME/.local}"
# Desktop launchers and symlinks must work independently of the caller's cwd.
mkdir -p -- "$install_root"
install_root="$(cd -- "$install_root" && pwd -P)"
target="$install_root/opt/sillyplayer"
data="${XDG_DATA_HOME:-$HOME/.local/share}/SillyPlayer/SillyPlayer"
for instance_lock in "$data/instance.lock" "$target/bin/data/instance.lock"; do
  if [[ -f "$instance_lock" ]]; then
    read -r pid < "$instance_lock" || true
    if [[ "${pid:-}" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
      printf 'Exit SillyPlayer before installing an update.\n' >&2
      exit 1
    fi
  fi
done
for item in sillyplayer bin/sillyplayer bin/qt.conf lib qml plugins assets/sillyplayer.png; do
  [[ -e "$source_dir/$item" ]] || { printf 'Incomplete release: missing %s.\n' "$item" >&2; exit 1; }
done
[[ -x "$source_dir/sillyplayer" && -x "$source_dir/bin/sillyplayer" ]] || { printf 'Release launchers are not executable. Extract the archive preserving permissions.\n' >&2; exit 1; }
if [[ -e "$target" && ! -d "$target" ]] || [[ -L "$target" ]]; then
  printf 'Installation path must be a normal directory: %s\n' "$target" >&2
  exit 1
fi
mkdir -p "$install_root/opt" "$install_root/bin" "$install_root/share/applications" "$install_root/share/icons/hicolor/512x512/apps"
stage="$(mktemp -d "$install_root/opt/.sillyplayer-stage.XXXXXX")"
backup="$stage/previous"
launcher="$install_root/bin/sillyplayer"
desktop="$install_root/share/applications/sillyplayer.desktop"
icon="$install_root/share/icons/hicolor/512x512/apps/sillyplayer.png"
swapped=false
integration_started=false
committed=false
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$committed" == false ]]; then
    if [[ "$integration_started" == true ]]; then
      for name in launcher desktop icon; do
        destination="${!name}"
        rm -f -- "$destination"
        if [[ -e "$stage/saved/$name" || -L "$stage/saved/$name" ]]; then
          cp -a -- "$stage/saved/$name" "$destination" || true
        fi
      done
    fi
    if [[ "$swapped" == true ]]; then rm -rf -- "$target"; fi
    if [[ -d "$backup" ]]; then mv -- "$backup" "$target"; fi
    printf 'Installation failed; the previous version has been kept.\n' >&2
  fi
  rm -rf -- "$stage"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -- "$stage/app" "$stage/saved"
cp -a -- "$source_dir/." "$stage/app/"
# An existing portable library belongs to the user, rather than to the release.
if [[ -d "$target/bin/data" ]]; then
  rm -rf -- "$stage/app/bin/data"
  cp -a -- "$target/bin/data" "$stage/app/bin/data"
fi
# Validate the real executable, not just the environment-setting shell wrapper.
QT_QPA_PLATFORM=offscreen "$stage/app/bin/sillyplayer" --version
# Desktop Exec is not a shell: quote its path and escape desktop-entry special characters.
escaped="$(printf '%s' "$target/sillyplayer" | sed 's/\\/\\\\\\\\/g; s/"/\\\\"/g; s/`/\\\\`/g; s/\$/\\\\$/g; s/%/%%/g')"
printf '[Desktop Entry]\nType=Application\nName=SillyPlayer\nExec="%s"\nIcon=sillyplayer\nCategories=AudioVideo;Audio;Player;\nTerminal=false\nStartupWMClass=SillyPlayer\n' "$escaped" > "$stage/desktop"
ln -s -- "$target/sillyplayer" "$stage/launcher"
cp -- "$stage/app/assets/sillyplayer.png" "$stage/icon"
for name in launcher desktop icon; do
  destination="${!name}"
  if [[ -d "$destination" ]]; then
    printf 'Cannot replace directory used by desktop integration: %s\n' "$destination" >&2
    exit 1
  fi
  if [[ -e "$destination" || -L "$destination" ]]; then
    cp -a -- "$destination" "$stage/saved/$name"
  fi
done
updating=false
if [[ -d "$target" ]]; then
  updating=true
  mv -- "$target" "$backup"
fi
mv -- "$stage/app" "$target"
swapped=true
integration_started=true
mv -Tf -- "$stage/launcher" "$launcher"
mv -Tf -- "$stage/icon" "$icon"
mv -f -- "$stage/desktop" "$desktop"
committed=true
# Retire the incorrectly sized icon and any stale staging dirs left by interrupted older installs.
rm -f -- "$install_root/share/icons/hicolor/256x256/apps/sillyplayer.png" || true
for stale in "$install_root/opt"/.sillyplayer-stage.*; do
  [[ -d "$stale" && "$stale" != "$stage" ]] && rm -rf -- "$stale" || true
done
if command -v gtk-update-icon-cache >/dev/null; then gtk-update-icon-cache -f -t "$install_root/share/icons/hicolor" >/dev/null 2>&1 || true; fi
if command -v update-desktop-database >/dev/null; then update-desktop-database "$install_root/share/applications" >/dev/null 2>&1 || true; fi
if [[ "$updating" == true ]]; then
  printf 'Replaced the previous SillyPlayer installation. Library, settings and media have been kept.\n'
else
  printf 'Installed SillyPlayer.\n'
fi
printf 'Launch it from your applications menu or:\n  %s\n' "$target/sillyplayer"
