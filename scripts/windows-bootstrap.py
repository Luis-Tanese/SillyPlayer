#!/usr/bin/env python3
"""Fetch the Windows build SDK locally; none of this SDK is shipped wholesale."""
from pathlib import Path
import concurrent.futures, hashlib, json, subprocess, urllib.request, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]; sdk=root/'.deps/windows'; downloads=sdk/'downloads';downloads.mkdir(parents=True,exist_ok=True)
manifest={}
def fetch(url,name=None):
 p=downloads/(name or url.rsplit('/',1)[-1])
 if not p.exists():
  print('Fetching '+p.name,flush=True)
  with urllib.request.urlopen(url,timeout=90) as source,p.with_suffix(p.suffix+'.part').open('wb') as out:
   import shutil;shutil.copyfileobj(source,out)
  p.with_suffix(p.suffix+'.part').rename(p)
 manifest[p.name]={'url':url,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
 return p
base='https://download.qt.io/online/qtsdkrepository/windows_x86/desktop/qt6_642/'
metadata=ET.fromstring(urllib.request.urlopen(base+'Updates.xml',timeout=30).read())
items=[]
for p in metadata.findall('PackageUpdate'):
 name=p.findtext('Name','')
 if name not in ('qt.qt6.642.win64_mingw','qt.qt6.642.addons.qtimageformats.win64_mingw','qt.qt6.642.qtshadertools.win64_mingw'):continue
 for archive in p.findtext('DownloadableArchives','').split(', '):
  if archive.startswith(('qtbase-','qtdeclarative-','qtsvg-','qtimageformats-','qtshadertools-')):
   items.append((base+name+'/'+p.findtext('Version')+archive,archive))
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 archives=list(pool.map(lambda item:fetch(*item),items))
qt=sdk/'qt'
if not (qt/'.ready').exists():
 qt.mkdir(exist_ok=True)
 for archive in archives:
  subprocess.run(['7z','x','-y','-o'+str(qt),str(archive)],stdout=subprocess.DEVNULL,check=True)
 (qt/'.ready').touch()
mpv=fetch('https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261021/mpv-dev-x86_64-20261021-git-e471496c56.7z')
if not (sdk/'mpv/.ready').exists():
 (sdk/'mpv').mkdir(exist_ok=True)
 subprocess.run(['7z','x','-y','-o'+str(sdk/'mpv'),str(mpv)],stdout=subprocess.DEVNULL,check=True)
 (sdk/'mpv/.ready').touch()
for name,url in {
 'lame-3.100.tar.gz':'https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz',
 'ffmpeg-6.1.5.tar.xz':'https://ffmpeg.org/releases/ffmpeg-6.1.5.tar.xz',
 'libwebp-1.6.0.tar.gz':'https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz',
 'taglib-1.13.1.tar.gz':'https://taglib.org/releases/taglib-1.13.1.tar.gz',
 'libarchive-3.8.2.tar.xz':'https://www.libarchive.org/downloads/libarchive-3.8.2.tar.xz',
 'zlib-1.3.1.tar.gz':'https://zlib.net/fossils/zlib-1.3.1.tar.gz',
 'mingw-w64-x86_64-openssl-3.6.0-1-any.pkg.tar.zst':'https://mirror.msys2.org/mingw/mingw64/mingw-w64-x86_64-openssl-3.6.0-1-any.pkg.tar.zst'
}.items():
 try: fetch(url,name)
 except Exception as e:
  if 'openssl' not in name:raise
  # Resolve the version from the official MSYS2 package index rather than inventing a version.
  import re
  page=urllib.request.urlopen('https://packages.msys2.org/packages/mingw-w64-x86_64-openssl',timeout=45).read().decode()
  url=re.search(r'https://mirror.msys2.org/mingw/mingw64/mingw-w64-x86_64-openssl-[^"<> ]+\.pkg\.tar\.zst',page).group(0)
  fetch(url)
(sdk/'downloads.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('Windows SDK downloads complete',flush=True)
