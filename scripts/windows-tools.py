#!/usr/bin/env python3
from pathlib import Path
from extractor_bundle import install_extractor
import hashlib, json, re, subprocess, urllib.request
root=Path(__file__).resolve().parents[1]; sdk=root/'.deps/windows';cache=sdk/'downloads'; tools=sdk/'tools';tools.mkdir(exist_ok=True)
def fetch(url,dest):
 if not dest.exists():
  print('Fetching '+dest.name,flush=True)
  with urllib.request.urlopen(url,timeout=90) as source,dest.open('wb') as out:
   import shutil;shutil.copyfileobj(source,out)
 digest=hashlib.sha256(dest.read_bytes()).hexdigest()
 return {'url':url,'sha256':digest}
install_extractor('win', tools)
entries={}
page=urllib.request.urlopen('https://packages.msys2.org/packages/mingw-w64-x86_64-vulkan-loader',timeout=45).read().decode()
url=re.search(r'https://mirror.msys2.org/mingw/mingw64/mingw-w64-x86_64-vulkan-loader-[^"<> ]+\.pkg\.tar\.zst',page).group(0)
archive=cache/url.rsplit('/',1)[1];entries[archive.name]=fetch(url,archive)
subprocess.run(['tar','--zstd','-xf',str(archive),'-C',str(sdk/'msys')],check=True)
(sdk/'tools.json').write_text(json.dumps(entries,indent=2)+'\n')
