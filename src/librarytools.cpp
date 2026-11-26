#include "processutils.h"
#include "librarytools.h"
#include "fileavailability.h"
#include "platformfiles.h"
#include "online.h"
#include <QCryptographicHash>
#include <QDirIterator>
#include <QElapsedTimer>
#include <QFileDialog>
#include <QFileInfo>
#include <QJsonDocument>
#include <QStorageInfo>
#include <QUuid>
#include <QtConcurrent>
#include <taglib/fileref.h>
#include <sys/stat.h>
#include <stdexcept>
#include <cmath>

namespace {
void require(bool ok,const QString &message) { if(!ok) throw std::runtime_error(message.toStdString()); }
QVariantMap fingerprint(const QString &path) {
  const QFileInfo info(path); const auto identity = fileIdentity(path);
  require(info.isFile() && info.isReadable() && !info.isSymLink() && !identity.isEmpty(),"File is unavailable or not a regular readable file: "+path);
  return {{"size",info.size()},{"mtime",info.lastModified().toMSecsSinceEpoch()},{"identity",identity}};
}
QString hashFile(const QString &path,const std::shared_ptr<std::atomic_bool> &stop) {
  QFile file(path); require(file.open(QIODevice::ReadOnly),file.errorString()); QCryptographicHash hash(QCryptographicHash::Sha256);
  while(!file.atEnd()) { require(!*stop,"Cancelled"); const auto block=file.read(256*1024); require(!block.isEmpty() || file.error()==QFile::NoError,file.errorString()); hash.addData(block); }
  return QString::fromLatin1(hash.result().toHex());
}
bool same(const QVariantMap &a,const QVariantMap &b) { return a["size"]==b["size"] && a["mtime"]==b["mtime"] && a["identity"]==b["identity"]; }
QVariantMap checkedFile(QVariantMap t,const std::shared_ptr<std::atomic_bool> &stop,bool fresh=false) {
  const QString path=t["path"].toString(); const auto before=fingerprint(path);
  QString digest;
  if(!fresh && same(before,t["cache"].toMap())) digest=t["cache"].toMap()["sha256"].toString();
  if(digest.isEmpty()) digest=hashFile(path,stop);
  require(same(before,fingerprint(path)),"File changed while checking: "+path);
  t["file"]=before; t["hash"]=digest; t.remove("cache"); return t;
}
QVariantMap errorResult(const std::exception &e) { return {{"error",QString::fromUtf8(e.what())}}; }
QVariantList candidatesFor(const QVariantList &requests,const std::shared_ptr<std::atomic_bool> &stop,const std::function<void(qint64,qint64,const QString&)> &progress) {
  QVariantList result; int n=0;
  for(auto v:requests) {
    require(!*stop,"Cancelled"); auto t=v.toMap(); t["id"]=t["track"].toMap()["id"];
    try {
      const auto old=t["track"].toMap(); const auto path=t["path"].toString();
      require(!QFileInfo(old["path"].toString()).isFile(),"Original file is already available");
      t=checkedFile(t,stop,true);
      const auto cache=old["cache"].toMap();
      const QString expected=same(old,cache)?cache["sha256"].toString():QString();
      bool verified=!expected.isEmpty();
      if(verified) require(t["hash"]==expected,"Checksum differs from the missing original");
      else {
        require(old["size"].toLongLong()>0 && t["file"].toMap()["size"].toLongLong()==old["size"].toLongLong(),"File size differs or original size is unknown");
        auto media = taggedFile(path);
        double duration=0;
        if(!media.isNull() && media.audioProperties()) duration=media.audioProperties()->lengthInMilliseconds()/1000.0;
        else {
          QProcess probe;
#ifdef Q_OS_WIN
    configureToolProcess(&probe);
#endif
          probe.start(Online::tool("ffprobe"),{"-v","error","-select_streams","a:0","-show_entries","stream=codec_type","-show_entries","format=duration","-of","json",path});
          if(!probe.waitForFinished(5000)) {probe.kill();probe.waitForFinished();throw std::runtime_error("Audio verification timed out");}
          const auto data=QJsonDocument::fromJson(probe.readAllStandardOutput()).toVariant().toMap();
          require(probe.exitCode()==0 && !data["streams"].toList().isEmpty(),"Cannot verify this audio file without a stored checksum");
          duration=data["format"].toMap()["duration"].toDouble();
        }
        require(old["duration"].toDouble()<=0 || std::abs(duration-old["duration"].toDouble())<=1.0,"Audio duration differs from the original");
      }
      t["eligible"]=true; t["selected"]=verified || t["manual"].toBool();
      t["reason"]=verified?"Checksum matches":"Path/file choice, size and duration match; original checksum unavailable — review before selecting";
    } catch(const std::exception &e) { require(!*stop,"Cancelled"); t["eligible"]=false; t["selected"]=false; t["reason"]=QString::fromUtf8(e.what()); }
    result<<t; progress(++n,requests.size(),"Checking replacement files");
  }
  return result;
}
}
LibraryTools::LibraryTools(Store *store,Library *library,Player *p,QObject *parent):QObject(parent),s(store),lib(library),player(p) {
  connect(&worker,&QFutureWatcher<QVariantMap>::finished,this,&LibraryTools::completed);
}
LibraryTools::~LibraryTools() { shutdown(); }
bool LibraryTools::begin() {
  if(held) return false;
  if(!lib->beginMaintenance()) { status="Wait for indexing, tag edits, backup or Trash to finish"; emit changed(); return false; }
  held=true; stopped=std::make_shared<std::atomic_bool>(false); fraction=-1; return true;
}
void LibraryTools::release() { if(held) { held=false; lib->endMaintenance(); } emit changed(); }
void LibraryTools::launch(const QString &kind,std::function<QVariantMap()> task) {
  operation=kind; status=kind; emit changed(); worker.setFuture(QtConcurrent::run(std::move(task)));
}
void LibraryTools::report(qint64 done,qint64 total,const QString &text) {
  QMetaObject::invokeMethod(this,[this,done,total,text] { if(!held || !worker.isRunning() || closing) return; status=text; fraction=total?double(done)/total:-1; emit changed(); },Qt::QueuedConnection);
}
QVariantMap LibraryTools::cached(const QVariantMap &track) const {
  auto copy=track; copy["cache"]=s->rows("SELECT * FROM track_hashes WHERE track=?",{track["id"]}).value(0).toMap(); return copy;
}
void LibraryTools::findMissing() {
  emit opened(); if(!begin()) return; mode="missing"; matches.clear();
  // bulk-load track_roots in 1 query instead of N+1 per-track queries on the UI thread
  QHash<QString, QVariantList> rootsByTrack;
  for (const auto &v : s->rows("SELECT track_roots.track, roots.* FROM roots JOIN track_roots ON roots.id=track_roots.root")) {
    const auto row = v.toMap();
    rootsByTrack[row["track"].toString()] << row;
  }
  auto tracks=s->rows("SELECT * FROM tracks WHERE deleted=0 AND mergedInto='' ORDER BY title COLLATE NOCASE");
  for(auto &v:tracks) { auto t=v.toMap(); t["roots"]=rootsByTrack.value(t["id"].toString()); v=t; }
  auto stop=stopped;
  launch("Finding missing files",[this,tracks,stop] {
    QVariantList rows;
    int n=0;
    QElapsedTimer throttle; throttle.start();
    for(auto v:tracks) {
      if(*stop) return QVariantMap{{"error","Cancelled"}};
      auto t=v.toMap();
      t["availability"]=fileAvailability(t,t["roots"].toList());
      if(t["availability"]!="Available" && t["availability"]!="Excluded") rows<<t;
      ++n;
      if(throttle.elapsed()>100 || n==tracks.size()) { throttle.restart(); report(n,tracks.size(),"Finding missing files"); }
    }
    return QVariantMap{{"missing",rows}};
  });
}
void LibraryTools::chooseFile(const QString &id) {
  if(held) return;
  const auto path=QFileDialog::getOpenFileName(nullptr,"Locate the missing song",{},"Audio (*.mp3 *.flac *.ogg *.oga *.opus *.m4a *.aac *.wav)");
  if(!path.isEmpty()) prepareFile(id,path);
}
void LibraryTools::chooseFolder(const QString &root) {
  if(held) return;
  const auto path=QFileDialog::getExistingDirectory(nullptr,"Locate moved music folder"); if(!path.isEmpty()) prepareFolder(root,path);
}
void LibraryTools::prepareFile(const QString &id,const QString &path) {
  emit opened(); if(!begin()) return; mode="recover"; rootId.clear(); newRoot.clear();
  const auto track=lib->track(id);
  QVariantList requests{QVariantMap{{"track",cached(track)},{"path",QFileInfo(path).canonicalFilePath()},{"manual",true}}}; auto stop=stopped;
  launch("Checking replacement files",[this,requests,stop] { try { return QVariantMap{{"matches",candidatesFor(requests,stop,[this](auto a,auto b,const auto &c){report(a,b,c);})}}; } catch(const std::exception &e) {return errorResult(e);} });
}
void LibraryTools::prepareFolder(const QString &id,const QString &path) {
  emit opened(); if(!begin()) return; mode="recover"; rootId=id; newRoot=QFileInfo(path).canonicalFilePath();
  const auto root=s->rows("SELECT * FROM roots WHERE id=?",{id}).value(0).toMap();
  if(root.isEmpty() || newRoot.isEmpty() || !s->rows("SELECT id FROM roots WHERE path=? AND id<>?",{newRoot,id}).isEmpty()) { status="Choose a readable folder that is not registered separately"; release(); return; }
  QHash<QString, QVariantMap> hashesByTrack;
  for (const auto &v : s->rows("SELECT * FROM track_hashes")) {
    const auto row = v.toMap();
    hashesByTrack.insert(row["track"].toString(), row);
  }
  QVariantList requests;
  const QString prefix=QDir::cleanPath(root["path"].toString())+"/";
  for(auto v:s->rows("SELECT * FROM tracks WHERE mergedInto=''")) {
    auto t=v.toMap(); const QString old=t["path"].toString();
    if(old.startsWith(prefix) && !QFileInfo(old).isFile()) {
      t["cache"] = hashesByTrack.value(t["id"].toString());
      requests<<QVariantMap{{"track",t},{"path",QDir(newRoot).absoluteFilePath(old.mid(prefix.size()))}};
    }
  }
  auto stop=stopped;
  launch("Checking replacement files",[this,requests,stop] { try { return QVariantMap{{"matches",candidatesFor(requests,stop,[this](auto a,auto b,const auto &c){report(a,b,c);})}}; } catch(const std::exception &e) {return errorResult(e);} });
}
void LibraryTools::toggleCandidate(const QString &id) {
  if(held) return;
  for(auto &v:matches) { auto t=v.toMap(); if(t["track"].toMap()["id"]==id && t["eligible"].toBool()) {t["selected"]=!t["selected"].toBool();v=t;break;} } emit changed();
}
void LibraryTools::selectCandidates() {
  if(held) return;
  bool all=true; for(auto v:matches) if(v.toMap()["eligible"].toBool() && !v.toMap()["selected"].toBool()) all=false;
  for(auto &v:matches) {auto t=v.toMap();t["selected"]=t["eligible"].toBool() && !all;v=t;} emit changed();
}
void LibraryTools::recover() {
  if(!begin()) return;
  QVariantList chosen; for(auto v:matches) if(v.toMap()["selected"].toBool() && v.toMap()["eligible"].toBool()) chosen<<v;
  if(chosen.isEmpty()) {status="Select replacement files first"; release(); return;}
  auto stop=stopped;
  launch("Verifying recovery",[this,chosen,stop] {
    try { int n=0; for(auto v:chosen) {const auto t=v.toMap(); const auto checked=checkedFile(t,stop,true); require(checked["hash"]==t["hash"] && same(checked["file"].toMap(),t["file"].toMap()),"Replacement changed; review it again"); report(++n,chosen.size(),"Verifying recovery");} return QVariantMap{{"matches",chosen}}; }
    catch(const std::exception &e) {return errorResult(e);}
  });
}
void LibraryTools::findDuplicates() {
  emit opened(); if(!begin()) return; mode="duplicates"; duplicates.clear();
  // bulk-load cached file hashes in 1 query instead of N+1 selects
  QHash<QString, QVariantMap> hashesByTrack;
  for (const auto &v : s->rows("SELECT * FROM track_hashes")) {
    const auto row = v.toMap();
    hashesByTrack.insert(row["track"].toString(), row);
  }
  QVariantList tracks;
  for(auto v:s->rows("SELECT * FROM tracks WHERE deleted=0 AND mergedInto='' ORDER BY added,id")) {
    auto t = v.toMap();
    t["cache"] = hashesByTrack.value(t["id"].toString());
    tracks << t;
  }
  auto stop=stopped;
  const QString loaded=player->mediaPath(),loadedId=player->loaded() && currentTrack?currentTrack():QString();
  launch("Finding exact duplicates",[this,tracks,stop,loaded,loadedId] {
    try {
      QMap<QString,QVariantList> groups; QVariantList hashes,failures; int n=0;
      QElapsedTimer throttle; throttle.start();
      for(auto v:tracks) {
        require(!*stop,"Cancelled"); const auto t=v.toMap();
        try { const auto checked=checkedFile(t,stop); groups[checked["hash"].toString()]<<checked; hashes<<checked; }
        catch(const std::exception &e) {require(!*stop,"Cancelled");failures<<QString::fromUtf8(e.what());}
        if(throttle.elapsed()>100) {throttle.restart();report(n,tracks.size(),"Checking exact file contents");} ++n;
      }
      QVariantList result;
      for(auto it=groups.cbegin();it!=groups.cend();++it) if(it.value().size()>1) {
        QString keep=it.value().first().toMap()["id"].toString();
        for(auto v:it.value()) if(v.toMap()["path"]==loaded || v.toMap()["id"]==loadedId) keep=v.toMap()["id"].toString();
        result<<QVariantMap{{"key",it.key()},{"hash",it.key()},{"tracks",it.value()},{"keep",keep}};
      }
      return QVariantMap{{"groups",result},{"hashes",hashes},{"failures",failures}};
    } catch(const std::exception &e) {return errorResult(e);}
  });
}
void LibraryTools::consolidate(const QString &hash,const QString &keep) {
  if(held) return;
  QVariantList tracks;
  for(auto v:duplicates) if(v.toMap()["hash"]==hash) tracks=v.toMap()["tracks"].toList();
  if(tracks.size()<2) {status="Scan for duplicates again"; emit changed(); return;}
  bool found=false;
  for(auto v:tracks) {const auto t=v.toMap(); if(t["id"]==keep) found=true; else if(player->loaded() && (t["path"]==player->mediaPath() || (currentTrack && t["id"]==currentTrack()))) {status="Keep the loaded song, or load another song before consolidating this group";emit changed();return;}}
  if(!found || !begin()) return;
  mergePlan={{"keep",keep},{"tracks",tracks},{"hash",hash}}; auto stop=stopped;
  launch("Verifying duplicates",[this,tracks,hash,stop] {
    try {int n=0; for(auto v:tracks) {const auto t=v.toMap();const auto checked=checkedFile(t,stop,true);require(checked["hash"]==hash && same(checked["file"].toMap(),t["file"].toMap()),"A duplicate changed; scan again before consolidating"); report(++n,tracks.size(),"Verifying duplicates");} return QVariantMap{{"verified",true}};}
    catch(const std::exception &e) {return errorResult(e);}
  });
}
void LibraryTools::mapReferences(const QString &keep,const QStringList &remove) {
  auto sql=[&](const QString &q,const QVariantList &args=QVariantList{}) {if(!s->exec(q,args)) throw std::runtime_error(s->error().toStdString());};
  for(const auto &id:remove) {
    sql("UPDATE members SET track=? WHERE track=?",{keep,id});
    sql("INSERT OR IGNORE INTO likes SELECT ? FROM likes WHERE track=?",{keep,id}); sql("DELETE FROM likes WHERE track=?",{id});
    const auto counts=s->rows("SELECT * FROM track_listens WHERE track=?",{id});
    if(!counts.isEmpty()) {const auto c=counts.first().toMap();sql("INSERT INTO track_listens VALUES(?,?,?) ON CONFLICT(track) DO UPDATE SET plays=plays+excluded.plays,lastPlayed=MAX(lastPlayed,excluded.lastPlayed)",{keep,c["plays"],c["lastPlayed"]});sql("DELETE FROM track_listens WHERE track=?",{id});}
    sql("UPDATE listening_plays SET track=? WHERE track=?",{keep,id});
    sql("UPDATE sync_counters SET track=? WHERE track=?",{keep,id});
    sql("UPDATE restore_ids SET local=? WHERE kind='track' AND local=?",{keep,id});
    sql("UPDATE tracks SET mergedInto=? WHERE mergedInto=?",{keep,id});
    sql("UPDATE tracks SET deleted=1,available=0,managed=0,mergedInto=? WHERE id=?",{keep,id});
  }
}
bool LibraryTools::commitRecovery(const QVariantList &rows,QString *error) {
  emit beforeMerge(); QVariantMap remap;
  if(!s->db().transaction()) {*error="Cannot start recovery transaction";return false;}
  try {
    auto sql=[&](const QString &q,const QVariantList &args=QVariantList{}) {if(!s->exec(q,args)) throw std::runtime_error(s->error().toStdString());};
    for(auto v:rows) {
      const auto t=v.toMap(),old=t["track"].toMap(); const QString id=old["id"].toString(),path=t["path"].toString();
      const auto current=lib->track(id); require(!current.isEmpty() && current["mergedInto"].toString().isEmpty() && current["path"]==old["path"] && !QFileInfo(current["path"].toString()).isFile(),"Library changed; review recovery again");
      require(same(fingerprint(path),t["file"].toMap()),"Replacement changed during recovery");
      const auto occupied=s->rows("SELECT * FROM tracks WHERE path=? AND id<>?",{path,id});
      if(!occupied.isEmpty()) {
        const auto other=occupied.first().toMap(); const QString from=other["id"].toString();
        require(other["mergedInto"].toString().isEmpty(),"Replacement is a consolidated alias; choose another file");
        mapReferences(id,{from}); remap[from]=id;
        sql("INSERT OR IGNORE INTO track_roots SELECT ?,root FROM track_roots WHERE track=?",{id,from});
        sql("UPDATE tracks SET path=? WHERE id=?",{path+".sillyplayer-alias-"+from,from});
      }
      const auto f=t["file"].toMap();
      sql("UPDATE tracks SET path=?,size=?,mtime=?,identity=?,managed=1,available=1,deleted=0 WHERE id=?",{path,f["size"],f["mtime"],f["identity"],id});
      sql("INSERT INTO track_hashes VALUES(?,?,?,?,?) ON CONFLICT(track) DO UPDATE SET size=excluded.size,mtime=excluded.mtime,identity=excluded.identity,sha256=excluded.sha256",{id,f["size"],f["mtime"],f["identity"],t["hash"]});
    }
    if(!rootId.isEmpty()) sql("UPDATE roots SET path=?,device=?,available=1 WHERE id=?",{newRoot,storageIdentity(newRoot),rootId});
    require(s->db().commit(),s->error()); emit remapped(remap); return true;
  } catch(const std::exception &e) {s->db().rollback();*error=QString::fromUtf8(e.what());return false;}
}
bool LibraryTools::commitMerge(const QString &keep,const QStringList &remove,QString *error) {
  emit beforeMerge();
  if(!s->db().transaction()) {*error="Cannot start consolidation transaction";return false;}
  try {
    require(!lib->track(keep).isEmpty() && lib->track(keep)["mergedInto"].toString().isEmpty() && QFileInfo(lib->track(keep)["path"].toString()).isFile(),"Kept song is no longer available");
    for(const auto &id:remove) require(!lib->track(id).isEmpty() && lib->track(id)["mergedInto"].toString().isEmpty(),"Duplicate was already consolidated");
    for(auto v:mergePlan["tracks"].toList()) {
      const auto t=v.toMap(),f=t["file"].toMap();
      require(same(fingerprint(t["path"].toString()),f),"A duplicate changed before committing; scan again");
      if(remove.contains(t["id"].toString())) require(s->exec("UPDATE tracks SET size=?,mtime=?,identity=? WHERE id=?",{f["size"],f["mtime"],f["identity"],t["id"]}),s->error());
    }
    mapReferences(keep,remove); require(s->db().commit(),s->error());
    QVariantMap ids; for(const auto &id:remove) ids[id]=keep; emit remapped(ids); return true;
  } catch(const std::exception &e) {s->db().rollback();*error=QString::fromUtf8(e.what());return false;}
}
void LibraryTools::completed() {
  if(closing || !worker.future().isValid()) return;
  const auto result=worker.result();
  worker.setFuture({});
  if(result.contains("error") || (*stopped && !finishing)) {
    if(operation=="Verifying duplicates") for(auto v:mergePlan["tracks"].toList()) s->exec("DELETE FROM track_hashes WHERE track=?",{v.toMap()["id"]});
    status=result.value("error","Cancelled").toString();release();return;
  }
  fraction=1;
  if(operation=="Finding missing files") {missingRows=result["missing"].toList();status=QString("%1 records need attention. Each row distinguishes absent files, unavailable storage and unresolved locations.").arg(missingRows.size());}
  else if(operation=="Checking replacement files") {matches=result["matches"].toList();status="Review replacements. Unverified matches require your explicit selection; metadata and song IDs are kept.";}
  else if(operation=="Finding exact duplicates") {
    duplicates=result["groups"].toList();
    bool cachedOk=s->db().transaction();
    if(cachedOk) for(auto v:result["hashes"].toList()) {const auto t=v.toMap(),f=t["file"].toMap();cachedOk=s->exec("INSERT INTO track_hashes VALUES(?,?,?,?,?) ON CONFLICT(track) DO UPDATE SET size=excluded.size,mtime=excluded.mtime,identity=excluded.identity,sha256=excluded.sha256",{t["id"],f["size"],f["mtime"],f["identity"],t["hash"]}) && cachedOk;}
    if(cachedOk) cachedOk=s->db().commit();
    if(!cachedOk) s->db().rollback();
    status=QString("%1 exact duplicate groups · %2 unreadable/unavailable files skipped").arg(duplicates.size()).arg(result["failures"].toList().size());
    if(!cachedOk) status+=" · checksum cache could not be saved";
  } else if(operation=="Verifying recovery") {
    QString error; if(commitRecovery(result["matches"].toList(),&error)) {status=QString("Reconnected %1 songs without changing their IDs").arg(result["matches"].toList().size());matches.clear();missingRows.clear();lib->rescan();}else status=error;
  } else if(operation=="Verifying duplicates") {
    const QString keep=mergePlan["keep"].toString(); QStringList remove; QVariantList extras; QString keeper;
    for(auto v:mergePlan["tracks"].toList()) {const auto t=v.toMap();if(t["id"]==keep) keeper=t["path"].toString();else {remove<<t["id"].toString();extras<<t;}}
    QString error;
    // a new song could have been loaded while hashing so never move its file
    for(auto v:extras) if(player->loaded() && (player->mediaPath()==v.toMap()["path"] || (currentTrack && v.toMap()["id"]==currentTrack()))) {status="Loaded song changed; keep it or try again after loading another song";release();return;}
    if(!commitMerge(keep,remove,&error)) {status=error;release();return;}
    finishing=true; const QString hash=mergePlan["hash"].toString();
    launch("Finishing Trash",[extras,keeper,hash] {
      QVariantList failures; int moved=0; auto neverStop=std::make_shared<std::atomic_bool>(false);
      for(auto v:extras) {const auto t=v.toMap();try {
        require(hashFile(keeper,neverStop)==hash,"Kept file changed; extra file was retained");
        require(same(fingerprint(t["path"].toString()),t["file"].toMap()) && hashFile(t["path"].toString(),neverStop)==hash,"Extra file changed; it was retained");
        QFile file(t["path"].toString());require(file.moveToTrash(),file.errorString());++moved;
      }catch(const std::exception &e){failures<<t["path"].toString()+": "+QString::fromUtf8(e.what());}}
      return QVariantMap{{"moved",moved},{"failures",failures}};
    }); return;
  } else if(operation=="Finishing Trash") {
    status=QString("Consolidated references · %1 extra files moved to Trash").arg(result["moved"].toInt());
    for(auto v:result["failures"].toList()) status+="\nRetained file: "+v.toString();
    duplicates.clear();mergePlan.clear();finishing=false;
  }
  release();
}
void LibraryTools::cancel() {if(stopped && !finishing) *stopped=true;}
void LibraryTools::shutdown() {if(closing)return;closing=true;if(stopped && !finishing)*stopped=true;worker.waitForFinished();release();}
