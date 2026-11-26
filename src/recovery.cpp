#include "recovery.h"
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QSaveFile>
#include <QSqlDatabase>
#include <QSqlError>
#include <QSqlQuery>
#include <QStandardPaths>
#include <QTemporaryFile>
#include <QUuid>
#include <QtConcurrent>

namespace {
constexpr const char *kFormat = "SillyPlayer Desktop SQLite Library";
const QStringList kSlotNames = {".sillyplayer-library-0.sqlite3", ".sillyplayer-library-1.sqlite3"};
const QStringList kPortableSettings = {
    "palette",
    "light",
    "pauseFadeEnabled",
    "pauseFadeMs",
    "crossfadeMs",
    "smartShuffle",
    "volume",
    "youtubeVolume",
    "eqEnabled",
    "eqGains",
    "eqPreset",
    "eqCustomPresets",
    "eqStateUpdatedAt",
    "allowPlaylistDuplicates",
    "pinnedPlaylists",
    "pinnedArtists",
    "discordRpcEnabled",
    "discordRpcLookupCovers",
    "discordRpcUploadCovers",
    "updateNotifications",
};

QString tempDbPath() {
  const QString dir = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
  return QDir(dir.isEmpty() ? QDir::tempPath() : dir)
      .filePath("sillyplayer-recovery-" + QUuid::createUuid().toString(QUuid::WithoutBraces) + ".sqlite3");
}

void cleanupSidecars(const QString &path) {
  QFile::remove(path);
  QFile::remove(path + "-wal");
  QFile::remove(path + "-shm");
  QFile::remove(path + "-journal");
}
} // namespace

DesktopRecovery::DesktopRecovery(Store *store, QObject *parent) : QObject(parent), s(store) {
  debounce.setSingleShot(true);
  debounce.setInterval(4000);
  connect(&debounce, &QTimer::timeout, this, [this] {
    if (closing) return;
    if (busyGuard && busyGuard()) {
      debounce.start(1500);
      return;
    }
    saveInternal(false, false);
  });
}

DesktopRecovery::~DesktopRecovery() { shutdown(); }

void DesktopRecovery::shutdown() {
  closing = true;
  debounce.stop();
  if (worker.isRunning()) worker.waitForFinished();
}

QVariantMap DesktopRecovery::readMeta(const QString &dbFile) {
  QFileInfo fi(dbFile);
  if (!fi.isFile() || fi.size() < 100) return {};
  const QString conn = "recovery-inspect-" + QUuid::createUuid().toString(QUuid::WithoutBraces);
  QVariantMap meta;
  {
    QSqlDatabase db = QSqlDatabase::addDatabase("QSQLITE", conn);
    db.setDatabaseName(dbFile);
    db.setConnectOptions("QSQLITE_OPEN_READONLY");
    if (db.open()) {
      QSqlQuery check(db);
      if (check.exec("PRAGMA integrity_check") && check.next() &&
          check.value(0).toString().compare("ok", Qt::CaseInsensitive) == 0) {
        QSqlQuery q(db);
        if (q.exec("SELECT key,value FROM sillyplayer_portable_meta")) {
          while (q.next()) meta.insert(q.value(0).toString(), q.value(1).toString());
        }
      }
      db.close();
    }
  }
  QSqlDatabase::removeDatabase(conn);
  if (meta.value("format").toString() != QLatin1String(kFormat) || meta.value("version").toString() != "1")
    return {};
  return meta;
}

void DesktopRecovery::inspectFolder(const QString &folder) {
  if (folder.trimmed().isEmpty()) return;
  QDir dir(folder);
  if (!dir.exists()) return;
  qint64 newest = 0;
  int slot = -1;
  for (int i = 0; i < kSlotNames.size(); ++i) {
    const auto meta = readMeta(dir.filePath(kSlotNames[i]));
    const qint64 created = meta.value("created").toLongLong();
    if (created > newest) {
      newest = created;
      slot = i;
    }
  }
  if (newest > 0) {
    newestSlot = slot;
    m_lastSaved = newest;
    emit changed();
  }
}

void DesktopRecovery::requestSave() {
  if (closing || qEnvironmentVariableIsSet("SILLYPLAYER_NO_AUTO_RECOVERY")) return;
  debounce.start();
}

QVariantMap DesktopRecovery::saveNow(bool allowEmpty) {
  debounce.stop();
  return saveInternal(allowEmpty, true);
}

