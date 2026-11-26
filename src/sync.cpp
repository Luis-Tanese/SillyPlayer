#include "sync.h"
#include "contenthash.h"
#include "third_party/qrcodegen.hpp"
#include <QClipboard>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QElapsedTimer>
#include <memory>
#include <QGuiApplication>
#include <QHostInfo>
#include <QJsonArray>
#include <QJsonObject>
#include <QSqlQuery>
#include <QSqlError>
#include <QJsonDocument>
#include <QNetworkInterface>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSslCertificate>
#include <QSslKey>
#include <QSslSocket>
#include <QUrl>
#include <QUrlQuery>
#include <QUuid>
#include <QThread>
#include <QThreadPool>
#include <QtConcurrent>
#include <algorithm>
#include <climits>
#include <cmath>
#include <cstring>
#include <openssl/crypto.h>
#include <openssl/ec.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <stdexcept>

namespace {
QByteArray json(const QVariant &value) {
  if (!value.isValid() || value.isNull())
    return "null";
  return QJsonDocument::fromVariant(value).toJson(QJsonDocument::Compact);
}
QVariant decode(const QByteArray &data) {
  return QJsonDocument::fromJson(data).toVariant();
}
QString randomToken() {
  QByteArray bytes(32, Qt::Uninitialized);
  for (int i = 0; i < 32; i += 4) {
    auto n = QRandomGenerator::system()->generate();
    memcpy(bytes.data() + i, &n, 4);
  }
  return bytes.toBase64(QByteArray::Base64UrlEncoding |
                        QByteArray::OmitTrailingEquals);
}
QString hashToken(const QString &value) {
  return QString::fromLatin1(
      QCryptographicHash::hash(value.toUtf8(), QCryptographicHash::Sha256)
          .toHex());
}
QString hashFile(const QString &path) {
  QFile file(path);
  if (!file.open(QIODevice::ReadOnly))
    throw std::runtime_error("Could not read a shared media file");
  ContentHash h;
  if (!h.addData(&file))
    throw std::runtime_error("File hashing failed");
  return QString::fromLatin1(h.result().toHex());
}
bool validHash(const QString &hash) {
  return QRegularExpression("^[a-f0-9]{64}$").match(hash).hasMatch();
}
QString filePath(QString path) {
  return QUrl(path).isLocalFile() ? QUrl(path).toLocalFile() : path;
}
QString uuid() { return QUuid::createUuid().toString(QUuid::WithoutBraces); }
} // namespace
SyncManager::SyncManager(Store *store, QObject *parent, quint16 port)
    : QObject(parent), s(store), server(this), discovery(this), listenPort(port) {
  connect(&discovery, &QUdpSocket::readyRead, this, &SyncManager::handleDiscoveryDatagram);
  if (!s->exec("CREATE TABLE IF NOT EXISTS sync_device_keys(key TEXT PRIMARY KEY,peer TEXT NOT NULL UNIQUE)"))
    throw std::runtime_error("Cannot initialize sync device identities");
  if (!s->exec("CREATE INDEX IF NOT EXISTS sync_blob_path ON sync_blobs(path,mtime)") ||
      !s->exec("CREATE INDEX IF NOT EXISTS sync_audio_hash ON sync_entities(json_extract(payload,'$.audioHash')) WHERE entity='tracks' AND payload<>'null'"))
    throw std::runtime_error("Cannot initialize sync lookup indexes");
  stateDir = QFileInfo(s->db().databaseName()).absolutePath() + "/sync";
  QDir().mkpath(stateDir + "/incoming");
  certPath = stateDir + "/certificate.pem";
  keyPath = stateDir + "/private-key.pem";
  server.accept = [this](qintptr fd) { accept(fd); };
  debounce.setInterval(1000);
  debounce.setSingleShot(true);
  connect(&debounce, &QTimer::timeout, this, &SyncManager::prepare);
  expiry.setInterval(5000);
  connect(&expiry, &QTimer::timeout, this, [this] {
    if (pairUntil > 0 && pairUntil < QDateTime::currentMSecsSinceEpoch()) {
      pairToken.clear();
      pairUntil = 0;
      link.clear();
      qr.clear();
      emit changed();
    }
  });
  expiry.start();
  connect(&worker, &QFutureWatcher<QVariantMap>::finished, this, [this] {
    if (closing || !worker.future().isValid())
      return;
    const auto result = worker.result();
    worker.setFuture({});
    preparing = false;
    if (result.contains("error")) {
      message = result["error"].toString();
      emit changed();
      return;
    }
    if (!s->db().transaction()) {
      message = "Cannot update sync journal";
      emit changed();
      return;
    }
    bool ok = true;
    try {
      for (auto value : result["blobs"].toList()) {
        const auto b = value.toMap();
        ok = s->exec(
                 "INSERT INTO sync_blobs(hash,path,size,mtime,kind) "
                 "VALUES(?,?,?,?,?) ON CONFLICT(hash) DO UPDATE SET "
                 "path=excluded.path,size=excluded.size,mtime=excluded.mtime",
                 {b["hash"], b["path"], b["size"], b["mtime"], b["kind"]}) &&
             ok;
      }
      for (auto value : result["items"].toList()) {
        const auto item = value.toMap();
        const auto dirty =
            s->rows(
                 "SELECT generation FROM sync_dirty WHERE entity=? AND key=?",
                 {item["entity"], item["key"]})
                .value(0)
                .toMap();
        if (dirty["generation"] != item["generation"])
          continue;
        auto payload = item["payload"];
        if (item["entity"] == "tracks" && payload.isValid()) {
          auto p = payload.toMap();
          const auto pending = s->rows("SELECT desired,actual FROM "
                                       "sync_media_pending WHERE track=?",
                                       {item["key"]})
                                   .value(0)
                                   .toMap();
          auto hash = item["audioHash"].toString();
          if (!pending.isEmpty()) {
            if (hash == pending["actual"].toString()) {
              hash = pending["desired"].toString();
              p["audioSize"] =
                  decode(s->rows("SELECT payload FROM sync_entities WHERE "
                                 "entity='tracks' AND key=?",
                                 {item["key"]})
                             .value(0)
                             .toMap()["payload"]
                             .toByteArray())
                      .toMap()
                      .value("audioSize", p["audioSize"]);
            } else
              s->exec("DELETE FROM sync_media_pending WHERE track=?",
                      {item["key"]});
          }
          p["audioHash"] = hash;
          p["artHash"] = item["artHash"];
          payload = p;
        }
        commitEntity(item["entity"].toString(), item["key"].toString(), payload,
                     s->setting("libraryIdentity").toString());
        ok = s->exec("DELETE FROM sync_dirty WHERE entity=? AND key=? AND "
                     "generation=?",
                     {item["entity"], item["key"], item["generation"]}) &&
             ok;
      }
    } catch (const std::exception &e) {
      ok = false;
      message = QString::fromUtf8(e.what());
    }
    if (ok)
      ok = s->db().commit();
    if (!ok)
      s->db().rollback();
    const qint64 remaining = ok ? s->rows("SELECT COUNT(*) AS n FROM sync_dirty").value(0).toMap()["n"].toLongLong() : 0;
    const bool more = ok && remaining > 0;
    preparing = more;
    if (more) {
      if (prepareTotal < remaining) prepareTotal = remaining;
      const qint64 done = qMax<qint64>(0, prepareTotal - remaining);
      progressValue = prepareTotal > 0 ? qBound(0.0, double(done) / double(prepareTotal), 1.0) : -1;
      message = QString("Preparing library for sync (%1/%2)").arg(done).arg(prepareTotal);
    } else {
      prepareTotal = 0;
      progressValue = -1;
      message = ok ? "Ready to sync" : "Could not save sync journal";
    }
    emit changed();
    if (more) QTimer::singleShot(0, this, [this] { preparing = false; prepare(); });
  });
  if (s->setting("syncEnabled", false).toBool())
    QTimer::singleShot(0, this, [this] { setEnabled(true); });
}
SyncManager::~SyncManager() { shutdown(); }
QStringList SyncManager::addresses() const {
  QStringList result;
  for (auto address : QNetworkInterface::allAddresses())
    if (address.protocol() == QAbstractSocket::IPv4Protocol &&
        !address.isLoopback())
      result << address.toString();
  if (result.isEmpty())
    result << "127.0.0.1";
  return result;
}
QVariantList SyncManager::devices() const {
  return s->rows("SELECT id,name,lastSeen,ack FROM sync_peers ORDER BY name");
}
QVariantList SyncManager::conflicts() const {
  auto rows = s->rows("SELECT id,peer,entity,key,fields,local,remote,revision "
                      "FROM sync_conflicts ORDER BY rowid");
  for (auto &v : rows) {
    auto c = v.toMap();
    if (c["entity"] == "orders") {
      c["label"] =
          s->rows("SELECT name FROM collections WHERE id=?", {c["key"]})
              .value(0)
              .toMap()
              .value("name", "Playlist order");
      for (const auto &side : QStringList{"local", "remote"}) {
        QStringList titles;
        for (const auto &id :
             decode(c[side].toByteArray()).toMap()["entries"].toList())
          titles << s->rows("SELECT t.title FROM members m JOIN tracks t ON "
                            "t.id=m.track WHERE m.id=?",
                            {id})
                        .value(0)
                        .toMap()
                        .value("title", "Removed song")
                        .toString();
        c[side + "Order"] = titles;
      }
    }
    v = c;
  }
  return rows;
}
void SyncManager::initializeTls() {
  if (!QFileInfo::exists(certPath) || !QFileInfo::exists(keyPath)) {
    EVP_PKEY_CTX *context = EVP_PKEY_CTX_new_id(EVP_PKEY_EC, nullptr);
    EVP_PKEY *key = nullptr;
    X509 *certificate = X509_new();
    bool ok = context && certificate && EVP_PKEY_keygen_init(context) > 0 &&
              EVP_PKEY_CTX_set_ec_paramgen_curve_nid(
                  context, NID_X9_62_prime256v1) > 0 &&
              EVP_PKEY_keygen(context, &key) > 0;
    if (ok) {
      X509_set_version(certificate, 2);
      ASN1_INTEGER_set(X509_get_serialNumber(certificate),
                       QRandomGenerator::system()->bounded(1, INT_MAX));
      X509_gmtime_adj(X509_get_notBefore(certificate), -3600);
      X509_gmtime_adj(X509_get_notAfter(certificate), 10L * 365 * 86400);
      X509_set_pubkey(certificate, key);
      auto name = X509_get_subject_name(certificate);
      X509_NAME_add_entry_by_txt(
          name, "CN", MBSTRING_ASC,
          reinterpret_cast<const unsigned char *>("SillyPlayer"), -1, -1, 0);
      X509_set_issuer_name(certificate, name);
      ok = X509_sign(certificate, key, EVP_sha256()) > 0;
    }
    BIO *cert = BIO_new(BIO_s_mem()), *privateKey = BIO_new(BIO_s_mem());
    if (ok)
      ok = PEM_write_bio_X509(cert, certificate) &&
           PEM_write_bio_PrivateKey(privateKey, key, nullptr, nullptr, 0,
                                    nullptr, nullptr);
    auto write = [&](BIO *bio, const QString &path) {
      char *data = nullptr;
      const auto size = BIO_get_mem_data(bio, &data);
      QSaveFile out(path);
      if (!out.open(QIODevice::WriteOnly))
        return false;
      out.setPermissions(QFile::ReadOwner | QFile::WriteOwner);
      return out.write(data, size) == size && out.commit();
    };
    if (ok)
      ok = write(cert, certPath) && write(privateKey, keyPath);
    BIO_free(cert);
    BIO_free(privateKey);
    X509_free(certificate);
    EVP_PKEY_free(key);
    EVP_PKEY_CTX_free(context);
    if (!ok)
      throw std::runtime_error("Could not create sync certificate");
  }
  QFile cert(certPath), key(keyPath);
  if (!cert.open(QIODevice::ReadOnly) || !key.open(QIODevice::ReadOnly))
    throw std::runtime_error("Cannot read sync identity");
  QSslCertificate certificate(cert.readAll());
  QSslKey privateKey(key.readAll(), QSsl::Ec);
  if (certificate.isNull() || privateKey.isNull())
    throw std::runtime_error("Invalid sync identity");
  tls = QSslConfiguration::defaultConfiguration();
  tls.setLocalCertificate(certificate);
  tls.setPrivateKey(privateKey);
  tls.setProtocol(QSsl::TlsV1_2OrLater);
  tls.setPeerVerifyMode(QSslSocket::VerifyNone);
  fingerprint = QString::fromLatin1(
      certificate.digest(QCryptographicHash::Sha256).toHex());
}
void SyncManager::setEnabled(bool value) {
  if (closing)
    return;
  if (value && !server.isListening()) {
    try {
      initializeTls();
      if (!server.listen(QHostAddress::AnyIPv4, listenPort))
        throw std::runtime_error(server.errorString().toStdString());
      startDiscovery();
      message = "Ready to pair / sync";
      prepare();
    } catch (const std::exception &e) {
      message = QString::fromUtf8(e.what());
      emit error(message);
    }
  } else if (!value) {
    stopDiscovery();
    server.close();
    for (auto socket : QSet<QSslSocket *>(sockets))
      socket->disconnectFromHost();
    pairToken.clear();
    link.clear();
    qr.clear();
    message = "Sync is off";
  }
  s->setSetting("syncEnabled", enabled());
  emit changed();
}
void SyncManager::pair(const QString &address, const QString &replacement) {
  if (!enabled())
    setEnabled(true);
  if (!enabled())
    return;
  if (!addresses().contains(address))
    return;
  if (!replacement.isEmpty() && s->rows("SELECT id FROM sync_peers WHERE id=?", {replacement}).isEmpty()) return;
  pairReplacement = replacement;
  pairToken = randomToken();
  pairUntil = QDateTime::currentMSecsSinceEpoch() + 300000;
  QUrl url;
  url.setScheme("sillyplayer");
  url.setHost("pair");
  QUrlQuery query;
  query.addQueryItem("v", "1");
  query.addQueryItem("host",
                     address + ":" + QString::number(server.serverPort()));
  query.addQueryItem("fp", fingerprint);
  query.addQueryItem("token", pairToken);
  url.setQuery(query);
  link = url.toString(QUrl::FullyEncoded);
  const auto code = qrcodegen::QrCode::encodeText(link.toUtf8().constData(),
                                                  qrcodegen::QrCode::Ecc::LOW);
  QString svg =
      QString(
          "<svg xmlns='http://www.w3.org/2000/svg' viewBox='-4 -4 %1 %1'><path "
          "fill='white' d='M-4-4h%1v%1H-4z'/><path fill='black' d='")
          .arg(code.getSize() + 8);
  for (int y = 0; y < code.getSize(); ++y)
    for (int x = 0; x < code.getSize(); ++x)
      if (code.getModule(x, y))
        svg += QString("M%1,%2h1v1h-1z").arg(x).arg(y);
  svg += "'/></svg>";
  qr = "data:image/svg+xml;base64," + svg.toUtf8().toBase64();
  emit changed();
}
void SyncManager::copyLink() { QGuiApplication::clipboard()->setText(link); }
void SyncManager::revoke(const QString &id) {
  s->exec("DELETE FROM sync_device_keys WHERE peer=?", {id});
  s->exec("DELETE FROM sync_peers WHERE id=?", {id});
  s->exec("DELETE FROM sync_conflicts WHERE peer=?", {id});
  emit changed();
}
void SyncManager::markDirty() {
  if (enabled() && !debounce.isActive())
    debounce.start();
}
QVariantMap SyncManager::wire(const QString &entity, const QString &key,
                              const QVariantMap &) const {
  if (entity == "tracks") {
    auto t =
        s->rows(
             "SELECT * FROM tracks WHERE id=? AND deleted=0 AND mergedInto=''",
             {key})
            .value(0)
            .toMap();
    if (t.isEmpty())
      return {};
    QVariantMap p;
    p["audioSize"] = QFileInfo(t["path"].toString()).size();
    for (const auto &field :
         {"id", "title", "artist", "album", "albumArtist", "genre", "year",
          "number", "duration", "speed", "volumePercent", "sourceUrl",
          "uploader", "videoTitle", "sourceProvider", "sourceItemId", "added"})
      p[field] =
          t[field].isNull()
              ? ((QStringList{"year", "number", "duration", "added"}.contains(
                     field))
                     ? QVariant(0)
                     : (QString(field) == "speed" ? QVariant(1)
                        : QString(field) == "volumePercent"
                            ? QVariant(100)
                            : QVariant(QString(""))))
              : t[field];
    p["nightcore"] = t["nightcore"].toBool();
    p["fileName"] = QFileInfo(t["path"].toString()).fileName();
    if (p["fileName"].toString().isEmpty() ||
        t["path"].toString().startsWith("sync://"))
      p["fileName"] = key + ".mp3";
    return p;
  }
  if (entity == "collections") {
    auto c =
        s->rows("SELECT id,name,kind,art FROM collections WHERE id=?", {key})
            .value(0)
            .toMap();
    if (c.isEmpty())
      return {};
    c.remove("art");
    return c;
  }
  if (entity == "orders") {
    if (s->rows("SELECT id FROM collections WHERE id=?", {key}).isEmpty())
      return {};
    QVariantList entries;
    for (const auto &v : s->rows(
             "SELECT id FROM members WHERE collection=? ORDER BY position,id",
             {key}))
      entries << v.toMap()["id"];
    return {{"id", key}, {"entries", entries}};
  }
  if (entity == "members")
    return s->rows("SELECT id,collection,track FROM members WHERE id=?", {key})
        .value(0)
        .toMap();
  if (entity == "likes")
    return s->rows("SELECT track FROM likes WHERE track=?", {key})
        .value(0)
        .toMap();
  if (entity == "counters")
    return s
        ->rows("SELECT device,component,track,milliseconds,plays,lastPlayed "
               "FROM sync_counters WHERE device||':'||component=?",
               {key})
        .value(0)
        .toMap();
  if (entity == "plays") {
    auto p = s->rows("SELECT session,track,playedAt FROM listening_plays WHERE "
                     "session=?",
                     {key})
                 .value(0)
                 .toMap();
    if (!p.isEmpty()) {
      p["qualified"] = true;
      p["heardMs"] = 0;
    }
    return p;
  }
  return {};
}
void SyncManager::prepare() {
  if (closing || preparing || exchanging || resolving || maintenance())
    return;
  // repair uploads recorded by older builds which kept an already verified file unavailable
  for (const auto &value : s->rows("SELECT t.id,b.path,b.size,b.mtime FROM tracks t JOIN sync_entities e ON e.entity='tracks' AND e.key=t.id JOIN sync_blobs b ON b.hash=json_extract(e.payload,'$.audioHash') AND b.kind='audio' WHERE t.available=0 AND t.deleted=0 AND t.path=b.path")) {
    const auto row=value.toMap(); const QFileInfo file(row["path"].toString());
    if (file.isFile() && file.size()==row["size"].toLongLong() && file.lastModified().toMSecsSinceEpoch()==row["mtime"].toLongLong())
      s->exec("UPDATE tracks SET available=1 WHERE id=?", {row["id"]});
  }
  auto dirty = s->rows("SELECT * FROM sync_dirty ORDER BY CASE entity WHEN "
                       "'tracks' THEN 0 WHEN 'collections' THEN 1 WHEN "
                       "'members' THEN 2 WHEN 'orders' THEN 3 ELSE 4 END,key LIMIT 100");
  if (dirty.isEmpty()) {
    prepareTotal = 0;
    progressValue = -1;
    message = "Ready to sync";
    emit changed();
    return;
  }
  const qint64 remaining = s->rows("SELECT COUNT(*) AS n FROM sync_dirty").value(0).toMap()["n"].toLongLong();
  if (prepareTotal <= 0 || remaining > prepareTotal) prepareTotal = remaining;
  const qint64 done = qMax<qint64>(0, prepareTotal - remaining);
  preparing = true;
  progressValue = prepareTotal > 0 ? qBound(0.0, double(done) / double(prepareTotal), 1.0) : -1;
  message = QString("Preparing library for sync (%1/%2)").arg(done).arg(prepareTotal);
  emit changed();
  QVariantList items;
  for (auto value : dirty) {
    auto item = value.toMap();
    const auto entity = item["entity"].toString(), key = item["key"].toString();
    auto payload = wire(entity, key);
    item["payload"] = payload.isEmpty() ? QVariant() : QVariant(payload);
    if (entity == "tracks") {
      auto t = s->rows("SELECT path,art FROM tracks WHERE id=?", {key})
                   .value(0)
                   .toMap();
      item["path"] = t["path"];
      item["art"] = t["art"];
      auto old = s->rows("SELECT payload FROM sync_entities WHERE "
                         "entity='tracks' AND key=?",
                         {key})
                     .value(0)
                     .toMap();
      item["previous"] = decode(old["payload"].toByteArray());
    } else if (entity == "collections") {
      item["art"] = s->rows("SELECT art FROM collections WHERE id=?", {key})
                        .value(0)
                        .toMap()["art"];
      item["previous"] = decode(s->rows("SELECT payload FROM sync_entities "
                                        "WHERE entity='collections' AND key=?",
                                        {key})
                                    .value(0)
                                    .toMap()["payload"]
                                    .toByteArray());
    }
    items << item;
  }
  // only this bounded batch's paths are needed, retaining every historical blob on each pass made preparation memory grow with the whole library
  QSet<QString> paths;
  for (const auto &value : items) {
    const auto item = value.toMap();
    if (!item["path"].toString().isEmpty()) paths.insert(item["path"].toString());
    const auto art = filePath(item["art"].toString());
    if (!art.isEmpty()) paths.insert(art);
  }
  QVariantMap byPath;
  for (const auto &path : paths) {
    const auto row = s->rows("SELECT * FROM sync_blobs WHERE path=? ORDER BY mtime DESC LIMIT 1", {path}).value(0);
    if (row.isValid()) byPath[path] = row;
  }
  worker.setFuture(QtConcurrent::run([items, byPath] {
    QVariantList result, blobs;
    try {
      // collect unique uncached files to hash in parallel across a bounded thread pool
      struct HashJob { QString path; qint64 size; qint64 mtime; };
      struct HashResult { QString path; QString hash; qint64 size; qint64 mtime; QString error; };
      QHash<QString, HashJob> pendingHashes;
      QHash<QString, QVariantMap> cachedHashes;
      for (const auto &v : items) {
        const auto item = v.toMap();
        for (const auto &field : {"path", "art"}) {
          const QString path = filePath(item[field].toString());
          if (path.isEmpty() || cachedHashes.contains(path) || pendingHashes.contains(path))
            continue;
          const QFileInfo fi(path);
          if (!fi.isFile())
            continue;
          const auto cached = byPath[path].toMap();
          if (cached["size"].toLongLong() == fi.size() &&
              cached["mtime"].toLongLong() == fi.lastModified().toMSecsSinceEpoch()) {
            cachedHashes.insert(path, {{"hash", cached["hash"].toString()},
                                       {"size", fi.size()},
                                       {"mtime", fi.lastModified().toMSecsSinceEpoch()}});
          } else {
            pendingHashes.insert(path, {path, fi.size(), fi.lastModified().toMSecsSinceEpoch()});
          }
        }
      }
      if (!pendingHashes.isEmpty()) {
        QList<HashJob> jobs = pendingHashes.values();
        QThreadPool pool;
        pool.setMaxThreadCount(qBound(2, QThread::idealThreadCount(), 6));
        const QList<HashResult> computed = QtConcurrent::blockingMapped(
            &pool, jobs, [](const HashJob &job) -> HashResult {
              try {
                const QString hash = hashFile(job.path);
                const QFileInfo after(job.path);
                if (!after.isFile() || after.size() != job.size ||
                    after.lastModified().toMSecsSinceEpoch() != job.mtime)
                  return {job.path, {}, job.size, job.mtime,
                          "A file changed while preparing sync; retry"};
                return {job.path, hash, job.size, job.mtime, {}};
              } catch (const std::exception &e) {
                return {job.path, {}, job.size, job.mtime, QString::fromUtf8(e.what())};
              }
            });
        for (const auto &r : computed) {
          if (!r.error.isEmpty())
            throw std::runtime_error(r.error.toStdString());
          cachedHashes.insert(r.path, {{"hash", r.hash}, {"size", r.size}, {"mtime", r.mtime}});
        }
      }
      QSet<QString> emittedBlobs;
      for (auto v : items) {
        auto item = v.toMap();
        auto payload = item["payload"].toMap();
        for (const auto &field : {"path", "art"}) {
          const QString path = filePath(item[field].toString());
          QString hash;
          if (cachedHashes.contains(path)) {
            const auto entry = cachedHashes.value(path);
            hash = entry["hash"].toString();
            const QString kind = field == QString("path") ? "audio" : "art";
            const QString blobKey = kind + ":" + hash;
            if (!emittedBlobs.contains(blobKey)) {
              emittedBlobs.insert(blobKey);
              blobs << QVariantMap{
                  {"hash", hash},
                  {"path", path},
                  {"size", entry["size"]},
                  {"mtime", entry["mtime"]},
                  {"kind", kind}};
            }
          } else {
            hash =
                item["previous"]
                    .toMap()
                    .value(field == QString("path") ? "audioHash" : "artHash",
                           "")
                    .toString();
          }
          if (field == QString("path"))
            item["audioHash"] = hash;
          else {
            item["artHash"] = hash;
            if (item["entity"] == "collections" && !payload.isEmpty())
              payload["artHash"] = hash;
          }
        }
        if (item["entity"] == "collections" && !payload.isEmpty())
          item["payload"] = payload;
        result << item;
      }
      return QVariantMap{{"items", result}, {"blobs", blobs}};
    } catch (const std::exception &e) {
      return QVariantMap{{"error", QString::fromUtf8(e.what())}};
    }
  }));
}
qint64 SyncManager::commitEntity(const QString &entity, const QString &key,
                                 const QVariant &payload,
                                 const QString &author, bool force) {
  auto previous = s->rows("SELECT revision,payload FROM sync_entities WHERE "
                          "entity=? AND key=?",
                          {entity, key})
                      .value(0)
                      .toMap();
  const auto encoded = json(payload);
  if (!force && previous["payload"].toByteArray() == encoded)
    return previous["revision"].toLongLong();
  if (!s->exec(
          "INSERT INTO sync_changes(entity,key,payload,author) VALUES(?,?,?,?)",
          {entity, key, QString::fromUtf8(encoded), author}))
    throw std::runtime_error(s->error().toStdString());
  const auto revision = s->rows("SELECT last_insert_rowid() AS id")
                            .first()
                            .toMap()["id"]
                            .toLongLong();
  if (!s->exec("INSERT INTO sync_entities VALUES(?,?,?,?,?) ON "
               "CONFLICT(entity,key) DO UPDATE SET "
               "revision=excluded.revision,payload=excluded.payload,author="
               "excluded.author",
               {entity, key, revision, QString::fromUtf8(encoded), author}))
    throw std::runtime_error(s->error().toStdString());
  // pending choices stay aligned with the latest desktop snapshot
  // callers pass the displayed revision when resolving, so stale clicks cannot overwrite it
  s->exec("UPDATE sync_conflicts SET local=?,revision=? WHERE entity=? AND key=?",
          {QString::fromUtf8(encoded), revision, entity, key});
  return revision;
}
QVariantMap SyncManager::changes(qint64 cursor, int limit) const {
  // stream the journal instead of first retaining hundreds of potentially large playlist-order payloads
  // the cursor advances only for rows in this page
  QSqlQuery query(s->db());
  query.setForwardOnly(true);
  query.prepare("SELECT revision,entity,key,payload FROM sync_changes WHERE revision>? ORDER BY revision LIMIT ?");
  query.addBindValue(cursor);
  query.addBindValue(qBound(1, limit, 500) + 1);
  if (!query.exec()) return {{"error", "Cannot read sync journal"}};
  QVariantList rows;
  qint64 last = cursor, bytes = 0;
  bool more = false;
  while (query.next()) {
    const auto payload = query.value(3).toByteArray();
    const qint64 rowBytes = payload.size() + query.value(1).toString().size() + query.value(2).toString().size() + 128;
    if (rows.size() >= qBound(1, limit, 500) || (!rows.isEmpty() && bytes + rowBytes > 1024 * 1024)) {
      more = true;
      break;
    }
    last = query.value(0).toLongLong();
    rows << QVariantMap{{"revision", last}, {"entity", query.value(1)},
                        {"key", query.value(2)}, {"payload", decode(payload)}};
    bytes += rowBytes;
  }
  const qint64 latest = s->rows("SELECT COALESCE(MAX(revision),0) AS r FROM sync_changes").value(0).toMap()["r"].toLongLong();
  return {{"changes", rows}, {"cursor", last}, {"latest", latest}, {"more", more},
          {"deviceId", s->setting("libraryIdentity")}};
}

