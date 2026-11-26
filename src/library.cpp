#include "processutils.h"
#include "library.h"
#include "fileavailability.h"
#include "platformfiles.h"
#include "online.h"
#include "source.h"
#include "sourcetags.h"
#include "tagcleanup.h"
#include <QBuffer>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDirIterator>
#include <QImage>
#include <QJsonDocument>
#include <QScopeGuard>
#include <QStorageInfo>
#include <QStandardPaths>
#include <QRegularExpression>
#include <QUrl>
#include <QUuid>
#include <QtConcurrent>
#include <cerrno>
#include <cstring>
#include <utility>
#include <sys/stat.h>
#include <taglib/attachedpictureframe.h>
#include <taglib/fileref.h>
#include <taglib/flacfile.h>
#include <taglib/flacpicture.h>
#include <taglib/id3v2tag.h>
#include <taglib/infotag.h>
#include <taglib/mp4coverart.h>
#include <taglib/mp4file.h>
#include <taglib/mp4tag.h>
#include <taglib/mpegfile.h>
#include <taglib/opusfile.h>
#include <taglib/tag.h>
#include <taglib/tpropertymap.h>
#include <taglib/vorbisfile.h>
#include <taglib/wavfile.h>
#include <taglib/xiphcomment.h>

static QString str(const TagLib::String &s) {
  return QString::fromStdString(s.to8Bit(true));
}
static QString filePath(const QString &p) {
  return QUrl(p).isLocalFile() ? QUrl(p).toLocalFile() : p;
}
static QString identity(const QString &path) { return fileIdentity(path); }
static QVariantMap readTrack(const QString &path, const QString &cache) {
  QFileInfo fi(path);
  auto f = taggedFile(path);
  if ((f.isNull() || !f.tag()) &&
      QStringList{"aac", "webm", "bin"}.contains(fi.suffix().toLower()) && fi.isReadable()) {
    QProcess probe;
#ifdef Q_OS_WIN
    configureToolProcess(&probe);
#endif
    probe.start(Online::tool("ffprobe"),
                {"-v", "error", "-select_streams", "a:0", "-show_streams", "-show_format", "-of", "json", path});
    if (!probe.waitForFinished(10000)) {
      probe.kill();
      probe.waitForFinished(1000);
      return {};
    }
    const auto media = QJsonDocument::fromJson(probe.readAllStandardOutput()).toVariant().toMap();
    const auto format = media["format"].toMap();
    if (probe.exitCode() != 0 || format.isEmpty() || media["streams"].toList().isEmpty()) return {};
    QVariantMap tags;
    const auto originalTags = format["tags"].toMap();
    for (auto it=originalTags.begin(); it!=originalTags.end(); ++it) tags[it.key().toLower()]=it.value();
    QStringList sourceHints;
    for (const auto &k : {"comment", "purl", "url", "description", "woas"}) {
      const QString val = tags.value(k).toString().trimmed();
      if (!val.isEmpty()) sourceHints << val;
    }
    return {{"path", fi.absoluteFilePath()},
            {"title", tags.value("title", fi.completeBaseName())},
            {"fallbackTitle", tags.value("title").toString().trimmed().isEmpty()},
            {"fallbackArtist", tags.value("artist").toString().trimmed().isEmpty()},
            {"artist", tags.value("artist", "Unknown artist")},
            {"album", tags.value("album", "")},
            {"albumArtist", tags.value("album_artist", "")},
            {"genre", tags.value("genre", "")},
            {"year", 0},
            {"number", 0},
            {"disc", 0},
            {"duration", format["duration"].toDouble()},
            {"mtime", fi.lastModified().toMSecsSinceEpoch()},
            {"size", fi.size()},
            {"identity", identity(path)},
            {"extractedSource", extractSourceMetadata(sourceHints, fi.fileName())},
            {"art", ""}};
  }
  if (f.isNull() || !f.tag())
    return {};
  auto t = f.tag();
  QVariantMap m{
      {"path", fi.absoluteFilePath()},
      {"title", str(t->title()).trimmed()},
      {"artist", str(t->artist()).trimmed()},
      {"album", str(t->album()).trimmed()},
      {"genre", str(t->genre())},
      {"year", t->year()},
      {"number", t->track()},
      {"disc", 0},
      {"albumArtist", ""},
      {"duration", f.audioProperties()
                       ? f.audioProperties()->lengthInMilliseconds() / 1000.0
                       : 0},
      {"mtime", fi.lastModified().toMSecsSinceEpoch()},
      {"size", fi.size()},
      {"identity", identity(path)},
      {"art", ""}};
  if (auto wave = dynamic_cast<TagLib::RIFF::WAV::File *>(f.file());
      wave && wave->hasInfoTag()) {
    auto info = wave->InfoTag();
    if (m["title"].toString().isEmpty())
      m["title"] = str(info->title()).trimmed();
    if (m["artist"].toString().isEmpty())
      m["artist"] = str(info->artist()).trimmed();
    if (m["album"].toString().isEmpty())
      m["album"] = str(info->album()).trimmed();
    if (m["genre"].toString().isEmpty())
      m["genre"] = str(info->genre()).trimmed();
    if (!m["year"].toInt())
      m["year"] = info->year();
    if (!m["number"].toInt())
      m["number"] = info->track();
  }
  if (m["title"].toString().isEmpty()) {
    m["fallbackTitle"]=true;
    m["title"] = fi.completeBaseName();
  }
  if (m["artist"].toString().isEmpty()) {
    m["fallbackArtist"]=true;
    m["artist"] = "Unknown artist";
  }
  auto props = f.file()->properties();
  if (props.contains("ALBUMARTIST") && !props["ALBUMARTIST"].isEmpty())
    m["albumArtist"] = str(props["ALBUMARTIST"].front());
  if (props.contains("DISCNUMBER") && !props["DISCNUMBER"].isEmpty())
    m["disc"] = str(props["DISCNUMBER"].front()).section('/', 0, 0).toInt();
  // grab source links embedded by yt-dlp/sillyplayer so local rescans keep youtube ids and covers
  QStringList sourceHints;
  const QString commentVal = str(t->comment()).trimmed();
  if (!commentVal.isEmpty()) sourceHints << commentVal;
  for (const char *key : {"COMMENT", "PURL", "URL", "WOAS", "DESCRIPTION", "WEBSITE"}) {
    if (props.contains(key)) {
      for (const auto &v : props[key]) {
        const QString s = str(v).trimmed();
        if (!s.isEmpty()) sourceHints << s;
      }
    }
  }
  const auto extracted = extractSourceMetadata(sourceHints, fi.fileName());
  if (!extracted.isEmpty()) m["extractedSource"] = extracted;
  QByteArray cover;
  TagLib::Ogg::XiphComment *xiph = nullptr;
  if (auto vorbis = dynamic_cast<TagLib::Ogg::Vorbis::File *>(f.file()))
    xiph = vorbis->tag();
  if (auto opus = dynamic_cast<TagLib::Ogg::Opus::File *>(f.file()))
    xiph = opus->tag();
  if (xiph && !xiph->pictureList().isEmpty()) {
    auto picture = xiph->pictureList().front()->data();
    cover = QByteArray(picture.data(), picture.size());
  }
  if (auto mp3 = dynamic_cast<TagLib::MPEG::File *>(f.file())) {
    if (auto tag = mp3->ID3v2Tag()) {
      auto frames = tag->frameListMap()["APIC"];
      if (!frames.isEmpty()) {
        auto pic =
            dynamic_cast<TagLib::ID3v2::AttachedPictureFrame *>(frames.front());
        if (pic)
          cover = QByteArray(pic->picture().data(), pic->picture().size());
      }
    }
  }
  if (auto flac = dynamic_cast<TagLib::FLAC::File *>(f.file())) {
    auto pictures = flac->pictureList();
    if (!pictures.isEmpty())
      cover = QByteArray(pictures.front()->data().data(),
                         pictures.front()->data().size());
  }
  if (auto mp4 = dynamic_cast<TagLib::MP4::File *>(f.file())) {
    auto tag = mp4->tag();
    if (tag->contains("covr")) {
      auto covers = tag->item("covr").toCoverArtList();
      if (!covers.isEmpty())
        cover = QByteArray(covers.front().data().data(),
                           covers.front().data().size());
    }
  }
  if (!cover.isEmpty()) {
    auto img = QImage::fromData(cover);
    if (!img.isNull()) {
      QString out =
          cache + "/" +
          QCryptographicHash::hash(cover, QCryptographicHash::Sha256).toHex() +
          ".jpg";
      if (QFile::exists(out) ||
          img.scaled(640, 640, Qt::KeepAspectRatio, Qt::SmoothTransformation)
              .save(out, "JPG", 85))
        m["art"] = QUrl::fromLocalFile(out).toString();
    }
  }
  return m;
}
Library::Library(Store *store, const QString &dir, QObject *parent)
    : QObject(parent), s(store), cache(dir) {
  connect(this,&Library::changed,this,[this]{
    tracksValid=foldersValid=false;cachedLikedCount=-1;trackPositions.clear();emit likesChanged();
  });
  QDir().mkpath(cache);
  // older managed-file reconciliation could clear deleted while leaving the durable sync tombstone
  // repair that inconsistent state before exposing rows
  s->exec("UPDATE tracks SET deleted=1,available=0 WHERE mergedInto='sync-deleted' AND (deleted<>1 OR available<>0)");
  connect(&trashWorker, &QFutureWatcher<QVariantMap>::finished, this, &Library::finishTrash);
  debounce.setSingleShot(true);
  debounce.setInterval(800);
  connect(&watches, &QFileSystemWatcher::directoryChanged, &debounce,
          qOverload<>(&QTimer::start));
  connect(&watches, &QFileSystemWatcher::fileChanged, &debounce,
          qOverload<>(&QTimer::start));
  connect(&debounce, &QTimer::timeout, this, [this] { startScan(false); });
  periodic.setInterval(300000);
  connect(&periodic, &QTimer::timeout, this, [this] { startScan(false); });
  periodic.start();
  connect(&scan, &QFutureWatcher<QVariantMap>::finished, this, [this] {
    if(!scan.future().isValid()) return;
    auto result = scan.result();
    scan.setFuture({}); // release the completed snapshot and retain a finished empty future for shutdown
    if(!s->db().transaction()) {
      m_busy=false; m_status="Scan not saved: cannot begin database transaction";
      emit error(m_status); emit statusChanged(); return;
    }
    bool ok=true;
    auto sql=[&](const QString &query,const QVariantList &args=QVariantList{}) {
      if(ok) ok=s->exec(query,args);
      return ok;
    };
    for (auto rv : result["roots"].toList()) {
      auto r = rv.toMap();
      QString id = r["id"].toString();
      bool rootOk = r["ok"].toBool();
      sql("UPDATE roots SET available=? WHERE id=?", {rootOk, id});
      if(rootOk && !r["device"].toString().isEmpty()) sql("UPDATE roots SET device=? WHERE id=?",{r["device"],id});
      if (!rootOk) {
        sql("UPDATE tracks SET available=0 WHERE managed=0 AND id IN "
                "(SELECT track FROM track_roots WHERE root=?)",
                {id});
        continue;
      }
      // keep provenance for absent files so diagnostics can verify their storage
      sql("UPDATE tracks SET available=0 WHERE id IN (SELECT track FROM track_roots WHERE root=?)", {id});
      for (auto tv : r["tracks"].toList())
        ok=applyTrack(tv.toMap(), id) && ok;
      for (auto bad : r["failed"].toList()) {
        sql("INSERT OR IGNORE INTO track_roots SELECT id,? FROM tracks "
                "WHERE path=?",
                {id, bad});
        sql("UPDATE tracks SET available=0 WHERE path=?", {bad});
      }
    }
    for (auto v : result["managed"].toList()) {
      auto m = v.toMap();
      if (m["data"].toMap().isEmpty()) {
        if(track(m["id"].toString())["path"]!=m["path"]) continue;
        sql("UPDATE tracks SET available=0 WHERE id=? AND deleted=0 AND mergedInto=''",
                {m["id"]});
      } else
        ok=applyTrack(m["data"].toMap(), {}, true) && ok;
    }
    sql("UPDATE tracks SET available=0 WHERE managed=0 AND id "
            "NOT IN (SELECT track FROM track_roots) AND path NOT LIKE 'sync://%'");
    if(!ok || !s->db().commit()) {
      const auto reason=s->error(); s->db().rollback();
      m_busy=false; pending=false;
      m_status="Scan not saved; previous library retained: "+reason;
      emit changed(); emit error(m_status); emit statusChanged(); return;
    }
    QStringList dirs;
    for (auto d : result["dirs"].toList())
      dirs << d.toString();
    if (!watches.directories().isEmpty())
      watches.removePaths(watches.directories());
    if (!watches.files().isEmpty())
      watches.removePaths(watches.files());
    if (!dirs.isEmpty())
      watches.addPaths(dirs);
    QStringList files;
    for (auto v : result["roots"].toList())
      for (auto t : v.toMap()["tracks"].toList())
        files << t.toMap()["path"].toString();
    for (auto v : result["managed"].toList())
      if (!v.toMap()["data"].toMap().isEmpty())
        files << v.toMap()["data"].toMap()["path"].toString();
    files.removeDuplicates();
    if (!files.isEmpty())
      watches.addPaths(files);
    m_busy = false;
    m_status = QString("Library scan · %1 refreshed, %2 unchanged · %3 unique files · %4 new, %5 updated · %6 missing, %7 storage unavailable, %8 unresolved · %9 errors")
      .arg(result["read"].toInt()).arg(result["reused"].toInt()).arg(result["unique"].toInt())
      .arg(result["imported"].toInt()).arg(result["updated"].toInt()).arg(result["missing"].toInt())
      .arg(result["unavailable"].toInt()).arg(result["unresolved"].toInt()).arg(result["processingErrors"].toInt());
    if(!result["errors"].toStringList().isEmpty()) m_status+=" · "+result["errors"].toStringList().join("; ");
    emit changed();
    emit statusChanged();
    if (pending) {
      pending = false;
      const bool force = pendingForce;
      pendingForce = false;
      startScan(force);
    }
  });
  QTimer::singleShot(0, this, [this] { startScan(false); });
}
Library::~Library() {
  finishTrash();
  scan.waitForFinished();
  for (auto w : edits)
    w->waitForFinished();
}
QVariantList Library::tracks() const {
  if(tracksValid && tracksSerial==s->writeSerial())return cachedTracks;
  cachedTracks=s->rows(
      "SELECT t.*, EXISTS(SELECT 1 FROM likes WHERE track=t.id) AS liked FROM "
      "tracks t WHERE deleted=0 AND mergedInto='' ORDER BY added DESC,title COLLATE NOCASE");
  trackPositions.clear();trackPositions.reserve(cachedTracks.size());
  for(int i=0;i<cachedTracks.size();++i)trackPositions.insert(cachedTracks[i].toMap().value("id").toString(),i);
  tracksValid=true;tracksSerial=s->writeSerial();tracksCatalogueSerial=s->catalogueSerial();return cachedTracks;
}
QVariantList Library::folders() const {
  if(!foldersValid || foldersSerial!=s->writeSerial()){cachedFolders=s->rows("SELECT * FROM roots ORDER BY path");foldersValid=true;foldersSerial=s->writeSerial();}
  return cachedFolders;
}
int Library::likedCount() const {
  if(cachedLikedCount<0)cachedLikedCount=s->rows("SELECT COUNT(*) AS n FROM likes l JOIN tracks t ON t.id=l.track WHERE t.deleted=0").value(0).toMap().value("n").toInt();
  return cachedLikedCount;
}
void Library::patchTracks(const QVariantMap &patches) {
  // a field patch cannot reconcile intervening metadata or membership writes
  if (tracksCatalogueSerial != s->catalogueSerial()) tracksValid = false;
  bool likes=false;
  for(auto it=patches.cbegin();it!=patches.cend();++it) {
    const auto fields=it.value().toMap();likes=likes||fields.contains("liked");
    const auto at=trackPositions.constFind(it.key());
    if(tracksValid && at!=trackPositions.cend()) {
      auto row=cachedTracks[*at].toMap();for(auto f=fields.cbegin();f!=fields.cend();++f)row[f.key()]=f.value();
      cachedTracks[*at]=row;
    }
  }
  tracksSerial=s->writeSerial();
  if(likes){cachedLikedCount=-1;emit likesChanged();}
  emit tracksUpdated(patches);
}
QVariantMap Library::track(const QString &id) const {
  auto r = s->rows("SELECT t.*,EXISTS(SELECT 1 FROM likes WHERE track=t.id) AS "
                   "liked FROM tracks t WHERE id=?",
                   {id});
  return r.isEmpty() ? QVariantMap{} : r[0].toMap();
}
void Library::addFolder(const QString &url) {
  QString p = QFileInfo(filePath(url)).canonicalFilePath();
  if (p.isEmpty() || !QFileInfo(p).isDir()) {
    emit error("Choose an existing readable folder");
    return;
  }
  s->exec("INSERT OR IGNORE INTO roots(id,path,device) VALUES(?,?,?)",
          {QUuid::createUuid().toString(QUuid::WithoutBraces), p,
           storageIdentity(p)});
  emit changed();
  rescan();
}
void Library::removeFolder(const QString &id) {
  s->exec("DELETE FROM roots WHERE id=?", {id});
  s->exec("UPDATE tracks SET deleted=1,available=0 WHERE managed=0 AND id NOT "
          "IN(SELECT track FROM track_roots)");
  emit changed();
  rescan();
}
void Library::rescan() { startScan(true); }
void Library::startScan(bool force) {
  if (m_busy || m_trashing || maintenance) {
    pending = true;
    pendingForce |= force;
    return;
  }
  m_busy = true;
  m_status = "Indexing music folders…";
  emit statusChanged();
  auto roots = folders();
  auto cacheDir = cache;
  auto managed = s->rows("SELECT id,path,identity FROM tracks WHERE managed=1 AND deleted=0 AND mergedInto='' AND path NOT LIKE 'sync://%'");
  QHash<QString,QVariantList> provenance;
  for(const auto &v:s->rows("SELECT track_roots.track,roots.* FROM track_roots JOIN roots ON roots.id=track_roots.root")) {
    const auto row=v.toMap(); provenance[row["track"].toString()]<<row;
  }
  QHash<QString, QVariantMap> indexed;
  for (const auto &v : s->rows("SELECT * FROM tracks")) {
    auto t = v.toMap();
    t["roots"]=provenance.value(t["id"].toString());
    indexed.insert(t.value("path").toString(), t);
  }
  const auto restoreDirectory=QDir(s->setting("musicDir",QStandardPaths::writableLocation(QStandardPaths::MusicLocation)+"/SillyPlayer").toString()).absoluteFilePath("Restored");
  QSet<QString> restoreReferences;
  for(const auto &v:s->rows("SELECT path,art FROM tracks UNION ALL SELECT '',art FROM collections")) {
    const auto row=v.toMap();restoreReferences.insert(row["path"].toString());restoreReferences.insert(filePath(row["art"].toString()));
  }
  scan.setFuture(QtConcurrent::run([roots, cacheDir, managed, indexed, force,restoreDirectory,restoreReferences] {
    // a killed restore can leave published files before its SQL transaction
    // only remove recorded destinations inside this restore directory, and preserve every path already referenced by a committed library row
    const QDir restore(restoreDirectory);
    for(const auto &name:restore.entryList({".sillyplayer-restore-*"},QDir::Dirs|QDir::Hidden|QDir::NoDotAndDotDot)) {
      const auto staging=restore.absoluteFilePath(name);
      if(QFileInfo(staging).isSymLink())continue;
      QFile record(staging+"/publication.json");
      bool safe=true;
      if(record.exists()) {
        if(!record.open(QIODevice::ReadOnly) || record.size()>16*1024*1024)continue;
        const auto doc=QJsonDocument::fromJson(record.readAll());if(!doc.isArray())continue;
        const auto paths=doc.toVariant().toList();
        for(const auto &v:paths) {
          const QFileInfo file(v.toString());
          if(file.isSymLink() || (file.absolutePath()!=restore.absolutePath() && file.absolutePath()!=restore.absoluteFilePath(".covers")) ||
              !QRegularExpression("^[a-f0-9-]{36}\\.[a-z0-9]{1,10}$").match(file.fileName()).hasMatch()) {safe=false;break;}
        }
        if(!safe)continue;
        for(const auto &v:paths)if(!restoreReferences.contains(v.toString()))QFile::remove(v.toString());
      }
      QDir(staging).removeRecursively();
    }
    int read = 0, reused = 0, imported=0, processingErrors=0;
    QSet<QString> foundPaths, observedIdentities;
    QHash<QString,QString> knownIdentities;
    for(const auto &track:indexed) if(!track["identity"].toString().isEmpty())
      knownIdentities.insert(track["identity"].toString(),track["path"].toString());
    QHash<QString,QVariantMap> processed;
    auto readOrReuse = [&](const QString &path) {
      const QFileInfo fi(path);
      const auto key = fi.canonicalFilePath().isEmpty() ? fi.absoluteFilePath() : fi.canonicalFilePath();
      if (processed.contains(key)) return processed.value(key);
      if(fi.isFile()) foundPaths.insert(key);
      const auto old = indexed.value(path);
      const auto art = old.value("art").toString();
      if (!force && fi.isFile() && fi.isReadable() && !old.isEmpty() &&
          fi.size() == old.value("size").toLongLong() &&
          fi.lastModified().toMSecsSinceEpoch() == old.value("mtime").toLongLong() &&
          identity(path) == old.value("identity").toString() &&
          (art.isEmpty() || QFileInfo::exists(filePath(art)))) {
        ++reused;
        observedIdentities.insert(old["identity"].toString());
        processed.insert(key,old);
        return old;
      }
      auto data=readTrack(path, cacheDir);
      if(!data.isEmpty()) { ++read; observedIdentities.insert(data["identity"].toString()); if(old.isEmpty() && !(knownIdentities.contains(data["identity"].toString()) && !QFileInfo::exists(knownIdentities.value(data["identity"].toString())))) ++imported; }
      else if(fi.isFile()) ++processingErrors;
      processed.insert(key,data);
      return data;
    };
    QVariantList output, dirs, managedOutput;
    QStringList errors;
    for (auto rv : roots) {
      auto r = rv.toMap();
      QString root = r["path"].toString();
      QStorageInfo storage(root);
      bool ok = storageMatches(r);
      QVariantList tracks, failed;
      if (ok) {
        dirs << root;
        QDirIterator it(root, QDir::Files | QDir::Dirs | QDir::NoDotAndDotDot,
                        QDirIterator::Subdirectories);
        while (it.hasNext()) {
          QString p = it.next();
          if(p.contains("/.sillyplayer-restore-"))continue;
          auto fi = it.fileInfo();
          if (fi.isSymLink())
            continue;
          if (fi.isDir()) {
            if (!fi.isReadable())
              ok = false;
            else
              dirs << p;
            continue;
          }
          if (!QStringList{"mp3", "flac", "ogg", "oga", "opus", "m4a", "aac",
                           "wav", "webm"}
                   .contains(fi.suffix().toLower()))
            continue;
          auto t = readOrReuse(p);
          if (!t.isEmpty())
            tracks << t;
          else {
            failed << p;
            errors << "Could not read tags: " + p;
          }
        }
      }
      if (!ok)
        errors << "Folder unavailable or incomplete scan: " + root;
      output << QVariantMap{
          {"id", r["id"]}, {"ok", ok}, {"device",ok?storageIdentity(root):QString()}, {"tracks", tracks}, {"failed", failed}};
    }
    for (auto v : managed) {
      auto m = v.toMap();
      QString path = m["path"].toString();
      auto data = readOrReuse(path);
      QString parent = QFileInfo(path).absolutePath();
      bool deleted = data.isEmpty() && !QFile::exists(path) &&
                     QFileInfo(parent).isReadable() &&
                     identity(parent).section(':', 0, 0) ==
                         m["identity"].toString().section(':', 0, 0);
      managedOutput << QVariantMap{
          {"id", m["id"]}, {"path",path}, {"data", data}, {"deleted", deleted}};
    }
    int missing=0, unavailable=0, unresolved=0;
    for(const auto &track:indexed) {
      const auto state=!track["deleted"].toBool() && track["mergedInto"].toString().isEmpty() &&
        !track["identity"].toString().isEmpty() && observedIdentities.contains(track["identity"].toString())
        ? QString("Available") : fileAvailability(track,track["roots"].toList());
      if(state=="Missing") ++missing;
      else if(state=="Storage unavailable") ++unavailable;
      else if(state=="Unresolved") ++unresolved;
    }
    return QVariantMap{{"missing",missing},{"unavailable",unavailable},{"unresolved",unresolved},
                       {"imported",imported},{"updated",read-imported},{"processingErrors",processingErrors},{"roots", output},
                       {"dirs", dirs},
                       {"errors", errors},
                       {"managed", managedOutput}, {"read", read}, {"reused", reused}, {"unique",foundPaths.size()}};
  }));
}
bool Library::applyTrack(const QVariantMap &data, const QString &root,
                         bool managed, const QVariantMap &source) {
  bool ok=true;
  auto sql=[&](const QString &query,const QVariantList &args=QVariantList{}) {
    if(ok) ok=s->exec(query,args);
    return ok;
  };
  const auto alias=s->rows("SELECT * FROM tracks WHERE path=? AND mergedInto<>''",{data["path"]}).value(0).toMap();
  if(!alias.isEmpty()) {
    if(alias["mergedInto"].toString()=="sync-deleted") return ok;
    if(alias["size"]==data["size"] && alias["mtime"]==data["mtime"] && alias["identity"]==data["identity"]) return ok;
    sql("UPDATE tracks SET mergedInto='' WHERE id=?",{alias["id"]});
  }
  QString id;
  auto old = s->rows("SELECT * FROM tracks WHERE path=?", {data["path"]});
  if (old.isEmpty() && !data["identity"].toString().isEmpty()) {
    auto renamed = s->rows(
        "SELECT * FROM tracks WHERE identity=? AND size=? AND mtime=?",
        {data["identity"], data["size"], data["mtime"]});
    if (renamed.size() == 1 &&
        !QFile::exists(renamed[0].toMap()["path"].toString()))
      old = renamed;
  }
  const QFileInfo currentFile(data.value("path").toString());
  if (!currentFile.isFile() || currentFile.size() != data.value("size").toLongLong() ||
      currentFile.lastModified().toMSecsSinceEpoch() != data.value("mtime").toLongLong() ||
      identity(currentFile.filePath()) != data.value("identity").toString()) {
    if (!root.isEmpty() && !old.isEmpty())
      sql("INSERT OR IGNORE INTO track_roots VALUES(?,?)", {old[0].toMap()["id"], root});
    if (m_busy) pending = true;
    else QTimer::singleShot(0, this, [this] { startScan(false); });
    return ok;
  }
  id = old.isEmpty() ? QUuid::createUuid().toString(QUuid::WithoutBraces)
                     : old[0].toMap()["id"].toString();
  sql("INSERT OR IGNORE INTO tracks(id,path,added) VALUES(?,?,?)",
          {id, data["path"], QDateTime::currentMSecsSinceEpoch()});
  QStringList fields = {"path",  "title", "artist", "album",   "albumArtist",
                        "genre", "year",  "number", "disc",    "duration",
                        "art",   "mtime", "size",   "identity"};
  // legacy downloaded WebM may have no writable embedded tags
  // keep synced metadata/art instead of replacing it with a checksum filename during the first local index
  const auto previous = old.value(0).toMap();
  bool metadataChanged=previous.isEmpty() || !previous["available"].toBool() || previous["deleted"].toBool();
  QStringList set;
  QVariantList args;
  for (auto &f : fields) {
    auto value=data.value(f);
    if (f=="title" && data.value("fallbackTitle").toBool()) {
      const auto saved=previous["title"].toString();
      if (!saved.isEmpty()) value=saved;
      else if (!source["title"].toString().isEmpty()) value=source["title"];
    }
    if (f=="artist" && data.value("fallbackArtist").toBool() && !previous["artist"].toString().isEmpty()) value=previous["artist"];
    if (f=="art" && data.contains("fallbackTitle") && value.toString().isEmpty()) value=previous.value("art",value);
    if(previous.value(f)!=value)metadataChanged=true;
    set << f + "=?";
    args << value;
  }
  args << id;
  if(metadataChanged)
    sql("UPDATE tracks SET " + set.join(',') +
                ",available=1,deleted=0 WHERE id=?", args);
  if (managed && !previous["managed"].toBool())
    sql("UPDATE tracks SET managed=1 WHERE id=?", {id});
  if (!source.isEmpty())
    sql("UPDATE tracks SET sourceUrl=?,uploader=?,videoTitle=?,videoId=?,sourceProvider=?,sourceItemId=? "
            "WHERE id=?",
            {source.value("webpage_url", ""), source.value("uploader", ""),
             source.value("title", ""), Source::provider(source)=="youtube" ? source.value("id", "") : QVariant(""), Source::provider(source),source.value("id", ""), id});
  else {
    const auto extracted = data.value("extractedSource").toMap();
    // backfill missing source url/videoId from tags or filename without clobbering existing db attribution
    if (!extracted.isEmpty() &&
        previous.value("sourceUrl").toString().trimmed().isEmpty() &&
        previous.value("videoId").toString().trimmed().isEmpty()) {
      const QString prov = extracted.value("sourceProvider").toString();
      sql("UPDATE tracks SET sourceUrl=?,uploader=CASE WHEN uploader='' THEN ? ELSE uploader END,"
          "videoTitle=CASE WHEN videoTitle='' THEN ? ELSE videoTitle END,"
          "videoId=?,sourceProvider=CASE WHEN sourceProvider='' OR sourceProvider='unknown' THEN ? ELSE sourceProvider END,"
          "sourceItemId=CASE WHEN sourceItemId='' THEN ? ELSE sourceItemId END WHERE id=?",
          {extracted.value("sourceUrl", ""), extracted.value("uploader", ""),
           extracted.value("videoTitle", ""), extracted.value("videoId", ""),
           prov.isEmpty() ? QStringLiteral("unknown") : prov,
           extracted.value("sourceItemId", ""), id});
    }
  }
  if (!root.isEmpty())
    sql("INSERT OR IGNORE INTO track_roots VALUES(?,?)", {id, root});
  return ok;
}
QVariantMap Library::reinstateFolder(const QString &folderPath) {
  if (m_busy || m_trashing || maintenance)
    return {{"ok", false}, {"message", "Wait for current library work to finish first"}};
  const QString clean = QDir::cleanPath(QFileInfo(filePath(folderPath)).absoluteFilePath());
  const QFileInfo dirInfo(clean);
  if (clean.isEmpty() || !dirInfo.isDir() || !dirInfo.isReadable())
    return {{"ok", false}, {"message", "Choose a readable folder first"}};

  static const QStringList exts{"mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "webm"};
  QStringList files;
  QDirIterator it(clean, QDir::Files, QDirIterator::Subdirectories);
  while (it.hasNext()) {
    const QString p = it.next();
    if (p.contains("/.sillyplayer-restore-") || p.contains("/.sillyplayer/")) continue;
    const QFileInfo fi = it.fileInfo();
    if (fi.isSymLink() || !exts.contains(fi.suffix().toLower())) continue;
    files << fi.absoluteFilePath();
  }
  if (files.isEmpty())
    return {{"ok", true}, {"checked", 0}, {"reinstated", 0}, {"linksRestored", 0}, {"coversRestored", 0},
            {"message", "No audio files found in that folder"}};

  // match an existing watched root if this folder is inside one so track_roots stays accurate
  QString matchedRoot;
  for (const auto &v : s->rows("SELECT id,path FROM roots")) {
    const auto r = v.toMap();
    const QString rp = QDir::cleanPath(QFileInfo(r["path"].toString()).absoluteFilePath());
    if (clean == rp || clean.startsWith(rp + '/')) {
      matchedRoot = r["id"].toString();
      break;
    }
  }

  int reinstated = 0, linksRestored = 0, coversRestored = 0, failed = 0;
  if (!s->db().transaction())
    return {{"ok", false}, {"message", "Could not start database transaction"}};
  bool ok = true;
  for (const QString &p : files) {
    const auto data = readTrack(p, cache);
    if (data.isEmpty()) {
      ++failed;
      continue;
    }
    const auto extracted = data.value("extractedSource").toMap();
    auto existing = s->rows("SELECT * FROM tracks WHERE path=?", {data["path"]});
    if (existing.isEmpty() && !data["identity"].toString().isEmpty()) {
      auto moved = s->rows("SELECT * FROM tracks WHERE identity=? AND size=? AND mtime=?",
                           {data["identity"], data["size"], data["mtime"]});
      if (moved.size() == 1 && !QFile::exists(moved[0].toMap()["path"].toString()))
        existing = moved;
    }
    if (existing.isEmpty()) {
      if (applyTrack(data, matchedRoot, false, extracted)) {
        ++reinstated;
        if (!extracted.isEmpty()) ++linksRestored;
        if (!data.value("art").toString().isEmpty()) ++coversRestored;
      } else {
        ok = false;
        break;
      }
      continue;
    }
    const auto prev = existing.first().toMap();
    const QString id = prev["id"].toString();
    const bool wasDeleted = prev["deleted"].toBool() && prev["mergedInto"].toString() != "sync-deleted";
    const bool wasUnavailable = !prev["available"].toBool();
    if (wasDeleted || wasUnavailable) {
      ok = s->exec("UPDATE tracks SET path=?,available=1,deleted=0 WHERE id=?", {data["path"], id}) && ok;
      if (wasDeleted) ++reinstated;
    }
    const QString oldArt = prev["art"].toString();
    const bool oldArtMissing = oldArt.isEmpty() || !QFileInfo::exists(filePath(oldArt));
    const QString newArt = data.value("art").toString();
    if (oldArtMissing && !newArt.isEmpty()) {
      ok = s->exec("UPDATE tracks SET art=? WHERE id=?", {newArt, id}) && ok;
      ++coversRestored;
    }
    const bool missingSource =
        prev["videoId"].toString().trimmed().isEmpty() && prev["sourceUrl"].toString().trimmed().isEmpty();
    if (missingSource && !extracted.isEmpty()) {
      const QString prov = extracted.value("sourceProvider").toString();
      ok = s->exec(
               "UPDATE tracks SET sourceUrl=?,uploader=CASE WHEN uploader='' THEN ? ELSE uploader END,"
               "videoTitle=CASE WHEN videoTitle='' THEN ? ELSE videoTitle END,"
               "videoId=?,sourceProvider=CASE WHEN sourceProvider='' OR sourceProvider='unknown' THEN ? ELSE sourceProvider END,"
               "sourceItemId=CASE WHEN sourceItemId='' THEN ? ELSE sourceItemId END WHERE id=?",
               {extracted.value("sourceUrl", ""), extracted.value("uploader", ""),
                extracted.value("videoTitle", ""), extracted.value("videoId", ""),
                prov.isEmpty() ? QStringLiteral("unknown") : prov,
                extracted.value("sourceItemId", ""), id}) &&
           ok;
      ++linksRestored;
    }
    if (!matchedRoot.isEmpty())
      ok = s->exec("INSERT OR IGNORE INTO track_roots VALUES(?,?)", {id, matchedRoot}) && ok;
    if (!ok) break;
  }
  if (!ok || !s->db().commit()) {
    s->db().rollback();
    return {{"ok", false}, {"message", "Could not save reinstated folder changes: " + s->error()}};
  }
  emit changed();
  QStringList parts;
  parts << QString("Checked %1 %2").arg(files.size()).arg(files.size() == 1 ? "file" : "files");
  if (reinstated > 0) parts << QString("%1 reinstated").arg(reinstated);
  if (linksRestored > 0) parts << QString("%1 source %2 restored").arg(linksRestored).arg(linksRestored == 1 ? "link" : "links");
  if (coversRestored > 0) parts << QString("%1 %2 restored").arg(coversRestored).arg(coversRestored == 1 ? "cover" : "covers");
  if (failed > 0) parts << QString("%1 unreadable").arg(failed);
  if (reinstated == 0 && linksRestored == 0 && coversRestored == 0 && failed == 0)
    parts << "everything already up to date";
  return {{"ok", true},
          {"checked", files.size()},
          {"reinstated", reinstated},
          {"linksRestored", linksRestored},
          {"coversRestored", coversRestored},
          {"failed", failed},
          {"message", parts.join(QStringLiteral(" · "))}};
}
void Library::importFile(const QString &path, const QVariantMap &source) {
  if(maintenance) { deferredImports.append({path,source}); return; }
  auto w = new QFutureWatcher<QVariantMap>(this);
  edits << w;
  auto c = cache;
  connect(w, &QFutureWatcher<QVariantMap>::finished, this, [this, w, source, path] {
    auto t = w->result();
    if (t.isEmpty())
      emit error("Downloaded file could not be indexed");
    else {
      const bool started=s->db().transaction();
      const bool saved=started && applyTrack(t, {}, true, source) && s->db().commit();
      if(!saved) { if(started) s->db().rollback(); emit error("File retained, but indexing failed: "+s->error()); }
      emit changed();
      const auto indexed=s->rows("SELECT id FROM tracks WHERE path=? AND deleted=0",{path}).value(0).toMap();
      if(saved && !indexed.isEmpty()) emit fileIndexed(path,indexed["id"].toString());
    }
    edits.removeOne(w);
    w->deleteLater();
  });
  w->setFuture(QtConcurrent::run([path, c] { return readTrack(path, c); }));
}
// a missing file under an offline watched root must stay recoverable
static bool missingCanBeRemoved(const QString &path, const QVariantList &roots) {
  if (path.startsWith("sync://")) return true;
  if (roots.isEmpty()) {
    const QFileInfo parent(QFileInfo(path).absolutePath());
    return parent.isDir() && parent.isReadable() && QStorageInfo(parent.absoluteFilePath()).isReady();
  }
  for (const auto &value : roots) {
    const auto root=value.toMap(); const auto path=root["path"].toString();
    const QFileInfo directory(path); const QStorageInfo storage(path);
    if (!storageMatches(root)) return false;
  }
  return true;
}
bool Library::trashFiles(const QStringList &ids) {
  if (m_trashing || m_busy || maintenance || !edits.isEmpty()) {
    emit error("Wait for the current library update to finish, then try again");
    return false;
  }
  QVariantList files, failures;
  QSet<QString> seen;
  for (const auto &id : ids) {
    if (seen.contains(id)) continue;
    seen.insert(id);
    const auto t = track(id);
    if (t.isEmpty() || t.value("deleted").toBool()) {
      failures << QVariantMap{{"id",id},{"error","Song is no longer in the library"}};
      continue;
    }
    const auto path=t["path"].toString();
    const auto key=QDir::cleanPath(QFileInfo(path).absoluteFilePath());
    const auto roots=s->rows("SELECT roots.path,roots.available,roots.device FROM roots JOIN track_roots ON roots.id=track_roots.root WHERE track_roots.track=?", {id});
    files << QVariantMap{{"id",id},{"path",path},{"key",key},{"title",t["title"]},{"roots",roots},{"identity",t["identity"]}};
  }
  m_trashing = true;
  emit statusChanged();
  trashWorker.setFuture(QtConcurrent::run([files, failures]() mutable {
    QStringList successful;
    QHash<QString, QString> outcomes;
    for (const auto &v : files) {
      const auto t=v.toMap(); const auto path=t["path"].toString();
      const auto key=t["key"].toString();
      if (!outcomes.contains(key)) {
        QFile file(path);
        QString reason;
        if (!file.exists()) reason="File is absent; use Remove from library.";
        else if (!missingCanBeRemoved(path,t["roots"].toList())) reason="Storage identity could not be verified.";
        else if (!t["identity"].toString().isEmpty() && identity(path)!=t["identity"].toString()) reason="File identity changed; rescan before moving it to Trash.";
        else if (!file.moveToTrash()) reason=file.errorString();
        outcomes.insert(key,reason);
      }
      if (outcomes[key].isEmpty()) successful << t["id"].toString();
      else failures << QVariantMap{{"id",t["id"]},{"title",t["title"]},{"error",outcomes[key]}};
    }
    return QVariantMap{{"successful",successful},{"failures",failures}};
  }));
  return true;
}
void Library::finishTrash() {
  if (!m_trashing || !trashWorker.future().isValid()) return;
  trashWorker.waitForFinished();
  auto result=trashWorker.result();
  trashWorker.setFuture({});
  const auto successful=result["successful"].toStringList();
  bool ok=s->db().transaction();
  if (ok) for (const auto &id:successful)
    ok=s->exec("UPDATE tracks SET deleted=1,available=0,mergedInto='sync-deleted' WHERE id=?",{id}) && ok;
  if (ok) ok=s->db().commit();
  if (!ok) {
    s->db().rollback();
    auto failures=result["failures"].toList();
    failures << QVariantMap{{"error","Files were moved to Trash, but the index update failed. A rescan will reconcile them."}};
    result["failures"]=failures;
    pending=true;
  }
  m_trashing=false;
  emit changed();
  emit statusChanged();
  emit trashFinished(result);
  if (pending) {
    const bool force=pendingForce; pending=false; pendingForce=false;
    QTimer::singleShot(0,this,[this,force]{startScan(force);});
  }
}
bool Library::trashFile(const QString &id) {
  if(maintenance) { emit error("Wait for backup or restore to finish"); return false; }
  if (m_trashing) { emit error("Wait for the current Trash operation to finish"); return false; }
  const auto t = track(id);
  if (t.isEmpty() || t.value("deleted").toBool()) { emit error("This song is no longer in the library"); return false; }
  if (!edits.isEmpty()) { emit error("Wait for the current file update to finish, then try again"); return false; }
  QFile file(t.value("path").toString());
  const bool missing = !file.exists();
  const auto roots=s->rows("SELECT roots.* FROM roots JOIN track_roots ON roots.id=track_roots.root WHERE track_roots.track=?",{id});
  if (missing || !missingCanBeRemoved(file.fileName(),roots)) {
    emit error("File or verified storage unavailable; use Remove from library to retain the file."); return false;
  }
  if (!t["identity"].toString().isEmpty() && identity(file.fileName())!=t["identity"].toString()) { emit error("File identity changed; rescan before moving it to Trash."); return false; }
  if (!missing && !file.moveToTrash()) {
    emit error("Could not move this file to the system Trash: " + file.errorString()); return false;
  }
  if (!s->exec("UPDATE tracks SET deleted=1,available=0,mergedInto='sync-deleted' WHERE id=?", {id})) {
    emit error("The file was moved to Trash, but the index update failed. Rescan the library.");
    rescan(); return true;
  }
  emit changed();
  return true;
}
bool Library::removeRecords(const QStringList &ids) {
  if (m_busy || m_trashing || maintenance || !edits.isEmpty()) { emit error("Wait for the library update to finish"); return false; }
  if (!s->db().transaction()) return false;
  for (const auto &id:ids) {
    if (!s->exec("UPDATE tracks SET deleted=1,available=0,mergedInto='sync-deleted' WHERE id=? AND mergedInto=''",{id})) {
      s->db().rollback(); emit error(s->error()); return false;
    }
  }
  if (!s->db().commit()) { s->db().rollback(); emit error(s->error()); return false; }
  emit changed(); emit likesChanged(); return true;
}
bool Library::beginMaintenance() {
  if(maintenance || m_busy || m_trashing || !edits.isEmpty()) return false;
  maintenance=true;
  return true;
}
void Library::endMaintenance() {
  maintenance=false;
  const auto imports=std::exchange(deferredImports,{});
  for(const auto &item:imports) importFile(item.first,item.second);
  emit changed();
  if(pending) { const bool force=pendingForce; pending=false; pendingForce=false; startScan(force); }
}
static QVariantMap writeTagsToFile(const QString &path, const QVariantMap &tags,
                                   const QString &c) {
  const QFileInfo original(path);
  const auto originalSize = original.size();
  const auto originalTime = original.lastModified();
  QFile file(path);
  if (!file.open(QIODevice::ReadWrite))
    return QVariantMap{
        {"error", "Cannot write metadata: " + file.errorString()}};
  file.close();
  QString temporary = original.absolutePath() + "/.sillyplayer-tags-" +
                      QUuid::createUuid().toString(QUuid::WithoutBraces) +
                      "." + original.suffix();
  if (!QFile::copy(path, temporary))
    return QVariantMap{
        {"error", "Cannot create a safe metadata copy beside this file"}};
  auto cleanup = qScopeGuard([temporary] { QFile::remove(temporary); });
  auto f = taggedFile(temporary);
  if (f.isNull())
    return QVariantMap{{"error", "Unsupported metadata format"}};
  auto props = f.file()->properties();
  for (auto pair :
       {qMakePair("title", "TITLE"), qMakePair("artist", "ARTIST"),
        qMakePair("album", "ALBUM"),
        qMakePair("albumArtist", "ALBUMARTIST")}) {
    if (!tags.contains(pair.first))
      continue;
    const auto value = tags.value(pair.first).toString();
    // removing an optional tag is different from writing an empty ID3 text frame: TagLib can expose the latter as a present key with zero values
    if (value.trimmed().isEmpty())
      props.erase(pair.second);
    else
      props.replace(pair.second,
                    TagLib::String(value.toStdString(), TagLib::String::UTF8));
  }
  auto rejected = f.file()->setProperties(props);
  if (!rejected.isEmpty())
    return QVariantMap{
        {"error", "This format cannot store all requested tags"}};
  QString coverPath = tags.value("cover").toString();
  if (!coverPath.isEmpty()) {
    QImage image(coverPath);
    if (image.isNull())
      return QVariantMap{{"error", "Could not read cover image"}};
    QByteArray bytes;
    QBuffer buffer(&bytes);
    buffer.open(QIODevice::WriteOnly);
    image.scaled(1500, 1500, Qt::KeepAspectRatio, Qt::SmoothTransformation)
        .save(&buffer, "JPEG", 90);
    TagLib::ByteVector data(bytes.constData(), bytes.size());
    if (auto mp3 = dynamic_cast<TagLib::MPEG::File *>(f.file())) {
      auto tag = mp3->ID3v2Tag(true);
      tag->removeFrames("APIC");
      auto picture = new TagLib::ID3v2::AttachedPictureFrame;
      picture->setType(TagLib::ID3v2::AttachedPictureFrame::FrontCover);
      picture->setMimeType("image/jpeg");
      picture->setPicture(data);
      tag->addFrame(picture);
    } else if (auto flac = dynamic_cast<TagLib::FLAC::File *>(f.file())) {
      flac->removePictures();
      auto picture = new TagLib::FLAC::Picture;
      picture->setType(TagLib::FLAC::Picture::FrontCover);
      picture->setMimeType("image/jpeg");
      picture->setData(data);
      flac->addPicture(picture);
    } else if (auto mp4 = dynamic_cast<TagLib::MP4::File *>(f.file())) {
      TagLib::MP4::CoverArtList covers;
      covers.append(TagLib::MP4::CoverArt(TagLib::MP4::CoverArt::JPEG, data));
      mp4->tag()->setItem("covr", TagLib::MP4::Item(covers));
    } else
      return QVariantMap{
          {"error",
           "Embedded cover editing is supported for MP3, FLAC and M4A/MP4"}};
  }
  if (!f.save())
    return QVariantMap{
        {"error", "Saving embedded metadata failed; rescan this file"}};
  f = TagLib::FileRef();
  const QFileInfo updated(path);
  if (updated.size() != originalSize ||
      updated.lastModified() != originalTime)
    return QVariantMap{
        {"error",
         "The file changed while editing; please rescan and try again"}};
  const auto replacementError = replaceAudioFile(temporary, path);
  if (!replacementError.isEmpty())
    return QVariantMap{{"error", "Could not replace tagged file: " + replacementError}};
  return readTrack(path, c);
}

void Library::editTags(const QString &id, const QVariantMap &tags) {
  if(maintenance) { emit error("Wait for backup or restore to finish"); return; }
  if (m_trashing) { emit error("Wait for the current Trash operation to finish"); return; }
  auto t = track(id);
  QString path = t["path"].toString(), c = cache;
  auto w = new QFutureWatcher<QVariantMap>(this);
  edits << w;
  connect(w, &QFutureWatcher<QVariantMap>::finished, this, [this, w] {
    auto t = w->result();
    if (t.contains("error"))
      emit error(t["error"].toString());
    else {
      const bool started=s->db().transaction();
      if(!started || !applyTrack(t) || !s->db().commit()) {
        if(started) s->db().rollback();
        emit error("Tags were written, but the library update failed: "+s->error());
      }
      emit changed();
    }
    edits.removeOne(w);
    w->deleteLater();
  });
  w->setFuture(QtConcurrent::run([path, tags, c] {
    return writeTagsToFile(path, tags, c);
  }));
}

bool Library::editTagsBatch(const QStringList &ids, const QVariantMap &tags) {
  if (maintenance || m_trashing) {
    emit error("Wait for the current library operation to finish");
    return false;
  }
  QList<QVariantMap> work;
  QSet<QString> seen;
  for (const auto &id : ids) {
    if (seen.contains(id))
      continue;
    seen.insert(id);
    const auto t = track(id);
    if (t.isEmpty() || t.value("deleted").toBool())
      continue;
    work << QVariantMap{{"id", id}, {"path", t["path"]}, {"title", t["title"]}, {"tags", tags}};
  }
  if (work.isEmpty())
    return false;
  auto w = new QFutureWatcher<QVariantMap>(this);
  edits << w;
  const QString c = cache;
  connect(w, &QFutureWatcher<QVariantMap>::finished, this, [this, w] {
    const auto res = w->result();
    const auto updated = res.value("updated").toList();
    auto failures = res.value("failures").toList();
    int saved = 0;
    if (!updated.isEmpty()) {
      const bool started = s->db().transaction();
      bool ok = started;
      for (const auto &item : updated) {
        if (!ok)
          break;
        ok = applyTrack(item.toMap());
        if (ok)
          saved++;
      }
      if (!ok || !s->db().commit()) {
        if (started)
          s->db().rollback();
        saved = 0;
        failures << QVariantMap{{"title", "Library"}, {"error", s->error()}};
      }
      emit changed();
    }
    QString msg = QString("Updated tags for %1 %2")
                      .arg(saved)
                      .arg(saved == 1 ? "song" : "songs");
    if (!failures.isEmpty())
      msg += QString(" · %1 failed").arg(failures.size());
    emit batchEditFinished({{"updated", saved},
                            {"failed", failures.size()},
                            {"failures", failures},
                            {"message", msg}});
    edits.removeOne(w);
    w->deleteLater();
  });
  w->setFuture(QtConcurrent::run([work, c] {
    QVariantList updated, failures;
    for (const auto &item : work) {
      const auto out = writeTagsToFile(item["path"].toString(), item["tags"].toMap(), c);
      if (out.contains("error"))
        failures << QVariantMap{{"id", item["id"]},
                                {"title", item["title"]},
                                {"error", out["error"]}};
      else
        updated << out;
    }
    return QVariantMap{{"updated", updated}, {"failures", failures}};
  }));
  return true;
}

bool Library::cleanTitles(const QStringList &ids) {
  if (maintenance || m_trashing) {
    emit error("Wait for the current library operation to finish");
    return false;
  }
  QList<QVariantMap> work;
  QSet<QString> seen;
  for (const auto &id : ids) {
    if (seen.contains(id))
      continue;
    seen.insert(id);
    const auto t = track(id);
    if (t.isEmpty() || t.value("deleted").toBool())
      continue;
    const QString oldTitle = t.value("title").toString();
    const QString cleaned = cleanSongTitle(oldTitle);
    if (cleaned == oldTitle)
      continue;
    work << QVariantMap{{"id", id},
                        {"path", t["path"]},
                        {"title", oldTitle},
                        {"tags", QVariantMap{{"title", cleaned}}}};
  }
  if (work.isEmpty()) {
    emit batchEditFinished({{"updated", 0},
                            {"failed", 0},
                            {"failures", QVariantList{}},
                            {"message", "All selected titles are already clean"}});
    return false;
  }
  auto w = new QFutureWatcher<QVariantMap>(this);
  edits << w;
  const QString c = cache;
  connect(w, &QFutureWatcher<QVariantMap>::finished, this, [this, w] {
    const auto res = w->result();
    const auto updated = res.value("updated").toList();
    auto failures = res.value("failures").toList();
    int saved = 0;
    if (!updated.isEmpty()) {
      const bool started = s->db().transaction();
      bool ok = started;
      for (const auto &item : updated) {
        if (!ok)
          break;
        ok = applyTrack(item.toMap());
        if (ok)
          saved++;
      }
      if (!ok || !s->db().commit()) {
        if (started)
          s->db().rollback();
        saved = 0;
        failures << QVariantMap{{"title", "Library"}, {"error", s->error()}};
      }
      emit changed();
    }
    QString msg = QString("Cleaned %1 %2")
                      .arg(saved)
                      .arg(saved == 1 ? "title" : "titles");
    if (!failures.isEmpty())
      msg += QString(" · %1 failed").arg(failures.size());
    emit batchEditFinished({{"updated", saved},
                            {"failed", failures.size()},
                            {"failures", failures},
                            {"message", msg}});
    edits.removeOne(w);
    w->deleteLater();
  });
  w->setFuture(QtConcurrent::run([work, c] {
    QVariantList updated, failures;
    for (const auto &item : work) {
      const auto out = writeTagsToFile(item["path"].toString(), item["tags"].toMap(), c);
      if (out.contains("error"))
        failures << QVariantMap{{"id", item["id"]},
                                {"title", item["title"]},
                                {"error", out["error"]}};
      else
        updated << out;
    }
    return QVariantMap{{"updated", updated}, {"failures", failures}};
  }));
  return true;
}
