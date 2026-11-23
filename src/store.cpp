#include "store.h"
#include "source.h"
#include <QFile>
#include <QJsonDocument>
#include <QSqlError>
#include <QSqlQuery>
#include <QSqlRecord>
#include <QUuid>
#include <stdexcept>

Store::Store(const QString &path) {
  m_db = QSqlDatabase::addDatabase("QSQLITE", QUuid::createUuid().toString());
  m_db.setDatabaseName(path);
  if (!m_db.open())
    throw std::runtime_error(m_db.lastError().text().toStdString());
  exec("PRAGMA foreign_keys=ON");
  exec("PRAGMA journal_mode=WAL");
  exec("PRAGMA busy_timeout=5000");
  const int version = rows("PRAGMA user_version")
                          .value(0)
                          .toMap()
                          .value("user_version")
                          .toInt();
  if (version > 11)
    throw std::runtime_error(
        "Database was created by a newer SillyPlayer version");
  if (!version) {
    m_db.transaction();
    const QStringList schema = {
        "CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
        "CREATE TABLE roots(id TEXT PRIMARY KEY,path TEXT UNIQUE NOT "
        "NULL,device TEXT,available INTEGER DEFAULT 1)",
        "CREATE TABLE tracks(id TEXT PRIMARY KEY,path TEXT UNIQUE NOT "
        "NULL,title TEXT,artist TEXT,album TEXT,albumArtist TEXT,genre "
        "TEXT,year INTEGER,number INTEGER,disc INTEGER,duration REAL,art "
        "TEXT,available INTEGER DEFAULT 1,deleted INTEGER DEFAULT 0,added "
        "INTEGER,mtime INTEGER,size INTEGER,identity TEXT,managed INTEGER "
        "DEFAULT 0,speed REAL NOT NULL DEFAULT 1.0 CHECK(speed BETWEEN 0.5 AND 2.0),nightcore INTEGER NOT NULL DEFAULT 0,sourceUrl TEXT DEFAULT '',uploader TEXT DEFAULT "
        "'',videoTitle TEXT DEFAULT '',videoId TEXT DEFAULT '',sourceProvider TEXT NOT NULL DEFAULT 'unknown',sourceItemId TEXT NOT NULL DEFAULT '')",
        "CREATE TABLE track_roots(track TEXT REFERENCES tracks(id) ON DELETE "
        "CASCADE,root TEXT REFERENCES roots(id) ON DELETE CASCADE,PRIMARY "
        "KEY(track,root))",
        "CREATE TABLE collections(id TEXT PRIMARY KEY,name TEXT NOT NULL,kind "
        "TEXT NOT NULL,art TEXT DEFAULT '')",
        "CREATE TABLE members(id TEXT PRIMARY KEY,collection TEXT REFERENCES "
        "collections(id) ON DELETE CASCADE,track TEXT REFERENCES "
        "tracks(id),position INTEGER)",
        "CREATE TABLE likes(track TEXT PRIMARY KEY REFERENCES tracks(id) ON "
        "DELETE CASCADE)",
        "CREATE TABLE jobs(id TEXT PRIMARY KEY,payload TEXT NOT NULL)",
        "CREATE TABLE searches(provider TEXT NOT NULL,query TEXT NOT NULL,used INTEGER NOT NULL,PRIMARY KEY(provider,query))",
        "CREATE INDEX tracks_tags ON tracks(album,albumArtist,artist)",
        "CREATE INDEX members_order ON members(collection,position)",
        "CREATE TABLE IF NOT EXISTS listening_totals(id INTEGER PRIMARY KEY CHECK(id=1),milliseconds REAL NOT NULL DEFAULT 0,started INTEGER NOT NULL)",
        "INSERT OR IGNORE INTO listening_totals VALUES(1,0,CAST(strftime('%s','now') AS INTEGER)*1000)",
        "CREATE TABLE IF NOT EXISTS track_listens(track TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,plays INTEGER NOT NULL,lastPlayed INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS listening_plays(session TEXT PRIMARY KEY,track TEXT REFERENCES tracks(id) ON DELETE SET NULL,playedAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS listening_window ON listening_plays(playedAt,track)",
        "PRAGMA user_version=5"};
    for (const auto &sql : schema)
      if (!exec(sql)) {
        m_db.rollback();
        throw std::runtime_error(m_error.toStdString());
      }
    if (!m_db.commit())
      throw std::runtime_error(m_db.lastError().text().toStdString());
  }
  if (version >= 1 && version < 5) {
    QString backup = path + ".before-v5.sqlite3";
    if (QFile::exists(backup)) backup += "." + QUuid::createUuid().toString(QUuid::WithoutBraces);
    if (!exec("VACUUM INTO ?", {backup}))
      throw std::runtime_error(("Cannot back up library before upgrade: " + m_error).toStdString());
    if (!m_db.transaction()) throw std::runtime_error("Cannot begin library upgrade");
    QStringList fields;
    for (const auto &v : rows("PRAGMA table_info(tracks)")) fields << v.toMap()["name"].toString();
    bool ok = true;
    if (!fields.contains("speed")) ok = exec("ALTER TABLE tracks ADD COLUMN speed REAL NOT NULL DEFAULT 1.0 CHECK(speed BETWEEN 0.5 AND 2.0)");
    if (ok && !fields.contains("nightcore")) ok = exec("ALTER TABLE tracks ADD COLUMN nightcore INTEGER NOT NULL DEFAULT 0");
    if (ok && !fields.contains("sourceProvider")) ok = exec("ALTER TABLE tracks ADD COLUMN sourceProvider TEXT NOT NULL DEFAULT 'unknown'");
    if (ok && !fields.contains("sourceItemId")) ok = exec("ALTER TABLE tracks ADD COLUMN sourceItemId TEXT NOT NULL DEFAULT ''");
    bool scopedHistory=false;
    for (const auto &v:rows("PRAGMA table_info(searches)")) if(v.toMap()["name"]=="provider") scopedHistory=true;
    if (ok && !scopedHistory) ok=exec("ALTER TABLE searches RENAME TO old_searches") &&
      exec("CREATE TABLE searches(provider TEXT NOT NULL,query TEXT NOT NULL,used INTEGER NOT NULL,PRIMARY KEY(provider,query))") &&
      exec("INSERT INTO searches SELECT 'youtube',query,used FROM old_searches") && exec("DROP TABLE old_searches");
    if (ok && version<4) for (const auto &v:rows("SELECT id,sourceUrl,videoId,sourceProvider FROM tracks")) {
      const auto t=v.toMap(); const auto provider=Source::providerForUrl(t["sourceUrl"].toString());
      if (provider!="unknown") ok=exec("UPDATE tracks SET sourceProvider=?,sourceItemId=? WHERE id=?",{provider,t["videoId"],t["id"]}) && ok;
    }
    const QStringList statistics={
        "CREATE TABLE IF NOT EXISTS listening_totals(id INTEGER PRIMARY KEY CHECK(id=1),milliseconds REAL NOT NULL DEFAULT 0,started INTEGER NOT NULL)",
        "INSERT OR IGNORE INTO listening_totals VALUES(1,0,CAST(strftime('%s','now') AS INTEGER)*1000)",
        "CREATE TABLE IF NOT EXISTS track_listens(track TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,plays INTEGER NOT NULL,lastPlayed INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS listening_plays(session TEXT PRIMARY KEY,track TEXT REFERENCES tracks(id) ON DELETE SET NULL,playedAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS listening_window ON listening_plays(playedAt,track)",
    };
    for (const auto &sql:statistics) if(ok) ok=exec(sql);
    if (!ok || !exec("PRAGMA user_version=5") || !m_db.commit()) {
      m_db.rollback(); throw std::runtime_error(("Cannot upgrade library: " + m_error).toStdString());
    }
  }
  if(version<6) {
    if(version) {
      QString backup=path+".before-v6.sqlite3";
      if(QFile::exists(backup)) backup+="."+QUuid::createUuid().toString(QUuid::WithoutBraces);
      if(!exec("VACUUM INTO ?",{backup})) throw std::runtime_error(m_error.toStdString());
    }
    if(!m_db.transaction()) throw std::runtime_error("Cannot begin backup identity migration");
    if(!exec("CREATE TABLE IF NOT EXISTS restore_ids(origin TEXT NOT NULL,kind TEXT NOT NULL,remote TEXT NOT NULL,local TEXT NOT NULL,PRIMARY KEY(origin,kind,remote))") ||
       !exec("PRAGMA user_version=6") || !m_db.commit()) {
      m_db.rollback(); throw std::runtime_error(m_error.toStdString());
    }
  }
  if(version<7) {
    if(version) {
      QString backup=path+".before-v7.sqlite3";
      if(QFile::exists(backup)) backup+="."+QUuid::createUuid().toString(QUuid::WithoutBraces);
      if(!exec("VACUUM INTO ?",{backup})) throw std::runtime_error(m_error.toStdString());
    }
    if(!m_db.transaction()) throw std::runtime_error("Cannot begin library tools migration");
    bool hasAlias=false; for(auto v:rows("PRAGMA table_info(tracks)")) if(v.toMap()["name"]=="mergedInto") hasAlias=true;
    if((!hasAlias && !exec("ALTER TABLE tracks ADD COLUMN mergedInto TEXT NOT NULL DEFAULT ''")) ||
       !exec("CREATE TABLE IF NOT EXISTS track_hashes(track TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,size INTEGER,mtime INTEGER,identity TEXT,sha256 TEXT NOT NULL)") ||
       !exec("PRAGMA user_version=7") || !m_db.commit()) {
      m_db.rollback(); throw std::runtime_error(m_error.toStdString());
    }
  }
  if(version<8) {
    if(version) {
      QString backup=path+".before-v8.sqlite3";
      if(QFile::exists(backup)) backup+="."+QUuid::createUuid().toString(QUuid::WithoutBraces);
      if(!exec("VACUUM INTO ?",{backup})) throw std::runtime_error(m_error.toStdString());
    }
    if(!m_db.transaction()) throw std::runtime_error("Cannot begin playlist-download migration");
    if(!exec("CREATE TABLE IF NOT EXISTS playlist_download_items(id TEXT PRIMARY KEY,collection TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,job TEXT NOT NULL,path TEXT NOT NULL DEFAULT '',position INTEGER NOT NULL)") ||
       !exec("PRAGMA user_version=8") || !m_db.commit()) {m_db.rollback();throw std::runtime_error(m_error.toStdString());}
  }
  if(version<9) {
    if(version) {
      QString backup=path+".before-v9.sqlite3";
      if(QFile::exists(backup)) backup+="."+QUuid::createUuid().toString(QUuid::WithoutBraces);
      if(!exec("VACUUM INTO ?",{backup})) throw std::runtime_error(m_error.toStdString());
    }
    if(!m_db.transaction()) throw std::runtime_error("Cannot begin song-volume migration");
    bool hasVolume=false;for(auto v:rows("PRAGMA table_info(tracks)")) if(v.toMap()["name"]=="volumePercent")hasVolume=true;
    if((!hasVolume && !exec("ALTER TABLE tracks ADD COLUMN volumePercent REAL NOT NULL DEFAULT 100 CHECK(volumePercent BETWEEN 0 AND 200)")) ||
       !exec("DELETE FROM settings WHERE key='normalization'") || !exec("PRAGMA user_version=9") || !m_db.commit()) {
      m_db.rollback();throw std::runtime_error(m_error.toStdString());
    }
  }
  if(version<10) {
    if(version) {QString backup=path+".before-v10.sqlite3";if(QFile::exists(backup))backup+="."+QUuid::createUuid().toString(QUuid::WithoutBraces);if(!exec("VACUUM INTO ?",{backup}))throw std::runtime_error(m_error.toStdString());}
    auto device=setting("libraryIdentity").toString();if(device.isEmpty()){device=QUuid::createUuid().toString(QUuid::WithoutBraces);setSetting("libraryIdentity",device);}
    if(!m_db.transaction())throw std::runtime_error("Cannot begin sync migration");
    QStringList schema{
      "CREATE TABLE IF NOT EXISTS sync_control(id INTEGER PRIMARY KEY CHECK(id=1),applying INTEGER NOT NULL)",
      "INSERT OR IGNORE INTO sync_control VALUES(1,0)",
      "CREATE TABLE IF NOT EXISTS sync_dirty(entity TEXT NOT NULL,key TEXT NOT NULL,generation INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(entity,key))",
      "CREATE TABLE IF NOT EXISTS sync_entities(entity TEXT NOT NULL,key TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,author TEXT NOT NULL,PRIMARY KEY(entity,key))",
      "CREATE TABLE IF NOT EXISTS sync_changes(revision INTEGER PRIMARY KEY AUTOINCREMENT,entity TEXT NOT NULL,key TEXT NOT NULL,payload TEXT NOT NULL,author TEXT NOT NULL)",
      "CREATE INDEX IF NOT EXISTS sync_entity_history ON sync_changes(entity,key,revision)",
      "CREATE TABLE IF NOT EXISTS sync_peers(id TEXT PRIMARY KEY,name TEXT NOT NULL,token TEXT NOT NULL,lastSeen INTEGER NOT NULL,ack INTEGER NOT NULL DEFAULT 0)",
      "CREATE TABLE IF NOT EXISTS sync_operations(peer TEXT NOT NULL,operation TEXT NOT NULL,response TEXT NOT NULL,PRIMARY KEY(peer,operation))",
      "CREATE TABLE IF NOT EXISTS sync_conflicts(id TEXT PRIMARY KEY,peer TEXT NOT NULL,operation TEXT NOT NULL,entity TEXT NOT NULL,key TEXT NOT NULL,local TEXT NOT NULL,remote TEXT NOT NULL,revision INTEGER NOT NULL,fields TEXT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS sync_aliases(peer TEXT NOT NULL,entity TEXT NOT NULL,remote TEXT NOT NULL,canonical TEXT NOT NULL,PRIMARY KEY(peer,entity,remote))",
      "CREATE TABLE IF NOT EXISTS sync_blobs(hash TEXT PRIMARY KEY,path TEXT NOT NULL,size INTEGER NOT NULL,mtime INTEGER NOT NULL,kind TEXT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS sync_counters(device TEXT NOT NULL,component TEXT NOT NULL,track TEXT NOT NULL,milliseconds INTEGER NOT NULL DEFAULT 0,plays INTEGER NOT NULL DEFAULT 0,lastPlayed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(device,component))"
    };
    bool ok=true;for(auto sql:schema)if(ok)ok=exec(sql);
    if(ok)ok=exec("INSERT OR IGNORE INTO sync_counters(device,component,track,milliseconds,plays,lastPlayed) SELECT ?,'','',CAST(milliseconds AS INTEGER),0,0 FROM listening_totals WHERE id=1",{device});
    if(ok)ok=exec("INSERT OR IGNORE INTO sync_counters(device,component,track,milliseconds,plays,lastPlayed) SELECT ?,track,track,0,plays,lastPlayed FROM track_listens",{device});
    for(const auto &entry:QList<QPair<QString,QString>>{{"tracks","id"},{"collections","id"},{"members","id"},{"likes","track"},{"listening_plays","session"},{"sync_counters","device||':'||component"}}) {
      const auto table=entry.first,key=entry.second,entity=table=="listening_plays"?QString("plays"):table=="sync_counters"?QString("counters"):table;
      for(const auto &action:{QString("INSERT"),QString("UPDATE"),QString("DELETE")}) {
        auto reference=action=="DELETE"?QString("OLD"):QString("NEW");auto expression=key=="device||':'||component"?reference+".device||':'||"+reference+".component":reference+"."+key;
        QString event=action;
        if(table=="tracks"&&action=="UPDATE")event+=" OF title,artist,album,albumArtist,genre,year,number,duration,art,speed,nightcore,volumePercent,sourceUrl,uploader,videoTitle,sourceProvider,sourceItemId,deleted,mergedInto";
        if(ok)ok=exec("CREATE TRIGGER IF NOT EXISTS sync_"+table+"_"+action+" AFTER "+event+" ON "+table+" WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN INSERT INTO sync_dirty(entity,key,generation) VALUES('"+entity+"',"+expression+",1) ON CONFLICT(entity,key) DO UPDATE SET generation=generation+1; END");
      }
      if(ok)ok=exec("INSERT OR IGNORE INTO sync_dirty(entity,key) SELECT '"+entity+"',"+key+" FROM "+table);
    }
    if(!ok||!exec("PRAGMA user_version=10")||!m_db.commit()){m_db.rollback();throw std::runtime_error(m_error.toStdString());}
  }
  if(!exec("CREATE TABLE IF NOT EXISTS sync_media_pending(track TEXT PRIMARY KEY,desired TEXT NOT NULL,actual TEXT NOT NULL)"))throw std::runtime_error("Cannot initialize pending media state");
  // order is a collection-wide revision, independent from membership edits
  if(version<11) {
    if(version) {
      QString backup=path+".before-v11.sqlite3";
      if(QFile::exists(backup))backup+="."+QUuid::createUuid().toString(QUuid::WithoutBraces);
      if(!exec("VACUUM INTO ?",{backup}))throw std::runtime_error(m_error.toStdString());
    }
    if(!m_db.transaction())throw std::runtime_error("Cannot begin 300-percent volume migration");
    // retain the old constrained column rather than rebuilding tracks and its foreign keys its default stays 100, all current readers/writers use the new column
    bool ok=exec("DROP TRIGGER IF EXISTS sync_tracks_UPDATE") &&
      exec("ALTER TABLE tracks RENAME COLUMN volumePercent TO legacyVolumePercent") &&
      exec("ALTER TABLE tracks ADD COLUMN volumePercent REAL NOT NULL DEFAULT 100 CHECK(volumePercent BETWEEN 0 AND 300)") &&
      exec("UPDATE tracks SET volumePercent=legacyVolumePercent") &&
      exec("CREATE TRIGGER sync_tracks_UPDATE AFTER UPDATE OF title,artist,album,albumArtist,genre,year,number,duration,art,speed,nightcore,volumePercent,sourceUrl,uploader,videoTitle,sourceProvider,sourceItemId,deleted,mergedInto ON tracks WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN INSERT INTO sync_dirty(entity,key,generation) VALUES('tracks',NEW.id,1) ON CONFLICT(entity,key) DO UPDATE SET generation=generation+1; END") &&
      exec("PRAGMA user_version=11");
    if(!ok || !m_db.commit()){m_db.rollback();throw std::runtime_error(m_error.toStdString());}
  }
  if(!m_db.transaction())throw std::runtime_error("Cannot register collection order journal");
  bool orderOk=true;
  for(const auto &event:QStringList{"INSERT","UPDATE","DELETE"}) {
    QString body;
    if(event!="DELETE")body+="INSERT INTO sync_dirty(entity,key,generation) VALUES('orders',NEW.collection,1) ON CONFLICT(entity,key) DO UPDATE SET generation=generation+1;";
    if(event!="INSERT")body+="INSERT INTO sync_dirty(entity,key,generation) VALUES('orders',OLD.collection,1) ON CONFLICT(entity,key) DO UPDATE SET generation=generation+1;";
    orderOk=exec("CREATE TRIGGER IF NOT EXISTS sync_order_"+event+" AFTER "+event+" ON members WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN "+body+" END")&&orderOk;
  }
  for(const auto &event:QStringList{"INSERT","DELETE"}) {
    auto ref=event=="DELETE"?QString("OLD"):QString("NEW");
    orderOk=exec("CREATE TRIGGER IF NOT EXISTS sync_collection_order_"+event+" AFTER "+event+" ON collections WHEN (SELECT applying FROM sync_control WHERE id=1)=0 BEGIN INSERT INTO sync_dirty(entity,key,generation) VALUES('orders',"+ref+".id,1) ON CONFLICT(entity,key) DO UPDATE SET generation=generation+1; END")&&orderOk;
  }
  orderOk=exec("INSERT OR IGNORE INTO sync_dirty(entity,key) SELECT 'orders',id FROM collections WHERE NOT EXISTS(SELECT 1 FROM sync_entities e WHERE e.entity='orders' AND e.key=collections.id)")&&orderOk;
  if(!orderOk||!m_db.commit()){m_db.rollback();throw std::runtime_error("Cannot initialize collection order journal");}
  exec("UPDATE sync_control SET applying=0 WHERE id=1");
  if(setting("libraryIdentity").toString().isEmpty())
    setSetting("libraryIdentity",QUuid::createUuid().toString(QUuid::WithoutBraces));
}
Store::~Store() {
  auto name = m_db.connectionName();
  m_db.close();
  m_db = QSqlDatabase();
  QSqlDatabase::removeDatabase(name);
}
QVariantList Store::rows(const QString &sql, const QVariantList &args) const {
  QSqlQuery q(m_db);
  q.setForwardOnly(true);
  q.prepare(sql);
  for (const auto &arg : args)
    q.addBindValue(arg);
  if (!q.exec()) {
    m_error = q.lastError().text();
    return {};
  }
  QVariantList result;
  const auto rec = q.record();
  QStringList names;names.reserve(rec.count());
  for(int i=0;i<rec.count();++i)names.append(rec.fieldName(i));
  while (q.next()) {
    QVariantMap row;
    for (int i = 0; i < names.size(); ++i)
      row[names[i]] = q.value(i);
    result.append(row);
  }
  return result;
}
bool Store::exec(const QString &sql, const QVariantList &args) {
  QSqlQuery q(m_db);
  q.prepare(sql);
  for (const auto &arg : args)
    q.addBindValue(arg);
  if (!q.exec()) {
    m_error = q.lastError().text();
    return false;
  }
  if(!q.isSelect() && q.numRowsAffected()>0){
    ++serial;
    if((sql.contains("tracks",Qt::CaseInsensitive)||sql.contains("collections",Qt::CaseInsensitive)||sql.contains("members",Qt::CaseInsensitive)) &&
       !sql.startsWith("UPDATE tracks SET speed=",Qt::CaseInsensitive) &&
       !sql.startsWith("UPDATE tracks SET volumePercent=",Qt::CaseInsensitive))++catalogue;
  }
  return true;
}
QVariant Store::setting(const QString &key, const QVariant &fallback) const {
  auto r = rows("SELECT value FROM settings WHERE key=?", {key});
  if (r.isEmpty())
    return fallback;
  auto doc = QJsonDocument::fromJson(r[0].toMap()["value"].toByteArray());
  return doc.array().isEmpty() ? fallback : doc.array().first().toVariant();
}
void Store::setSetting(const QString &key, const QVariant &value) {
  exec("INSERT INTO settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET "
       "value=excluded.value WHERE settings.value<>excluded.value",
       {key, QString::fromUtf8(QJsonDocument::fromVariant(QVariantList{value})
                                   .toJson(QJsonDocument::Compact))});
}