QVariantMap DesktopRecovery::saveInternal(bool allowEmpty, bool createMissingDir) {
  if (closing || m_saving) return {{"ok", false}, {"message", "Database mirror is already saving"}};
  const QString folder = folderProvider ? folderProvider().trimmed() : QString();
  if (folder.isEmpty()) return {{"ok", false}, {"message", "Choose a music folder first"}};
  QDir dir(folder);
  if (!dir.exists()) {
    if (!createMissingDir) return {{"ok", false}, {"message", ""}};
    if (!dir.mkpath(".")) {
      m_status = "Cannot create music folder for library mirror";
      emit changed();
      return {{"ok", false}, {"message", m_status}};
    }
  }
  if (!allowEmpty) {
    const bool hasTracks = !s->rows("SELECT id FROM tracks WHERE deleted=0 LIMIT 1").isEmpty();
    const bool hasCollections = !s->rows("SELECT id FROM collections LIMIT 1").isEmpty();
    // dont wipe a surviving folder mirror when starting up with an empty library
    if (!hasTracks && !hasCollections)
      return {{"ok", false}, {"message", "Library is empty; skipping mirror save"}};
  }

  inspectFolder(folder);
  m_saving = true;
  emit changed();

  const QString stage = tempDbPath();
  cleanupSidecars(stage);
  const qint64 created = qMax(QDateTime::currentMSecsSinceEpoch(), m_lastSaved + 1);
  bool ok = s->exec("VACUUM INTO ?", {stage});
  QString err = ok ? QString() : s->error();

  if (ok) {
    const QString conn = "recovery-stage-" + QUuid::createUuid().toString(QUuid::WithoutBraces);
    {
      QSqlDatabase snap = QSqlDatabase::addDatabase("QSQLITE", conn);
      snap.setDatabaseName(stage);
      if (!snap.open()) {
        ok = false;
        err = snap.lastError().text();
      } else {
        snap.transaction();
        QSqlQuery q(snap);
        const QStringList stripTables = {
            "sync_peers", "sync_operations", "sync_conflicts", "sync_aliases",
            "sync_dirty", "sync_entities", "sync_changes", "sync_blobs",
            "sync_media_pending", "roots", "track_roots",
        };
        for (const auto &tbl : stripTables) {
          q.exec("DELETE FROM " + tbl);
        }
        // only keep portable settings so local tokens and window positions dont leak into the folder copy
        QStringList placeholders;
        for (int i = 0; i < kPortableSettings.size(); ++i) placeholders << "?";
        q.prepare("DELETE FROM settings WHERE key NOT IN (" + placeholders.join(',') + ")");
        for (const auto &k : kPortableSettings) q.addBindValue(k);
        q.exec();

        ok = q.exec("CREATE TABLE IF NOT EXISTS sillyplayer_portable_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        auto putMeta = [&](const QString &k, const QString &v) {
          q.prepare("INSERT OR REPLACE INTO sillyplayer_portable_meta(key,value) VALUES(?,?)");
          q.addBindValue(k);
          q.addBindValue(v);
          return q.exec();
        };
        if (ok) ok = putMeta("format", kFormat);
        if (ok) ok = putMeta("version", "1");
        if (ok) ok = putMeta("created", QString::number(created));
        if (ok) ok = snap.commit();
        else {
          err = q.lastError().text();
          snap.rollback();
        }
        if (ok) {
          q.exec("PRAGMA journal_mode=DELETE");
          if (!q.exec("PRAGMA integrity_check") || !q.next() ||
              q.value(0).toString().compare("ok", Qt::CaseInsensitive) != 0) {
            ok = false;
            err = "Library database integrity check failed";
          }
        }
        snap.close();
      }
    }
    QSqlDatabase::removeDatabase(conn);
  }

  if (ok) {
    const int targetSlot = newestSlot == 0 ? 1 : 0;
    const QString destPath = dir.filePath(kSlotNames[targetSlot]);
    QFile in(stage);
    QSaveFile out(destPath);
    if (!in.open(QIODevice::ReadOnly) || !out.open(QIODevice::WriteOnly)) {
      ok = false;
      err = "Cannot write library database file in music folder";
    } else {
      while (!in.atEnd()) {
        const auto chunk = in.read(256 * 1024);
        if (out.write(chunk) != chunk.size()) {
          ok = false;
          err = "Could not finish writing library database file";
          break;
        }
      }
      if (ok && !out.commit()) {
        ok = false;
        err = out.errorString();
      }
    }
    if (ok) {
      newestSlot = targetSlot;
      m_lastSaved = created;
      m_status = "Library database saved in your music folder";
    }
  }
  cleanupSidecars(stage);

  if (!ok) {
    m_status = err.isEmpty() ? "Could not save library database" : ("Library database mirror: " + err);
  }
  m_saving = false;
  emit changed();
  return {{"ok", ok}, {"message", m_status}, {"lastSaved", m_lastSaved}};
}

