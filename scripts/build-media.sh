#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
project_dir="$PWD"
sdk="$project_dir/.deps/root/usr"
media="$project_dir/.deps/media"
mkdir -p .deps/debs "$media"

# fetch build-time headers/tools locally without touching system packages
pkgs=(libmp3lame-dev libxml2-dev nasm meson libpipewire-0.3-dev libspa-0.2-dev libass-dev libunibreak-dev)
(
  cd .deps/debs
  for pkg in "${pkgs[@]}"; do
    ls "${pkg}_"*.deb >/dev/null 2>&1 || apt-get download "$pkg"
  done
)
for package in "${pkgs[@]}"; do
  for deb in .deps/debs/"$package"_*.deb; do dpkg-deb -x "$deb" .deps/root; done
done

python3 - <<'PY'
from pathlib import Path
sdk = Path('.deps/root/usr').resolve()
root = sdk / 'lib/x86_64-linux-gnu'
root.mkdir(parents=True, exist_ok=True)
for pattern in ('libmp3lame*', 'libxml2*', 'libpipewire-0.3*', 'libass*', 'libunibreak*'):
    for source in Path('/usr/lib/x86_64-linux-gnu').glob(pattern):
        dest = root / source.name
        if not dest.exists():
            if dest.is_symlink():
                dest.unlink()
            dest.symlink_to(source)
# rewrite prefix in extracted pkgconfig files so headers resolve inside .deps/root/usr
for pc in (root / 'pkgconfig').glob('*.pc'):
    text = pc.read_text(errors='replace')
    updated = text.replace('prefix=/usr\n', f'prefix={sdk}\n')
    if updated != text:
        pc.write_text(updated)
PY

archive=.deps/ffmpeg-6.1.5.tar.xz
if [[ ! -f "$archive" && -f .deps/windows/downloads/ffmpeg-6.1.5.tar.xz ]]; then
  cp .deps/windows/downloads/ffmpeg-6.1.5.tar.xz "$archive"
fi
if [[ ! -f "$archive" ]]; then
  curl -fL --retry 2 -o "$archive" https://ffmpeg.org/releases/ffmpeg-6.1.5.tar.xz
fi
printf '%s  %s\n' '05fbe9db1f5452a3605bb1d9bf91da9d4bb08162891692e4f172b58c49109412' "$archive" | sha256sum --check --status
[[ -d .deps/ffmpeg-6.1.5 ]] || tar -xJf "$archive" -C .deps

export PATH="$sdk/bin:$PATH"
export PKG_CONFIG_PATH="$media/lib/pkgconfig:$sdk/lib/x86_64-linux-gnu/pkgconfig:/usr/lib/x86_64-linux-gnu/pkgconfig"

if [[ ! -f "$media/bin/ffmpeg" || ! -f "$media/lib/libavcodec.so" ]]; then
  mkdir -p .deps/build-ffmpeg-linux
  (
    cd .deps/build-ffmpeg-linux
    "$project_dir/.deps/ffmpeg-6.1.5/configure" --prefix="$media" \
      --extra-cflags="-I$sdk/include -I$sdk/include/libxml2 -O2" \
      --extra-ldflags="-L$sdk/lib/x86_64-linux-gnu" \
      --disable-static --enable-shared --disable-debug --disable-doc \
      --disable-autodetect --disable-everything --enable-pic --enable-network \
      --enable-openssl --enable-libmp3lame --enable-libxml2 --enable-zlib \
      --enable-decoder=aac,aac_fixed,alac,mp3,mp3float,flac,vorbis,opus,wmav1,wmav2,wmalossless,wmapro,wavpack,ape,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,pcm_u8,pcm_s16be,pcm_s24be,pcm_s32be,pcm_alaw,pcm_mulaw,mjpeg,png,webp,bmp,gif \
      --enable-encoder=libmp3lame,aac,flac,pcm_s16le,pcm_f32le,png,mjpeg \
      --enable-demuxer=ffmetadata,aac,mp3,flac,ogg,mov,wav,matroska,hls,dash,mpegts,aiff,ape,asf,wv,image2,image2pipe \
      --enable-muxer=ipod,mp3,mp4,mov,flac,ogg,wav,pcm_f32le,matroska,webm,image2,image2pipe,adts \
      --enable-parser=aac,aac_latm,flac,mpegaudio,opus,png,vorbis \
      --enable-bsf=setts,mjpeg2jpeg,aac_adtstoasc,extract_extradata,opus_metadata \
      --enable-protocol=file,pipe,http,https,tcp,tls,crypto,data,httpproxy \
      --enable-filter=aresample,anull,aformat,atempo,alimiter,apad,atrim,asetpts,volume,equalizer,abuffer,abuffersink,buffer,buffersink,scale,format,copy,null,trim,setpts \
      > "$project_dir/.deps/media-configure.log" 2>&1
    make -j "${BUILD_JOBS:-4}" > "$project_dir/.deps/media-build.log" 2>&1
    make install >> "$project_dir/.deps/media-build.log" 2>&1
  )
fi

# build minimal audio-only libmpv 0.36.0 against our minimal FFmpeg so system libmpv2/libav bloat is gone
mpv_archive=.deps/mpv-0.36.0.tar.gz
if [[ ! -f "$mpv_archive" ]]; then
  curl -fL --retry 2 -o "$mpv_archive" https://github.com/mpv-player/mpv/archive/refs/tags/v0.36.0.tar.gz
fi
[[ -d .deps/mpv-0.36.0 ]] || tar -xzf "$mpv_archive" -C .deps

if [[ ! -f "$media/lib/libmpv.so" ]]; then
  export PYTHONPATH="$sdk/lib/python3/dist-packages${PYTHONPATH:+:$PYTHONPATH}"
  rm -rf .deps/build-mpv-linux
  "$sdk/bin/meson" setup .deps/build-mpv-linux .deps/mpv-0.36.0 \
    --prefix="$media" --libdir=lib --buildtype=release \
    --auto-features=disabled \
    -Dlibmpv=true -Dcplayer=false -Dgl=disabled \
    -Diconv=enabled -Dzlib=enabled \
    -Dalsa=enabled -Dpulse=enabled -Dpipewire=enabled \
    -Dgpl=true \
    > "$project_dir/.deps/mpv-linux-configure.log" 2>&1
  ninja -C .deps/build-mpv-linux -j "${BUILD_JOBS:-4}" > "$project_dir/.deps/mpv-linux-build.log" 2>&1
  ninja -C .deps/build-mpv-linux install >> "$project_dir/.deps/mpv-linux-build.log" 2>&1
fi

# keep .deps/media self-contained via $ORIGIN rpaths so host tools don't need LD_LIBRARY_PATH overrides
patchelf_bin="$(command -v patchelf || echo "$sdk/bin/patchelf")"
if [[ -x "$patchelf_bin" ]]; then
  for f in "$media"/lib/*.so*; do
    [[ -f "$f" && ! -L "$f" ]] && "$patchelf_bin" --set-rpath '$ORIGIN' "$f"
  done
  for f in "$media"/bin/*; do
    [[ -f "$f" ]] && "$patchelf_bin" --set-rpath '$ORIGIN/../lib' "$f"
  done
fi

printf 'Minimal FFmpeg + libmpv built in %s\n' "$media"
