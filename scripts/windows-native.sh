#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
project_dir="$PWD"
windows_sdk="$project_dir/.deps/windows"
native_prefix="$windows_sdk/native"
mkdir -p "$windows_sdk/src" "$native_prefix" "$windows_sdk/host"
for archive in "$windows_sdk"/debs/*.deb; do dpkg-deb -x "$archive" "$windows_sdk/host"; done
for archive in "$windows_sdk"/downloads/*openssl*.pkg.tar.zst; do mkdir -p "$windows_sdk/msys"; tar --zstd -xf "$archive" -C "$windows_sdk/msys"; done
for name in zlib-1.3.1 taglib-1.13.1 libarchive-3.8.2; do
 if [[ ! -d "$windows_sdk/src/$name" ]]; then tar -xf "$windows_sdk/downloads/$name".tar.* -C "$windows_sdk/src"; fi
 if [[ "$name" == zlib* && -f "$native_prefix/bin/libzlib1.dll" ]] || [[ "$name" == taglib* && -f "$native_prefix/bin/libtag.dll" ]] || [[ "$name" == libarchive* && -f "$native_prefix/bin/libarchive.dll" ]]; then continue; fi
 build_dir="$windows_sdk/build/$name"
 flags=()
 if [[ "$name" == taglib* ]]; then flags+=(-DBUILD_TESTING=OFF -DBUILD_EXAMPLES=OFF -DWITH_ZLIB=ON); fi
 if [[ "$name" == libarchive* ]]; then flags+=(-DENABLE_TEST=OFF -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF -DENABLE_LZMA=OFF -DENABLE_BZip2=OFF -DENABLE_LZ4=OFF -DENABLE_ZSTD=OFF -DENABLE_LIBB2=OFF -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF -DENABLE_WERROR=OFF); fi
 cmake -S "$windows_sdk/src/$name" -B "$build_dir" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$project_dir/cmake/windows-mingw.cmake" -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$native_prefix" -DBUILD_SHARED_LIBS=ON "${flags[@]}"
 cmake --build "$build_dir" -j3
 cmake --install "$build_dir"
done
if [[ ! -f "$native_prefix/lib/libmp3lame.a" ]]; then
 tar -xf "$windows_sdk/downloads/lame-3.100.tar.gz" -C "$windows_sdk/src"
 (
 cd "$windows_sdk/src/lame-3.100"
 ./configure --host=x86_64-w64-mingw32 --prefix="$native_prefix" --disable-shared --enable-static --disable-frontend CC=x86_64-w64-mingw32-gcc-posix CFLAGS='-O2 -fno-asynchronous-unwind-tables'
 make -j3
 make install
 )
fi
if [[ ! -f "$native_prefix/lib/libz.dll.a" && -f "$native_prefix/lib/libzlib.dll.a" ]]; then
 ln -sf libzlib.dll.a "$native_prefix/lib/libz.dll.a"
fi
if [[ ! -f "$native_prefix/lib/pkgconfig/libavcodec.pc" ]]; then
 if [[ ! -d "$windows_sdk/src/ffmpeg-6.1.5" ]]; then tar -xf "$windows_sdk/downloads/ffmpeg-6.1.5.tar.xz" -C "$windows_sdk/src"; fi
 rm -rf "$windows_sdk/build/ffmpeg"
 mkdir -p "$windows_sdk/build/ffmpeg"
 (
 cd "$windows_sdk/build/ffmpeg"
 "$windows_sdk/src/ffmpeg-6.1.5/configure" --prefix="$native_prefix" --target-os=mingw32 --arch=x86_64 --enable-cross-compile --cross-prefix=x86_64-w64-mingw32- --cc=x86_64-w64-mingw32-gcc-posix --cxx=x86_64-w64-mingw32-g++-posix \
 --extra-cflags="-I$native_prefix/include -O2" --extra-ldflags="-L$native_prefix/lib -static-libgcc" \
 --x86asmexe="$project_dir/.deps/root/usr/bin/nasm" --enable-shared --disable-static --disable-debug --disable-doc --disable-autodetect --disable-everything --enable-network --enable-schannel --enable-libmp3lame --enable-zlib \
 --enable-decoder=aac,aac_fixed,alac,mp3,mp3float,flac,vorbis,opus,wmav1,wmav2,wmalossless,wmapro,wavpack,ape,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,pcm_u8,pcm_s16be,pcm_s24be,pcm_s32be,pcm_alaw,pcm_mulaw,mjpeg,png,webp,bmp,gif \
 --enable-encoder=libmp3lame,aac,flac,pcm_s16le,pcm_f32le,png,mjpeg \
 --enable-demuxer=ffmetadata,aac,mp3,flac,ogg,mov,wav,matroska,hls,mpegts,aiff,ape,asf,wv,image2,image2pipe \
 --enable-muxer=ipod,mp3,mp4,mov,flac,ogg,wav,pcm_f32le,matroska,webm,image2,image2pipe,adts \
 --enable-parser=aac,aac_latm,flac,mpegaudio,opus,png,vorbis \
 --enable-bsf=setts,mjpeg2jpeg,aac_adtstoasc,extract_extradata,opus_metadata \
 --enable-protocol=file,pipe,http,https,tcp,tls,crypto,data,httpproxy \
 --enable-filter=aresample,anull,aformat,atempo,alimiter,apad,atrim,asetpts,volume,equalizer,abuffer,abuffersink,buffer,buffersink,scale,format,copy,null,trim,setpts \
 > "$windows_sdk/ffmpeg-configure.log" 2>&1
 make -j "${BUILD_JOBS:-4}" > "$windows_sdk/ffmpeg-build.log" 2>&1
 make install >> "$windows_sdk/ffmpeg-build.log" 2>&1
 )
fi

# Official Qt's WebP plugin imports these ABI-versioned names.
if [[ ! -f "$native_prefix/bin/libwebp-7__.dll" ]]; then
 if [[ ! -d "$windows_sdk/src/libwebp-1.6.0" ]]; then tar -xf "$windows_sdk/downloads/libwebp-1.6.0.tar.gz" -C "$windows_sdk/src"; fi
 webp_source="$windows_sdk/src/libwebp-1.6.0/CMakeLists.txt"
 if ! rg -q 'webp-7__' "$webp_source"; then
 cat >> "$webp_source" <<'CMAKE'
set_target_properties(webp PROPERTIES OUTPUT_NAME "webp-7__")
set_target_properties(webpdemux PROPERTIES OUTPUT_NAME "webpdemux-2__")
set_target_properties(libwebpmux PROPERTIES OUTPUT_NAME "webpmux-3__")
CMAKE
 fi
 webp_flags=()
 for option in ANIM_UTILS CWEBP DWEBP EXTRAS FUZZTEST GIF2WEBP IMG2WEBP VWEBP WEBPINFO WEBPMUX WEBP_JS; do webp_flags+=("-DWEBP_BUILD_$option=OFF"); done
 cmake -S "$windows_sdk/src/libwebp-1.6.0" -B "$windows_sdk/build/webp" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$project_dir/cmake/windows-mingw.cmake" -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$native_prefix" -DBUILD_SHARED_LIBS=ON -DWEBP_BUILD_LIBWEBPMUX=ON "${webp_flags[@]}"
 cmake --build "$windows_sdk/build/webp" -j3
 cmake --install "$windows_sdk/build/webp"
fi

# build minimal static libass deps (fribidi + freetype + harfbuzz + libass) and minimal audio-only libmpv-2.dll
if [[ ! -f "$native_prefix/bin/libmpv-2.dll" ]]; then
 meson_bin="$project_dir/.deps/root/usr/bin/meson"
 export PYTHONPATH="$project_dir/.deps/root/usr/lib/python3/dist-packages${PYTHONPATH:+:$PYTHONPATH}"
 export PKG_CONFIG_LIBDIR="$native_prefix/lib/pkgconfig"
 unset PKG_CONFIG_PATH || true
 cross_file="$project_dir/cmake/windows-mingw-meson.ini"

 build_static_meson() {
  local name="$1" url="$2" pc="$3"
  shift 3
  if [[ -f "$native_prefix/lib/pkgconfig/$pc.pc" ]]; then return 0; fi
  local arc="$windows_sdk/downloads/$name.tar.xz"
  [[ -f "$arc" ]] || curl -fL --retry 2 -o "$arc" "$url"
  [[ -d "$windows_sdk/src/$name" ]] || tar -xf "$arc" -C "$windows_sdk/src"
  local bdir="$windows_sdk/build/$name"
  rm -rf "$bdir"
  "$meson_bin" setup "$bdir" "$windows_sdk/src/$name" \
   --cross-file="$cross_file" --prefix="$native_prefix" --libdir=lib \
   --buildtype=release --default-library=static "$@" \
   > "$windows_sdk/$name-configure.log" 2>&1
  ninja -C "$bdir" -j "${BUILD_JOBS:-4}" > "$windows_sdk/$name-build.log" 2>&1
  ninja -C "$bdir" install >> "$windows_sdk/$name-build.log" 2>&1
 }

 build_static_meson fribidi-1.0.15 "https://github.com/fribidi/fribidi/releases/download/v1.0.15/fribidi-1.0.15.tar.xz" fribidi \
  -Ddocs=false -Dbin=false -Dtests=false
 build_static_meson freetype-2.13.3 "https://downloads.sourceforge.net/project/freetype/freetype2/2.13.3/freetype-2.13.3.tar.xz" freetype2 \
  --auto-features=disabled
 build_static_meson harfbuzz-10.1.0 "https://github.com/harfbuzz/harfbuzz/releases/download/10.1.0/harfbuzz-10.1.0.tar.xz" harfbuzz \
  --auto-features=disabled -Dtests=disabled -Ddocs=disabled -Dbenchmark=disabled -Dutilities=disabled
 build_static_meson libass-0.17.3 "https://github.com/libass/libass/releases/download/0.17.3/libass-0.17.3.tar.xz" libass \
  --auto-features=disabled -Ddirectwrite=enabled

 mpv_arc="$project_dir/.deps/mpv-0.36.0.tar.gz"
 [[ -f "$mpv_arc" ]] || curl -fL --retry 2 -o "$mpv_arc" https://github.com/mpv-player/mpv/archive/refs/tags/v0.36.0.tar.gz
 [[ -d "$project_dir/.deps/mpv-0.36.0" ]] || tar -xzf "$mpv_arc" -C "$project_dir/.deps"
 mpv_build="$windows_sdk/build/mpv"
 rm -rf "$mpv_build"
 "$meson_bin" setup "$mpv_build" "$project_dir/.deps/mpv-0.36.0" \
  --cross-file="$cross_file" --prefix="$native_prefix" --libdir=lib \
  --buildtype=release --auto-features=disabled \
  -Dlibmpv=true -Dcplayer=false -Dgl=disabled \
  -Dwasapi=enabled -Dzlib=enabled -Dgpl=true \
  -Dc_link_args="-static-libgcc" \
  > "$windows_sdk/mpv-configure.log" 2>&1
 ninja -C "$mpv_build" -j "${BUILD_JOBS:-4}" > "$windows_sdk/mpv-build.log" 2>&1
 ninja -C "$mpv_build" install >> "$windows_sdk/mpv-build.log" 2>&1
fi