QVariantMap DesktopRecovery::recoverFromFolder(const QString &folderInput) {
  const QString folder = folderInput.trimmed().isEmpty()
                             ? (folderProvider ? folderProvider().trimmed() : QString())
                             : folderInput.trimmed();
  if (folder.isEmpty()) return {{"ok", false}, {"restored", 0}, {"message", ""}};
  const bool hasTracks = !s->rows("SELECT id FROM tracks WHERE deleted=0 LIMIT 1").isEmpty();
  const bool hasCollections = !s->rows("SELECT id FROM collections LIMIT 1").isEmpty();
  if (hasTracks || hasCollections) {
    inspectFolder(folder);
    return {{"ok", false}, {"restored", 0}, {"message", ""}};
  }
  QDir dir(folder);
  if (!dir.exists()) return {{"ok", false}, {"restored", 0}, {"message", ""}};
  QString newestPath;
  qint64 newest = 0;
  int slot = -1;
  for (int i = 0; i < kSlotNames.size(); ++i) {
    const QString candidate = dir.filePath(kSlotNames[i]);
    const auto meta = readMeta(candidate);
    const qint64 created = meta.value("created").toLongLong();
    if (created > newest) {
      newest = created;
      newestPath = candidate;
      slot = i;
    }
  }
  if (newestPath.isEmpty()) return {{"ok", false}, {"restored", 0}, {"message", ""}};

  int restoredTracks = 0, restoredCollections = 0;
  const QString conn = "recovery-load-" + QUuid::createUuid().toString(QUuid::WithoutBraces);
  bool ok = true;
  QString err;
  {
    QSqlDatabase src = QSqlDatabase::addDatabase("QSQLITE", conn);
    src.setDatabaseName(newestPath);
    src.setConnectOptions("QSQLITE_OPEN_READONLY");
    if (!src.open()) {
      ok = false;
      err = src.lastError().text();
    } else if (!s->db().transaction()) {
      ok = false;
      err = "Could not start recovery transaction";
    } else {
      s->exec("UPDATE sync_control SET applying=1 WHERE id=1");
      auto copyTable = [&](const QString &table, const QStringList &cols) {
        QSqlQuery q(src);
        if (!q.exec("SELECT " + cols.join(',') + " FROM " + table)) return 0;
        QStringList placeholders;
        for (int i = 0; i < cols.size(); ++i) placeholders << "?";
        const QString insertSql = "INSERT OR REPLACE INTO " + table + "(" + cols.join(',') +
                                  ") VALUES(" + placeholders.join(',') + ")";
        int n = 0;
        while (q.next()) {
          QVariantList vals;
          for (int i = 0; i < cols.size(); ++i) vals << q.value(i);
          if (s->exec(insertSql, vals)) ++n;
        }
        return n;
      };
      restoredTracks = copyTable(
          "tracks",
          {"id", "path", "title", "artist", "album", "albumArtist", "genre", "year", "number",
           "disc", "duration", "art", "available", "deleted", "added", "mtime", "size",
           "identity", "managed", "speed", "nightcore", "volumePercent", "sourceUrl", "uploader",
           "videoTitle", "videoId", "sourceProvider", "sourceItemId", "mergedInto"});
      restoredCollections = copyTable("collections", {"id", "name", "kind", "art"});
      copyTable("members", {"id", "collection", "track", "position"});
      copyTable("likes", {"track"});
      copyTable("jobs", {"id", "payload"});
      copyTable("searches", {"provider", "query", "used"});
      copyTable("restore_ids", {"origin", "kind", "remote", "local"});
      copyTable("listening_totals", {"id", "milliseconds", "started"});
      copyTable("track_listens", {"track", "plays", "lastPlayed"});
      copyTable("listening_plays", {"session", "track", "playedAt"});
      copyTable("sync_counters", {"device", "component", "track", "milliseconds", "plays", "lastPlayed"});
      copyTable("rpc_covers", {"track", "url", "provider", "checkedAt"});
      QSqlQuery sq(src);
      if (sq.exec("SELECT key,value FROM settings")) {
        while (sq.next()) {
          const QString k = sq.value(0).toString();
          if (kPortableSettings.contains(k)) {
            s->exec("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", {k, sq.value(1)});
          }
        }
      }
      s->exec("UPDATE sync_control SET applying=0 WHERE id=1");
      if (!s->db().commit()) {
        s->db().rollback();
        s->exec("UPDATE sync_control SET applying=0 WHERE id=1");
        ok = false;
        err = s->error();
      }
      src.close();
    }
  }
  QSqlDatabase::removeDatabase(conn);

  if (ok) {
    newestSlot = slot;
    m_lastSaved = newest;
    m_status = QString("Recovered library database (%1 songs, %2 playlists)")
                   .arg(restoredTracks)
                   .arg(restoredCollections);
    emit changed();
    emit recovered();
  }
  return {{"ok", ok}, {"restored", restoredTracks}, {"collections", restoredCollections}, {"message", ok ? m_status : err}};
}
