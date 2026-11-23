#include "backup.h"
#include "platformfiles.h"
#include <archive.h>
#include <archive_entry.h>
#include "contenthash.h"
#include <QDateTime>
#include <QDir>
#include <QElapsedTimer>
#include <QFileDialog>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSqlQuery>
#include <QSqlError>
#include <memory>
#include <map>
#include <QScopeGuard>
#include <QSet>
#include <QStorageInfo>
#include <QTemporaryDir>
#include <QUrl>
#include <QUuid>
#include <QtConcurrent>
#include <cmath>
#include <stdexcept>
#include <limits>
#include <sys/stat.h>

namespace {
QString uuid() { return QUuid::createUuid().toString(QUuid::WithoutBraces); }
void require(bool ok,const QString &error) { if(!ok) throw std::runtime_error(error.toStdString()); }
QString localPath(const QString &value) { return QUrl(value).isLocalFile()?QUrl(value).toLocalFile():value; }
void check(const std::shared_ptr<std::atomic_bool> &stop) { require(!*stop,"Cancelled"); }
using Progress=std::function<void(qint64,qint64,const QString &)>;
QString hashFile(const QString &path,const std::shared_ptr<std::atomic_bool> &stop) {
  QFile f(path); require(f.open(QIODevice::ReadOnly),"Cannot read "+path+": "+f.errorString());
  ContentHash hash;
  while(!f.atEnd()) { check(stop); const auto block=f.read(256*1024); require(!block.isEmpty() || f.error()==QFile::NoError,"Read failed: "+path); hash.addData(block); }
  return QString::fromLatin1(hash.result().toHex());
}
QVariantMap failure(const std::exception &e) { return {{"error",QString::fromUtf8(e.what())}}; }
QVariantMap inspect(QVariantMap manifest) {
  QVariantList tracks,omissions;
  qint64 bytes=0;
  for(const auto &v:manifest["tracks"].toList()) {
    const auto t=v.toMap(); const QFileInfo fi(t["path"].toString());
    if(!fi.isFile() || !fi.isReadable()) omissions<<QVariantMap{{"id",t["id"]},{"title",t["title"]},{"reason","Audio unavailable or unreadable"}};
    else { tracks<<t; bytes+=fi.size(); }
  }
  manifest["tracks"]=tracks; manifest["omissions"]=omissions;
  return {{"manifest",manifest},{"count",tracks.size()},{"bytes",bytes},{"omissions",omissions}};
}
QVariantMap writeZip(QVariantMap manifest,const QString &path,const std::shared_ptr<std::atomic_bool> &stop,const Progress &progress) {
  try {
    QSaveFile out(path); require(out.open(QIODevice::WriteOnly),out.errorString());
    auto a=archive_write_new(); require(a,"Cannot initialize ZIP writer");
    auto release=qScopeGuard([&] { archive_write_free(a); });
    require(archive_write_set_format_zip(a)==ARCHIVE_OK,"ZIP format unavailable");
    require(archive_write_set_options(a,"zip:compression=store,zip:zip64")==ARCHIVE_OK,"ZIP64 unavailable");
    require(archive_write_open_fd(a,out.handle())==ARCHIVE_OK,"Cannot open ZIP output");
    QVariantList assets;
    QSet<QString> written;
    qint64 done=0,total=0;
    for(const auto &v:manifest["tracks"].toList()) total+=QFileInfo(v.toMap()["path"].toString()).size();
    auto add=[&](const QString &source,const QString &kind,bool optional)->QString {
      const QString file=localPath(source);
      if(file.isEmpty() || !QFileInfo(file).isFile()) { require(optional,"Audio unavailable: "+file); return {}; }
      const QFileInfo before(file);
      const QString digest=hashFile(file,stop);
      QString ext=before.suffix().toLower();
      if(!QRegularExpression("^[a-z0-9]{1,10}$").match(ext).hasMatch()) ext="bin";
      const QString entry=kind+"/"+digest+"."+ext;
      if(written.contains(entry)) return entry;
      QFile f(file); require(f.open(QIODevice::ReadOnly),f.errorString());
      auto e=archive_entry_new(); auto free=qScopeGuard([&] { archive_entry_free(e); });
      archive_entry_set_pathname_utf8(e,entry.toUtf8().constData());
      archive_entry_set_size(e,before.size()); archive_entry_set_filetype(e,AE_IFREG); archive_entry_set_perm(e,0600);
      require(archive_write_header(a,e)==ARCHIVE_OK,"Cannot write ZIP entry");
      ContentHash verify;
      qint64 bytes=0;
      while(!f.atEnd()) {
        check(stop); auto block=f.read(256*1024); require(!block.isEmpty() || f.error()==QFile::NoError,"Cannot read "+file);
        require(archive_write_data(a,block.constData(),block.size())==block.size(),"Cannot write ZIP data (disk full?)");
        verify.addData(block); bytes+=block.size(); if(kind=="media") done+=block.size();
        progress(done,total,"Backing up "+before.fileName());
      }
      require(bytes==before.size() && QFileInfo(file).lastModified()==before.lastModified() && QString::fromLatin1(verify.result().toHex())==digest,"File changed during backup: "+file);
      written.insert(entry); assets<<QVariantMap{{"path",entry},{"sha256",digest},{"size",bytes}};
      return entry;
    };
    QVariantList tracks,collections;
    for(auto v:manifest["tracks"].toList()) {
      auto t=v.toMap(); const QString media=add(t["path"].toString(),"media",false);
      t.remove("path"); t.remove("identity"); t.remove("mtime"); t.remove("available"); t.remove("deleted"); t.remove("liked");
      t["media"]=media; t["art"]=add(t["art"].toString(),"art",true); tracks<<t;
    }
    for(auto v:manifest["collections"].toList()) { auto c=v.toMap(); c["art"]=add(c["art"].toString(),"art",true); collections<<c; }
    manifest["tracks"]=tracks; manifest["collections"]=collections; manifest["assets"]=assets;
    QSet<QString> present; for(auto v:tracks) present.insert(v.toMap()["id"].toString());
    QVariantList members,likes;
    for(auto v:manifest["members"].toList()) if(present.contains(v.toMap()["track"].toString())) members<<v;
    for(auto v:manifest["likes"].toList()) if(present.contains(v.toMap()["track"].toString())) likes<<v;
    manifest["members"]=members; manifest["likes"]=likes;
    const auto json=QJsonDocument::fromVariant(manifest).toJson(QJsonDocument::Compact);
    require(json.size()<=16*1024*1024,"Library manifest exceeds 16 MiB");
    auto e=archive_entry_new(); auto free=qScopeGuard([&] { archive_entry_free(e); });
    archive_entry_set_pathname(e,"manifest.json"); archive_entry_set_filetype(e,AE_IFREG); archive_entry_set_perm(e,0600); archive_entry_set_size(e,json.size());
    require(archive_write_header(a,e)==ARCHIVE_OK && archive_write_data(a,json.constData(),json.size())==json.size(),"Cannot write manifest");
    require(archive_write_close(a)==ARCHIVE_OK,"Cannot finish ZIP");
    check(stop); require(out.commit(),"Cannot publish backup: "+out.errorString());
    return {{"count",tracks.size()},{"omitted",manifest["omissions"].toList().size()}};
  } catch(const std::exception &e) { return failure(e); }
}
struct Reader {
  archive *a=archive_read_new();
  explicit Reader(const QString &file) {
    archive_read_support_format_zip(a); archive_read_support_filter_none(a);
#ifdef Q_OS_WIN
    const int opened = archive_read_open_filename_w(a, reinterpret_cast<const wchar_t *>(file.utf16()), 256*1024);
#else
    const int opened = archive_read_open_filename(a, QFile::encodeName(file).constData(), 256*1024);
#endif
    if(opened != ARCHIVE_OK) {
      const QString error=QString::fromUtf8(archive_error_string(a)); archive_read_free(a); a=nullptr;
      throw std::runtime_error(error.toStdString());
    }
  }
  ~Reader() { if(a) archive_read_free(a); }
};
QString entryName(archive_entry *e) {
  const char *name=archive_entry_pathname_utf8(e);
  require(name,"Archive path is not UTF-8");
  const QString p=QString::fromUtf8(name);
  require(archive_entry_filetype(e)==AE_IFREG && !archive_entry_symlink(e) && !archive_entry_hardlink(e),"Archive contains a link or non-file entry");
  require(p=="manifest.json" || QRegularExpression("^(media|art)/[a-f0-9]{64}\\.[a-z0-9]{1,10}$").match(p).hasMatch(),"Unsafe or unsupported archive path");
  return p;
}
QVariantMap readZip(const QString &file,const QString &destination,const std::shared_ptr<std::atomic_bool> &stop,const Progress &progress) {
  QString stage;
  try {
    QByteArray json; QSet<QString> seen;
    { Reader reader(file); archive_entry *entry=nullptr; int status;
      while((status=archive_read_next_header(reader.a,&entry))==ARCHIVE_OK) {
        check(stop); const auto name=entryName(entry);
        require(!seen.contains(name) && seen.size()<100000,"Duplicate or excessive archive entries"); seen.insert(name);
        if(name=="manifest.json") {
          require(archive_entry_size(entry)>0 && archive_entry_size(entry)<=16*1024*1024,"Invalid manifest size");
          char buffer[65536]; la_ssize_t n;
          while((n=archive_read_data(reader.a,buffer,sizeof buffer))>0) { json.append(buffer,n); require(json.size()<=16*1024*1024,"Manifest too large"); }
          require(n==0,"Corrupt manifest");
        } else require(archive_read_data_skip(reader.a)==ARCHIVE_OK,"Corrupt ZIP entry");
      }
      require(status==ARCHIVE_EOF,"Corrupt ZIP headers");
    }
    const auto doc=QJsonDocument::fromJson(json); require(doc.isObject(),"Missing or invalid manifest.json");
    auto manifest=doc.toVariant().toMap();
    require(manifest["format"]=="SillyPlayer Library" && manifest["version"].toInt()==1,"Unsupported backup format or version");
    require(!QUuid(manifest["libraryId"].toString()).isNull(),"Invalid source library identity");
    QHash<QString,QVariantMap> assets; qint64 total=0;
    for(auto v:manifest["assets"].toList()) {
      auto asset=v.toMap(); const QString name=asset["path"].toString(),hash=asset["sha256"].toString();
      const qint64 size=asset["size"].toLongLong();
      require(QRegularExpression("^(media|art)/[a-f0-9]{64}\\.[a-z0-9]{1,10}$").match(name).hasMatch() && name.section('/',1).section('.',0,0)==hash && size>=0 && !assets.contains(name),"Invalid asset declaration");
      require(size<=std::numeric_limits<qint64>::max()-total,"Backup size overflow"); total+=size; assets.insert(name,asset);
    }
    require(seen.size()==assets.size()+1,"Undeclared archive payload");
    for(auto it=assets.cbegin();it!=assets.cend();++it) require(seen.contains(it.key()),"Missing archive payload");
    auto validateIds=[&](const QString &key) {
      QSet<QString> ids;
      for(auto v:manifest[key].toList()) { const auto id=v.toMap()["id"].toString(); require(!id.isEmpty() && id.size()<=200 && !ids.contains(id),"Invalid or duplicate "+key+" identity"); ids.insert(id); }
      return ids;
    };
    const auto tracks=validateIds("tracks"),collections=validateIds("collections"); validateIds("members");
    for(auto v:manifest["tracks"].toList()) {
      const auto t=v.toMap(); const double speed=t.value("speed",1.0).toDouble();
      require(std::isfinite(speed) && speed>=0.5 && speed<=2,"Invalid song speed");
      bool volumeOk=false;const double volume=t.value("volumePercent",100.0).toDouble(&volumeOk);
      require(volumeOk && std::isfinite(volume) && volume>=0 && volume<=300,"Invalid song volume");
      require(t["media"].toString().startsWith("media/") && assets.contains(t["media"].toString()),"Missing song audio");
      const QString art=t["art"].toString(); require(art.isEmpty() || (art.startsWith("art/") && assets.contains(art)),"Invalid song cover");
    }
    for(auto v:manifest["collections"].toList()) {
      const auto c=v.toMap(); const QString art=c["art"].toString();
      require(QStringList{"playlist","custom"}.contains(c["kind"].toString()) && (art.isEmpty() || (art.startsWith("art/") && assets.contains(art))),"Invalid collection");
    }
    for(auto v:manifest["members"].toList()) { auto m=v.toMap(); require(tracks.contains(m["track"].toString()) && collections.contains(m["collection"].toString()),"Broken playlist reference"); }
    for(auto v:manifest["likes"].toList()) require(tracks.contains(v.toMap()["track"].toString()),"Broken liked-song reference");
    require(QDir().mkpath(destination),"Cannot create restore destination");
    require(QStorageInfo(destination).bytesAvailable()>1024*1024 && total<QStorageInfo(destination).bytesAvailable()-1024*1024,"Not enough space to restore this archive");
    QTemporaryDir temp(destination+"/.sillyplayer-restore-XXXXXX"); require(temp.isValid(),"Cannot stage restore"); stage=temp.path();
    qint64 done=0;
    { Reader reader(file); archive_entry *entry=nullptr; int status; QSet<QString> extracted;
      while((status=archive_read_next_header(reader.a,&entry))==ARCHIVE_OK) {
        check(stop); const QString name=entryName(entry);
        require(!extracted.contains(name),"Duplicate archive entry"); extracted.insert(name);
        if(name=="manifest.json") { archive_read_data_skip(reader.a); continue; }
        require(assets.contains(name) && archive_entry_size(entry)==assets[name]["size"].toLongLong(),"Archive size mismatch");
        const QString target=stage+"/"+name; require(QDir().mkpath(QFileInfo(target).path()),"Cannot create staging directory");
        QFile out(target); require(out.open(QIODevice::WriteOnly|QIODevice::NewOnly),out.errorString());
        ContentHash hash; char buffer[256*1024]; la_ssize_t n; qint64 bytes=0;
        while((n=archive_read_data(reader.a,buffer,sizeof buffer))>0) {
          check(stop); bytes+=n; require(bytes<=assets[name]["size"].toLongLong(),"Archive payload exceeds declared size");
          require(out.write(buffer,n)==n,"Cannot extract audio (disk full?)"); hash.addData(QByteArrayView(buffer,n)); done+=n; progress(done,total,"Verifying backup files");
        }
        require(n==0 && bytes==assets[name]["size"].toLongLong() && QString::fromLatin1(hash.result().toHex())==assets[name]["sha256"].toString(),"Corrupt archive or checksum mismatch");
        require(out.flush(),"Cannot flush restored file");
      }
      require(status==ARCHIVE_EOF && extracted==seen,"Archive changed during restore");
    }
    temp.setAutoRemove(false);
    return {{"manifest",manifest},{"stage",stage},{"count",tracks.size()},{"collections",collections.size()},{"bytes",total},{"omissions",manifest["omissions"]}};
  } catch(const std::exception &e) { if(!stage.isEmpty()) QDir(stage).removeRecursively(); return failure(e); }
}
// hash and publish on the worker
// only the atomic SQL merge uses the owning thread
QVariantMap matchExisting(QVariantMap prepared,QVariantList existing,const std::shared_ptr<std::atomic_bool> &stop,const Progress &progress) {
  try {
    const auto manifest=prepared["manifest"].toMap(); QSet<qint64> sizes;
    for(auto v:manifest["assets"].toList()) if(v.toMap()["path"].toString().startsWith("media/")) sizes.insert(v.toMap()["size"].toLongLong());
    QVariantMap matches,verifiedHashes; QVariantList readable; int n=0;
    for(auto v:existing) {
      check(stop); const auto t=v.toMap(); const QString path=t["path"].toString(); const QFileInfo before(path);
      if(before.isFile() && before.isReadable()) {
        readable << t["id"];
        if (!sizes.contains(before.size())) { progress(++n,existing.size(),"Checking existing songs"); continue; }
        const auto hash=hashFile(path,stop);
        if(QFileInfo(path).size()==before.size() && QFileInfo(path).lastModified()==before.lastModified()) {
          verifiedHashes[t["id"].toString()]=hash;
          if(!t.value("deleted").toBool() && !matches.contains(hash))matches[hash]=t;
        }
      }
      progress(++n,existing.size(),"Checking existing songs");
    }
    prepared["matches"]=matches;
    prepared["verifiedHashes"]=verifiedHashes;
    prepared["readable"]=readable;
    const QString stage=prepared["stage"].toString();
    const QString destination=QFileInfo(stage).absolutePath();
    QVariantMap publications;
    QVariantList created;
    for (const auto &v : manifest["assets"].toList()) {
      check(stop);
      const auto asset=v.toMap()["path"].toString();
      const auto directory=destination+(asset.startsWith("art/")?"/.covers":"");
      require(QDir().mkpath(directory),"Cannot create restore destination");
      const auto target=directory+"/"+uuid()+"."+QFileInfo(asset).suffix();
      publications[asset]=target;
      created << target;
    }
    // record every destination before moving anything, for interruption recovery ofc
    QSaveFile record(stage+"/publication.json");
    require(record.open(QIODevice::WriteOnly),"Cannot record restore publication");
    const auto bytes=QJsonDocument::fromVariant(created).toJson(QJsonDocument::Compact);
    require(record.write(bytes)==bytes.size() && record.commit(),"Cannot record restore publication");
    prepared["published"]=publications;
    prepared["created"]=created;
    n=0;
    for (auto it=publications.cbegin();it!=publications.cend();++it) {
      check(stop);
      require(QFile::rename(stage+"/"+it.key(),it.value().toString()),"Cannot publish restored file: "+it.value().toString());
      progress(++n,publications.size(),"Publishing restored files");
    }
    return prepared;
  } catch(const std::exception &e) {
    for (const auto &v : prepared["created"].toList()) QFile::remove(v.toString());
    return failure(e);
  }
}
}

