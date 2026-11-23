#!/usr/bin/env python3
"""Keep current downloads in dist; preserve older archives outside it.
An old extracted runtime is removed only when every file matches its ZIP CRC.
Unrecognized or changed runtime trees are archived intact instead.
"""
from pathlib import Path
import json,re,shutil,zipfile,zlib
root=Path(__file__).resolve().parents[1];dist=root/'dist';archive=root/'build/release-archive'
desktop=re.search(r'project\(SillyPlayer VERSION ([\d.]+)',(root/'CMakeLists.txt').read_text()).group(1)
android=re.search(r'versionName\s*=\s*"([\d.]+)"',(root/'mobile/android/app/build.gradle.kts').read_text()).group(1)
report={'currentDesktop':desktop,'currentAndroid':android,'archived':[],'removedDuplicateTrees':[],'preservedChangedTrees':[]}
def move(source,destination):
 destination.parent.mkdir(parents=True,exist_ok=True)
 if destination.exists():raise RuntimeError('Archive destination already exists: '+str(destination))
 shutil.move(source,destination)
 report['archived'].append(str(destination.relative_to(root)))
for tree in sorted(dist.glob('SillyPlayer-*-linux-x86_64')):
 if not tree.is_dir():continue
 match=re.fullmatch(r'SillyPlayer-([\d.]+)-linux-x86_64',tree.name)
 if not match:continue
 version=match[1];package=Path(str(tree)+'.zip');identical=False
 if package.exists():
  with zipfile.ZipFile(package) as z:
   entries={n.filename:n for n in z.infolist() if not n.is_dir()}
   files={str(p.relative_to(dist)):p for p in tree.rglob('*') if p.is_file() or p.is_symlink()}
   identical=set(entries)==set(files)
   if identical:
    for name,file in files.items():
     crc=0
     if file.is_symlink():crc=zlib.crc32(str(file.readlink()).encode())
     else:
      if file.stat().st_size!=entries[name].file_size:identical=False;break
      with file.open('rb') as f:
       while block:=f.read(1024*1024):crc=zlib.crc32(block,crc)
     if crc!=entries[name].CRC:identical=False;break
   # Check the retained compressed copy before deleting a redundant extraction.
   if identical and z.testzip() is not None:raise RuntimeError('Corrupt ZIP: '+str(package))
 if identical:
  shutil.rmtree(tree);report['removedDuplicateTrees'].append(tree.name)
 else:
  move(tree,archive/'linux'/version/tree.name);report['preservedChangedTrees'].append(tree.name)
for file in sorted(dist.glob('SillyPlayer-*')):
 match=re.match(r'SillyPlayer-([\d.]+)-(android|linux)',file.name)
 if not match:continue
 version,platform=match.groups();current=android if platform=='android' else desktop
 destination=(dist/platform/version if version==current else archive/platform/version)/file.name
 move(file,destination)
(root/'build/dist-cleanup.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v if not isinstance(v,list) else len(v) for k,v in report.items()},indent=2))
