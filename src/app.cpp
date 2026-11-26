#include "app.h"
#include "artistsplit.h"
#include "foldertree.h"
#include "m3u.h"
#include "smartshuffle.h"
#include "tagcleanup.h"
#ifdef Q_OS_WIN
#include <QProcess>
#endif
#include <QDesktopServices>
#include <QClipboard>
#include <QGuiApplication>
#include <QRegularExpression>
#include <QSaveFile>
#include "extraction.h"
#include "source.h"
#include <QDir>
#include <QDirIterator>
#include <QFileDialog>
#include <QImage>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRandomGenerator>
#include <QStandardPaths>
#include <QUrl>
#include <QUuid>
#include <algorithm>
#include <cmath>
#include <random>

static QString uuid() {
  return QUuid::createUuid().toString(QUuid::WithoutBraces);
}
App::App(Store *store, const QString &cacheDir, QObject *parent)
    : QObject(parent), library(store, cacheDir + "/art", this),
      music(false, this), youtube(false, this), online(store, this), listening(store,&music,this), sleepTimer(this), backup(store,&library,this), recovery(store,this), libraryTools(store,&library,&music,this), sync(store,this), discord(store,this), s(store),
      cache(cacheDir) {
  // restore and sync can reuse prepared SQL statements outside Store::exec
  // their structural notifications must also invalidate cached snapshots otherwise it gets messed up
  connect(this,&App::collectionsChanged,this,[this]{collectionSerial=quint64(-1);});
  sync.setMaintenanceGuard([this] { return library.inMaintenance(); });
  recovery.setFolderProvider([this] { return settings().value("musicDir").toString(); });
  recovery.setBusyGuard([this] { return library.scanning() || library.trashing() || library.inMaintenance() || sync.busy() || backup.busy(); });
  connect(&library,&Library::changed,&sync,&SyncManager::markDirty);
  connect(&library,&Library::changed,&recovery,&DesktopRecovery::requestSave);
  connect(&library,&Library::tracksUpdated,this,[this](const QVariantMap &patches){
    const auto id=index>=0&&index<entries.size()?entries[index].toMap().value("trackId").toString():QString();
    if(patches.contains(id)){
      currentSerial=quint64(-1);
      const auto fields=patches.value(id).toMap();
      if(fields.size()==1 && fields.contains("liked"))emit currentLikeChanged();
      else emit currentChanged();
    }
    sync.markDirty();
    recovery.requestSave();
  });
  connect(this,&App::currentChanged,this,&App::currentLikeChanged);
  connect(this,&App::collectionsChanged,&sync,&SyncManager::markDirty);
  connect(this,&App::collectionsChanged,&recovery,&DesktopRecovery::requestSave);
  connect(&sync,&SyncManager::aboutToApply,&listening,&Listening::flush);
  connect(&sync,&SyncManager::presetsChanged,this,[this]{
    // push synced eq state right into mpv instead of waiting for the user to reopen the eq popup
    settingsSerial=quint64(-1);
    const auto opts=settings();
    music.setEq(opts["eqEnabled"].toBool(),opts["eqGains"].toList());
    emit settingsChanged();
    recovery.requestSave();
  });
  connect(&recovery,&DesktopRecovery::recovered,this,[this]{
    currentSerial=collectionSerial=queueSerial=settingsSerial=quint64(-1);
    listening.reload();
    const auto opts=settings();
    music.setEq(opts["eqEnabled"].toBool(),opts["eqGains"].toList());
    music.setPauseFade(opts["pauseFadeEnabled"].toBool(),opts["pauseFadeMs"].toInt());
    music.setCrossfadeMs(opts["crossfadeMs"].toInt());
    emit library.changed();
    emit collectionsChanged();
    emit settingsChanged();
  });
  connect(&sync,&SyncManager::fileReceived,&library,&Library::importFile);
  connect(&sync,&SyncManager::error,this,&App::error);
  connect(&sync,&SyncManager::applied,this,[this]{
    listening.reload();emit library.changed();emit collectionsChanged();
    const auto t=current();if(!t.isEmpty()){music.setTrackVolume(t.value("volumePercent",100.0).toDouble());music.setSpeed(t.value("speed",1.0).toDouble(),t.value("nightcore",false).toBool());}
  });
  libraryTools.setCurrentTrack([this] {return current()["id"].toString();});
  connect(&libraryTools,&LibraryTools::beforeMerge,&listening,&Listening::flush);
  connect(&libraryTools,&LibraryTools::remapped,this,[this](const QVariantMap &ids) {
    for(auto &v:entries) {auto item=v.toMap(); const auto id=item["trackId"].toString();if(ids.contains(id)) {item["trackId"]=ids[id];v=item;}}
    listening.remapTracks(ids);
    emit queueChanged(); emit currentChanged(); emit collectionsChanged(); save();
  });
  connect(&sleepTimer,&SleepTimer::expired,this,[this] {
    online.cancelResolution();
    music.pauseWithFade(); youtube.pauseWithFade();
  });
  connect(&listening,&Listening::rankingsChanged,this,&App::recentChanged);
  connect(&listening,&Listening::error,this,&App::error);
  connect(&music, &Player::wantsPlay, &youtube, &Player::pausePlayback);
  connect(&music, &Player::wantsPlay, &online, &Online::cancelResolution);
  connect(&youtube, &Player::wantsPlay, &music, &Player::pausePlayback);
  connect(&online, &Online::providerChanged, &youtube, &Player::pausePlayback);
  connect(&online, &Online::playbackRequested, this, [this](bool refresh) {
    if (!refresh) { onlineResume = 0; onlinePaused = false; }
    youtube.stop();
  });
  connect(&youtube, &Player::failed, this, [this](bool) {
    onlineResume = youtube.position(); onlinePaused = youtube.requestedPause();
    if (!online.refreshPlayback()) online.invalidatePlayback();
  });
  connect(&music, &Player::ended, this, [this] {
    if (m_mode == 1 && index >= 0)
      playIndex(index, true, true);
    else
      advance(true);
  });
  connect(
      &online, &Online::streamReady, &youtube,
      [this](const QString &url, const QString &audio,
             const QVariantMap &headers) { youtube.load(url, audio, headers, onlineResume, onlinePaused); });
  connect(&online, &Online::musicDownloaded, &library, &Library::importFile);
  connect(&library,&Library::fileIndexed,&online,&Online::attachPlaylistDownloads);
  connect(&online,&Online::playlistsChanged,this,&App::collectionsChanged);
  QTimer::singleShot(0,&online,&Online::restorePlaylistDownloads);
  connect(&library, &Library::error, this, &App::error);
  connect(&online, &Online::error, this, &App::error);
  connect(&library, &Library::changed, this, [this] {
    currentSerial=queueSerial=settingsSerial=quint64(-1);
    if (batchTrashing) return;
    emit collectionsChanged();
    emit currentChanged();
    emit queueChanged();
    emit recentChanged();
  });
  connect(&library, &Library::trashFinished, this, [this](const QVariantMap &result) {
    removeTrashedFromQueue(result.value("successful").toStringList());
    batchTrashing=false;
    emit collectionsChanged(); emit currentChanged(); emit queueChanged(); save();
    emit batchTrashFinished(result);
  });
  m_mode = s->setting("mode", 0).toInt();
  entries = s->setting("queue", QVariantList{}).toList();
  normalQueueOrder = s->setting("queueOrder").toStringList();
  reconcileQueueOrder();
  savedQueueVersion = queueVersion;
  index = s->setting("queueIndex", -1).toInt();
  m_context = s->setting("queueContext", "Songs").toString();
  m_source = s->setting("queueSource", QVariantMap{}).toMap();
  if (m_source.isEmpty()) {
    m_source = {{"kind", "unknown"}, {"id", ""}, {"name", m_context}};
    QVariantList candidates;
    if (m_context == "Songs") candidates << QVariantMap{{"kind","songs"},{"id",""},{"name",m_context}};
    if (m_context == "Liked Songs") candidates << QVariantMap{{"kind","liked"},{"id","liked"},{"name",m_context}};
    for (const auto &item : collections()) if (item.toMap()["name"] == m_context) candidates << item;
    if (candidates.size() == 1) {
      const auto item = candidates.first().toMap();
      m_source = {{"kind",item["kind"]},{"id",item["id"]},{"name",item["name"]}};
    }
  }
  if (index >= 0 && index < entries.size()) {
    auto t = current();
    if (QFileInfo(t["path"].toString()).isFile()) {
      listening.begin(t["id"].toString(),true);
      music.load(t["path"].toString(), {}, {},
                 s->setting("position", 0).toDouble(), true);
    }
  }
  auto opts = settings();
  music.setVolume(opts["volume"].toDouble());
  music.setTrackVolume(current().value("volumePercent",100.0).toDouble());
  youtube.setVolume(opts["youtubeVolume"].toDouble());
  music.setSpeed(opts["speed"].toDouble(), opts["nightcore"].toBool());
  music.setEq(opts["eqEnabled"].toBool(), opts["eqGains"].toList());
  music.setPauseFade(opts["pauseFadeEnabled"].toBool(), opts["pauseFadeMs"].toInt());
  music.setCrossfadeMs(opts["crossfadeMs"].toInt());
  youtube.setPauseFade(opts["pauseFadeEnabled"].toBool(), opts["pauseFadeMs"].toInt());
  discord.setLookupCovers(opts["discordRpcLookupCovers"].toBool());
  discord.setUploadCovers(opts["discordRpcUploadCovers"].toBool());
  if (opts["discordRpcEnabled"].toBool())
    QTimer::singleShot(1500, this, [this] {
      if (!closing && settings()["discordRpcEnabled"].toBool()) {
        discord.setEnabled(true);
        syncDiscordPresence(true);
      }
    });
  // save resolved discord cover urls into the folder mirror too
  connect(&discord, &DiscordRpc::coverResolved, &recovery, &DesktopRecovery::requestSave);
  recovery.inspectFolder(opts["musicDir"].toString());
  connect(this, &App::currentChanged, this, [this] { syncDiscordPresence(true); });
  connect(&music, &Player::changed, this, [this] { syncDiscordPresence(false); });
  syncDiscordPresence(true);
  saver.setInterval(15000);
  connect(&saver, &QTimer::timeout, this, &App::save);
  saver.start();
  updateTimer.setInterval(2 * 60 * 60 * 1000);
  connect(&updateTimer, &QTimer::timeout, this, [this] {
    if (!closing && settings()["updateNotifications"].toBool())
      checkForUpdates(false);
  });
  if (!qEnvironmentVariableIsSet("SILLYPLAYER_NO_UPDATE_CHECK")) {
    updateTimer.start();
    if (opts["updateNotifications"].toBool())
      QTimer::singleShot(3000, this, [this] { checkForUpdates(false); });
  }
}
App::~App() { shutdown(); }
QVariantMap App::settings() const {
  const auto active=index>=0&&index<entries.size()?entries[index].toMap().value("trackId").toString():QString();
  if(settingsSerial==s->settingsSerial() && settingsTracksSerial==s->tracksSerial() && settingsTrack==active)return settingsSnapshot;
  // load all settings in one query instead of 30+ individual selects
  const auto stored = s->allSettings();
  QVariantMap defaults{
      {"palette", "Catppuccin"},
      {"light", false},
      {"cropArtwork", true},
      {"pauseFadeEnabled", false},
      {"pauseFadeMs", 300},
      {"crossfadeMs", 0},
      {"smartShuffle", false},
      {"speed", 1.0},
      {"nightcore", false},
      {"volume", 70},
      {"youtubeVolume", stored.value("videoVolume", 70)},
      {"eqEnabled", false},
      {"eqGains", QVariantList{0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
      {"eqPreset", "Flat"},
      {"eqCustomPresets", QVariantList{}},
      {"eqStateUpdatedAt", 0},
      {"developerMode", false},
      {"allowPlaylistDuplicates", false},
      {"pinnedPlaylists", QStringList{}},
      {"pinnedArtists", QStringList{}},
      {"windowWidth", 1160},
      {"windowHeight", 800},
      {"windowX", -1},
      {"windowY", -1},
      {"windowMaximized", false},
      {"lastPage", "Music"},
      {"discordRpcEnabled", true},
      {"discordRpcLookupCovers", true},
      {"discordRpcUploadCovers", false},
      {"updateNotifications", true},
      {"lastNotifiedUpdate", ""},
      {"musicDir",
       QStandardPaths::writableLocation(QStandardPaths::MusicLocation) +
           "/SillyPlayer"}};
  for (auto it = defaults.begin(); it != defaults.end(); ++it)
    if (stored.contains(it.key())) it.value() = stored.value(it.key());
  QVariantList activePresets;
  for (const auto &v : defaults.value("eqCustomPresets").toList()) {
    const auto p = v.toMap();
    if (!p.value("deleted").toBool() && !p.value("name").toString().trimmed().isEmpty())
      activePresets << p;
  }
  std::sort(activePresets.begin(), activePresets.end(), [](const QVariant &a, const QVariant &b) {
    return a.toMap().value("name").toString().compare(b.toMap().value("name").toString(), Qt::CaseInsensitive) < 0;
  });
  defaults["eqCustomPresets"] = activePresets;
  const auto track = current();
  defaults["speed"] = track.value("speed", 1.0);
  defaults["nightcore"] = track.value("nightcore", false);
  settingsSerial=s->settingsSerial();settingsTracksSerial=s->tracksSerial();settingsTrack=active;settingsSnapshot=defaults;return settingsSnapshot;
}
void App::setSetting(const QString &key, const QVariant &value) {
  if (key == "pauseFadeMs" && (!std::isfinite(value.toDouble()) || value.toDouble()<50 || value.toDouble()>5000)) {
    emit error("Pause fade must be between 0.05 and 5.00 seconds"); return;
  }
  if (key == "crossfadeMs") {
    const double ms = value.toDouble();
    if (!std::isfinite(ms) || ms < 0 || ms > 8000) {
      emit error("Transition fade must be between 0 and 8.0 seconds"); return;
    }
  }
  s->setSetting(key, value);
  if (key == "pauseFadeEnabled" || key == "pauseFadeMs") {
    const auto opts = settings();
    music.setPauseFade(opts["pauseFadeEnabled"].toBool(),opts["pauseFadeMs"].toInt());
    youtube.setPauseFade(opts["pauseFadeEnabled"].toBool(),opts["pauseFadeMs"].toInt());
  }
  if (key == "crossfadeMs") {
    music.setCrossfadeMs(value.toInt());
  }
  if (key == "smartShuffle" && m_mode == 2 && !entries.isEmpty()) {
    rebuildShuffle();
    emit queueChanged();
    save();
  }
  if (key == "pinnedPlaylists" || key == "pinnedArtists") {
    emit collectionsChanged();
  }
  if (key == "discordRpcEnabled") {
    discord.setEnabled(value.toBool());
    syncDiscordPresence(true);
  }
  if (key == "discordRpcLookupCovers") {
    discord.setLookupCovers(value.toBool());
  }
  if (key == "discordRpcUploadCovers") {
    discord.setUploadCovers(value.toBool());
  }
  if (key == "musicDir") {
    // if the library is still empty and this folder already has a saved mirror, pull it back automatically
    recovery.recoverFromFolder(value.toString());
  }
  if (!QStringList{"windowWidth", "windowHeight", "windowX", "windowY", "windowMaximized", "lastPage", "lastNotifiedUpdate"}.contains(key)) {
    recovery.requestSave();
  }
  emit settingsChanged();
}
void App::setSettings(const QVariantMap &values) {
  if (values.isEmpty()) return;
  if (values.contains("pauseFadeMs")) {
    const double ms = values.value("pauseFadeMs").toDouble();
    if (!std::isfinite(ms) || ms < 50 || ms > 5000) {
      emit error("Pause fade must be between 0.05 and 5.00 seconds");
      return;
    }
  }
  if (values.contains("crossfadeMs")) {
    const double ms = values.value("crossfadeMs").toDouble();
    if (!std::isfinite(ms) || ms < 0 || ms > 8000) {
      emit error("Transition fade must be between 0 and 8.0 seconds");
      return;
    }
  }
  // commit multi-key updates in one SQLite transaction and one settingsChanged signal
  const bool startedTx = s->db().transaction();
  for (auto it = values.cbegin(); it != values.cend(); ++it)
    s->setSetting(it.key(), it.value());
  if (startedTx) s->db().commit();

  if (values.contains("pauseFadeEnabled") || values.contains("pauseFadeMs")) {
    const auto opts = settings();
    music.setPauseFade(opts["pauseFadeEnabled"].toBool(), opts["pauseFadeMs"].toInt());
    youtube.setPauseFade(opts["pauseFadeEnabled"].toBool(), opts["pauseFadeMs"].toInt());
  }
  if (values.contains("crossfadeMs"))
    music.setCrossfadeMs(values.value("crossfadeMs").toInt());
  if (values.contains("smartShuffle") && m_mode == 2 && !entries.isEmpty()) {
    rebuildShuffle();
    emit queueChanged();
    save();
  }
  if (values.contains("pinnedPlaylists") || values.contains("pinnedArtists"))
    emit collectionsChanged();
  if (values.contains("discordRpcEnabled")) {
    discord.setEnabled(values.value("discordRpcEnabled").toBool());
    syncDiscordPresence(true);
  }
  if (values.contains("discordRpcLookupCovers"))
    discord.setLookupCovers(values.value("discordRpcLookupCovers").toBool());
  if (values.contains("discordRpcUploadCovers"))
    discord.setUploadCovers(values.value("discordRpcUploadCovers").toBool());
  if (values.contains("musicDir"))
    recovery.recoverFromFolder(values.value("musicDir").toString());

  static const QSet<QString> uiOnlyKeys{"windowWidth", "windowHeight", "windowX", "windowY",
                                        "windowMaximized", "lastPage", "lastNotifiedUpdate"};
  for (auto it = values.cbegin(); it != values.cend(); ++it) {
    if (!uiOnlyKeys.contains(it.key())) {
      recovery.requestSave();
      break;
    }
  }
  emit settingsChanged();
}

static QList<int> parseSemverParts(const QString &tag) {
  auto clean = tag.trimmed();
  if (clean.startsWith('v', Qt::CaseInsensitive)) clean.remove(0, 1);
  const int dash = clean.indexOf('-');
  if (dash >= 0) clean = clean.left(dash);
  if (clean.isEmpty()) return {};
  QList<int> out;
  for (const auto &piece : clean.split('.')) {
    bool ok = false;
    const int n = piece.toInt(&ok);
    if (!ok) return {};
    out << n;
  }
  return out;
}

static bool isNewerReleaseVersion(const QString &latestTag, const QString &currentVersion) {
  const auto latest = parseSemverParts(latestTag);
  const auto current = parseSemverParts(currentVersion);
  if (latest.isEmpty() || current.isEmpty()) return false;
  const int len = std::max(latest.size(), current.size());
  for (int i = 0; i < len; ++i) {
    const int a = i < latest.size() ? latest[i] : 0;
    const int b = i < current.size() ? current[i] : 0;
    if (a != b) return a > b;
  }
  return false;
}

void App::checkForUpdates(bool manual) {
  if (m_updateInfo.value("checking").toBool()) return;
  m_updateInfo["checking"] = true;
  if (manual) m_updateInfo["status"] = "Checking for updates…";
  emit updateChanged();
  QNetworkRequest req(QUrl("https://api.github.com/repos/Luis-Tanese/SillyPlayer/releases/latest"));
  req.setRawHeader("Accept", "application/vnd.github+json");
  req.setRawHeader("User-Agent", QByteArray("SillyPlayer/") + QByteArray(SILLYPLAYER_VERSION));
  req.setTransferTimeout(10000);
  auto *reply = updateNet.get(req);
  connect(reply, &QNetworkReply::finished, this, [this, reply, manual] {
    reply->deleteLater();
    if (closing) return;
    m_updateInfo["checking"] = false;
    if (reply->error() != QNetworkReply::NoError) {
      if (manual) m_updateInfo["status"] = "Could not check for updates: " + reply->errorString();
      emit updateChanged();
      return;
    }
    const auto doc = QJsonDocument::fromJson(reply->readAll());
    const auto obj = doc.object();
    if (obj.value("draft").toBool() || obj.value("prerelease").toBool()) {
      m_updateInfo["available"] = false;
      if (manual) m_updateInfo["status"] = QString("You're on the latest version (v%1)").arg(SILLYPLAYER_VERSION);
      emit updateChanged();
      return;
    }
    const QString tag = obj.value("tag_name").toString();
    QString url = obj.value("html_url").toString();
    if (!url.startsWith("https://github.com/"))
      url = "https://github.com/Luis-Tanese/SillyPlayer/releases";
    QString clean = tag;
    if (clean.startsWith('v', Qt::CaseInsensitive)) clean.remove(0, 1);
    if (isNewerReleaseVersion(tag, SILLYPLAYER_VERSION)) {
      m_updateInfo["available"] = true;
      m_updateInfo["version"] = clean;
      m_updateInfo["url"] = url;
      m_updateInfo["status"] = QString("Version %1 is available").arg(clean);
      emit updateChanged();
      const auto opts = settings();
      if (!manual && opts["updateNotifications"].toBool() &&
          opts["lastNotifiedUpdate"].toString() != clean) {
        s->setSetting("lastNotifiedUpdate", clean);
        emit updateAvailable(clean, url);
      }
    } else {
      m_updateInfo["available"] = false;
      m_updateInfo["version"] = clean;
      m_updateInfo["url"] = url;
      if (manual) m_updateInfo["status"] = QString("You're on the latest version (v%1)").arg(SILLYPLAYER_VERSION);
      emit updateChanged();
    }
  });
}

void App::openReleasePage() {
  QString url = m_updateInfo.value("url").toString();
  if (!url.startsWith("https://github.com/"))
    url = "https://github.com/Luis-Tanese/SillyPlayer/releases";
  QDesktopServices::openUrl(QUrl(url));
}
QVariantMap App::current() const {
  const auto id=index>=0&&index<entries.size()?entries[index].toMap().value("trackId").toString():QString();
  if(currentSerial!=s->tracksSerial() || currentSnapshotId!=id) {
    currentSnapshot=id.isEmpty()?QVariantMap{}:library.track(id);currentSnapshotId=id;currentSerial=s->tracksSerial();
  }
  return currentSnapshot;
}
int App::recentCount() const {
  return std::min(100,s->rows("SELECT COUNT(*) AS n FROM track_listens l JOIN tracks t ON t.id=l.track WHERE t.deleted=0").value(0).toMap().value("n").toInt());
}
QString App::recentCover() const {
  return s->rows("SELECT t.art FROM track_listens l JOIN tracks t ON t.id=l.track WHERE t.deleted=0 AND t.art<>'' ORDER BY l.lastPlayed DESC,t.id LIMIT 1")
      .value(0).toMap().value("art").toString();
}
QVariantList App::queue() const {
  if(entries.isEmpty())return {};
  if(queueSerial==s->tracksSerial() && queueSnapshotIndex==index && queueSnapshotVersion==queueVersion)return queueSnapshot;
  // expand queue occurrences in one indexed join instead of one query per song to avoid unnecessary work
  queueSnapshot=s->rows("SELECT t.*,EXISTS(SELECT 1 FROM likes WHERE track=t.id) AS liked,"
                 "json_extract(e.value,'$.entryId') AS entryId,"
                 "CAST(e.key AS INTEGER)=? AS isCurrent,CAST(e.key AS INTEGER)<? AS passed "
                 "FROM json_each(?) e LEFT JOIN tracks t ON t.id=json_extract(e.value,'$.trackId') "
                 "ORDER BY CAST(e.key AS INTEGER)",
                 {index,index,QString::fromUtf8(QJsonDocument::fromVariant(entries).toJson(QJsonDocument::Compact))});
  queueSerial=s->tracksSerial();queueSnapshotIndex=index;queueSnapshotVersion=queueVersion;return queueSnapshot;
}
// keep the queue's ordinary occurrence order independently from shuffle
// song ids are insufficient because a playlist/queue may contain a song twice
void App::rememberQueueOrder() {
  normalQueueOrder.clear();
  for (const auto &value : entries) normalQueueOrder << value.toMap()["entryId"].toString();
  markQueueDirty();
}
void App::reconcileQueueOrder() {
  QSet<QString> live, seen;
  for (const auto &value : entries) live.insert(value.toMap()["entryId"].toString());
  QStringList retained;
  retained.reserve(entries.size());
  for (const auto &id : normalQueueOrder) if (live.contains(id) && !seen.contains(id)) {
    retained << id; seen.insert(id);
  }
  for (const auto &value : entries) {
    const auto id=value.toMap()["entryId"].toString();
    if (!seen.contains(id)) { retained << id; seen.insert(id); }
  }
  if (retained != normalQueueOrder) {
    normalQueueOrder = retained;
    markQueueDirty();
  }
}
void App::restoreQueueOrder() {
  reconcileQueueOrder();
  const auto playing=index>=0 && index<entries.size()?entries[index].toMap()["entryId"].toString():QString();
  QHash<QString,QVariant> byId;
  for (const auto &value : entries) byId.insert(value.toMap()["entryId"].toString(),value);
  QVariantList ordered;
  ordered.reserve(entries.size());
  for (const auto &id : normalQueueOrder) ordered << byId.value(id);
  entries=ordered;
  index=playing.isEmpty()?-1:normalQueueOrder.indexOf(playing);
  markQueueDirty();
}
void App::insertedQueueOrder(const QStringList &added,bool next) {
  if (m_mode!=2) { rememberQueueOrder(); return; }
  // play next inserts beside the current occurrence in either mode; append remains an append when shuffle is later disabled
  const auto playing=index>=0 && index<entries.size()?entries[index].toMap()["entryId"].toString():QString();
  int at=next?normalQueueOrder.indexOf(playing)+1:normalQueueOrder.size();
  for (const auto &id : added) normalQueueOrder.insert(at++,id);
  reconcileQueueOrder();
  markQueueDirty();
}
void App::rebuildShuffle() {
  if (m_mode != 2 || entries.isEmpty())
    return;
  QVariant playing;
  if (index >= 0 && index < entries.size())
    playing = entries.takeAt(index);
  const auto previousOrder = entries;
  if (s->setting("smartShuffle", false).toBool() && entries.size() > 1) {
    QHash<QString, QPair<QString, qint64>> meta;
    for (const auto &v : s->rows(
             "SELECT t.id, t.artist, COALESCE(l.lastPlayed, 0) AS lastPlayed "
             "FROM tracks t LEFT JOIN track_listens l ON l.track = t.id WHERE t.deleted = 0")) {
      const auto row = v.toMap();
      meta.insert(row["id"].toString(),
                  {row["artist"].toString(), row["lastPlayed"].toLongLong()});
    }
    const QString currentArtist = playing.isValid()
        ? meta.value(playing.toMap().value("trackId").toString()).first
        : QString();
    QVector<SmartShuffleItem> pool;
    pool.reserve(entries.size());
    for (int i = 0; i < entries.size(); ++i) {
      const auto info = meta.value(entries[i].toMap().value("trackId").toString());
      pool.append({i, info.first, info.second});
    }
    const auto order = smartShuffleOrder(pool, currentArtist, *QRandomGenerator::global());
    QVariantList reordered;
    reordered.reserve(entries.size());
    for (int pos : order) reordered << entries[pos];
    entries = reordered;
  } else {
    std::shuffle(entries.begin(), entries.end(), *QRandomGenerator::global());
  }
  if (entries.size() > 1 && entries == previousOrder)
    std::rotate(entries.begin(), entries.begin() + 1, entries.end());
  if (playing.isValid()) {
    entries.prepend(playing);
    index = 0;
  }
  markQueueDirty();
}
void App::playTrack(const QString &id, const QVariantList &ids,
                    const QString &context, const QString &collection, const QString &member) {
  const bool sameTrack = current()["id"] == id && music.loaded();
  if (sameTrack && m_source.value("id").toString() == collection && m_context == context &&
      (member.isEmpty() || (index >= 0 && entries[index].toMap().value("sourceMember").toString() == member))) {
    music.play(); return;
  }
  auto t = library.track(id);
  if (!t["available"].toBool() || !QFileInfo(t["path"].toString()).isFile()) {
    emit error(
        "This track is unavailable. Reconnect its drive or rescan the folder.");
    return;
  }
  entries.clear();
  index = -1;
  for (auto v : ids) {
    QString track = v.typeId() == QMetaType::QString
                        ? v.toString()
                        : v.toMap()["id"].toString();
    if (track.isEmpty())
      continue;
    if (track == id && index < 0 && (member.isEmpty() || v.toMap().value("memberId").toString() == member))
      index = entries.size();
    entries << QVariantMap{{"entryId", uuid()}, {"trackId", track}, {"sourceMember", v.toMap().value("memberId")}};
  }
  if (index < 0) {
    index = 0;
    entries.prepend(QVariantMap{{"entryId", uuid()}, {"trackId", id}});
  }
  m_context = context;
  QString kind = "songs";
  if (collection == "liked") kind = "liked";
  else if (collection=="recent") kind="recent";
  else if (collection.startsWith("stats:")) kind="statistics";
  else if (collection.startsWith("artist:")) kind = "artist";
  else if (collection.startsWith("album:")) kind = "generated";
  else if (collection.startsWith("folder:")) kind = "folder";
  else if (!collection.isEmpty()) {
    const auto rows = s->rows("SELECT kind FROM collections WHERE id=?", {collection});
    if (!rows.isEmpty()) kind = rows.first().toMap()["kind"].toString();
  }
  m_source = {{"id", collection}, {"name", context}, {"kind", kind}};
  rememberQueueOrder();
  rebuildShuffle();
  if (sameTrack) { music.play(); emit queueChanged(); save(); }
  else playIndex(index, true);
}
void App::playIndex(int i, bool force, bool autoTransition) {
  if (i < 0 || i >= entries.size())
    return;
  if (i == index && !force) {
    music.play();
    return;
  }
  listening.flush();
  index = i;
  auto t = current();
  if (!QFileInfo(t["path"].toString()).isFile()) {
    emit error("Track is unavailable: " + t["title"].toString());
    emit queueChanged();
    return;
  }
  music.pausePlayback();
  music.setTrackVolume(t.value("volumePercent",100.0).toDouble());
  music.setSpeed(t.value("speed", 1.0).toDouble(), t.value("nightcore", false).toBool());
  listening.begin(t["id"].toString());
  music.load(t["path"].toString(), {}, {}, 0, false, autoTransition);
  emit currentChanged();
  emit queueChanged();
  save();
}
void App::advance(bool autoTransition) {
  if (entries.isEmpty())
    return;
  if (m_mode == 2 && index == entries.size() - 1)
    rebuildShuffle();
  for (int n = 1; n <= entries.size(); ++n) {
    int i = (index + n) % entries.size();
    auto t = library.track(entries[i].toMap()["trackId"].toString());
    if (t["available"].toBool() && QFileInfo(t["path"].toString()).isFile()) {
      playIndex(i, true, autoTransition);
      return;
    }
  }
  music.stop();
  emit error("No available tracks in this queue");
}
void App::next() {
  advance(false);
}
void App::previous() {
  if (entries.isEmpty())
    return;
  playIndex((index - 1 + entries.size()) % entries.size(), true);
}
void App::toggle() {
  if (music.loaded())
    music.toggle();
  else if (index >= 0)
    playIndex(index, true);
  else
    emit error("Choose a song to start playback");
}
void App::cycleMode() {
  const auto previous=m_mode;
  m_mode = (m_mode + 1) % 3;
  if (m_mode==2) { rememberQueueOrder(); rebuildShuffle(); }
  else if (previous==2) restoreQueueOrder();
  save();
  emit settingsChanged();
  if(m_mode==2 || previous==2)emit queueChanged();
}
void App::playNext(const QString &id) {
  if (library.track(id).isEmpty())
    return;
  const auto occurrence=uuid();
  entries.insert(std::clamp(index + 1, 0, int(entries.size())),
                 QVariantMap{{"entryId", occurrence}, {"trackId", id}});
  if (index < 0) {
    index = 0;
    emit currentChanged();
  }
  insertedQueueOrder({occurrence},true);
  emit queueChanged();
  save();
}
QVariantMap App::queueTracks(const QStringList &tracks, bool next) {
  int at=next ? std::clamp(index+1,0,int(entries.size())) : entries.size();
  const int first=at;
  int added=0, skipped=0;
  QStringList occurrences;
  for (const auto &id:tracks) {
    const auto t=library.track(id);
    if (t.isEmpty() || t.value("deleted").toBool() || !t.value("available").toBool() || !QFileInfo(t.value("path").toString()).isFile()) { ++skipped; continue; }
    const auto occurrence=uuid();occurrences << occurrence;
    entries.insert(at++,QVariantMap{{"entryId",occurrence},{"trackId",id}}); ++added;
  }
  if (added) {
    if (index<0) {index=first; emit currentChanged();}
    insertedQueueOrder(occurrences,next);
    emit queueChanged(); save();
  }
  return {{"status","done"},{"added",added},{"skipped",skipped}};
}
QVariantMap App::addTracksToCollection(const QString &collection, const QStringList &tracks, const QString &policy) {
  const bool liked=collection=="liked";
  const auto target=s->rows("SELECT kind FROM collections WHERE id=?",{collection});
  if ((!liked && (target.isEmpty() || !QStringList{"playlist","custom"}.contains(target.first().toMap()["kind"].toString()))) || !QStringList{"ask","skip","duplicates","auto"}.contains(policy))
    return {{"status","error"},{"message","This collection cannot be edited"}};
  const QString effectivePolicy = (policy == "auto")
    ? (s->setting("allowPlaylistDuplicates", false).toBool() ? QStringLiteral("duplicates") : QStringLiteral("skip"))
    : policy;
  QStringList valid;
  QSet<QString> existingTracks, existingVideos, seen, videos;
  if (liked) {
    for (const auto &v : s->rows("SELECT track FROM likes"))
      existingTracks.insert(v.toMap()["track"].toString());
  } else {
    for (const auto &v : s->rows("SELECT m.track, t.videoId FROM members m JOIN tracks t ON t.id=m.track WHERE m.collection=?", {collection})) {
      const auto row = v.toMap();
      existingTracks.insert(row["track"].toString());
      const QString vid = row["videoId"].toString();
      if (!vid.isEmpty()) existingVideos.insert(vid);
    }
  }
  QHash<QString, QString> liveVideoById;
  for (const auto &v : library.tracks()) {
    const auto row = v.toMap();
    liveVideoById.insert(row["id"].toString(), row["videoId"].toString());
  }
  int duplicates=0, skipped=0;
  for (const auto &id:tracks) {
    auto it = liveVideoById.constFind(id);
    if (it == liveVideoById.cend()) {++skipped; continue;}
    const QString &video = it.value();
    const bool duplicate = existingTracks.contains(id) || seen.contains(id) ||
        (!liked && !video.isEmpty() && (existingVideos.contains(video) || videos.contains(video)));
    seen.insert(id); if (!video.isEmpty()) videos.insert(video);
    if (duplicate) {
      ++duplicates;
      if (liked || effectivePolicy!="duplicates") {++skipped; continue;}
    }
    valid << id;
  }
  if (!liked && effectivePolicy=="ask" && duplicates)
    return {{"status","duplicate"},{"duplicates",duplicates}};
  if (!s->db().transaction()) return {{"status","error"},{"message","Could not begin playlist update"}};
  const auto max=s->rows("SELECT COALESCE(MAX(position),-1) AS position FROM members WHERE collection=?",{collection});
  int position=max.first().toMap()["position"].toInt()+1;
  bool ok=true;
  for (const auto &id:valid) {
    if (liked) ok=s->exec("INSERT OR IGNORE INTO likes VALUES(?)",{id}) && ok;
    else ok=s->exec("INSERT INTO members(id,collection,track,position) VALUES(?,?,?,?)",{uuid(),collection,id,position++}) && ok;
  }
  if (!ok || !s->db().commit()) {s->db().rollback(); return {{"status","error"},{"message","Could not save playlist changes"}};}
  if (!valid.isEmpty()) {if (liked) {QVariantMap patches;for(const auto &id:valid)patches[id]=QVariantMap{{"liked",true}};library.patchTracks(patches);} else emit collectionsChanged();}
  return {{"status","done"},{"added",valid.size()},{"skipped",skipped}};
}
int App::countPlaylistDuplicates(const QString &collection) const {
  // skip soft-deleted tracks so invisible rows dont mess up the duplicate count
  const auto rows = s->rows("SELECT m.track FROM members m JOIN tracks t ON t.id=m.track WHERE m.collection=? AND t.deleted=0 ORDER BY m.position,m.rowid", {collection});
  QSet<QString> seen;
  int duplicates = 0;
  for (const auto &v : rows) {
    const QString track = v.toMap().value("track").toString();
    if (!seen.contains(track)) seen.insert(track);
    else ++duplicates;
  }
  return duplicates;
}
QVariantMap App::removePlaylistDuplicates(const QString &collection) {
  const auto target = s->rows("SELECT kind FROM collections WHERE id=?", {collection});
  if (target.isEmpty() || !QStringList{"playlist", "custom"}.contains(target.first().toMap()["kind"].toString()))
    return {{"status", "error"}, {"message", "This collection cannot be edited"}};
  // only dedupe live non-deleted tracks so deleted rows dont steal the first slot
  const auto rows = s->rows("SELECT m.id,m.track FROM members m JOIN tracks t ON t.id=m.track WHERE m.collection=? AND t.deleted=0 ORDER BY m.position,m.rowid", {collection});
  QSet<QString> seen;
  QStringList keepIds, duplicateIds;
  for (const auto &v : rows) {
    const auto row = v.toMap();
    const QString id = row["id"].toString();
    const QString track = row["track"].toString();
    if (!seen.contains(track)) {
      seen.insert(track);
      keepIds << id;
    } else {
      duplicateIds << id;
    }
  }
  if (duplicateIds.isEmpty()) return {{"status", "done"}, {"removed", 0}};
  if (!s->db().transaction()) return {{"status", "error"}, {"message", "Could not begin playlist cleanup"}};
  bool ok = true;
  for (const auto &id : duplicateIds)
    ok = s->exec("DELETE FROM members WHERE id=? AND collection=?", {id, collection}) && ok;
  for (int i = 0; i < keepIds.size(); ++i)
    ok = s->exec("UPDATE members SET position=? WHERE id=? AND collection=?", {i, keepIds[i], collection}) && ok;
  if (!ok || !s->db().commit()) {
    s->db().rollback();
    return {{"status", "error"}, {"message", "Could not remove duplicate songs"}};
  }
  emit collectionsChanged();
  return {{"status", "done"}, {"removed", duplicateIds.size()}};
}
QVariantMap App::removeMembers(const QString &collection, const QStringList &members) {
  const auto target=s->rows("SELECT kind FROM collections WHERE id=?",{collection});
  if (target.isEmpty() || !QStringList{"playlist","custom"}.contains(target.first().toMap()["kind"].toString()))
    return {{"status","error"},{"message","This collection cannot be edited"}};
  if (!s->db().transaction()) return {{"status","error"},{"message","Could not begin collection update"}};
  bool ok=true; int removed=0;
  QSet<QString> seen;
  for (const auto &id:members) {
    if(seen.contains(id)) continue;
    seen.insert(id);
    ok=s->exec("DELETE FROM members WHERE id=? AND collection=?",{id,collection}) && ok;
    removed+=s->lastAffected();
  }
  if (!ok || !s->db().commit()) {s->db().rollback(); return {{"status","error"},{"message","Could not remove entries"}};}
  if (removed) emit collectionsChanged();
  return {{"status","done"},{"removed",removed}};
}
QVariantMap App::unlikeTracks(const QStringList &tracks) {
  if(!s->db().transaction()) return {{"status","error"},{"message","Could not begin Liked Songs update"}};
  bool ok=true; int removed=0;
  for(const auto &id:QSet<QString>(tracks.begin(),tracks.end())) {
    ok=s->exec("DELETE FROM likes WHERE track=?",{id}) && ok;
    removed+=s->lastAffected();
  }
  if(!ok || !s->db().commit()) {s->db().rollback(); return {{"status","error"},{"message","Could not update Liked Songs"}};}
  if(removed){QVariantMap patches;for(const auto &id:tracks)patches[id]=QVariantMap{{"liked",false}};library.patchTracks(patches);}
  return {{"status","done"},{"removed",removed}};
}
QVariantMap App::removeLibraryTracks(const QStringList &ids) {
  QStringList valid; QVariantList failures;
  for(const auto &id:QSet<QString>(ids.begin(),ids.end())) {
    const auto t=library.track(id);
    if(t.isEmpty() || t["deleted"].toBool() || !t["mergedInto"].toString().isEmpty())
      failures<<QVariantMap{{"id",id},{"error","Song is no longer active"}};
    else valid<<id;
  }
  if(!library.removeRecords(valid)) return {{"status","error"},{"message","No entries removed: database update failed or library is busy"}};
  removeTrashedFromQueue(valid);
  emit collectionsChanged(); emit currentChanged(); emit queueChanged(); save();
  return {{"status","done"},{"removed",valid.size()},{"failures",failures}};
}
bool App::trashTracks(const QStringList &tracks) {
  if(batchTrashing) return false;
  batchTrashing=true;
  if(!library.trashFiles(tracks)) {batchTrashing=false; return false;}
  return true;
}
void App::removeTrashedFromQueue(const QStringList &tracks) {
  const QSet<QString> removed(tracks.begin(),tracks.end());
  if(removed.isEmpty()) return;
  const bool playing=removed.contains(current().value("id").toString());
  const bool paused=music.requestedPause();
  const auto currentEntry=index>=0 && index<entries.size() ? entries[index].toMap().value("entryId").toString() : QString();
  QString following;
  if(playing) for(int n=1;n<entries.size();++n) {
    const auto candidate=entries[(index+n)%entries.size()].toMap();
    if(!removed.contains(candidate.value("trackId").toString())) {
      const auto t=library.track(candidate.value("trackId").toString());
      if(t.value("available").toBool() && QFileInfo(t.value("path").toString()).isFile()) {following=candidate.value("entryId").toString(); break;}
    }
  }
  if(playing) {listening.flush(); music.stop();}
  for(int i=entries.size()-1;i>=0;--i) if(removed.contains(entries[i].toMap().value("trackId").toString())) entries.removeAt(i);
  markQueueDirty();
  index=-1;
  const auto desired=playing ? following : currentEntry;
  for(int i=0;i<entries.size();++i) if(entries[i].toMap().value("entryId").toString()==desired) index=i;
  if(playing && index>=0 && !closing) {
    const auto t=current();
    music.setTrackVolume(t.value("volumePercent",100.0).toDouble());
    music.setSpeed(t.value("speed",1.0).toDouble(),t.value("nightcore",false).toBool());
    listening.begin(t.value("id").toString());
    music.load(t.value("path").toString(),{},{},0,paused);
  }
}
void App::queuePlay(const QString &entry) {
  for (int i = 0; i < entries.size(); ++i)
    if (entries[i].toMap()["entryId"] == entry) {
      if (i == index && music.loaded())
        music.play();
      else
        playIndex(i, true);
      return;
    }
}
void App::removeQueue(const QString &entry) {
  for (int i = 0; i < entries.size(); ++i)
    if (entries[i].toMap()["entryId"] == entry) {
      if (i == index)
        return;
      entries.removeAt(i);
      markQueueDirty();
      if (i < index)
        --index;
      emit queueChanged();
      save();
      return;
    }
}
void App::moveQueue(int from, int to) {
  if (from < 0 || to < 0 || from >= entries.size() || to >= entries.size())
    return;
  QString playing =
      index >= 0 ? entries[index].toMap()["entryId"].toString() : QString{};
  entries.move(from, to);
  rememberQueueOrder();
  for (int i = 0; i < entries.size(); ++i)
    if (entries[i].toMap()["entryId"] == playing)
      index = i;
  emit queueChanged();
  save();
}
void App::clearUpcoming() {
  if (entries.size() <= index + 1) return;
  while (entries.size() > index + 1)
    entries.removeLast();
  markQueueDirty();
  emit queueChanged();
  save();
}
void App::like(const QString &id) {
  auto t = library.track(id);
  if (t.isEmpty())
    return;
  const bool liked=t["liked"].toBool();
  const bool ok=liked?s->exec("DELETE FROM likes WHERE track=?",{id}):s->exec("INSERT OR IGNORE INTO likes VALUES(?)",{id});
  if(ok)library.patchTracks({{id,QVariantMap{{"liked",!liked}}}});
  else emit error("Could not save Liked Songs");
}
QVariantList App::collections() const {
  if(collectionSerial==s->catalogueSerial() && collectionSettingsSerial==s->settingsSerial())return collectionSnapshot;
  const QStringList pinnedPlaylistsList = s->setting("pinnedPlaylists").toStringList();
  const QStringList pinnedArtistsList = s->setting("pinnedArtists").toStringList();
  const QSet<QString> pinnedPlaylists(pinnedPlaylistsList.begin(), pinnedPlaylistsList.end());
  const QSet<QString> pinnedArtists(pinnedArtistsList.begin(), pinnedArtistsList.end());

  QVariantList rawCollections = s->rows(
      "SELECT c.*,c.rowid AS sortOrder,COUNT(CASE WHEN mt.deleted=0 THEN m.id END) AS count,"
      "COALESCE(SUM(CASE WHEN mt.deleted=0 THEN mt.duration ELSE 0 END),0) AS totalDuration,"
      "COALESCE(MAX(CASE WHEN mt.deleted=0 THEN mt.added ELSE 0 END),0) AS latestAdded,"
      "COALESCE(NULLIF(c.art,''),(SELECT t.art "
      "FROM members mm JOIN tracks t ON t.id=mm.track WHERE mm.collection=c.id "
      "AND t.deleted=0 AND t.art<>'' ORDER BY mm.position LIMIT 1),'') AS cover FROM "
      "collections c LEFT JOIN members m ON m.collection=c.id LEFT JOIN tracks mt ON mt.id=m.track GROUP BY c.id "
      "ORDER BY c.rowid");
  QVariantList out;
  QVariantList pinnedPlaylistRows, regularCollectionRows;
  for (auto &v : rawCollections) {
    auto row = v.toMap();
    const bool isPlaylist = row["kind"].toString() == "playlist";
    const bool pinned = isPlaylist && pinnedPlaylists.contains(row["id"].toString());
    row["pinned"] = pinned;
    if (pinned) pinnedPlaylistRows << row;
    else regularCollectionRows << row;
  }
  out.append(pinnedPlaylistRows);
  out.append(regularCollectionRows);

  // build generated albums and artists in a single pass over cached tracks so splitArtists runs once per track
  struct AlbumBucket {
    QString title;
    int count = 0;
    QString cover;
    QSet<QString> albumArtists;
    QString firstAlbumArtist;
    QHash<QString, int> artistHits;
    QHash<QString, QString> artistDisplay;
  };
  struct ArtistBucket { QString name; int count = 0; QString cover; };
  QHash<QString, AlbumBucket> albumBuckets;
  QHash<QString, ArtistBucket> artistBuckets;
  for (const auto &v : library.tracks()) {
    const auto row = v.toMap();
    const QString cover = row["art"].toString();
    const QString artistRaw = row["artist"].toString();
    const QStringList artists = artistRaw.trimmed().isEmpty() ? QStringList{} : splitArtists(artistRaw);
    const QString albumName = row["album"].toString().trimmed();
    if (!albumName.isEmpty()) {
      const QString key = albumName.toLower();
      auto &b = albumBuckets[key];
      if (b.title.isEmpty()) b.title = albumName;
      b.count++;
      if (b.cover.isEmpty() && !cover.isEmpty()) b.cover = cover;
      const QString aa = row["albumArtist"].toString().trimmed();
      if (!aa.isEmpty()) {
        b.albumArtists.insert(aa.toLower());
        if (b.firstAlbumArtist.isEmpty()) b.firstAlbumArtist = aa;
      }
      for (const auto &artistName : artists) {
        const QString ak = artistName.toLower();
        b.artistHits[ak]++;
        if (!b.artistDisplay.contains(ak)) b.artistDisplay.insert(ak, artistName);
      }
    }
    for (const auto &artistName : artists) {
      const QString key = artistName.toLower();
      auto &bucket = artistBuckets[key];
      if (bucket.name.isEmpty()) bucket.name = artistName;
      bucket.count++;
      if (bucket.cover.isEmpty() && !cover.isEmpty()) bucket.cover = cover;
    }
  }
  QVariantList albumRows;
  albumRows.reserve(albumBuckets.size());
  for (auto it = albumBuckets.cbegin(); it != albumBuckets.cend(); ++it) {
    const auto &b = it.value();
    QString owner;
    if (b.albumArtists.size() == 1) {
      owner = b.firstAlbumArtist;
    } else if (b.artistHits.size() == 1) {
      owner = b.artistDisplay.value(b.artistHits.cbegin().key());
    } else if (!b.artistHits.isEmpty()) {
      QString bestKey;
      int bestCount = 0;
      for (auto ait = b.artistHits.cbegin(); ait != b.artistHits.cend(); ++ait) {
        if (ait.value() > bestCount) { bestCount = ait.value(); bestKey = ait.key(); }
      }
      owner = bestCount * 2 >= b.count ? b.artistDisplay.value(bestKey) : QStringLiteral("Various artists");
    }
    albumRows << QVariantMap{{"id", "album:" + it.key()},
                             {"album", b.title},
                             {"name", b.title},
                             {"owner", owner},
                             {"count", b.count},
                             {"cover", b.cover},
                             {"kind", "generated"}};
  }
  std::sort(albumRows.begin(), albumRows.end(), [](const QVariant &a, const QVariant &b) {
    return a.toMap()["name"].toString().compare(b.toMap()["name"].toString(), Qt::CaseInsensitive) < 0;
  });
  out.append(albumRows);

  QVariantList artistRows;
  artistRows.reserve(artistBuckets.size());
  for (auto it = artistBuckets.cbegin(); it != artistBuckets.cend(); ++it) {
    const QString artistId = "artist:" + it.key();
    artistRows << QVariantMap{{"id", artistId},
                              {"artistKey", it.key()},
                              {"name", it.value().name},
                              {"count", it.value().count},
                              {"cover", it.value().cover},
                              {"pinned", pinnedArtists.contains(artistId)},
                              {"kind", "artist"}};
  }
  std::sort(artistRows.begin(), artistRows.end(), [](const QVariant &a, const QVariant &b) {
    const auto ma = a.toMap(), mb = b.toMap();
    if (ma["pinned"].toBool() != mb["pinned"].toBool()) return ma["pinned"].toBool();
    return ma["name"].toString().compare(mb["name"].toString(), Qt::CaseInsensitive) < 0;
  });
  out.append(artistRows);
  collectionSnapshot=out;collectionSerial=s->catalogueSerial();collectionSettingsSerial=s->settingsSerial();return collectionSnapshot;
}
QVariantList App::collectionTracks(const QString &id) const {
  if(id=="recent") return listening.recentTracks();
  if (id == "liked")
    return s->rows(
        "SELECT t.*,1 AS liked FROM likes l JOIN tracks t ON t.id=l.track "
        "WHERE t.deleted=0 ORDER BY t.title COLLATE NOCASE");
  if (id.startsWith("artist:")) {
    const QString target = id.mid(7).trimmed().toLower();
    QVariantList matched;
    // pre-filter in SQL before splitting multi-artist strings
    for (const auto &v : s->rows("SELECT t.*,EXISTS(SELECT 1 FROM likes WHERE track=t.id) AS liked FROM tracks t WHERE deleted=0 AND INSTR(LOWER(artist),?)>0 ORDER BY title COLLATE NOCASE,added", {target})) {
      const auto row = v.toMap();
      for (const auto &artistName : splitArtists(row["artist"].toString())) {
        if (artistName.toLower() == target) {
          matched << row;
          break;
        }
      }
    }
    return matched;
  }
  if (id.startsWith("album:")) {
    const QString key = id.mid(6).section('\n', 0, 0).trimmed().toLower();
    return s->rows(
        "SELECT t.*,EXISTS(SELECT 1 FROM likes WHERE track=t.id) AS liked FROM "
        "tracks t WHERE deleted=0 AND LOWER(TRIM(album))=? "
        "ORDER BY "
        "disc,number,title COLLATE NOCASE",
        {key});
  }
  return s->rows("SELECT t.*,m.id AS memberId,EXISTS(SELECT 1 FROM likes WHERE "
                 "track=t.id) AS liked FROM members m JOIN tracks t ON "
                 "t.id=m.track WHERE m.collection=? AND t.deleted=0 ORDER BY m.position",
                 {id});
}
QString App::createCollection(const QString &name, const QString &kind) {
  if (name.trimmed().isEmpty())
    return {};
  QString id = uuid();
  if (!s->exec("INSERT INTO collections(id,name,kind) VALUES(?,?,?)",
          {id, name.trimmed(), kind == "custom" ? "custom" : "playlist"})) {
    emit error("Could not create playlist");
    return {};
  }
  emit collectionsChanged();
  return id;
}
void App::renameCollection(const QString &id, const QString &name) {
  if(id=="recent" || id=="liked") return;
  if (!name.trimmed().isEmpty())
    s->exec("UPDATE collections SET name=? WHERE id=?", {name.trimmed(), id});
  if (m_source.value("id").toString() == id && !name.trimmed().isEmpty()) {
    m_context = name.trimmed(); m_source["name"] = m_context; emit queueChanged(); save();
  }
  emit collectionsChanged();
}
void App::deleteCollection(const QString &id) {
  if(id=="recent" || id=="liked") return;
  s->exec("DELETE FROM collections WHERE id=?", {id});
  auto pinned = s->setting("pinnedPlaylists").toStringList();
  if (pinned.removeAll(id) > 0) {
    s->setSetting("pinnedPlaylists", pinned);
    emit settingsChanged();
  }
  emit collectionsChanged();
}
bool App::isPinned(const QString &kind, const QString &id) const {
  if (id.isEmpty()) return false;
  const QString key = kind == "artist" ? QStringLiteral("pinnedArtists") : QStringLiteral("pinnedPlaylists");
  return s->setting(key).toStringList().contains(id);
}
void App::togglePin(const QString &kind, const QString &id) {
  if (id.isEmpty()) return;
  const QString key = kind == "artist" ? QStringLiteral("pinnedArtists") : QStringLiteral("pinnedPlaylists");
  auto list = s->setting(key).toStringList();
  if (list.contains(id)) list.removeAll(id);
  else list.append(id);
  s->setSetting(key, list);
  collectionSerial = quint64(-1);
  emit collectionsChanged();
  emit settingsChanged();
}
QVariantMap App::folderView(const QString &path) const {
  return buildFolderView(library.tracks(), library.folders(),
                         settings().value("musicDir").toString(), path);
}
void App::addFiles() {
  const auto files = QFileDialog::getOpenFileNames(
      nullptr, "Add audio files", {},
      "Audio files (*.mp3 *.flac *.ogg *.oga *.opus *.m4a *.aac *.wav *.webm *.mp4)");
  for (const auto &path : files) {
    if (!path.isEmpty()) library.importFile(path);
  }
}
void App::coverCollection(const QString &id) {
  if(id=="recent" || id=="liked") return;
  QString path = QFileDialog::getOpenFileName(
      nullptr, "Choose cover", {}, "Images (*.png *.jpg *.jpeg *.webp)");
  if (path.isEmpty())
    return;
  QImage image(path);
  if (image.isNull()) {
    emit error("Could not read that image");
    return;
  }
  QDir().mkpath(cache + "/covers");
  QString dest = cache + "/covers/" + uuid() + ".jpg";
  if (!image.scaled(1000, 1000, Qt::KeepAspectRatio, Qt::SmoothTransformation)
           .save(dest)) {
    emit error("Could not save cover");
    return;
  }
  s->exec("UPDATE collections SET art=? WHERE id=?",
          {QUrl::fromLocalFile(dest).toString(), id});
  emit collectionsChanged();
}
bool App::collectionContains(const QString &collection, const QString &track) const {
  if (collection == "liked") return library.track(track).value("liked").toBool();
  const auto item = library.track(track);
  // ignore deleted tracks here so old removed entries dont trigger false already-added labels
  return !s->rows("SELECT m.id FROM members m JOIN tracks t ON t.id=m.track WHERE m.collection=? AND t.deleted=0 AND (m.track=? OR (?<>'' AND t.videoId=?)) LIMIT 1",
    {collection, track, item.value("videoId", ""), item.value("videoId", "")}).isEmpty();
}
QString App::addToCollection(const QString &collection, const QString &track, bool allowDuplicate) {
  const auto result = addTracksToCollection(collection, {track}, allowDuplicate ? "duplicates" : "ask");
  const auto status = result.value("status").toString();
  if (status == "duplicate") return "duplicate";
  if (status == "error") { emit error(result.value("message").toString()); return "error"; }
  return result.value("added").toInt() || (collection == "liked" && collectionContains(collection, track)) ? "added" : "invalid";
}
void App::removeMember(const QString &collection, const QString &member) {
  if(collection=="recent") return;
  if (s->exec("DELETE FROM members WHERE collection=? AND id=?", {collection, member})) emit collectionsChanged();
}
void App::moveMember(const QString &collection, const QString &member, int to) {
  if(collection=="recent") return;
  auto rows = s->rows("SELECT id FROM members WHERE collection=? ORDER BY position", {collection});
  int from = -1;
  for (int i=0; i<rows.size(); ++i) if (rows[i].toMap()["id"] == member) from=i;
  if (from < 0 || to < 0 || to >= rows.size() || from == to) return;
  rows.move(from,to);
  if (!s->db().transaction()) return;
  bool ok=true;
  for (int i=0; i<rows.size(); ++i) ok = s->exec("UPDATE members SET position=? WHERE id=? AND collection=?", {i,rows[i].toMap()["id"],collection}) && ok;
  if (ok && s->db().commit()) emit collectionsChanged(); else { s->db().rollback(); emit error("Could not reorder collection"); }
}
void App::removeFromCollection(const QString &collection,
                               const QString &track) {
  if(collection=="recent") return;
  s->exec("DELETE FROM members WHERE id=(SELECT id FROM members WHERE "
          "collection=? AND track=? ORDER BY position LIMIT 1)",
          {collection, track});
  emit collectionsChanged();
}
QString App::chooseFolder(const QString &initial) {
  return QFileDialog::getExistingDirectory(nullptr, "Choose folder", initial);
}
QString App::chooseImage() {
  return QFileDialog::getOpenFileName(nullptr, "Choose artwork", {},
                                      "Images (*.png *.jpg *.jpeg *.webp)");
}
void App::openFile(const QString &path, bool folder) {
  QString p = QUrl(path).isLocalFile() ? QUrl(path).toLocalFile() : path;
  if (!QFileInfo::exists(p)) {
    emit error("This path no longer exists");
    return;
  }
  if (!QDesktopServices::openUrl(
          QUrl::fromLocalFile(folder ? QFileInfo(p).absolutePath() : p)))
    emit error("Could not open path");
}
void App::applySpeed(double value, bool nightcore) {
  applyTrackSpeed(current().value("id").toString(), value, nightcore);
}
void App::applyTrackSpeed(const QString &id, double value, bool nightcore) {
  if (!std::isfinite(value) || value < 0.5 || value > 2.0) { emit error("Speed must be between 0.50 and 2.00×"); return; }
  const auto track = library.track(id);
  if (track.isEmpty() || track.value("deleted").toBool()) return;
  if (track.value("speed",1.0).toDouble()==value && track.value("nightcore",false).toBool()==nightcore) {
    if (current().value("id").toString()==id) music.setSpeed(value,nightcore);
    return;
  }
  if (!s->exec("UPDATE tracks SET speed=?,nightcore=? WHERE id=?", {value,nightcore,id})) { emit error("Could not save this song's playback speed"); return; }
  if (current().value("id").toString() == id) music.setSpeed(value, nightcore);
  library.patchTracks({{id,QVariantMap{{"speed",value},{"nightcore",nightcore}}}});emit settingsChanged();
}
void App::previewTrackSpeed(const QString &id, double value, bool nightcore) {
  if (!std::isfinite(value) || value<0.5 || value>2 || music.paused()) return;
  if (current().value("id").toString()==id) music.setSpeed(value,nightcore);
}
void App::applyTrackVolume(const QString &id,double percent) {
  if(!std::isfinite(percent) || percent<0 || percent>300) {emit error("Song volume must be between 0 and 300%");return;}
  const auto track=library.track(id);
  if(track.isEmpty() || track.value("deleted").toBool()) return;
  if(track.value("volumePercent",100.0).toDouble()!=percent) {
    if(!s->exec("UPDATE tracks SET volumePercent=? WHERE id=?",{percent,id})) {emit error("Could not save this song's volume");return;}
    library.patchTracks({{id,QVariantMap{{"volumePercent",percent}}}});
  }
  if(current().value("id").toString()==id) music.setTrackVolume(percent);
}
void App::previewTrackVolume(const QString &id,double percent) {
  if(std::isfinite(percent) && percent>=0 && percent<=300 && current().value("id").toString()==id) music.setTrackVolume(percent);
}
QString App::modifierLabel(const QVariantMap &track) const {
  const auto speed=speedLabel(track);
  const double percent=track.value("volumePercent",100.0).toDouble();
  if(std::abs(percent-100.0)<0.005) return speed;
  const auto volume=QString("Vol %1%").arg(QString::number(percent,'f',0));
  return speed.isEmpty()?volume:speed+" · "+volume;
}
QString App::sourceLink(const QString &id) const {
  const auto track=library.track(id); const auto url=track.value("sourceUrl").toString();
  if (Source::http(url)) return Source::providerForUrl(url)=="youtube" ? youtubeLink(id) : url;
  return track.value("sourceProvider").toString()=="youtube" ? youtubeLink(id) : QString();
}
QString App::youtubeLink(const QString &id) const {
  const auto provider=library.track(id).value("sourceProvider").toString();
  if (provider=="soundcloud" || provider=="direct") return {};
  const auto track = library.track(id);
  const auto key = ExtractionService::key(track.value("sourceUrl").toString());
  if (key.startsWith("youtube:") && key.size()==19) return "https://www.youtube.com/watch?v="+key.mid(8);
  const auto video = track.value("videoId").toString();
  static const QRegularExpression valid("^[A-Za-z0-9_-]{11}$");
  return valid.match(video).hasMatch() ? "https://www.youtube.com/watch?v="+video : QString();
}
bool App::copyLink(const QString &id) {
  const auto link = sourceLink(id);
  if (link.isEmpty()) { emit error("No source link is saved for this song"); return false; }
  QGuiApplication::clipboard()->setText(link);
  return true;
}
void App::openLink(const QString &id) {
  const auto link = sourceLink(id);
  if (link.isEmpty() || !QDesktopServices::openUrl(QUrl(link))) emit error("Could not open this song's source link");
}
QString App::speedLabel(const QVariantMap &track) const {
  const double rate = track.value("speed",1.0).toDouble();
  const bool pitch = track.value("nightcore",false).toBool();
  if (std::abs(rate-1.0)<0.005 && !pitch) return {};
  return QString::number(rate,'f',2) + QString::fromUtf8("×") + (pitch ? (rate < 1.0 ? " · Daycore" : " · Nightcore") : "");
}
void App::trashTrack(const QString &id) {
  if (!library.trashFile(id)) return;
  forgetRemovedTrack(id);
}
QVariantMap App::clearMusicFolder(const QString &folderPath) {
  if (library.scanning() || library.trashing() || library.inMaintenance() ||
      sync.busy() || backup.busy()) {
    const QString msg = "Wait for current library, sync, or backup work to finish first";
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  const QString rawTarget =
      folderPath.trimmed().isEmpty() ? settings().value("musicDir").toString() : folderPath.trimmed();
  const QString cleanTarget = QDir::cleanPath(QFileInfo(rawTarget).absoluteFilePath());
  const QString home = QDir::cleanPath(QDir::homePath());
  const QFileInfo dirInfo(cleanTarget);
  if (cleanTarget.isEmpty() || !dirInfo.isDir() || cleanTarget == "/" ||
      cleanTarget == home || cleanTarget.length() <= 3) {
    const QString msg = "Select a specific music subfolder before clearing it";
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  static const QSet<QString> audioExts{"mp3", "flac", "ogg", "oga", "opus",
                                       "m4a", "aac",  "wav", "webm", "mp4"};
  QSet<QString> trashedPaths;
  QStringList failedFiles;
  QDirIterator it(cleanTarget, QDir::Files, QDirIterator::Subdirectories);
  while (it.hasNext()) {
    const QString path = it.next();
    const QFileInfo fi(path);
    if (!audioExts.contains(fi.suffix().toLower()))
      continue;
    QFile file(path);
    if (file.moveToTrash())
      trashedPaths.insert(QDir::cleanPath(fi.absoluteFilePath()));
    else
      failedFiles << fi.fileName() + ": " + file.errorString();
  }
  const QString prefix = cleanTarget.endsWith('/') ? cleanTarget : cleanTarget + '/';
  QStringList removedIds;
  for (const auto &v : s->rows("SELECT id,path FROM tracks WHERE deleted=0 AND mergedInto=''")) {
    const auto row = v.toMap();
    const QString trackPath = QDir::cleanPath(QFileInfo(row["path"].toString()).absoluteFilePath());
    if (trackPath.startsWith(prefix) || trashedPaths.contains(trackPath))
      removedIds << row["id"].toString();
  }
  if (!removedIds.isEmpty()) {
    library.removeRecords(removedIds);
    removeTrashedFromQueue(removedIds);
    emit collectionsChanged();
    emit currentChanged();
    emit queueChanged();
    save();
  }
  QString msg = QString("Moved %1 audio files to Trash").arg(trashedPaths.size());
  if (!failedFiles.isEmpty())
    msg += QString(" · %1 failed").arg(failedFiles.size());
  return {{"ok", failedFiles.isEmpty()},
          {"trashed", trashedPaths.size()},
          {"failed", failedFiles.size()},
          {"message", msg}};
}
QVariantMap App::reinstateFolder(const QString &folderPath) {
  if (library.scanning() || library.trashing() || library.inMaintenance() ||
      sync.busy() || backup.busy()) {
    const QString msg = "Wait for current library, sync, or backup work to finish first";
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  const QString target =
      folderPath.trimmed().isEmpty() ? settings().value("musicDir").toString() : folderPath.trimmed();
  auto res = library.reinstateFolder(target);
  if (res.value("ok").toBool()) {
    // persist reinstated songs, restored links, and covers into the portable folder db right away
    const auto saved = recovery.saveNow(false);
    if (saved.value("ok").toBool()) {
      res["dbSaved"] = true;
      res["message"] = res.value("message").toString() + QStringLiteral(" · Database saved");
    }
    syncDiscordPresence(true);
  }
  return res;
}
QVariantMap App::repairDownloadedSongs() {
  if (library.scanning() || library.trashing() || library.inMaintenance() ||
      sync.busy() || backup.busy()) {
    const QString msg = "Wait for current library, sync, or backup work to finish first";
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  return online.repairDownloadedSongs();
}
QVariantMap App::resetDesktopLibrary(bool removeCachedMedia) {
  if (!settings().value("developerMode").toBool()) {
    const QString msg = "Enable Developer mode first";
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  if (library.scanning() || library.trashing() || library.inMaintenance() ||
      sync.busy() || backup.busy()) {
    const QString msg = "Finish or cancel scanning, sync, and backup before resetting";
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  music.stop();
  youtube.stop();
  sleepTimer.cancel();
  online.resetJobs();
  entries.clear();
  normalQueueOrder.clear();
  markQueueDirty();
  savedQueueVersion = quint64(-1);
  index = -1;
  m_context = "Songs";
  m_source = {{"kind", "songs"}, {"id", ""}, {"name", "Songs"}};

  if (!s->db().transaction()) {
    const QString msg = "Could not start library reset";
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  // flip applying=1 so resetting locally never emits deletion tombstones to paired phones
  bool ok = s->exec("UPDATE sync_control SET applying=1 WHERE id=1");
  const QStringList statements = {
      "DELETE FROM playlist_download_items",
      "DELETE FROM jobs",
      "DELETE FROM members",
      "DELETE FROM likes",
      "DELETE FROM collections",
      "DELETE FROM track_roots",
      "DELETE FROM track_hashes",
      "DELETE FROM listening_plays",
      "DELETE FROM track_listens",
      "DELETE FROM sync_counters",
      "DELETE FROM rpc_covers",
      "DELETE FROM tracks",
      "DELETE FROM roots",
      "DELETE FROM restore_ids",
      "DELETE FROM searches",
      "DELETE FROM sync_dirty",
      "DELETE FROM sync_entities",
      "DELETE FROM sync_changes",
      "DELETE FROM sync_operations",
      "DELETE FROM sync_conflicts",
      "DELETE FROM sync_aliases",
      "DELETE FROM sync_blobs",
      "DELETE FROM sync_media_pending",
      "UPDATE sync_peers SET ack=0",
      "UPDATE listening_totals SET milliseconds=0,started=CAST(strftime('%s','now') AS INTEGER)*1000 WHERE id=1",
      "DELETE FROM settings WHERE key IN ('queue','queueOrder','queueIndex','queueContext','queueSource','position','listeningSession','pinnedPlaylists','pinnedArtists')",
  };
  for (const auto &sql : statements) {
    if (ok) ok = s->exec(sql);
  }
  if (ok) ok = s->exec("UPDATE sync_control SET applying=0 WHERE id=1");
  if (!ok || !s->db().commit()) {
    s->db().rollback();
    s->exec("UPDATE sync_control SET applying=0 WHERE id=1");
    const QString msg = "Could not reset desktop library: " + s->error();
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  if (removeCachedMedia) {
    for (const auto &sub : {"art", "covers", "youtube-art", "sync"}) {
      QDir(cache + "/" + sub).removeRecursively();
    }
  }
  currentSerial = collectionSerial = collectionSettingsSerial = queueSerial = settingsSerial = quint64(-1);
  queueSnapshotVersion = quint64(-1);
  savedPlayback.clear();
  listening.resetState();
  sync.resetLocalState();
  emit library.changed();
  emit library.likesChanged();
  emit collectionsChanged();
  emit currentChanged();
  emit queueChanged();
  emit recentChanged();
  emit settingsChanged();
  return {{"ok", true},
          {"message", "Desktop library reset. Original audio files and paired phones were kept."}};
}
void App::removeLibraryTrack(const QString &id) {
  removeLibraryTracks({id});
}
void App::forgetRemovedTrack(const QString &id) {
  const bool playing = current().value("id").toString() == id;
  const QString currentEntry = index >= 0 && index < entries.size() ? entries[index].toMap().value("entryId").toString() : QString();
  QString followingEntry;
  if (playing) for (int n=1;n<entries.size();++n) {
    const auto candidate = entries[(index+n)%entries.size()].toMap();
    if (candidate.value("trackId").toString()!=id) { followingEntry=candidate.value("entryId").toString(); break; }
  }
  if (playing) music.stop();
  for (int i=entries.size()-1;i>=0;--i) if (entries[i].toMap().value("trackId").toString()==id) entries.removeAt(i);
  markQueueDirty();
  index = -1;
  if (!playing) for (int i=0;i<entries.size();++i) if (entries[i].toMap().value("entryId").toString()==currentEntry) index=i;
  if (playing && !entries.isEmpty()) {
    int nextIndex = 0;
    for (int i=0;i<entries.size();++i) if (entries[i].toMap().value("entryId").toString()==followingEntry) nextIndex=i;
    playIndex(nextIndex,true);
  }
  emit currentChanged(); emit queueChanged(); save();
}
QString App::cleanTitle(const QString &title) const {
  return cleanSongTitle(title);
}
void App::syncDiscordPresence(bool force) {
  if (!discord.enabled())
    return;
  const auto t = current();
  const bool playing = music.loaded() && !music.paused() && !music.pausing();
  const quint64 seekGen = music.seekGeneration();
  const QString key = t.value("id").toString() + "|" + t.value("title").toString() + "|" +
                      t.value("artist").toString() + "|" + t.value("album").toString() + "|" +
                      QString::number(t.value("speed", 1.0).toDouble(), 'f', 2) + "|" +
                      (t.value("nightcore", false).toBool() ? "1" : "0") + "|" +
                      t.value("sourceUrl").toString();
  if (!force && key == lastDiscordTrackKey && playing == lastDiscordPlaying &&
      seekGen == lastDiscordSeekGen)
    return;
  lastDiscordTrackKey = key;
  lastDiscordPlaying = playing;
  lastDiscordSeekGen = seekGen;
  if (t.isEmpty()) {
    discord.clearPresence();
    return;
  }
  discord.updatePresence(t, playing, music.position(), music.duration());
}
QVariantMap App::exportPlaylistM3u(const QString &collectionId) {
  const auto tracks = collectionId.isEmpty() ? library.tracks() : collectionTracks(collectionId);
  if (tracks.isEmpty()) {
    const QString msg = "No songs to export in this playlist";
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  QString baseName = "Playlist";
  if (collectionId == "liked")
    baseName = "Liked Songs";
  else if (collectionId == "recent")
    baseName = "Recently Played";
  else if (!collectionId.isEmpty()) {
    for (const auto &v : collections()) {
      const auto c = v.toMap();
      if (c.value("id").toString() == collectionId) {
        baseName = c.value("name").toString();
        break;
      }
    }
  }
  QString safe = baseName;
  safe.replace(QRegularExpression(R"([\\/:*?"<>|]+)"), "_");
  safe = safe.trimmed();
  if (safe.isEmpty())
    safe = "Playlist";
  const QString suggested =
      QDir(settings().value("musicDir").toString()).filePath(safe + ".m3u8");
  const QString dest = QFileDialog::getSaveFileName(
      nullptr, "Export playlist", suggested, "M3U Playlists (*.m3u8 *.m3u)");
  if (dest.isEmpty())
    return {{"ok", false}, {"cancelled", true}, {"message", ""}};
  const QString content = formatM3u(tracks);
  QSaveFile out(dest);
  if (!out.open(QIODevice::WriteOnly | QIODevice::Text) ||
      out.write(content.toUtf8()) < 0 || !out.commit()) {
    const QString msg = "Could not write playlist file: " + out.errorString();
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  const QString msg = QString("Exported %1 %2 to %3")
                          .arg(tracks.size())
                          .arg(tracks.size() == 1 ? "song" : "songs", QFileInfo(dest).fileName());
  return {{"ok", true}, {"count", tracks.size()}, {"path", dest}, {"message", msg}};
}
QVariantMap App::importPlaylistM3u() {
  const QString path = QFileDialog::getOpenFileName(
      nullptr, "Import playlist", settings().value("musicDir").toString(),
      "M3U Playlists (*.m3u8 *.m3u);;All Files (*)");
  if (path.isEmpty())
    return {{"ok", false}, {"cancelled", true}, {"message", ""}};
  QFile file(path);
  if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
    const QString msg = "Could not read playlist file: " + file.errorString();
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }
  const QFileInfo fi(path);
  const auto entries = parseM3u(QString::fromUtf8(file.readAll()), fi.absolutePath());
  if (entries.isEmpty()) {
    const QString msg = "No songs found in that M3U playlist";
    emit error(msg);
    return {{"ok", false}, {"message", msg}};
  }

  const auto allTracks = library.tracks();
  QHash<QString, QString> byPath, byFileName, byArtistTitle, byTitle;
  for (const auto &v : allTracks) {
    const auto t = v.toMap();
    const QString id = t.value("id").toString();
    const QString tPath = QDir::cleanPath(QFileInfo(t.value("path").toString()).absoluteFilePath());
    if (!tPath.isEmpty())
      byPath.insert(tPath.toLower(), id);
    const QString fn = QFileInfo(t.value("path").toString()).fileName().toLower();
    if (!fn.isEmpty() && !byFileName.contains(fn))
      byFileName.insert(fn, id);
    const QString title = t.value("title").toString().trimmed().toLower();
    const QString artist = t.value("artist").toString().trimmed().toLower();
    if (!title.isEmpty()) {
      if (!byTitle.contains(title))
        byTitle.insert(title, id);
      if (!artist.isEmpty())
        byArtistTitle.insert(artist + "|" + title, id);
    }
  }

  QStringList matchedIds;
  int unmatched = 0;
  for (const auto &entry : entries) {
    QString found;
    if (!entry.path.isEmpty()) {
      const QString clean = QDir::cleanPath(QFileInfo(entry.path).absoluteFilePath()).toLower();
      found = byPath.value(clean);
      if (found.isEmpty()) {
        const QString fn = QFileInfo(entry.path).fileName().toLower();
        found = byFileName.value(fn);
      }
    }
    if (found.isEmpty() && !entry.title.isEmpty()) {
      const QString tLower = entry.title.trimmed().toLower();
      const QString aLower = entry.artist.trimmed().toLower();
      if (!aLower.isEmpty())
        found = byArtistTitle.value(aLower + "|" + tLower);
      if (found.isEmpty())
        found = byTitle.value(tLower);
    }
    if (!found.isEmpty())
      matchedIds << found;
    else
      unmatched++;
  }

  if (matchedIds.isEmpty()) {
    const QString msg = QString("None of the %1 songs in %2 matched your library")
                            .arg(entries.size())
                            .arg(fi.fileName());
    emit error(msg);
    return {{"ok", false}, {"matched", 0}, {"unmatched", unmatched}, {"message", msg}};
  }
  const QString name = fi.completeBaseName().trimmed().isEmpty() ? QStringLiteral("Imported Playlist")
                                                                 : fi.completeBaseName().trimmed();
  const QString cid = createCollection(name, "playlist");
  if (cid.isEmpty())
    return {{"ok", false}, {"message", "Could not create imported playlist"}};
  addTracksToCollection(cid, matchedIds, "duplicates");
  QString msg = QString("Imported \"%1\" · %2 %3 matched")
                    .arg(name)
                    .arg(matchedIds.size())
                    .arg(matchedIds.size() == 1 ? "song" : "songs");
  if (unmatched > 0)
    msg += QString(" · %1 not in library").arg(unmatched);
  return {{"ok", true},
          {"collectionId", cid},
          {"name", name},
          {"matched", matchedIds.size()},
          {"unmatched", unmatched},
          {"message", msg}};
}
void App::openTrash() {
#ifdef Q_OS_WIN
  if (!QProcess::startDetached("explorer.exe", {"shell:RecycleBinFolder"}))
    emit error("Could not open the Recycle Bin");
#else
  if (!QDesktopServices::openUrl(QUrl("trash:///"))) emit error("Could not open the system Trash");
#endif
}
void App::previewEq(bool enabled, const QVariantList &gains) {
  music.setEq(enabled, gains);
}
void App::applyEq(bool enabled, const QVariantList &gains, const QString &preset) {
  music.setEq(enabled, gains);
  const qint64 now = QDateTime::currentMSecsSinceEpoch();
  const qint64 stamp = qMax(now, s->setting("eqStateUpdatedAt", 0).toLongLong() + 1);
  const bool startedTx = s->db().transaction();
  s->setSetting("eqEnabled", enabled);
  s->setSetting("eqGains", gains);
  if (!preset.isEmpty()) s->setSetting("eqPreset", preset);
  s->setSetting("eqStateUpdatedAt", stamp);
  if (startedTx) s->db().commit();
  recovery.requestSave();
  emit settingsChanged();
}
QVariantMap App::saveEqPreset(const QString &name, const QVariantList &gains) {
  const QString trimmed = name.trimmed();
  if (trimmed.isEmpty()) return {{"ok", false}, {"error", "Enter a preset name"}};
  for (const auto &builtin : {"Flat", "Bass Boost", "Rock", "Pop", "Classical", "Custom"}) {
    if (trimmed.compare(QLatin1String(builtin), Qt::CaseInsensitive) == 0)
      return {{"ok", false}, {"error", "Choose a name different from built-in presets"}};
  }
  QVariantList normalized;
  normalized.reserve(10);
  for (int i = 0; i < 10; ++i) {
    const double g = i < gains.size() ? gains[i].toDouble() : 0.0;
    normalized << (std::isfinite(g) ? std::clamp(g, -12.0, 12.0) : 0.0);
  }
  const qint64 now = QDateTime::currentMSecsSinceEpoch();
  QVariantList list = s->setting("eqCustomPresets", QVariantList{}).toList();
  QString existingId;
  qint64 prevMaxAt = 0;
  for (const auto &v : list) {
    const auto p = v.toMap();
    if (p.value("name").toString().compare(trimmed, Qt::CaseInsensitive) == 0) {
      prevMaxAt = qMax(prevMaxAt, p.value("updatedAt").toLongLong());
      if (!p.value("deleted").toBool() && existingId.isEmpty())
        existingId = p.value("id").toString();
    }
  }
  // bump past previous timestamp so clock skew with a paired phone cant silently swallow local saves
  const qint64 stamp = qMax(now, prevMaxAt + 1);
  const QString id = existingId.isEmpty() ? uuid() : existingId;
  QVariantMap entry{{"id", id}, {"name", trimmed}, {"gains", normalized}, {"updatedAt", stamp}, {"deleted", false}};
  const auto merged = SyncManager::mergeEqPresets(list, QVariantList{entry});
  s->setSetting("eqCustomPresets", merged);
  const bool enabled = s->setting("eqEnabled", false).toBool();
  music.setEq(enabled, normalized);
  s->setSetting("eqGains", normalized);
  s->setSetting("eqPreset", trimmed);
  s->setSetting("eqStateUpdatedAt", qMax(stamp, s->setting("eqStateUpdatedAt", 0).toLongLong() + 1));
  recovery.requestSave();
  emit settingsChanged();
  return {{"ok", true}, {"preset", entry}};
}
QVariantMap App::deleteEqPreset(const QString &id) {
  const qint64 now = QDateTime::currentMSecsSinceEpoch();
  QVariantList list = s->setting("eqCustomPresets", QVariantList{}).toList();
  QString deletedName;
  QString targetId;
  qint64 prevMaxAt = 0;
  for (const auto &v : list) {
    const auto p = v.toMap();
    if (!p.value("deleted").toBool() && (p.value("id").toString() == id || p.value("name").toString().compare(id, Qt::CaseInsensitive) == 0)) {
      targetId = p.value("id").toString();
      deletedName = p.value("name").toString();
      prevMaxAt = qMax(prevMaxAt, p.value("updatedAt").toLongLong());
      break;
    }
  }
  if (targetId.isEmpty()) return {{"ok", false}, {"error", "Preset not found"}};
  // keep timestamp strictly newer than the preset so local deletes always stick even with clock drift
  const qint64 stamp = qMax(now, prevMaxAt + 1);
  QVariantMap tombstone{{"id", targetId}, {"name", deletedName}, {"gains", QVariantList{}}, {"updatedAt", stamp}, {"deleted", true}};
  s->setSetting("eqCustomPresets", SyncManager::mergeEqPresets(list, QVariantList{tombstone}));
  if (s->setting("eqPreset").toString().compare(deletedName, Qt::CaseInsensitive) == 0) {
    s->setSetting("eqPreset", "Custom");
    s->setSetting("eqStateUpdatedAt", qMax(stamp, s->setting("eqStateUpdatedAt", 0).toLongLong() + 1));
  }
  recovery.requestSave();
  emit settingsChanged();
  return {{"ok", true}};
}
QString App::time(double value) const {
  if (value < 0 || !std::isfinite(value))
    return "–:––";
  int n = int(value);
  return QString::number(n / 60) + ":" +
         QString::number(n % 60).rightJustified(2, '0');
}
void App::save() {
  const bool queueChangedFlag = (savedQueueVersion != queueVersion);
  if (queueChangedFlag) reconcileQueueOrder();
  QVariantMap scalarSnapshot{
      {"mode", m_mode},
      {"queueIndex", index},
      {"queueContext", m_context},
      {"queueSource", m_source},
      {"position", music.position()},
      {"volume", music.volume()},
      {"youtubeVolume", youtube.volume()}};
  QVariantMap updates;
  if (queueChangedFlag) {
    updates.insert("queue", entries);
    updates.insert("queueOrder", normalQueueOrder);
  }
  for (auto it = scalarSnapshot.cbegin(); it != scalarSnapshot.cend(); ++it)
    if (!savedPlayback.contains(it.key()) || savedPlayback.value(it.key()) != it.value())
      updates.insert(it.key(), it.value());
  if (updates.isEmpty()) return;
  const bool startedTx = s->db().transaction();
  for (auto it = updates.cbegin(); it != updates.cend(); ++it)
    s->setSetting(it.key(), it.value());
  if (!startedTx || s->db().commit()) {
    for (auto it = scalarSnapshot.cbegin(); it != scalarSnapshot.cend(); ++it)
      savedPlayback.insert(it.key(), it.value());
    if (queueChangedFlag) savedQueueVersion = queueVersion;
  }
}
void App::shutdown() {
  if (closing)
    return;
  closing = true;
  saver.stop();
  updateTimer.stop();
  library.requestStop();
  discord.shutdown();
  sync.shutdown();
  sleepTimer.cancel();
  libraryTools.shutdown();
  backup.shutdown();
  recovery.shutdown();
  library.finishTrash();
  listening.shutdown();
  save();
  catalogue.cancel();
  online.shutdown();
  music.pausePlayback();
  youtube.pausePlayback();
}