Backup::Backup(Store *store,Library *library,QObject *parent):QObject(parent),s(store),lib(library) {
  connect(&worker,&QFutureWatcher<QVariantMap>::finished,this,&Backup::completed);
}
Backup::~Backup() { shutdown(); }
void Backup::chooseBackup() {
  if(busy()) return;
  const auto file=QFileDialog::getSaveFileName(nullptr,"Back up library",QDir::homePath()+"/SillyPlayer-library.zip","ZIP archives (*.zip)");
  if(!file.isEmpty()) prepareBackup(file.endsWith(".zip",Qt::CaseInsensitive)?file:file+".zip");
}
void Backup::chooseRestore(const QString &musicDirectory) {
  if(busy()) return;
  const auto file=QFileDialog::getOpenFileName(nullptr,"Restore library",{},"ZIP archives (*.zip)");
  if(!file.isEmpty()) prepareRestore(file,musicDirectory);
}
void Backup::notifyProgress(qint64 done,qint64 total,const QString &label) {
  QMetaObject::invokeMethod(this,[this,done,total,label] { if(closing || !worker.isRunning()) return; fraction=total>0?qBound(0.0,double(done)/total,1.0):-1; text=label; emit changed(); },Qt::QueuedConnection);
}
void Backup::launch(const QString &state,Task task) {
  phase=state; fraction=-1; text=state; emit changed();
  worker.setFuture(QtConcurrent::run(std::move(task)));
}
void Backup::prepareBackup(const QString &path) {
  if(busy()) return;
  if(!lib->beginMaintenance()) { finish("Failed","Wait for library indexing, tag edits or Trash to finish"); return; }
  held=true; stopped=std::make_shared<std::atomic_bool>(false); destination=path;
  QVariantMap snapshot{{"format","SillyPlayer Library"},{"version",1},{"libraryId",s->setting("libraryIdentity")},{"created",QDateTime::currentDateTimeUtc().toString(Qt::ISODate)},
    {"tracks",s->rows("SELECT * FROM tracks WHERE deleted=0")},
    {"collections",s->rows("SELECT * FROM collections WHERE kind IN ('playlist','custom')")},
    {"members",s->rows("SELECT m.* FROM members m JOIN collections c ON c.id=m.collection WHERE c.kind IN ('playlist','custom') ORDER BY m.collection,m.position,m.rowid")},
    {"likes",s->rows("SELECT * FROM likes")}};
  launch("Preparing backup",[snapshot] { return inspect(snapshot); });
}
void Backup::prepareRestore(const QString &path,const QString &musicDirectory) {
  if(busy()) return;
  if(!lib->beginMaintenance()) { finish("Failed","Wait for library indexing, tag edits or Trash to finish"); return; }
  held=true; stopped=std::make_shared<std::atomic_bool>(false); destination=QDir(musicDirectory).absoluteFilePath("Restored");
  auto stop=stopped; const auto dest=destination;
  launch("Reading backup",[this,path,dest,stop] {
    QElapsedTimer throttle; throttle.start();
    return readZip(path,dest,stop,[this,&throttle](qint64 n,qint64 total,const QString &label) { if(throttle.elapsed()>100) { throttle.restart(); notifyProgress(n,total,label); } });
  });
}
void Backup::confirm() {
  if(phase=="Backup ready") {
    const auto manifest=pending["manifest"].toMap(); const auto path=destination; auto stop=stopped;
    launch("Writing backup",[this,manifest,path,stop] {
      QElapsedTimer throttle; throttle.start();
      return writeZip(manifest,path,stop,[this,&throttle](qint64 n,qint64 total,const QString &label) { if(throttle.elapsed()>100) { throttle.restart(); notifyProgress(n,total,label); } });
    });
  } else if(phase=="Restore ready") {
    auto data=pending; auto existing=s->rows("SELECT * FROM tracks WHERE deleted=0 OR mergedInto='sync-deleted'"); auto stop=stopped;
    launch("Restoring",[this,data,existing,stop] {
      QElapsedTimer throttle; throttle.start();
      return matchExisting(data,existing,stop,[this,&throttle](qint64 n,qint64 total,const QString &label) { if(throttle.elapsed()>100) { throttle.restart(); notifyProgress(n,total,label); } });
    });
  }
}
void Backup::cleanup() { const auto stage=pending["stage"].toString(); if(!stage.isEmpty()) QDir(stage).removeRecursively(); pending.clear(); }
void Backup::finish(const QString &state,const QString &message) {
  cleanup(); phase=state; text=message; fraction=state=="Completed"?1:0;
  if(held) { held=false; lib->endMaintenance(); }
  emit changed();
}
void Backup::completed() {
  if(closing || !worker.future().isValid()) return;
  auto result=worker.result();
  worker.setFuture({});
  if(result.contains("error") || (stopped && *stopped && !pending["committed"].toBool())) {
    for(const auto &v:result["created"].toList())QFile::remove(v.toString());
    const auto stage=result["stage"].toString(); if(!stage.isEmpty()) QDir(stage).removeRecursively();
    const auto error=result.value("error","Cancelled").toString(); finish(error=="Cancelled"?"Cancelled":"Failed",error); return;
  }
  if(phase=="Preparing backup" || phase=="Reading backup") {
    pending=result; details=result; details.remove("manifest"); details.remove("stage");
    phase=phase=="Preparing backup"?"Backup ready":"Restore ready";
    text=QString("%1 songs · %2 unavailable files omitted").arg(result["count"].toInt()).arg(result["omissions"].toList().size()); fraction=0; emit changed();
  } else if(phase=="Writing backup") finish("Completed",QString("Backed up %1 songs · %2 omitted").arg(result["count"].toInt()).arg(result["omitted"].toInt()));
  else if(phase=="Restoring") {
    QString summary;
    const bool ok=merge(result,&summary);
    QSet<QString> referenced;
    if(ok) {
      for(const auto &v:s->rows("SELECT path,art FROM tracks UNION ALL SELECT '',art FROM collections")) {
        const auto row=v.toMap();referenced.insert(row["path"].toString());referenced.insert(localPath(row["art"].toString()));
      }
    }
    pending["committed"]=ok;
    pending.remove("manifest");
    const auto created=result["created"].toList();
    launch("Finalizing restore",[created,referenced,ok,summary] {
      for(const auto &v:created)if(!referenced.contains(v.toString()))QFile::remove(v.toString());
      return QVariantMap{{"ok",ok},{"summary",summary}};
    });
  } else if(phase=="Finalizing restore") {
    finish(result["ok"].toBool()?"Completed":"Failed",result["summary"].toString());
  }
}
void Backup::cancel() {
  if(stopped) *stopped=true;
  if(!worker.isRunning() && held) finish("Cancelled","Cancelled");
}
void Backup::shutdown() {
  if(closing) return;
  closing=true; if(stopped) *stopped=true;
  worker.waitForFinished();
  if(worker.future().isValid()) { const auto result=worker.result();if(!pending["committed"].toBool())for(const auto &v:result["created"].toList())QFile::remove(v.toString());const auto stage=result["stage"].toString(); if(!stage.isEmpty()) QDir(stage).removeRecursively(); }
  cleanup(); if(held) { held=false; lib->endMaintenance(); }
}