QString SyncManager::canonicalKey(const QString &peer, const QString &entity,
                                  const QString &key) const {
  return s
      ->rows("SELECT canonical FROM sync_aliases WHERE peer=? AND entity=? AND "
             "remote=?",
             {peer, entity, key})
      .value(0)
      .toMap()
      .value("canonical", key)
      .toString();
}
QVariantMap SyncManager::rewrite(const QString &peer, const QString &entity,
                                 QVariantMap payload) const {
  if (entity == "members") {
    payload.remove("position");
    payload["collection"] =
        canonicalKey(peer, "collections", payload["collection"].toString());
    payload["track"] =
        canonicalKey(peer, "tracks", payload["track"].toString());
  }
  if (entity == "orders") {
    payload["id"] = canonicalKey(peer, "collections", payload["id"].toString());
    QVariantList entries;
    for (const auto &v : payload["entries"].toList())
      entries << canonicalKey(peer, "members", v.toString());
    payload["entries"] = entries;
  }
  if (entity == "counters" && !payload.contains("component"))
    payload["component"] = payload["track"];
  if (entity == "likes" || entity == "plays" || entity == "counters")
    payload["track"] =
        canonicalKey(peer, "tracks", payload["track"].toString());
  return payload;
}
bool SyncManager::apply(const QString &entity, const QString &key,
                        const QVariant &value, QString *why) {
  auto reject = [&](const QString &message) {
    *why = message;
    return false;
  };
  const auto p = value.toMap();
  const bool remove = !value.isValid() || value.isNull();
  if (entity == "tracks") {
    if (remove) {
      s->exec("DELETE FROM sync_media_pending WHERE track=?", {key});
      return s->exec(
          "UPDATE tracks SET deleted=1,available=0,mergedInto='sync-deleted' "
          "WHERE id=?",
          {key});
    }
    bool speedOk = false, volumeOk = false;
    const double speed = p.value("speed", 1).toDouble(&speedOk),
                 volume = p.value("volumePercent", 100).toDouble(&volumeOk);
    if (!speedOk || !volumeOk || !std::isfinite(speed) || speed < .5 ||
        speed > 2 || !std::isfinite(volume) || volume < 0 || volume > 300)
      return reject("Invalid playback modifiers");
    for (const auto &field : {"audioHash", "artHash"})
      if (!p.value(field).toString().isEmpty() &&
          !validHash(p[field].toString()))
        return reject("Invalid content hash");
    auto old =
        s->rows("SELECT * FROM tracks WHERE id=?", {key}).value(0).toMap();
    if (old.isEmpty() &&
        !s->exec("INSERT INTO tracks(id,path,added,available,managed) "
                 "VALUES(?,?,?,0,1)",
                 {key, "sync://" + key, QDateTime::currentMSecsSinceEpoch()}))
      return reject(s->error());
    QStringList sets;
    QVariantList args;
    const auto hash = p.value("audioHash").toString();
    const auto blob =
        s->rows("SELECT * FROM sync_blobs WHERE hash=? AND kind='audio'",
                {hash})
            .value(0)
            .toMap();
    QFileInfo blobFile(blob["path"].toString());
    const bool ready = blobFile.isFile() &&
                       blobFile.size() == blob["size"].toLongLong() &&
                       blobFile.lastModified().toMSecsSinceEpoch() ==
                           blob["mtime"].toLongLong();
    const auto oldWire = s->rows("SELECT payload FROM sync_entities WHERE "
                                 "entity='tracks' AND key=?",
                                 {key})
                             .value(0)
                             .toMap();
    const auto oldHash = decode(oldWire["payload"].toByteArray())
                             .toMap()["audioHash"]
                             .toString();
    const auto waiting =
        s->rows("SELECT actual FROM sync_media_pending WHERE track=?", {key})
            .value(0)
            .toMap();
    const auto actual = waiting.value("actual", oldHash).toString();
    if (QFileInfo(old["path"].toString()).isFile() && hash != actual && !ready)
      s->exec("INSERT INTO sync_media_pending VALUES(?,?,?) ON CONFLICT(track) "
              "DO UPDATE SET desired=excluded.desired",
              {key, hash, actual});
    else
      s->exec("DELETE FROM sync_media_pending WHERE track=?", {key});
    for (const auto &field :
         {"title", "artist", "album", "albumArtist", "genre", "sourceUrl",
          "uploader", "videoTitle", "sourceProvider", "sourceItemId"}) {
      const auto text = p.value(field, QString("")).toString();
      if (text.size() > 16384)
        return reject("Metadata field is too long");
      sets << QString(field) + "=?";
      args << text;
    }
    for (const auto &field : {"year", "number", "duration"}) {
      sets << QString(field) + "=?";
      args << p.value(field, 0);
    }
    sets << "speed=?" << "nightcore=?" << "volumePercent=?" << "deleted=0"
         << "mergedInto=''";
    args << speed << p.value("nightcore", false).toBool() << volume;
    if (ready && (old["path"].toString().startsWith("sync://") ||
                  old.isEmpty() || hash != actual ||
                  old["path"].toString() == blob["path"].toString())) {
      sets << "path=?" << "available=1";
      args << blob["path"];
    }
    if (validHash(p["artHash"].toString())) {
      const auto art =
          s->rows("SELECT path FROM sync_blobs WHERE hash=?", {p["artHash"]})
              .value(0)
              .toMap()["path"]
              .toString();
      if (QFileInfo::exists(art)) {
        sets << "art=?";
        args << QUrl::fromLocalFile(art).toString();
      }
    }
    args << key;
    if (!s->exec("UPDATE tracks SET " + sets.join(',') + " WHERE id=?", args)) return reject(s->error());
    return true;
  }
  if (entity == "collections") {
    if (remove)
      return s->exec("DELETE FROM collections WHERE id=?", {key});
    if (!QStringList{"playlist", "custom"}.contains(p["kind"].toString()) ||
        p["name"].toString().trimmed().isEmpty())
      return reject("Invalid collection");
    auto cover =
        s->rows("SELECT path FROM sync_blobs WHERE hash=?", {p["artHash"]})
            .value(0)
            .toMap()["path"]
            .toString();
    if (!cover.isEmpty())
      cover = QUrl::fromLocalFile(cover).toString();
    return s->exec(
        "INSERT INTO collections VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
        "name=excluded.name,kind=excluded.kind,art=CASE WHEN excluded.art<>'' "
        "THEN excluded.art ELSE collections.art END",
        {key, p["name"], p["kind"], cover.isNull() ? QString("") : cover});
  }
  if (entity == "orders") {
    if (remove)
      return true;
    if (s->rows("SELECT id FROM collections WHERE id=?", {key}).isEmpty())
      return reject("Missing collection for order");
    auto requested = p["entries"].toList();
    if (requested.size() > 100000)
      return reject("Collection order is too large");
    const auto existing = s->rows("SELECT id,position FROM members WHERE collection=? ORDER BY position,id", {key});
    QHash<QString, int> positions;
    for (const auto &value : existing) {
      const auto row = value.toMap();
      positions.insert(row["id"].toString(), row["position"].toInt());
    }
    QSet<QString> seen;
    QStringList order;
    order.reserve(requested.size());
    for (const auto &v : requested) {
      const auto id = v.toString();
      if (id.isEmpty() || seen.contains(id))
        return reject("Invalid or duplicate collection occurrence");
      seen.insert(id);
      if (positions.contains(id)) order << id;
      else if (!s->rows("SELECT id FROM members WHERE id=?", {id}).isEmpty())
        return reject("Occurrence belongs to another collection");
    }
    for (const auto &value : existing) {
      const auto id = value.toMap()["id"].toString();
      if (!seen.contains(id)) order << id;
    }
    QSqlQuery update(s->db());
    if (!update.prepare("UPDATE members SET position=? WHERE id=?"))
      return reject(update.lastError().text());
    for (int i = 0; i < order.size(); ++i) {
      if (positions.value(order[i], -1) == i) continue;
      update.bindValue(0, i);
      update.bindValue(1, order[i]);
      if (!update.exec()) return reject(update.lastError().text());
    }
    return true;
  }
  if (entity == "members") {
    if (remove)
      return s->exec("DELETE FROM members WHERE id=?", {key});
    if (s->rows("SELECT id FROM tracks WHERE id=?", {p["track"]}).isEmpty() ||
        s->rows("SELECT id FROM collections WHERE id=?", {p["collection"]})
            .isEmpty())
      return reject("Missing collection or track dependency");
    return s->exec(
        "INSERT INTO members VALUES(?,?,?,(SELECT COALESCE(MAX(position),-1)+1 "
        "FROM members WHERE collection=?)) ON CONFLICT(id) DO UPDATE SET "
        "collection=excluded.collection,track=excluded.track",
        {key, p["collection"], p["track"], p["collection"]});
  }
  if (entity == "likes") {
    if (remove)
      return s->exec("DELETE FROM likes WHERE track=?", {key});
    return s->exec("INSERT OR IGNORE INTO likes VALUES(?)",
                   {p.value("track", key)});
  }
  if (entity == "counters") {
    if (remove)
      return true;
    const auto ms = p["milliseconds"].toLongLong(),
               plays = p["plays"].toLongLong();
    if (ms < 0 || plays < 0 || p["device"].toString().isEmpty())
      return reject("Invalid listening counters");
    return s->exec(
        "INSERT INTO sync_counters VALUES(?,?,?,?,?,?) ON "
        "CONFLICT(device,component) DO UPDATE SET "
        "track=excluded.track,milliseconds=MAX(milliseconds,excluded."
        "milliseconds),plays=MAX(plays,excluded.plays),lastPlayed=MAX("
        "lastPlayed,excluded.lastPlayed)",
        {p["device"], p.value("component", p.value("track", QString(""))),
         p.value("track", QString("")), ms, plays, p.value("lastPlayed", 0)});
  }
  if (entity == "plays") {
    if (remove)
      return true;
    if (s->rows("SELECT id FROM tracks WHERE id=?", {p["track"]}).isEmpty())
      return reject("Missing song for listening event");
    return s->exec("INSERT OR IGNORE INTO listening_plays VALUES(?,?,?)",
                   {key, p["track"], p["playedAt"]});
  }
  return reject("Unknown sync entity");
}
QVariantMap SyncManager::beginExchange() {
  if (maintenance())
    return {{"error", "Library maintenance is in progress"}, {"retry", true}};
  if (preparing || !s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty()) {
    prepare();
    return {{"error", "Preparing desktop library"}, {"retry", true}};
  }
  emit aboutToApply();
  // listening::flush may have created new local dirt, capture it before any remote merge
  for (auto value : s->rows(
           "SELECT * FROM sync_dirty WHERE entity IN ('counters','plays')")) {
    auto row = value.toMap();
    auto payload = wire(row["entity"].toString(), row["key"].toString());
    commitEntity(row["entity"].toString(), row["key"].toString(),
                 payload.isEmpty() ? QVariant() : QVariant(payload),
                 s->setting("libraryIdentity").toString());
    s->exec("DELETE FROM sync_dirty WHERE entity=? AND key=? AND generation=?",
            {row["entity"], row["key"], row["generation"]});
  }
  if (!s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty()) {
    prepare();
    return {{"error", "Preparing latest library changes"}, {"retry", true}};
  }
  return {};
}
QVariantMap SyncManager::exchange(const QString &peer, const QVariantList &operations) {
  if (operations.size() > 500) return {{"error", "Too many operations"}};
  if (exchanging || resolving) return {{"error", "Another sync request is in progress"}, {"retry", true}};
  const auto ready = beginExchange();
  if (!ready.isEmpty()) return ready;
  const auto response = applyOperations(peer, operations);
  finishExchange();
  return response;
}
QVariantMap SyncManager::applyOperations(const QString &peer, const QVariantList &operations) {
  QVariantList results, aliases;
  for (auto value : operations) {
    auto op = value.toMap();
    const QString opId = op["opId"].toString(),
                  entity = op["entity"].toString(),
                  remote = op["key"].toString();
    if (opId.isEmpty() || opId.size() > 200 || remote.isEmpty() ||
        remote.size() > 200 ||
        !QStringList{"tracks", "collections", "members", "orders", "likes",
                     "counters", "plays"}
             .contains(entity)) {
      results << QVariantMap{
          {"opId", opId}, {"status", "error"}, {"error", "Invalid operation"}};
      continue;
    }
    const auto previous = s->rows("SELECT response FROM sync_operations WHERE "
                                  "peer=? AND operation=?",
                                  {peer, opId})
                              .value(0)
                              .toMap();
    if (!previous.isEmpty()) {
      results << decode(previous["response"].toByteArray());
      continue;
    }
    if (!s->db().transaction())
      return {{"error", "Cannot start sync transaction"}};
    s->exec("UPDATE sync_control SET applying=1 WHERE id=1");
    try {
      QString key = canonicalKey(
          peer, entity == "orders" ? QString("collections") : entity, remote);
      QVariant payload = op["payload"];
      if (payload.isValid() && !payload.isNull())
        payload = rewrite(peer, entity, payload.toMap());
      auto incoming = payload.toMap();
      auto current =
          s->rows("SELECT * FROM sync_entities WHERE entity=? AND key=?",
                  {entity, key})
              .value(0)
              .toMap();
      // independently imported identical audio shares an identity, distinct colliding UUIDs are remapped
      if (op["base"].toLongLong() == 0 && entity == "tracks" &&
          !incoming.isEmpty()) {
        const auto hash = incoming["audioHash"].toString();
        auto same =
            validHash(hash)
                ? s->rows("SELECT key FROM sync_entities WHERE entity='tracks' "
                          "AND json_extract(payload,'$.audioHash')=? AND "
                          "payload<>'null' LIMIT 1",
                          {hash})
                      .value(0)
                      .toMap()
                : QVariantMap{};
        if (!same.isEmpty())
          key = same["key"].toString();
        else if (!current.isEmpty() && decode(current["payload"].toByteArray())
                                               .toMap()
                                               .value("audioHash")
                                               .toString() != hash)
          key = uuid();
      } else if (op["base"].toLongLong() == 0 && entity == "collections" &&
                 !current.isEmpty() && !incoming.isEmpty() &&
                 decode(current["payload"].toByteArray()).toMap() != incoming)
        key = uuid();
      if (op["base"].toLongLong() == 0 && entity == "members" &&
          !current.isEmpty() && !incoming.isEmpty() &&
          decode(current["payload"].toByteArray()).toMap() != incoming)
        key = uuid();
      if (key != remote) {
        s->exec("INSERT INTO sync_aliases VALUES(?,?,?,?) ON "
                "CONFLICT(peer,entity,remote) DO UPDATE SET "
                "canonical=excluded.canonical",
                {peer, entity, remote, key});
        aliases << QVariantMap{
            {"entity", entity}, {"remote", remote}, {"canonical", key}};
        if (!incoming.isEmpty() &&
            (entity == "tracks" || entity == "collections" ||
             entity == "members")) {
          incoming["id"] = key;
          payload = incoming;
        }
        current =
            s->rows("SELECT * FROM sync_entities WHERE entity=? AND key=?",
                    {entity, key})
                .value(0)
                .toMap();
      }
      if (entity == "likes") {
        key = canonicalKey(peer, "tracks", remote);
        if (!incoming.isEmpty()) {
          incoming["track"] = key;
          payload = incoming;
        }
      }
      if (entity == "counters" && !incoming.isEmpty()) {
        if (incoming["device"].toString() != peer)
          throw std::runtime_error("Cannot publish another device's counters");
        key = incoming["device"].toString() + ":" +
              incoming.value("component", incoming["track"]).toString();
      }
      if (entity == "plays" && !incoming.isEmpty()) {
        key = incoming.value("session", key).toString();
      }
      current = s->rows("SELECT * FROM sync_entities WHERE entity=? AND key=?",
                        {entity, key})
                    .value(0)
                    .toMap();
      const auto revision = current["revision"].toLongLong(),
                 base = op["base"].toLongLong();
      auto currentPayload = decode(current["payload"].toByteArray());
      auto baseline = decode(s->rows("SELECT payload FROM sync_changes WHERE "
                                     "revision=? AND entity=? AND key=?",
                                     {base, entity, key})
                                 .value(0)
                                 .toMap()["payload"]
                                 .toByteArray());
      QStringList conflicts;
      bool mergedOrder = false;
      if (entity == "orders" && !incoming.isEmpty() && revision != base &&
          current["author"].toString() != peer && !baseline.toMap().isEmpty()) {
        const auto old = baseline.toMap()["entries"].toList(),
                   now = currentPayload.toMap()["entries"].toList(),
                   sent = incoming["entries"].toList();
        auto keys = [](const QVariantList &values) {
          QSet<QString> result;
          for (const auto &value : values) result.insert(value.toString());
          return result;
        };
        const auto common = keys(old).intersect(keys(now)).intersect(keys(sent));
        auto shared = [&](const QVariantList &values) {
          QVariantList result;
          for (const auto &v : values)
            if (common.contains(v.toString())) result << v;
          return result;
        };
        const auto before = shared(old), desktop = shared(now),
                   phoneOrder = shared(sent);
        const bool movedHere = desktop != before,
                   movedThere = phoneOrder != before;
        if (!(movedHere && movedThere && desktop != phoneOrder)) {
          auto p = incoming;
          QVariantList merged = movedThere ? sent : now;
          auto present = keys(merged);
          for (const auto &v : (movedThere ? now : sent))
            if (!present.contains(v.toString())) {
              merged << v;
              present.insert(v.toString());
            }
          p["entries"] = merged;
          payload = p;
          mergedOrder = true;
        }
      }
      if (!mergedOrder && entity != "counters" && entity != "plays" &&
          json(payload) != json(currentPayload)) {
        if (payload.isNull() || !payload.isValid()) {
          if (currentPayload.isValid() &&
              (entity == "tracks" || revision != base))
            conflicts << "deletion";
        } else if (revision != base && current["author"].toString() != peer) {
          auto old = baseline.toMap(), now = currentPayload.toMap(),
               merged = now, desktopChoice = now;
          for (auto it = incoming.cbegin(); it != incoming.cend(); ++it) {
            if (it.value() == old.value(it.key()))
              continue;
            if (now.value(it.key()) != old.value(it.key()) &&
                now.value(it.key()) != it.value())
              conflicts << it.key();
            else
              desktopChoice[it.key()] = it.value();
            merged[it.key()] = it.value();
          }
          payload = merged;
          if (!conflicts.isEmpty())
            currentPayload = desktopChoice;
        }
      }
      QVariantMap response{{"opId", opId}, {"entity", entity}, {"key", key}};
      if (!conflicts.isEmpty()) {
        const auto id = uuid();
        s->exec("INSERT INTO sync_conflicts VALUES(?,?,?,?,?,?,?,?,?)",
                {id, peer, opId, entity, key,
                 QString::fromUtf8(json(currentPayload)),
                 QString::fromUtf8(json(payload)), revision,
                 QString::fromUtf8(json(conflicts))});
        response.insert("status", "conflict");
        response.insert("conflict", id);
        response.insert("local", currentPayload);
        response.insert("remote", payload);
        response.insert("revision", revision);
        response.insert("fields", conflicts);
      } else {
        QString why;
        if (!apply(entity, key, payload, &why))
          throw std::runtime_error(why.toStdString());
        if (entity == "counters" || entity == "orders")
          payload = wire(entity, key);
        if (entity == "plays" && !payload.toMap().isEmpty()) {
          auto p = payload.toMap();
          p["qualified"] = true;
          payload = p;
        }
        const auto next = commitEntity(entity, key, payload, peer);
        response.insert("status", "applied");
        response.insert("revision", next);
        response.insert("payload", payload);
      }
      s->exec("INSERT INTO sync_operations VALUES(?,?,?)",
              {peer, opId, QString::fromUtf8(json(response))});
      s->exec("UPDATE sync_control SET applying=0 WHERE id=1");
      if (!s->db().commit())
        throw std::runtime_error("Could not commit sync operation");
      results << response;
    } catch (const std::exception &e) {
      s->db().rollback();
      results << QVariantMap{{"opId", opId},
                             {"status", "error"},
                             {"error", QString::fromUtf8(e.what())}};
    }
  }
  return {{"results", results}, {"aliases", aliases}};
}
void SyncManager::finishExchange() {
  // counter maxima are authoritative, replaying an operation cannot increment them twice
  s->db().transaction();
  s->exec("UPDATE sync_control SET applying=1 WHERE id=1");
  s->exec("INSERT INTO track_listens(track,plays,lastPlayed) SELECT "
          "track,SUM(plays),MAX(lastPlayed) FROM sync_counters WHERE track<>'' "
          "AND track IN (SELECT id FROM tracks) GROUP BY track HAVING 1 ON "
          "CONFLICT(track) DO UPDATE SET "
          "plays=excluded.plays,lastPlayed=excluded.lastPlayed");
  s->exec("UPDATE listening_totals SET milliseconds=(SELECT "
          "COALESCE(SUM(milliseconds),0) FROM sync_counters) WHERE id=1");
  s->exec("UPDATE sync_control SET applying=0 WHERE id=1");
  s->db().commit();
  emit applied();
  emit changed();
}
// each operation commits before yielding, so painting, cancellation and local actions keep working without exposing a half-written SQLite transaction
void SyncManager::exchangeAsync(QSslSocket *socket, const QString &peer,
                                const QVariantList &operations) {
  if (operations.size() > 500) {
    reply(socket, 400, {{"error", "Too many operations"}}); return;
  }
  if (exchanging || resolving) {
    reply(socket, 503, {{"error", "Another sync request is in progress"}, {"retry", true}}); return;
  }
  const auto ready = beginExchange();
  if (!ready.isEmpty()) { reply(socket, ready.value("retry").toBool() ? 503 : 500, ready); return; }
  struct Batch { QVariantList operations, results, aliases; qsizetype next = 0; };
  auto batch = std::make_shared<Batch>();
  batch->operations = operations;
  auto timer = new QTimer(this);
  timer->setSingleShot(true);
  QPointer<QSslSocket> connection(socket);
  exchanging = true;
  emit changed();
  connect(timer, &QTimer::timeout, this, [this, timer, batch, connection, peer] {
    auto complete = [&](int code, const QVariantMap &response) {
      exchanging = false;
      timer->deleteLater();
      finishExchange();
      if (connection) reply(connection, code, response);
      if (!s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty())
        QTimer::singleShot(0, this, &SyncManager::prepare);
    };
    if (closing || !connection || connection->state() == QAbstractSocket::UnconnectedState) {
      complete(503, {{"error", "Sync connection closed"}, {"retry", true}}); return;
    }
    if (maintenance()) {
      complete(503, {{"error", "Library maintenance is in progress"}, {"retry", true}}); return;
    }
    if (!s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty()) {
      complete(503, {{"error", "Desktop library changed during sync"}, {"retry", true}}); return;
    }
    QElapsedTimer slice; slice.start();
    int processed = 0;
    while (batch->next < batch->operations.size()) {
      const auto response = applyOperations(peer, {batch->operations[batch->next++]});
      if (response.contains("error")) { complete(500, response); return; }
      batch->results += response.value("results").toList();
      batch->aliases += response.value("aliases").toList();
      if (++processed >= 8 || slice.elapsed() >= 5) break;
    }
    if (batch->next == batch->operations.size())
      complete(200, {{"results", batch->results}, {"aliases", batch->aliases}});
    else timer->start(0);
  });
  timer->start(0);
}
QVariantMap SyncManager::resolveConflict(const QString &id, bool usePhone,
    qint64 expectedRevision, const QString &expectedPhone, const QString &peer, bool tracksOnly) {
  auto outcome = [&](const QString &status, const QString &text = QString()) {
    return QVariantMap{{"id", id}, {"status", status}, {"message", text}};
  };
  const auto c = s->rows("SELECT * FROM sync_conflicts WHERE id=?", {id}).value(0).toMap();
  if (c.isEmpty()) return outcome("missing", "Choice was already resolved");
  if ((!peer.isEmpty() && c["peer"].toString() != peer) ||
      (tracksOnly && c["entity"].toString() != "tracks"))
    return outcome("skipped", "Only the selected phone's song choices are included");
  if (!s->rows("SELECT key FROM sync_dirty WHERE entity=? AND key=? LIMIT 1", {c["entity"], c["key"]}).isEmpty())
    return outcome("stale", "Library changed; review this choice again");
  const auto latest = s->rows("SELECT revision,payload FROM sync_entities WHERE entity=? AND key=?",
                              {c["entity"], c["key"]}).value(0).toMap();
  if (latest["revision"] != c["revision"] ||
      (expectedRevision >= 0 && expectedRevision != c["revision"].toLongLong()) ||
      (!expectedPhone.isEmpty() && expectedPhone != c["remote"].toString())) {
    s->exec("UPDATE sync_conflicts SET local=?,revision=? WHERE id=?",
            {latest["payload"], latest["revision"], id});
    return outcome("stale", "Library changed; review this choice again");
  }
  if (!s->db().transaction()) return outcome("error", "Cannot begin the library update");
  try {
    QString why;
    const auto payload = decode(c[usePhone ? "remote" : "local"].toByteArray());
    if (!s->exec("UPDATE sync_control SET applying=1 WHERE id=1") ||
        !apply(c["entity"].toString(), c["key"].toString(), payload, &why))
      throw std::runtime_error(why.isEmpty() ? "Cannot apply the library choice" : why.toStdString());
    commitEntity(c["entity"].toString(), c["key"].toString(), payload,
                 usePhone ? c["peer"].toString() : s->setting("libraryIdentity").toString(), true);
    if (!s->exec("DELETE FROM sync_conflicts WHERE id=?", {id}) ||
        !s->exec("UPDATE sync_control SET applying=0 WHERE id=1") || !s->db().commit())
      throw std::runtime_error("Cannot commit the library choice");
    return outcome("resolved");
  } catch (const std::exception &error) {
    s->db().rollback();
    return outcome("error", QString::fromUtf8(error.what()));
  }
}