bool Backup::merge(const QVariantMap &prepared,QString *summary) {
  const auto manifest=prepared["manifest"].toMap(); const QString origin=manifest["libraryId"].toString();
  const auto localOrigin=s->setting("libraryIdentity").toString();
  bool transaction=false;
  try {
    check(stopped); require(s->db().transaction(),"Cannot begin library restore"); transaction=true;
    // reuse prepared statements: a restore previously prepared and looked up the same SQL and entire membership lists for every occurrence
    std::map<QString,std::unique_ptr<QSqlQuery>> statements;
    auto sql=[&](const QString &query,const QVariantList &args=QVariantList{}) {
      auto &statement=statements[query];
      if (!statement) {statement=std::make_unique<QSqlQuery>(s->db());require(statement->prepare(query),statement->lastError().text());}
      for(int i=0;i<args.size();++i)statement->bindValue(i,args[i]);
      require(statement->exec(),statement->lastError().text());statement->finish();
    };
    auto remember=[&](const QString &kind,const QString &remote,const QString &local) { sql("INSERT INTO restore_ids VALUES(?,?,?,?) ON CONFLICT(origin,kind,remote) DO UPDATE SET local=excluded.local",{origin,kind,remote,local}); };
    const auto published=prepared["published"].toMap();
    auto publish=[&](const QString &asset) -> QString {
      if(asset.isEmpty()) return {};
      const auto target=published.value(asset).toString();
      require(!target.isEmpty(),"Missing published restore asset");return target;
    };
    auto cover=[&](const QString &asset) { const auto path=publish(asset); return path.isEmpty()?QString():QUrl::fromLocalFile(path).toString(); };
    QHash<QString,QString> trackMap,collectionMap;
    QVariantMap matches=prepared["matches"].toMap();
    int added=0,reused=0,repaired=0,conflicts=0,membersAdded=0;
    const auto trackColumns=s->rows("PRAGMA table_info(tracks)");
    QHash<QString,QString> restored;
    for (const auto &v : s->rows("SELECT kind,remote,local FROM restore_ids WHERE origin=?",{origin})) {
      const auto row=v.toMap(); restored[row["kind"].toString()+":"+row["remote"].toString()]=row["local"].toString();
    }
    QHash<QString,QVariantMap> indexed,collectionIndex,memberIndex;
    QHash<QString,int> positions;
    for (const auto &v : s->rows("SELECT * FROM tracks")) {const auto row=v.toMap();indexed[row["id"].toString()]=row;}
    for (const auto &v : s->rows("SELECT * FROM collections")) {const auto row=v.toMap();collectionIndex[row["id"].toString()]=row;}
    for (const auto &v : s->rows("SELECT * FROM members")) {
      const auto row=v.toMap();memberIndex[row["id"].toString()]=row;
      const auto collection=row["collection"].toString();positions[collection]=qMax(positions.value(collection,0),row["position"].toInt()+1);
    }
    QSet<QString> readable;for (const auto &v:prepared["readable"].toList())readable.insert(v.toString());
    const auto verifiedHashes=prepared["verifiedHashes"].toMap();
    for(auto v:manifest["tracks"].toList()) {
      auto t=v.toMap(); const QString remote=t["id"].toString(),asset=t["media"].toString(),hash=QFileInfo(asset).baseName();
      check(stopped);
      QString id=restored.value("track:"+remote);
      auto current=indexed.value(id);
      if(current.isEmpty() && origin==localOrigin)
        current=indexed.value(remote);
      QSet<QString> seenAliases;
      while(!current.isEmpty() && !current.value("mergedInto").toString().isEmpty() && current.value("mergedInto")!="sync-deleted") {
        const auto next=current.value("mergedInto").toString();
        if(seenAliases.contains(next)){current.clear();break;}seenAliases.insert(next);current=indexed.value(next);
      }
      if(!current.isEmpty() && (!readable.contains(current["id"].toString()) ||
          (current.value("deleted").toBool() && verifiedHashes.value(current.value("id").toString()).toString()!=hash) ||
          !QFileInfo(current["path"].toString()).isReadable() || !QFileInfo(current["path"].toString()).isFile())) {
        // reattach a missing mapped file without changing its stable local ID, metadata, modifiers or existing playlist/queue references
        const QString path=publish(asset); const QFileInfo fi(path); const auto identity = fileIdentity(path);
        require(!identity.isEmpty(),"Cannot identify restored audio");
        sql("UPDATE tracks SET path=?,available=1,deleted=0,mergedInto='',managed=1,mtime=?,size=?,identity=? WHERE id=?",{path,fi.lastModified().toMSecsSinceEpoch(),fi.size(),identity,current["id"]});
        current["path"]=path;current["available"]=1;current["deleted"]=0;current["mergedInto"]=QString("");indexed[current["id"].toString()]=current;readable.insert(current["id"].toString());++repaired;
      }
      if(!current.isEmpty() && current.value("deleted").toBool()) {
        sql("UPDATE tracks SET available=1,deleted=0,mergedInto='' WHERE id=?",{current.value("id")});
        current["available"]=1;current["deleted"]=0;current["mergedInto"]=QString("");indexed[current.value("id").toString()]=current;++repaired;
      }
      if(current.isEmpty() && matches.contains(hash)) {
        const auto candidate=matches[hash].toMap();const QFileInfo file(candidate["path"].toString());
        if(file.isFile() && file.isReadable())current=candidate;
      }
      if(!current.isEmpty()) { id=current["id"].toString(); if(!current["available"].toBool()) {sql("UPDATE tracks SET available=1 WHERE id=?",{id});++repaired;} ++reused; if(current["title"]!=t["title"] || current["artist"]!=t["artist"] || current["speed"]!=t["speed"] || current["nightcore"]!=t["nightcore"] || current.value("volumePercent",100.0).toDouble()!=t.value("volumePercent",100.0).toDouble()) ++conflicts; }
      else {
        id=remote; if(indexed.contains(id)) id=uuid();
        t["path"]=publish(asset); t["art"]=cover(t["art"].toString());
        const QFileInfo fi(t["path"].toString());
        const auto identity = fileIdentity(fi.absoluteFilePath());
        require(!identity.isEmpty(),"Cannot identify restored audio");
        t["id"]=id; t["available"]=1; t["deleted"]=0; t["managed"]=1; t["mergedInto"]=QString(""); t["mtime"]=fi.lastModified().toMSecsSinceEpoch(); t["size"]=fi.size(); t["identity"]=identity;
        QStringList keys,marks; QVariantList values;
        for(auto column:trackColumns) { const QString key=column.toMap()["name"].toString(); if(t.contains(key)) { keys<<key; marks<<"?"; values<<t[key]; } }
        sql("INSERT INTO tracks("+keys.join(',')+") VALUES("+marks.join(',')+")",values);
        matches[hash]=t;indexed[id]=t;readable.insert(id); ++added;
      }
      trackMap[remote]=id; remember("track",remote,id);
    }
    for(auto v:manifest["collections"].toList()) {
      const auto c=v.toMap(); const QString remote=c["id"].toString(); QString id=restored.value("collection:"+remote);
      auto current=collectionIndex.value(id);
      if(current.isEmpty() && origin==localOrigin) { current=collectionIndex.value(remote); if(!current.isEmpty()) id=remote; }
      if(current.isEmpty()) {
        id=remote; if(collectionIndex.contains(id) || QStringList{"liked","recent"}.contains(id)) id=uuid();
        sql("INSERT INTO collections VALUES(?,?,?,?)",{id,c["name"],c["kind"],cover(c["art"].toString())});collectionIndex[id]=c;
      }
      collectionMap[remote]=id; remember("collection",remote,id);
    }
    for(auto v:manifest["members"].toList()) {
      const auto m=v.toMap(); const QString remote=m["id"].toString(),collection=collectionMap[m["collection"].toString()],track=trackMap[m["track"].toString()];
      check(stopped);
      QString id=restored.value("member:"+remote);
      if(memberIndex.contains(id)) continue;
      if(origin==localOrigin && (memberIndex.contains(remote) && memberIndex[remote]["collection"].toString()==collection && memberIndex[remote]["track"].toString()==track)) { remember("member",remote,remote); continue; }
      id=remote; if(memberIndex.contains(id)) id=uuid();
      const int position=positions.value(collection,0);positions[collection]=position+1;
      sql("INSERT INTO members VALUES(?,?,?,?)",{id,collection,track,position}); remember("member",remote,id);memberIndex[id]=m; ++membersAdded;
    }
    for(auto v:manifest["likes"].toList()) sql("INSERT OR IGNORE INTO likes VALUES(?)",{trackMap[v.toMap()["track"].toString()]});
    check(stopped); require(s->db().commit(),"Cannot commit restored library"); transaction=false;
    *summary=QString("%1 songs added · %2 reused · %3 repaired · %4 playlist entries added · %5 conflicts kept current · %6 omitted").arg(added).arg(reused).arg(repaired).arg(membersAdded).arg(conflicts).arg(manifest["omissions"].toList().size());
    return true;
  } catch(const std::exception &e) {
    if(transaction) s->db().rollback();
    *summary=QString::fromUtf8(e.what()); return false;
  }
}