void SyncManager::resolve(const QString &id, bool usePhone, qint64 expectedRevision, const QString &expectedPhone) {
  if (maintenance() || exchanging || resolving) { emit error("Wait for the current library operation to finish"); return; }
  if (preparing || !s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty()) {
    prepare(); emit error("Preparing the latest library changes; review this choice again shortly"); return;
  }
  emit aboutToApply();
  const auto result = resolveConflict(id, usePhone, expectedRevision, expectedPhone);
  if (result["status"] == "resolved") emit applied();
  else if (result["status"] != "missing") emit error(result["message"].toString());
  emit changed();
}

void SyncManager::resolveMany(const QVariantList &snapshots, bool usePhone) {
  if (snapshots.isEmpty()) return;
  if (maintenance() || busy()) { emit error("Wait for the current library operation to finish"); return; }
  if (!s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty()) {
    prepare(); emit error("Preparing the latest library changes; review these choices again shortly"); return;
  }
  const auto peer = snapshots.first().toMap()["peer"].toString();
  if (peer.isEmpty()) { emit error("Select song choices for one paired phone"); return; }
  startResolutions(snapshots, usePhone, peer);
}

void SyncManager::cancelResolutions() { resolutionCancelled = true; }

void SyncManager::startResolutions(const QVariantList &snapshots, bool usePhone,
                                   const QString &peer, QSslSocket *socket) {
  struct Batch { QVariantList rows, results; qsizetype next = 0; int resolved = 0, skipped = 0, failed = 0; QSet<QString> ids; QElapsedTimer progress; };
  auto batch = std::make_shared<Batch>();
  batch->rows = snapshots;
  batch->progress.start();
  QPointer<QSslSocket> connection(socket);
  const bool remote = socket != nullptr;
  auto timer = new QTimer(this);
  timer->setSingleShot(true);
  resolving = true; resolutionCancelled = false;
  progressValue = snapshots.isEmpty() ? 1.0 : 0.0;
  resolutionResult = {{"resolved", 0}, {"skipped", 0}, {"failed", 0}, {"total", snapshots.size()}, {"cancelled", false}, {"message", "Applying song choices"}};
  message = "Applying song choices";
  emit aboutToApply();
  emit resolutionProgressChanged();
  emit changed();
  connect(timer, &QTimer::timeout, this, [this, timer, batch, connection, peer, remote, usePhone] {
    const bool interrupted = closing || resolutionCancelled || maintenance() ||
        (remote && (!connection || connection->state() == QAbstractSocket::UnconnectedState));
    QElapsedTimer slice; slice.start();
    int processed = 0;
    if (!interrupted) while (batch->next < batch->rows.size()) {
      const auto row = batch->rows[batch->next++].toMap();
      const auto id = row["id"].toString();
      const auto phone = row.contains("phoneVersion") ? row["phoneVersion"].toString() : row["remote"].toString();
      QVariantMap result;
      if (id.isEmpty() || !row.contains("revision") || row["revision"].toLongLong() < 0 || phone.isEmpty() || batch->ids.contains(id))
        result = {{"id", id}, {"status", "skipped"}, {"message", "Missing or duplicate review baseline"}};
      else { batch->ids.insert(id); result = resolveConflict(id, usePhone, row["revision"].toLongLong(), phone, peer, true); }
      const auto status = result["status"].toString();
      if (status == "resolved") ++batch->resolved;
      else if (status == "error") ++batch->failed;
      else ++batch->skipped;
      batch->results << result;
      if (++processed >= 8 || slice.elapsed() >= 5) break;
    }
    const bool finished = interrupted || batch->next == batch->rows.size();
    resolutionResult = {{"resolved", batch->resolved}, {"skipped", batch->skipped}, {"failed", batch->failed},
        {"total", batch->rows.size()}, {"cancelled", interrupted},
        {"message", QString("%1 song choices applied · %2 skipped · %3 failed%4").arg(batch->resolved).arg(batch->skipped).arg(batch->failed).arg(interrupted ? " · remaining choices kept" : "")}};
    message = finished ? resolutionResult["message"].toString() : QString("Applying song choices · %1/%2").arg(batch->next).arg(batch->rows.size());
    if (finished) {
      resolving = false;
      progressValue = -1;
      timer->deleteLater();
      if (batch->resolved) emit applied();
      emit resolutionProgressChanged();
      emit changed();
      if (connection) reply(connection, 200, {{"results", batch->results}, {"summary", resolutionResult}});
      if (!s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty()) QTimer::singleShot(0, this, &SyncManager::prepare);
    } else {
      progressValue = batch->rows.isEmpty() ? 1.0 : qBound(0.0, double(batch->next) / double(batch->rows.size()), 1.0);
      if (batch->progress.elapsed() >= 100) {
        batch->progress.restart(); emit resolutionProgressChanged(); emit changed();
      }
      timer->start(0);
    }
  });
  timer->start(0);
}
void SyncManager::accept(qintptr descriptor) {
  if (sockets.size() >= 32) {
    QTcpSocket rejected;
    rejected.setSocketDescriptor(descriptor);
    rejected.abort();
    return;
  }
  auto socket = new QSslSocket(this);
  if (!socket->setSocketDescriptor(descriptor)) {
    socket->deleteLater();
    return;
  }
  sockets.insert(socket);
  socket->setSslConfiguration(tls);
  socket->setReadBufferSize(9 * 1024 * 1024);
  auto timeout = new QTimer(socket);
  timeout->setObjectName("idleTimeout");
  timeout->setSingleShot(true);
  timeout->start(30000);
  auto deadline = new QTimer(socket);
  deadline->setSingleShot(true);
  deadline->start(10 * 60 * 1000);
  connect(deadline, &QTimer::timeout, socket, [socket] { socket->abort(); });
  connect(timeout, &QTimer::timeout, socket, [socket] { socket->abort(); });
  connect(socket, &QSslSocket::disconnected, this, [this, socket] {
    sockets.remove(socket);
    blobSockets.remove(socket);
    socket->deleteLater();
  });
  connect(socket, &QSslSocket::readyRead, this, [this, socket] {
    processRequest(socket);
  });
  socket->startServerEncryption();
}
void SyncManager::processRequest(QSslSocket *socket) {
  if (!socket || socket->property("handled").toBool())
    return;
  auto timeout = socket->findChild<QTimer *>("idleTimeout");
  if (timeout)
    timeout->start(30000);
  auto buffer = socket->property("request").toByteArray();
  socket->setProperty("request", QVariant());
  if (socket->bytesAvailable() > 0)
    buffer.append(socket->readAll());
  if (buffer.isEmpty())
    return;
  if (buffer.size() > 8 * 1024 * 1024 + 32768) {
    socket->setProperty("keepAlive", false);
    rawReply(socket, 413, "{}");
    return;
  }
  const int end = buffer.indexOf("\r\n\r\n");
  if (end < 0) {
    if (buffer.size() > 32768) {
      socket->setProperty("keepAlive", false);
      rawReply(socket, 400, "{}");
      return;
    }
    socket->setProperty("request", buffer);
    return;
  }
  auto lines = buffer.left(end).split('\n');
  auto first = lines.takeFirst().trimmed().split(' ');
  if (first.size() != 3 || first[2] != "HTTP/1.1") {
    socket->setProperty("keepAlive", false);
    rawReply(socket, 400, "{}");
    return;
  }
  QMap<QString, QString> headers;
  for (auto line : lines) {
    const int colon = line.indexOf(':');
    if (colon <= 0) {
      socket->setProperty("keepAlive", false);
      rawReply(socket, 400, "{}");
      return;
    }
    auto key = QString::fromLatin1(line.left(colon)).trimmed().toLower();
    if (headers.contains(key)) {
      socket->setProperty("keepAlive", false);
      rawReply(socket, 400, "{}");
      return;
    }
    headers[key] = QString::fromLatin1(line.mid(colon + 1)).trimmed();
  }
  if (headers.contains("transfer-encoding")) {
    socket->setProperty("keepAlive", false);
    rawReply(socket, 400, "{}");
    return;
  }
  bool lengthOk = true;
  const auto length =
      headers.value("content-length", "0").toLongLong(&lengthOk);
  if (!lengthOk || length < 0 || length > 8 * 1024 * 1024) {
    socket->setProperty("keepAlive", false);
    rawReply(socket, 413, "{}");
    return;
  }
  const qsizetype totalSize = end + 4 + length;
  if (buffer.size() < totalSize) {
    socket->setProperty("request", buffer);
    return;
  }
  const auto body = buffer.mid(end + 4, length);
  const auto remainder = buffer.mid(totalSize);
  const int count = socket->property("keepAliveCount").toInt();
  const bool wantKeepAlive =
      headers.value("connection").toLower() != "close" && count < 200;
  socket->setProperty("keepAlive", wantKeepAlive);
  socket->setProperty("handled", true);
  socket->setProperty("request", remainder.isEmpty() ? QVariant() : QVariant(remainder));
  if (timeout)
    timeout->stop();
  route(socket, QString::fromLatin1(first[0]), QString::fromLatin1(first[1]),
        headers, body);
}
void SyncManager::rawReply(QSslSocket *socket, int code, const QByteArray &body,
                           const QByteArray &type, const QByteArray &headers) {
  if (!socket || socket->state() == QAbstractSocket::UnconnectedState)
    return;
  blobSockets.remove(socket);
  const bool keep = socket->property("keepAlive").toBool() && code != 400 &&
                    code != 413 && !closing;
  socket->setProperty("handled", true);
  const auto reason = code >= 200 && code < 300 ? "OK"
                      : code == 503             ? "Service Unavailable"
                      : code == 401             ? "Unauthorized"
                      : code == 404             ? "Not Found"
                      : code == 409             ? "Conflict"
                                                : "Bad Request";
  const QByteArray connectionHeader =
      keep ? "Connection: keep-alive\r\nKeep-Alive: timeout=30, max=200\r\n"
           : "Connection: close\r\n";
  socket->write("HTTP/1.1 " + QByteArray::number(code) + " " + reason +
                "\r\nContent-Type: " + type +
                "\r\nContent-Length: " + QByteArray::number(body.size()) +
                "\r\n" + connectionHeader + headers + "\r\n");
  socket->write(body);
  if (keep) {
    socket->setProperty("keepAliveCount",
                        socket->property("keepAliveCount").toInt() + 1);
    socket->setProperty("handled", false);
    if (auto timeout = socket->findChild<QTimer *>("idleTimeout"))
      timeout->start(30000);
    if (socket->bytesAvailable() > 0 ||
        !socket->property("request").toByteArray().isEmpty()) {
      QPointer<QSslSocket> conn(socket);
      QTimer::singleShot(0, this, [this, conn] {
        if (conn)
          processRequest(conn);
      });
    }
  } else {
    socket->disconnectFromHost();
  }
}
void SyncManager::reply(QSslSocket *socket, int status,
                        const QVariantMap &value) {
  rawReply(socket, status, json(value));
}
void SyncManager::route(QSslSocket *socket, const QString &method,
                        const QString &target,
                        const QMap<QString, QString> &headers,
                        const QByteArray &body) {
  const QUrl url("https://localhost" + target);
  const auto path = url.path();
  if (path == "/sync/v1/pair" && method == "POST") {
    auto &attempt = pairingAttempts[socket->peerAddress().toString()];
    const auto now = QDateTime::currentMSecsSinceEpoch();
    if (now - attempt.first > 60000)
      attempt = {now, 0};
    if (++attempt.second > 10) {
      reply(socket, 429, {{"error", "Too many pairing attempts"}});
      return;
    }
    auto request = decode(body).toMap();
    const auto token = request["token"].toString().toUtf8(),
               expected = pairToken.toUtf8();
    if (expected.isEmpty() || pairUntil < now ||
        token.size() != expected.size() ||
        CRYPTO_memcmp(token.constData(), expected.constData(),
                      expected.size()) != 0) {
      reply(socket, 401, {{"error", "Pairing expired or invalid"}});
      return;
    }
    auto id = request["deviceId"].toString();
    if (QUuid(id).isNull()) {
      reply(socket, 400, {{"error", "Invalid device identity"}});
      return;
    }
    id = QUuid(id).toString(QUuid::WithoutBraces);
    const auto deviceKey = request["deviceKey"].toString();
    if (!deviceKey.isEmpty() && !validHash(deviceKey)) {
      reply(socket, 400, {{"error", "Invalid device key"}}); return;
    }
    QSet<QString> replaced;
    if (!pairReplacement.isEmpty()) replaced.insert(pairReplacement);
    if (!deviceKey.isEmpty())
      for (auto value : s->rows("SELECT peer FROM sync_device_keys WHERE key=?", {deviceKey}))
        replaced.insert(value.toMap()["peer"].toString());
    auto previous = request["previousCredential"].toString();
    if (!previous.isEmpty())
      for (auto value : s->rows("SELECT id FROM sync_peers WHERE token=?", {hashToken(previous)}))
        replaced.insert(value.toMap()["id"].toString());
    for (auto value : s->rows("SELECT id FROM sync_peers")) {
      const auto old = value.toMap()["id"].toString();
      if (QUuid(old) == QUuid(id)) replaced.insert(old);
    }
    if (!s->db().transaction()) { reply(socket, 500, {{"error", "Cannot update pairing"}}); return; }
    bool ok = true;
    for (const auto &old : replaced) if (old != id) {
      ok = s->exec("DELETE FROM sync_device_keys WHERE peer=?", {old}) && ok;
      ok = s->exec("DELETE FROM sync_peers WHERE id=?", {old}) && ok;
      ok = s->exec("DELETE FROM sync_conflicts WHERE peer=?", {old}) && ok;
    }
    auto credential = randomToken();
    ok = s->exec(
        "INSERT INTO sync_peers(id,name,token,lastSeen,ack) VALUES(?,?,?,?,0) "
        "ON CONFLICT(id) DO UPDATE SET "
        "name=excluded.name,token=excluded.token,lastSeen=excluded.lastSeen",
        {id, request["name"].toString().left(200), hashToken(credential), now}) && ok;
    if (!deviceKey.isEmpty()) {
      ok = s->exec("DELETE FROM sync_device_keys WHERE peer=?", {id}) && ok;
      ok = s->exec("INSERT INTO sync_device_keys(key,peer) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET peer=excluded.peer", {deviceKey,id}) && ok;
    }
    if (!ok || !s->db().commit()) {
      s->db().rollback(); reply(socket, 500, {{"error", "Cannot save pairing"}}); return;
    }
    pairReplacement.clear();
    pairToken.clear();
    pairUntil = 0;
    link.clear();
    qr.clear();
    reply(socket, 200,
          {{"deviceId", s->setting("libraryIdentity")},
           {"name", QHostInfo::localHostName()},
           {"credential", credential},
           {"protocol", 1}});
    emit changed();
    return;
  }
  const auto authorization = headers.value("authorization");
  const auto credential =
      authorization.startsWith("Bearer ") ? authorization.mid(7) : QString();
  const auto peer = s->rows("SELECT id FROM sync_peers WHERE token=?",
                            {hashToken(credential)})
                        .value(0)
                        .toMap()["id"]
                        .toString();
  if (credential.isEmpty() || peer.isEmpty()) {
    reply(socket, 401, {{"error", "Device is not paired"}});
    return;
  }
  s->exec("UPDATE sync_peers SET lastSeen=? WHERE id=?",
          {QDateTime::currentMSecsSinceEpoch(), peer});
  if (maintenance() && (method == "POST" || method == "PUT")) {
    reply(socket, 503, {{"error", "Library maintenance is in progress"}, {"retry", true}}); return;
  }
  if (path == "/sync/v1/changes" && method == "GET") {
    if (preparing || !s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty()) {
      prepare();
      reply(socket, 503,
            {{"error", "Preparing desktop library"}, {"retry", true}});
      return;
    }
    QUrlQuery query(url);
    reply(socket, 200, changes(query.queryItemValue("cursor").toLongLong()));
    return;
  }
  if (path == "/sync/v1/changes" && method == "POST") {
    const auto request = QJsonDocument::fromJson(body);
    if (!request.isObject() || !request.object().value("operations").isArray()) {
      reply(socket, 400, {{"error", "Expected a sync operations array"}}); return;
    }
    exchangeAsync(socket, peer, request.object().value("operations").toArray().toVariantList());
    return;
  }
  if (path == "/sync/v1/ack" && method == "POST") {
    s->exec("UPDATE sync_peers SET ack=MAX(ack,?) WHERE id=?",
            {decode(body).toMap()["cursor"].toLongLong(), peer});
    reply(socket, 200, {{"ok", true}});
    return;
  }
  if (path == "/sync/v1/review" && method == "POST") {
    if (preparing || !s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty()) {
      prepare(); reply(socket,503,{{"error","Preparing desktop library"},{"retry",true}}); return;
    }
    const auto request=decode(body).toMap();
    const auto key=canonicalKey(peer,"tracks",request["key"].toString());
    const auto current=s->rows("SELECT revision,payload FROM sync_entities WHERE entity='tracks' AND key=?",{key}).value(0).toMap();
    if (key.isEmpty() || key.size()>200 || current.isEmpty()) {reply(socket,400,{{"error","Song is not in the shared library"}});return;}
    auto phone=request.value("phone");
    if (phone.isValid() && !phone.isNull()) { auto fields=rewrite(peer,"tracks",phone.toMap());fields["id"]=key;phone=fields; }
    const auto desktop=decode(current["payload"].toByteArray());
    QStringList changedFields;
    if (phone.isNull() || !phone.isValid() || desktop.isNull() || !desktop.isValid()) changedFields<<"deletion";
    else {
      const auto left=desktop.toMap(),right=phone.toMap();
      for (const auto &field : {"title","artist","album","albumArtist","genre","year","number","duration","speed","nightcore","volumePercent","sourceUrl","uploader","videoTitle","sourceProvider","sourceItemId","audioHash","artHash"}) {
        const auto lv = left.value(field), rv = right.value(field);
        bool lvNum = false, rvNum = false;
        const double ln = lv.toDouble(&lvNum), rn = rv.toDouble(&rvNum);
        const bool numeric = (lv.typeId() == QMetaType::Double || lv.typeId() == QMetaType::Int || lv.typeId() == QMetaType::LongLong ||
                              rv.typeId() == QMetaType::Double || rv.typeId() == QMetaType::Int || rv.typeId() == QMetaType::LongLong) &&
                             lv.typeId() != QMetaType::Bool && rv.typeId() != QMetaType::Bool && lvNum && rvNum;
        if (numeric ? std::fabs(ln - rn) > 1e-6 : lv != rv) changedFields<<field;
      }
    }
    if (changedFields.isEmpty()) {reply(socket,200,{{"unchanged",true}});return;}
    auto id=s->rows("SELECT id FROM sync_conflicts WHERE peer=? AND entity='tracks' AND key=? LIMIT 1",{peer,key}).value(0).toMap()["id"].toString();
    if (id.isEmpty()) id=uuid();
    if (!s->exec("INSERT INTO sync_conflicts VALUES(?,?,?,'tracks',?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET local=excluded.local,remote=excluded.remote,revision=excluded.revision,fields=excluded.fields",
         {id,peer,QString("review"),key,QString::fromUtf8(json(desktop)),QString::fromUtf8(json(phone)),current["revision"],QString::fromUtf8(json(changedFields))})) {
      reply(socket,500,{{"error","Could not save the review"}});return;
    }
    reply(socket,200,{{"conflict",id},{"revision",current["revision"]},{"desktop",desktop}});emit changed();return;
  }
  if (path == "/sync/v1/conflicts" && method == "GET") {
    reply(socket, 200,
          {{"conflicts",
            s->rows("SELECT id,entity,key,local,remote,revision,fields FROM "
                    "sync_conflicts WHERE peer=?",
                    {peer})}});
    return;
  }
  if ((path == "/sync/v1/resolve" || path == "/sync/v1/resolve-batch") && method == "POST") {
    if (maintenance() || busy()) {
      reply(socket, 503, {{"error", "Another library operation is in progress"}, {"retry", true}}); return;
    }
    if (!s->rows("SELECT key FROM sync_dirty LIMIT 1").isEmpty()) {
      prepare(); reply(socket, 503, {{"error", "Preparing desktop library"}, {"retry", true}}); return;
    }
    const auto r = decode(body).toMap();
    if (path.endsWith("resolve-batch")) {
      const auto choices = r["choices"].toList();
      if (choices.isEmpty() || choices.size() > 100) {
        reply(socket, 400, {{"error", "Choose between 1 and 100 song choices per request"}}); return;
      }
      startResolutions(choices, r["usePhone"].toBool(), peer, socket); return;
    }
    const auto id = r["id"].toString();
    if (s->rows("SELECT id FROM sync_conflicts WHERE id=? AND peer=?", {id, peer}).isEmpty()) {
      reply(socket, 404, {{"error", "Conflict no longer exists"}}); return;
    }
    emit aboutToApply();
    auto result = resolveConflict(id, r["usePhone"].toBool(), r.value("revision", -1).toLongLong(), r["phoneVersion"].toString(), peer);
    result["ok"] = result["status"] == "resolved";
    if (result["status"] == "resolved") emit applied();
    emit changed(); reply(socket, 200, result); return;
  }
  if (path == "/sync/v1/presets") {
    auto currentEqState = [&]() -> QVariantMap {
      return {
          {"enabled", s->setting("eqEnabled", false).toBool()},
          {"preset", s->setting("eqPreset", "Flat").toString()},
          {"gains", s->setting("eqGains", QVariantList{0, 0, 0, 0, 0, 0, 0, 0, 0, 0}).toList()},
          {"updatedAt", s->setting("eqStateUpdatedAt", 0).toLongLong()},
      };
    };
    if (method == "GET") {
      reply(socket, 200, {
          {"eqPresets", s->setting("eqCustomPresets", QVariantList{}).toList()},
          {"eqState", currentEqState()},
      });
      return;
    }
    if (method == "POST") {
      const auto req = decode(body).toMap();
      const auto incoming = req.value("eqPresets").toList();
      const auto local = s->setting("eqCustomPresets", QVariantList{}).toList();
      const auto merged = mergeEqPresets(local, incoming);
      const auto localState = currentEqState();
      const auto incomingState = req.value("eqState").toMap();
      const auto mergedState = mergeEqState(localState, incomingState, merged);
      bool changedAny = false;
      if (merged != local) {
        s->setSetting("eqCustomPresets", merged);
        changedAny = true;
      }
      if (mergedState != localState) {
        s->setSetting("eqEnabled", mergedState.value("enabled").toBool());
        s->setSetting("eqPreset", mergedState.value("preset").toString());
        s->setSetting("eqGains", mergedState.value("gains").toList());
        s->setSetting("eqStateUpdatedAt", mergedState.value("updatedAt").toLongLong());
        changedAny = true;
      }
      if (changedAny) emit presetsChanged();
      reply(socket, 200, {{"eqPresets", merged}, {"eqState", mergedState}});
      return;
    }
  }
  if (path == "/sync/v1/blobs/check" && method == "POST") {
    const auto r = decode(body).toMap();
    const auto hashes = r["hashes"].toList();
    if (hashes.size() > 5000) {
      reply(socket, 400, {{"error", "Too many hashes in one check"}});
      return;
    }
    QVariantList missing;
    QSqlQuery q(s->db());
    q.prepare("SELECT path,size,mtime FROM sync_blobs WHERE hash=?");
    for (const auto &value : hashes) {
      const auto hash = value.toString();
      if (!validHash(hash))
        continue;
      q.bindValue(0, hash);
      bool ok = false;
      if (q.exec() && q.next()) {
        QFileInfo fi(q.value(0).toString());
        ok = fi.isFile() && fi.size() == q.value(1).toLongLong() &&
             fi.lastModified().toMSecsSinceEpoch() == q.value(2).toLongLong();
      }
      if (!ok)
        missing << hash;
    }
    reply(socket, 200, {{"missing", missing}});
    return;
  }
  if (path.startsWith("/sync/v1/blobs/")) {
    const auto hash = path.section('/', -1);
    if (!validHash(hash)) {
      reply(socket, 400, {{"error", "Invalid hash"}});
      return;
    }
    if (method == "GET" || method == "HEAD") {
      const auto blob = s->rows("SELECT * FROM sync_blobs WHERE hash=?", {hash})
                            .value(0)
                            .toMap();
      QFileInfo fi(blob["path"].toString());
      if (!fi.isFile()) {
        reply(socket, 404, {{"error", "Content unavailable"}});
        return;
      }
      if (fi.size() != blob["size"].toLongLong() ||
          fi.lastModified().toMSecsSinceEpoch() != blob["mtime"].toLongLong()) {
        reply(socket, 409, {{"error", "File changed; synchronize again"}});
        return;
      }
      if (method == "HEAD") {
        rawReply(socket, 200, {}, "application/octet-stream",
                 "X-Content-Size: " + QByteArray::number(fi.size()) + "\r\n");
        return;
      }
      auto range = headers.value("range");
      qint64 offset = 0;
      if (!range.isEmpty()) {
        const auto match = QRegularExpression("^bytes=(\\d+)-$").match(range);
        if (!match.hasMatch()) {
          reply(socket, 400, {{"error", "Invalid range"}});
          return;
        }
        offset = match.captured(1).toLongLong();
      }
      if (offset >= fi.size()) {
        reply(socket, 416, {{"error", "Range exceeds file"}, {"size", fi.size()}});
        return;
      }
      if (activeBlobReads >= 4 || blobSockets.size() >= 4) {
        reply(socket, 503, {{"error", "Desktop transfers are busy"}, {"retry", true}});
        return;
      }
      // slow disks must not block the Qt event loop
      // bound both file reads and replies waiting for a slow peer, rather than buffering a library in RAM
      blobSockets.insert(socket);
      ++activeBlobReads;
      auto watcher = new QFutureWatcher<QVariantMap>(this);
      QPointer<QSslSocket> connection(socket);
      connect(watcher, &QFutureWatcher<QVariantMap>::finished, this,
              [this, watcher, connection, offset, total = fi.size()] {
        --activeBlobReads;
        const auto result = watcher->result();
        watcher->deleteLater();
        if (!connection) return;
        if (result.contains("error")) {
          reply(connection, result.value("status", 404).toInt(), result); return;
        }
        const auto data = result["data"].toByteArray();
        rawReply(connection, 206, data, "application/octet-stream",
                 "Content-Range: bytes " + QByteArray::number(offset) + "-" +
                 QByteArray::number(offset + data.size() - 1) + "/" +
                 QByteArray::number(total) + "\r\n");
      });
      watcher->setFuture(QtConcurrent::run([fi, offset] {
        QFile file(fi.filePath());
        if (!file.open(QIODevice::ReadOnly) || !file.seek(offset))
          return QVariantMap{{"error", "Cannot open content"}, {"status", 404}};
        const auto count = qMin(qint64(8 * 1024 * 1024), fi.size() - offset);
        const auto data = file.read(count);
        const QFileInfo after(fi.filePath());
        if (data.size() != count || after.size() != fi.size() || after.lastModified() != fi.lastModified())
          return QVariantMap{{"error", "File changed; synchronize again"}, {"status", 409}};
        return QVariantMap{{"data", data}};
      }));
      return;
    }
    if (method == "PUT") {
      const auto offset = headers.value("x-offset").toLongLong();
      const auto filePath =
          stateDir + "/incoming/" + peer + "-" + hash + ".part";
      QFile file(filePath);
      if (!file.open(QIODevice::ReadWrite)) {
        reply(socket, 400, {{"error", "Cannot stage incoming content"}});
        return;
      }
      if (offset != file.size()) {
        reply(socket, 409, {{"offset", file.size()}});
        return;
      }
      if (!file.seek(offset) || file.write(body) != body.size() ||
          !file.flush()) {
        reply(socket, 400,
              {{"error", "Could not write content; check disk space"}});
        return;
      }
      file.close();
      if (headers.value("x-complete") == "1")
        finishUpload(socket, hash, filePath);
      else
        reply(socket, 200, {{"offset", offset + body.size()}});
      return;
    }
  }
  reply(socket, 404, {{"error", "Unknown endpoint"}});
}
void SyncManager::finishUpload(QSslSocket *socket, const QString &hash,
                               const QString &file) {
  auto reference =
      s->rows("SELECT entity,key,payload FROM sync_entities WHERE "
              "payload<>'null' AND (json_extract(payload,'$.audioHash')=? OR "
              "json_extract(payload,'$.artHash')=?) LIMIT 1",
              {hash, hash})
          .value(0)
          .toMap();
  if (reference.isEmpty()) {
    reply(socket, 400,
          {{"error", "Content is not referenced by the shared library"}});
    return;
  }
  const auto payload = decode(reference["payload"].toByteArray()).toMap();
  const bool audio = payload["audioHash"] == hash;
  QString target;
  if (audio) {
    const auto current =
        s->rows("SELECT path FROM tracks WHERE id=?", {reference["key"]})
            .value(0)
            .toMap()["path"]
            .toString();
    if (QFileInfo(current).isFile())
      target = current;
    else {
      auto dir = s->setting("musicDir", QDir::homePath() + "/Music/SillyPlayer")
                     .toString() +
                 "/Synced";
      QDir().mkpath(dir);
      auto extension =
          QFileInfo(payload["fileName"].toString()).suffix().toLower();
      if (!QRegularExpression("^[a-z0-9]{1,10}$").match(extension).hasMatch())
        extension = "mp3";
      target = dir + "/" + hash + "." + extension;
    }
  } else {
    QDir().mkpath(stateDir + "/art");
    target = stateDir + "/art/" + hash + ".img";
  }
  auto watcher = new QFutureWatcher<QVariantMap>(this);
  QPointer<QSslSocket> connection(socket);
  connect(
      watcher, &QFutureWatcher<QVariantMap>::finished, this,
      [this, watcher, connection, hash, target, audio, reference] {
        auto result = watcher->result();
        watcher->deleteLater();
        if (result.contains("error")) {
          if (connection)
            reply(connection, 400, result);
          return;
        }
        QFileInfo fi(target);
        s->exec("INSERT INTO sync_blobs VALUES(?,?,?,?,?) ON CONFLICT(hash) DO "
                "UPDATE SET "
                "path=excluded.path,size=excluded.size,mtime=excluded.mtime",
                {hash, target, fi.size(), fi.lastModified().toMSecsSinceEpoch(),
                 audio ? "audio" : "art"});
        s->db().transaction();
        s->exec("UPDATE sync_control SET applying=1 WHERE id=1");
        const auto rows =
            s->rows("SELECT entity,key,payload FROM sync_entities WHERE "
                    "json_extract(payload,'$.audioHash')=? OR "
                    "json_extract(payload,'$.artHash')=?",
                    {hash, hash});
        QString why;
        for (auto v : rows) {
          auto r = v.toMap();
          auto p = decode(r["payload"].toByteArray()).toMap();
          if (audio && r["entity"] == "tracks")
            s->exec("UPDATE tracks SET path=?,available=0,managed=1 WHERE id=?",
                    {target, r["key"]});
          apply(r["entity"].toString(), r["key"].toString(), p, &why);
          if (audio && r["entity"] == "tracks")
            emit fileReceived(target, {{"provider", p["sourceProvider"]},
                                       {"id", p["sourceItemId"]},
                                       {"webpage_url", p["sourceUrl"]},
                                       {"uploader", p["uploader"]},
                                       {"title", p["videoTitle"]}});
        }
        s->exec("UPDATE sync_control SET applying=0 WHERE id=1");
        s->db().commit();
        if (connection)
          reply(connection, 200,
                {{"complete", true}, {"hash", hash}, {"offset", fi.size()}});
        emit applied();
        emit changed();
      });
  watcher->setFuture(QtConcurrent::run([file, target, hash] {
    try {
      if (hashFile(file) != hash) {
        QFile::remove(file);
        throw std::runtime_error(
            "Content checksum mismatch; retry the transfer");
      }
      QFile input(file);
      QSaveFile output(target);
      if (!input.open(QIODevice::ReadOnly) ||
          !output.open(QIODevice::WriteOnly))
        throw std::runtime_error(
            "Destination is not writable; grant access and retry");
      while (!input.atEnd()) {
        auto bytes = input.read(256 * 1024);
        if (bytes.isEmpty() && input.error() != QFile::NoError)
          throw std::runtime_error("Read failed");
        if (output.write(bytes) != bytes.size())
          throw std::runtime_error("Disk full or write failed");
      }
      if (!output.commit())
        throw std::runtime_error("Could not publish verified content");
      QFile::remove(file);
      return QVariantMap{{"ok", true}};
    } catch (const std::exception &e) {
      return QVariantMap{{"error", QString::fromUtf8(e.what())}};
    }
  }));
}
QVariantList SyncManager::mergeEqPresets(const QVariantList &local, const QVariantList &remote) {
  QMap<QString, QVariantMap> byId;
  QStringList idOrder;
  auto ingest = [&](const QVariantList &source) {
    for (const auto &v : source) {
      const auto raw = v.toMap();
      const QString id = raw.value("id").toString().trimmed();
      if (id.isEmpty()) continue;
      const QString name = raw.value("name").toString().trimmed();
      const bool deleted = raw.value("deleted").toBool();
      if (!deleted && name.isEmpty()) continue;
      const qint64 updatedAt = raw.value("updatedAt").toLongLong();
      const auto rawGains = raw.value("gains").toList();
      QVariantList gains;
      gains.reserve(10);
      for (int i = 0; i < 10; ++i) {
        const double g = i < rawGains.size() ? rawGains[i].toDouble() : 0.0;
        gains << (std::isfinite(g) ? std::clamp(g, -12.0, 12.0) : 0.0);
      }
      QVariantMap item{{"id", id}, {"name", name}, {"gains", gains}, {"updatedAt", updatedAt}, {"deleted", deleted}};
      if (!byId.contains(id)) idOrder << id;
      const auto prev = byId.value(id);
      const qint64 prevAt = prev.value("updatedAt").toLongLong();
      if (!byId.contains(id) || updatedAt > prevAt || (updatedAt == prevAt && prev.value("deleted").toBool() && !deleted)) {
        byId.insert(id, item);
      }
    }
  };
  ingest(local);
  ingest(remote);
  QMap<QString, QVariantMap> activeByName;
  QVariantList tombstones;
  for (const auto &id : idOrder) {
    const auto item = byId.value(id);
    if (item.value("deleted").toBool()) {
      tombstones << item;
      continue;
    }
    const QString key = item.value("name").toString().toLower();
    if (!activeByName.contains(key) || item.value("updatedAt").toLongLong() > activeByName.value(key).value("updatedAt").toLongLong()) {
      activeByName.insert(key, item);
    }
  }
  QVariantList active;
  active.reserve(activeByName.size());
  for (auto it = activeByName.cbegin(); it != activeByName.cend(); ++it) active << it.value();
  std::sort(active.begin(), active.end(), [](const QVariant &a, const QVariant &b) {
    return a.toMap().value("name").toString().compare(b.toMap().value("name").toString(), Qt::CaseInsensitive) < 0;
  });
  QVariantList merged = tombstones;
  merged.append(active);
  return merged;
}
QVariantMap SyncManager::mergeEqState(const QVariantMap &local, const QVariantMap &remote,
                                      const QVariantList &mergedPresets) {
  auto normalize = [](const QVariantMap &raw) -> QVariantMap {
    const auto rawGains = raw.value("gains").toList();
    QVariantList gains;
    gains.reserve(10);
    for (int i = 0; i < 10; ++i) {
      const double g = i < rawGains.size() ? rawGains[i].toDouble() : 0.0;
      gains << (std::isfinite(g) ? std::clamp(g, -12.0, 12.0) : 0.0);
    }
    const QString preset = raw.value("preset").toString().trimmed();
    return {
        {"enabled", raw.value("enabled").toBool()},
        {"preset", preset.isEmpty() ? QStringLiteral("Flat") : preset},
        {"gains", gains},
        {"updatedAt", qMax<qint64>(0, raw.value("updatedAt").toLongLong())},
    };
  };
  const auto l = normalize(local);
  QVariantMap win = l;
  if (!remote.isEmpty()) {
    const auto r = normalize(remote);
    if (r.value("updatedAt").toLongLong() > l.value("updatedAt").toLongLong()) win = r;
  }
  // if the selected custom preset got updated or deleted remotely keep the live eq config in sync with it
  const QString activeName = win.value("preset").toString();
  static const QStringList builtins{"Flat", "Bass Boost", "Rock", "Pop", "Classical", "Custom"};
  bool isBuiltin = false;
  for (const auto &b : builtins) {
    if (activeName.compare(b, Qt::CaseInsensitive) == 0) {
      isBuiltin = true;
      break;
    }
  }
  if (!isBuiltin) {
    bool foundActive = false;
    qint64 deletedAt = -1;
    for (const auto &v : mergedPresets) {
      const auto p = v.toMap();
      if (p.value("name").toString().compare(activeName, Qt::CaseInsensitive) != 0) continue;
      if (!p.value("deleted").toBool()) {
        foundActive = true;
        if (p.value("updatedAt").toLongLong() > win.value("updatedAt").toLongLong()) {
          win["gains"] = p.value("gains").toList();
          win["preset"] = p.value("name").toString();
        }
        break;
      } else {
        deletedAt = qMax(deletedAt, p.value("updatedAt").toLongLong());
      }
    }
    if (!foundActive && deletedAt >= 0) win["preset"] = QStringLiteral("Custom");
  }
  return win;
}
void SyncManager::resetLocalState() {
  resolutionResult.clear();
  emit resolutionProgressChanged();
  emit changed();
}
QByteArray SyncManager::discoveryReplyPayload(const QString &deviceId, const QString &name,
                                              quint16 port, const QString &fp) {
  return QJsonDocument(QJsonObject{
                           {"service", "sillyplayer-sync"},
                           {"v", 1},
                           {"deviceId", deviceId},
                           {"name", name},
                           {"port", int(port)},
                           {"fp", fp},
                       })
      .toJson(QJsonDocument::Compact);
}
void SyncManager::startDiscovery() {
  if (discovery.state() == QAbstractSocket::BoundState)
    return;
  // todo: also try mDNS/DNS-SD if a router blocks UDP broadcast
  discovery.bind(QHostAddress::AnyIPv4, kDiscoveryPort,
                 QAbstractSocket::ShareAddress | QAbstractSocket::ReuseAddressHint);
}
void SyncManager::stopDiscovery() {
  if (discovery.state() != QAbstractSocket::UnconnectedState)
    discovery.close();
}
void SyncManager::handleDiscoveryDatagram() {
  while (discovery.hasPendingDatagrams()) {
    QByteArray datagram;
    datagram.resize(int(qMin<qint64>(discovery.pendingDatagramSize(), 4096)));
    QHostAddress sender;
    quint16 senderPort = 0;
    const qint64 read = discovery.readDatagram(datagram.data(), datagram.size(), &sender, &senderPort);
    if (read <= 0 || !server.isListening() || fingerprint.isEmpty())
      continue;
    datagram.truncate(int(read));
    const auto obj = QJsonDocument::fromJson(datagram).object();
    if (obj.value("discover").toString() != "sillyplayer-sync" || obj.value("v").toInt() != 1)
      continue;
    const QString deviceId = s->setting("libraryIdentity").toString();
    const QString name = QHostInfo::localHostName().isEmpty() ? QStringLiteral("Desktop")
                                                              : QHostInfo::localHostName();
    const QByteArray reply = discoveryReplyPayload(deviceId, name, server.serverPort(), fingerprint);
    discovery.writeDatagram(reply, sender, senderPort);
  }
}
void SyncManager::shutdown() {
  if (closing)
    return;
  closing = true;
  stopDiscovery();
  server.close();
  debounce.stop();
  expiry.stop();
  worker.waitForFinished();
  for (auto child : findChildren<QFutureWatcher<QVariantMap> *>())
    child->waitForFinished();
  for (auto socket : QSet<QSslSocket *>(sockets))
    socket->abort();
}
