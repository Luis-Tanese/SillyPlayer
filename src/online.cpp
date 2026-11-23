#include "online.h"
#include "source.h"
#include "youtubeplaylist.h"
#include "directtransfer.h"
#include "extraction.h"
#include "processutils.h"
#include "thumbnailurls.h"
#include <QTemporaryFile>
#include <QSaveFile>
#include <QSet>
#include <QCryptographicHash>
#include <QImage>
#include <QImageReader>
#include <QBuffer>
#include <QNetworkRequest>
#include <QElapsedTimer>
#include <QCoreApplication>
#include <QDateTime>
#include <QDesktopServices>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStandardPaths>
#include <QUrl>
#include <QUuid>
#include <signal.h>
#include <memory>

static QString pathOf(const QString &url) {
  return QUrl(url).isLocalFile() ? QUrl(url).toLocalFile() : url;
}
QVariantMap Online::normalizeResult(QVariantMap e) {
  if (e.value("thumbnail").toString().isEmpty()) {
    QString thumbnail;
    int largest = -1;
    for (const auto &value : e.value("thumbnails").toList()) {
      auto candidate = value.toMap();
      int width = candidate.value("width").toInt();
      if (!candidate.value("url").toString().isEmpty() && width > largest) {
        thumbnail = candidate.value("url").toString();
        largest = width;
      }
    }
    e["thumbnail"] = thumbnail;
  }
  if (e.value("webpage_url").toString().isEmpty()) {
    QString url = e.value("url").toString();
    if (!Source::http(url))
      url = Source::provider(e)=="youtube" ? "https://www.youtube.com/watch?v=" + e.value("id").toString() : QString();
    e["webpage_url"] = url;
  }
  e["provider"] = Source::provider(e);
  e["key"] = Source::identity(e);
  const auto candidates=thumbnailCandidates(e.value("thumbnail").toString(),
      e["provider"]=="youtube"?e.value("id").toString():QString());
  e["thumbnailCandidates"]=candidates;
  if(!candidates.isEmpty())e["thumbnail"]=candidates.first();
  return e;
}
QString Online::tool(const QString &name) {
  for (const QString &dir : {QCoreApplication::applicationDirPath(),
                             qEnvironmentVariable("SILLYPLAYER_TOOL_DIR")}) {
    if (dir.isEmpty())
      continue;
    QString p = dir + "/" + name;
#ifdef Q_OS_WIN
    if (!p.endsWith(".exe", Qt::CaseInsensitive)) p += ".exe";
#endif
    if (QFileInfo(p).isExecutable())
      return p;
  }
  return QStandardPaths::findExecutable(name);
}
Online::Online(Store *store, QObject *parent)
    : QObject(parent), s(store), playlist(new YouTubePlaylist(this)), extraction(new ExtractionService(this)) {
  progressNotifications.setSingleShot(true);
  progressNotifications.setInterval(100);
  connect(&progressNotifications, &QTimer::timeout, this, &Online::jobsChanged);
  for (auto v : s->rows("SELECT payload FROM jobs ORDER BY rowid")) {
    auto j = QJsonDocument::fromJson(v.toMap()["payload"].toByteArray())
                 .toVariant()
                 .toMap();
    savedJobs.insert(j.value("id").toString(), j);
    if (QStringList{"Queued", "Resolving", "Active", "Post-processing"}
            .contains(j["state"].toString()))
      j["state"] = "Interrupted";
    auto source=j["source"].toMap();
    source["provider"]=Source::provider(source);
    j["provider"]=source["provider"];
    j["source"]=source;
    m_jobs << j;
  }
  persist();
}
Online::~Online() { shutdown(); }
QObject *Online::playlistObject() const { return playlist; }
QVariantList Online::history() const {
  return s->rows("SELECT query FROM searches WHERE provider=? ORDER BY used DESC LIMIT 20",{m_provider});
}
QStringList Online::baseArgs() {
  QStringList a{"--ignore-config",
                "--no-warnings",
                "--no-playlist",
                "--socket-timeout",
                "20",
                "--retries",
                "1"};
  const QString deno = tool("deno"), qjs = tool("qjs"), node = tool("node");
  if (!deno.isEmpty())
    a << "--js-runtimes" << "deno:" + deno;
  else if (!qjs.isEmpty())
    a << "--js-runtimes" << "quickjs:" + qjs;
  else if (!node.isEmpty())
    a << "--js-runtimes" << "node:" + node;
  QString ff = tool("ffmpeg");
  if (!ff.isEmpty())
    a << "--ffmpeg-location" << QFileInfo(ff).absolutePath();
  return a;
}
void Online::removeHistory(const QString &q) {
  s->exec("DELETE FROM searches WHERE provider=? AND query=?", {m_provider,q});
  emit searchChanged();
}
void Online::clearHistory() {
  s->exec("DELETE FROM searches WHERE provider=?",{m_provider});
  emit searchChanged();
}
QString Online::providerName() const { return Source::name(m_provider); }
void Online::setActiveProvider(const QString &provider) {
  if (provider==m_provider || !QStringList{"youtube","soundcloud","direct"}.contains(provider)) return;
  cancelResolution();
  if (searchProcess) { discardProcess(searchProcess,this); searchProcess=nullptr; }
  searchStates[m_provider]={m_results,lastQuery,searching ? QString("Search interrupted; submit again to retry") : m_status,limit};
  searching=false;
  ++imageGeneration;
  if (imageReply) { imageReply->abort(); imageReply=nullptr; }
  m_details.clear(); detailError.clear(); playbackUrl.clear();
  m_provider=provider;
  const auto state=searchStates.value(provider);
  m_results=state.results; lastQuery=state.query; m_status=state.status; limit=state.limit;
  emit providerChanged(); emit searchChanged(); emit detailsChanged();
}
void Online::prepareDirect(const QString &input) {
  const auto url=input.trimmed();
  setActiveProvider("direct");
  if (!Source::http(url)) { emit error("Paste an HTTP or HTTPS audio file URL without embedded credentials"); return; }
  QString title=QFileInfo(QUrl(url).path()).fileName();
  if (title.isEmpty()) title="Audio download";
  emit downloadReady({{"provider","direct"},{"id",Source::directId(url)},{"webpage_url",url},{"title",title}});
}
void Online::search(const QString &input, bool more) {
  QString query = input.trimmed();
  if (query.isEmpty() || (more && searching))
    return;
  cancelResolution();
  if (searchProcess) {
    auto previous = searchProcess;
    searchProcess = nullptr;
    discardProcess(previous, this);
  }
  if (Source::http(query)) {
    searching = false;
    m_status.clear();
    emit searchChanged();
    const auto provider=Source::providerForUrl(query);
    if (provider=="unknown") prepareDirect(query);
    else {
      setActiveProvider(provider);
      if(provider=="youtube" && !YouTubePlaylist::canonicalUrl(query).isEmpty()) playlist->show(query);
      else resolve(query, true);
    }
    return;
  }
  if (m_provider=="direct") { prepareDirect(query); return; }
  if (more && query == lastQuery)
    limit += 20;
  else {
    limit = 20;
    m_results.clear();
  }
  lastQuery = query;
  searching = true;
  m_status = "Searching "+providerName()+"…";
  s->exec("INSERT INTO searches VALUES(?,?,?) ON CONFLICT(provider,query) DO UPDATE SET "
          "used=excluded.used",
          {m_provider,query, QDateTime::currentMSecsSinceEpoch()});
  emit searchChanged();
  auto p = new QProcess(this);
  configureToolProcess(p, true);
  searchProcess = p;
  auto knownResults = std::make_shared<QSet<QString>>();
  for (const auto &row : m_results) knownResults->insert(row.toMap().value("key").toString());
  auto consume = [this, knownResults](const QByteArray &lines) {
    bool changed = false;
    for (const auto &line : lines.split('\n')) {
      const auto doc = QJsonDocument::fromJson(line);
      if (!doc.isObject()) continue;
      QVariantList entries;
      if (doc.object().contains("entries")) entries = doc.object().value("entries").toArray().toVariantList();
      else entries << doc.object().toVariantMap();
      for (const auto &value : entries) {
        auto raw=value.toMap(); raw["provider"]=m_provider;
        auto e = normalizeResult(raw);
        if (e.value("id").toString().isEmpty()) continue;
        const auto key = e.value("key").toString();
        if (knownResults->contains(key)) continue;
        knownResults->insert(key);
        m_results << e;
        changed = true;
      }
    }
    if (changed) emit searchChanged();
  };
  connect(p, &QProcess::readyReadStandardOutput, this, [p, consume] {
    auto buffer = p->property("searchBuffer").toByteArray() + p->readAllStandardOutput();
    const auto end = buffer.lastIndexOf('\n');
    if (end >= 0) {
      consume(buffer.left(end));
      buffer.remove(0, end + 1);
    }
    p->setProperty("searchBuffer", buffer);
  });
  QStringList args = baseArgs();
  args << "--flat-playlist" << "--lazy-playlist" << "--dump-json"
       << "--playlist-start" << QString::number(more && !m_results.isEmpty() ? limit - 19 : 1)
       << (m_provider=="soundcloud" ? "scsearch" : "ytsearch") + QString::number(limit) + ":" + query;
  connect(p, &QProcess::errorOccurred, this,
          [this, p](QProcess::ProcessError e) {
            if (e == QProcess::FailedToStart) {
              searching = false;
              m_status = "yt-dlp could not start. Check bundled tools.";
              emit searchChanged();
              p->deleteLater();
              searchProcess = nullptr;
            }
          });
  connect(p, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this,
          [this, p, consume](int code, QProcess::ExitStatus) {
            searching = false;
            searchProcess = nullptr;
            consume(p->property("searchBuffer").toByteArray() + p->readAllStandardOutput());
            if (code) {
              limit = qMax(0, limit - 20); // retry an interrupted page without skipping its remaining entries
              m_status = p->property("timedOut").toBool()
                             ? "Search timed out"
                             : QString::fromUtf8(p->readAllStandardError()).trimmed().right(1200);
              if (m_status.isEmpty()) m_status = providerName()+" search failed";
            } else m_status = m_results.isEmpty() ? "No results" : "";
            emit searchChanged();
            p->deleteLater();
          });
  p->start(tool("yt-dlp"), args);
  deadline(p, 60000);
}
void Online::resolve(const QString &url, bool play, bool verifyFormats) {
  if (play) {
    if (extracting && !verifyFormats && ExtractionService::key(playbackUrl) == ExtractionService::key(url)) return;
    const auto provider=Source::providerForUrl(url);
    if (provider!="unknown") setActiveProvider(provider);
  }
  auto &token = play ? detailRequest : downloadRequest;
  extraction->cancel(token);
  token = 0;
  if (play) {
    ++imageGeneration;
    if (imageReply) { imageReply->abort(); imageReply = nullptr; }
    extracting = true;
    verifiedPlayback = true;
    playbackUrl = url;
    detailError.clear();
    const bool sameItem = !m_details.isEmpty() && ExtractionService::key(m_details.value("webpage_url").toString()) == ExtractionService::key(url);
    if (!sameItem) {
      m_details.clear();
      const auto key = ExtractionService::key(url);
      for (const auto &row : m_results) {
        const auto item = row.toMap();
        if (ExtractionService::key(item.value("webpage_url").toString()) == key) {
          m_details = item;
          break;
        }
      }
      if (m_details.isEmpty() && key.startsWith("youtube:"))
        m_details = normalizeResult({{"provider", "youtube"}, {"id", key.mid(8)}, {"webpage_url", url}});
    }
    emit playbackRequested(verifyFormats);
    if (!m_details.isEmpty() && m_details.value("artwork").toString().isEmpty())
      cacheArtwork(m_details);
    emit detailsChanged();
  } else {
    downloadUrl = url;
  }
  QElapsedTimer elapsed;
  elapsed.start();
  token = extraction->request(url, [this, play, elapsed, verifyFormats](const QVariantMap &details,
                                               const QString &message, bool hit) {
    emit lookupMeasured(play ? "playback" : "download-options", hit, elapsed.elapsed());
    if (play) {
      detailRequest = 0;
      extracting = false;
      verifiedPlayback = verifyFormats;
      detailError = message;
    } else {
      downloadRequest = 0;
      downloadUrl.clear();
      emit downloadLookupChanged();
    }
    if (!message.isEmpty()) {
      if (!play) emit error(message);
    } else if (play) {
      const auto artwork = m_details.value("artwork");
      m_details = details;
      if (artwork.isValid()) m_details["artwork"] = artwork;
      QString audio = details.value("url").toString();
      for (const auto &v : details.value("requested_formats").toList()) {
        const auto f = v.toMap();
        if (f.value("acodec").toString() != "none") audio = f.value("url").toString();
      }
      if (audio.isEmpty()) {
        detailError = "No playable audio returned for this item";
        emit error(detailError);
      } else {
        emit streamReady(audio, {}, details.value("http_headers").toMap());
        if (!imageReply && !artwork.isValid()) cacheArtwork(details);
      }
    } else emit downloadReady(details);
    if (play) emit detailsChanged();
  }, verifyFormats);
  if (!play) emit downloadLookupChanged();
}
bool Online::refreshPlayback() {
  if (verifiedPlayback || extracting || playbackUrl.isEmpty() || closing) return false;
  const auto url = playbackUrl;
  extraction->invalidate(url);
  resolve(url, true, true); // fresh extraction probes formats and is never marked as a cache hit
  return true;
}
void Online::invalidatePlayback() { extraction->invalidate(playbackUrl); }
void Online::retryVideo() {
  if (closing || extracting || playbackUrl.isEmpty()) return;
  const auto url = playbackUrl;
  extraction->invalidate(url);
  resolve(url, true, true);
}
int Online::jobIndex(const QString &id) const {
  for (int i = 0; i < m_jobs.size(); ++i)
    if (m_jobs[i].toMap()["id"].toString() == id)
      return i;
  return -1;
}
void Online::cancelResolution() {
  const bool pending = detailRequest != 0 || extracting;
  extraction->cancel(detailRequest);
  detailRequest = 0;
  extracting = false;
  if (pending) verifiedPlayback = true;
  emit detailsChanged();
}
QVariantMap Online::musicMetadata(const QVariantMap &details) {
  auto text = [](const QVariant &v) {
    if (v.canConvert<QVariantList>() && v.typeId() != QMetaType::QString) {
      QStringList names;
      for (const auto &item : v.toList())
        if (!item.toString().trimmed().isEmpty()) names << item.toString().trimmed();
      return names.join(", ");
    }
    return v.toString().trimmed();
  };
  QString artist;
  for (const auto &field : {"artist", "artists", "creator", "creators"}) {
    artist = text(details.value(field));
    if (!artist.isEmpty()) break;
  }
  return {{"title", details.value("track").toString().trimmed().isEmpty()
                       ? details.value("title") : details.value("track")},
          {"artist", artist.isEmpty() ? "Unknown artist" : artist},
          {"album", details.value("album", "")},
          {"origin", artist.isEmpty() ? "source-title" : "structured-source"}};
}
void Online::cacheArtwork(const QVariantMap &details, int attempt) {
  const auto generation = ++imageGeneration;
  if (imageReply) { imageReply->abort(); imageReply = nullptr; }
  const QString dir = QFileInfo(s->db().databaseName()).absolutePath() + "/cache/youtube-art";
  QDir().mkpath(dir);
  const QString path = dir + "/" + QString::fromLatin1(QCryptographicHash::hash(
      (Source::provider(details)=="youtube" ? details.value("id").toString() : Source::identity(details)).toUtf8(), QCryptographicHash::Sha256).toHex()) + ".jpg";
  if (QFileInfo(path).size() > 0) {
    m_details["artwork"] = QUrl::fromLocalFile(path).toString(); emit detailsChanged(); return;
  }
  const auto candidates = details.value("thumbnailCandidates").toStringList();
  if (attempt >= candidates.size()) return;
  QUrl url(candidates[attempt]);
  if (url.scheme() != "https" && url.scheme() != "http") return;
  QNetworkRequest request(url); request.setTransferTimeout(5000);
  auto reply = images.get(request); imageReply = reply;
  connect(reply, &QNetworkReply::readyRead, reply, [reply] {
    if (reply->bytesAvailable() > 8 * 1024 * 1024) reply->abort();
  });
  connect(reply, &QNetworkReply::finished, this, [this, reply, path, dir, generation, details, attempt] {
    bool saved = false;
    if (imageReply == reply) imageReply = nullptr;
    const auto bytes = reply->readAll();
    if (generation == imageGeneration && reply->error() == QNetworkReply::NoError && bytes.size() <= 8 * 1024 * 1024) {
      QBuffer buffer; buffer.setData(bytes); buffer.open(QIODevice::ReadOnly);
      QImageReader reader(&buffer); const auto size=reader.size();
      if(size.isValid() && size.width()<=8192 && size.height()<=8192) {
        if (size.width() > 1200 || size.height() > 1200)
          reader.setScaledSize(size.scaled(1200,1200,Qt::KeepAspectRatio));
        const auto image=reader.read();
        if(!image.isNull()) {
          QSaveFile file(path);
          if (file.open(QIODevice::WriteOnly) && image.save(&file,"JPG") && file.commit()) {
            saved = true;
            m_details["artwork"] = QUrl::fromLocalFile(path).toString(); emit detailsChanged();
            auto files = QDir(dir).entryInfoList(QDir::Files, QDir::Time);
            qint64 size = 0;
            for (const auto &item : files) { size += item.size(); if (size > 100*1024*1024 && item.filePath() != path) QFile::remove(item.filePath()); }
          }
        }
      }
    }
    reply->deleteLater();
    if (!saved && generation == imageGeneration) cacheArtwork(details, attempt + 1);
  });
}
QString Online::enqueue(const QVariantMap &d, const QString &kind,
                        const QString &quality, const QString &destination,
                        bool again) {
  if (Source::provider(d)=="direct" && kind!="Original") { emit error("Direct links preserve the original audio file only"); return {}; }
  if (kind != "Original" && kind != "MP3") { emit error("Video downloads are no longer supported"); return {}; }
  if (d.value("webpage_url").toString().isEmpty()) { emit error("Missing source URL"); return {}; }
  QString dest = pathOf(destination);
  if (!QDir().mkpath(dest) || !QFileInfo(dest).isWritable()) {
    emit error("Destination is not writable");
    return {};
  }
  for (auto v : m_jobs) {
    auto j = v.toMap();
    if (Source::identity(j["source"].toMap()) == Source::identity(d) && j["kind"] == kind &&
        j["quality"] == quality && j["state"] == "Completed" &&
        QFile::exists(j["path"].toString()) && !again)
      return "duplicate:" + j["path"].toString();
  }
  QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
  m_jobs << QVariantMap{
      {"id", id},
      {"videoId", d["id"]},
      {"provider",Source::provider(d)},
      {"title", d["title"]},
      {"source", QVariantMap{{"provider",Source::provider(d)},{"id", d["id"]},
                             {"title", d["title"]},
                             {"uploader", d["uploader"]},
                             {"webpage_url", d["webpage_url"]},
                             {"musicMetadata", musicMetadata(d)}}},
      {"url", d["webpage_url"]},
      {"kind", kind},
      {"quality", quality},
      {"destination", dest},
      {"state", "Queued"},
      {"progress", -1},
      {"path", ""},
      {"error", ""},
      {"created", QDateTime::currentMSecsSinceEpoch()}};
  if(!deferringJobs) { persist(); emit jobsChanged(); pump(); }
  return id;
}
QVariantMap Online::enqueueBatch(const QVariantList &entries,const QString &kind,const QString &destination,bool again,const QString &playlistName) {
  if(closing) return {{"error","The application is exiting"}};
  if(entries.isEmpty()) return {{"error","Select at least one song"}};
  if(kind!="Original" && kind!="MP3") return {{"error","Choose Original audio or MP3"}};
  const auto dest=pathOf(destination),name=playlistName.trimmed();
  if(!playlistName.isEmpty() && name.isEmpty()) return {{"error","Enter a playlist name"}};
  if(dest.trimmed().isEmpty() || !QDir().mkpath(dest) || !QFileInfo(dest).isWritable()) return {{"error","Destination is not writable"}};
  const auto previous=m_jobs;
  if(!s->db().transaction()) return {{"error","Cannot start the download batch"}};
  QString collection;
  if(!name.isEmpty()) {
    collection=QUuid::createUuid().toString(QUuid::WithoutBraces);
    if(!s->exec("INSERT INTO collections(id,name,kind,art) VALUES(?,?,'playlist','')",{collection,name})) {s->db().rollback();return {{"error",s->error()}};}
  }
  int queued=0,skipped=0,position=0;
  QHash<QString,QVariantMap> seen;
  bool ok=true;
  deferringJobs=true;
  for(auto value:entries) {
    const auto item=value.toMap(); const QString identity=Source::identity(item);
    QVariantMap target=seen.value(identity);
    if(!target.isEmpty()) ++skipped;
    else {
      for(auto v:m_jobs) { const auto job=v.toMap(); if(Source::identity(job["source"].toMap())==identity && job["kind"]==kind && QStringList{"Queued","Resolving","Active","Post-processing","Paused"}.contains(job["state"].toString())) {target={{"job",job["id"]},{"path",job["path"]}};break;} }
      if(!target.isEmpty()) ++skipped;
      else {
        const auto result=enqueue(item,kind,"best",dest,again);
        if(result.startsWith("duplicate:")) {
          ++skipped; const QString path=result.mid(10);
          for(auto v:m_jobs) {const auto job=v.toMap();if(job["path"]==path && job["state"]=="Completed") {target={{"job",job["id"]},{"path",path}};break;}}
        } else if(!result.isEmpty()) {++queued;target={{"job",result},{"path",QString("")}};}
        else {ok=false;break;}
      }
      seen.insert(identity,target);
    }
    if(!collection.isEmpty() && !target.isEmpty()) {
      ok=s->exec("INSERT INTO playlist_download_items VALUES(?,?,?,?,?)",{QUuid::createUuid().toString(QUuid::WithoutBraces),collection,target["job"],target.value("path").toString().isEmpty()?QString(""):target.value("path").toString(),position++});
      if(!ok) break;
    }
  }
  deferringJobs=false;
  if(ok) ok=persist(true);
  if(ok) ok=s->db().commit();
  if(!ok) {s->db().rollback();m_jobs=previous;return {{"error",QString("Could not save download batch: ")+s->error()}};}
  savedJobs.clear();
  for(const auto &value:m_jobs) {const auto job=value.toMap();savedJobs.insert(job.value("id").toString(),job);}
  emit jobsChanged();
  if(!collection.isEmpty()) {emit playlistsChanged();restorePlaylistDownloads();}
  pump();
  return {{"queued",queued},{"skipped",skipped},{"playlistId",collection},{"playlistName",name}};
}
bool Online::persist(bool inTransaction) {
  if(!inTransaction && !s->db().transaction()) return false;
  QHash<QString,QVariantMap> current;
  bool ok=true;
  for (const auto &value : m_jobs) {
    const auto job=value.toMap(); const auto id=job.value("id").toString();
    current.insert(id,job);
    if (inTransaction || !savedJobs.contains(id) || savedJobs.value(id)!=job)
      ok=s->exec("INSERT INTO jobs VALUES(?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",
        {id,QString::fromUtf8(QJsonDocument::fromVariant(job).toJson(QJsonDocument::Compact))}) && ok;
  }
  for(auto it=savedJobs.cbegin();it!=savedJobs.cend();++it)
    if(!current.contains(it.key())) ok=s->exec("DELETE FROM jobs WHERE id=?",{it.key()}) && ok;
  if(!inTransaction) {
    if(ok) ok=s->db().commit();
    if(!ok) s->db().rollback();
    else savedJobs=std::move(current);
  }
  return ok;
}
void Online::publishDownloaded(const QString &job,const QString &path,const QVariantMap &source) {
  if(!s->exec("UPDATE playlist_download_items SET path=? WHERE job=?",{path,job})) emit error("Playlist attachment could not be recorded: "+s->error());
  emit musicDownloaded(path,source);
}
void Online::attachPlaylistDownloads(const QString &path,const QString &track) {
  const auto pending=s->rows("SELECT * FROM playlist_download_items WHERE path=? ORDER BY position",{path});
  if(pending.isEmpty()) return;
  if(!s->db().transaction()) return;
  bool ok=true;
  for(auto v:pending) {const auto row=v.toMap();
    ok=s->exec("INSERT INTO members(id,collection,track,position) VALUES(?,?,?,?) ON CONFLICT(id) DO NOTHING",{row["id"],row["collection"],track,row["position"]}) && ok;
    if(ok)ok=s->exec("DELETE FROM playlist_download_items WHERE id=?",{row["id"]});
  }
  if(ok)ok=s->db().commit();
  if(!ok) {s->db().rollback();emit error("Could not add downloaded songs to the playlist: "+s->error());return;}
  emit playlistsChanged();
}
void Online::restorePlaylistDownloads() {
  QSet<QString> requested;
  const auto pending=s->rows("SELECT DISTINCT job,path FROM playlist_download_items");
  for(auto v:pending) {
    auto row=v.toMap();QString path=row["path"].toString();QVariantMap source;
    const int i=jobIndex(row["job"].toString());
    if(i>=0) {const auto job=m_jobs[i].toMap();source=job["source"].toMap();if(path.isEmpty() && job["state"]=="Completed")path=job["path"].toString();}
    if(path.isEmpty() || !QFileInfo(path).isFile() || requested.contains(path)) continue;
    requested.insert(path);s->exec("UPDATE playlist_download_items SET path=? WHERE job=?",{path,row["job"]});
    auto indexed=s->rows("SELECT id FROM tracks WHERE path=? AND deleted=0",{path}).value(0).toMap();
    if(indexed.isEmpty()) indexed=s->rows("SELECT k.id FROM tracks a JOIN tracks k ON k.id=a.mergedInto WHERE a.path=? AND k.deleted=0",{path}).value(0).toMap();
    if(!indexed.isEmpty()) attachPlaylistDownloads(path,indexed["id"].toString());
    else emit musicDownloaded(path,source);
  }
}
void Online::pump() {
  if (closing)
    return;
  for (int i = 0; i < m_jobs.size() && workers.size() + jobRequests.size() + transfers.size() < 1; ++i)
    if (m_jobs[i].toMap()["state"] == "Queued")
      startJob(i);
}
void Online::startJob(int index, bool useCache) {
  auto j = m_jobs[index].toMap();
  QString id = j["id"].toString();
  if (j["provider"]=="direct") {
    auto transfer=new DirectTransfer(this);
    transfers[id]=transfer;
    connect(transfer,&DirectTransfer::updated,this,[this,id](const QVariantMap &changes) {
      const int i=jobIndex(id); if(i<0) return;
      auto job=m_jobs[i].toMap();
      const bool stageChanged=job["state"]!=changes["state"];
      for(auto it=changes.begin();it!=changes.end();++it) job[it.key()]=it.value();
      m_jobs[i]=job;
      if(stageChanged) persist();
      if(stageChanged) emit jobsChanged();
      else if(!progressNotifications.isActive()) progressNotifications.start();
    });
    connect(transfer,&DirectTransfer::finished,this,[this,id,transfer](const QString &state,const QString &path,const QString &message) {
      transfers.remove(id);
      const int i=jobIndex(id);
      if(i>=0) {
        auto job=m_jobs[i].toMap(); job["state"]=state; job["error"]=message; job["stage"]="";
        if(state=="Completed") { job["path"]=path; job["progress"]=100; }
        m_jobs[i]=job;
        if(state=="Completed") publishDownloaded(id,path,job["source"].toMap());
      }
      persist(); emit jobsChanged(); transfer->deleteLater(); pump();
    });
    transfer->start(j);
    return;
  }
  if (useCache && !jobInfo.contains(id) && extraction->cached(j["url"].toString()).isEmpty()) {
    j["state"] = "Resolving"; j["progress"] = -1; j["error"] = "";
    m_jobs[index] = j; persist(); emit jobsChanged();
    jobRequests[id] = extraction->request(j["url"].toString(), [this,id](const QVariantMap &info, const QString &message, bool) {
      jobRequests.remove(id);
      int i = jobIndex(id); if (i < 0) { pump(); return; }
      auto job = m_jobs[i].toMap();
      if (!message.isEmpty()) {
        job["state"] = "Failed"; job["error"] = message; m_jobs[i] = job;
        persist(); emit jobsChanged(); pump(); return;
      }
      auto source = job["source"].toMap();
      source["musicMetadata"] = musicMetadata(info);
      for (const auto &key : {"id", "provider", "uploader", "title", "webpage_url"}) if (info.contains(key)) source[key] = info[key];
      job["source"] = source; m_jobs[i] = job;
      jobInfo[id] = info; startJob(i);
    });
    return;
  }
  auto p = new QProcess(this);
  configureToolProcess(p, true);
  workers[id] = p;
  j["state"] = "Resolving";
  j["error"] = "";
  j["diagnostics"] = "";
  j["progress"] = -1;
  m_jobs[index] = j;
  persist();
  emit jobsChanged();
  auto args = baseArgs();
  QString kind = j["kind"].toString();
  args << "--check-formats" << "--newline" << "--progress" << "--continue"
       << "--no-overwrites"
       << "--progress-template"
       << "download:SP_PROGRESS %(progress.downloaded_bytes)s "
          "%(progress.total_bytes)s %(progress.total_bytes_estimate)s"
       << "--progress-template" << "postprocess:SP_STAGE %(progress.status)s"
       << "--print" << "after_move:SP_FILE %(filepath)s" << "--no-simulate";
  args << "-f" << "bestaudio/best" << "-x" << "--audio-format" << (kind == "MP3" ? "mp3" : "best");
  if (kind == "MP3") args << "--audio-quality" << "0";
  args << "--embed-thumbnail" << "--convert-thumbnails" << "jpg" << "--embed-metadata";
  // an empty override lets yt-dlp's default tag mapping substitute the uploader as artist
  // supply an explicit conservative value when structured artist metadata is absent
  // uploader attribution stays in the separate comment/DB
  args << "--parse-metadata"
       << "%(artist,artists,creator,creators|Unknown artist)l:%(meta_artist)s"
       << "--parse-metadata"
       << "%(webpage_url)s · Uploader: %(uploader|Unknown)s · Original title: "
          "%(title)s:%(meta_comment)s";
  // source attribution is embedded by yt-dlp so artist is not inferred from channel/uploader
  QString stage = j["destination"].toString() + "/.sillyplayer/" + id;
  QDir().mkpath(stage);
  args << "-P" << "home:" + j["destination"].toString() << "-P"
       << "temp:" + stage << "-o" << "%(title).150B [%(id)s] " + id + ".%(ext)s";
  // reuse only current in-memory transport data.
  // Jobs on disk contain attribution, never signed stream URLs and yt-dlp falls back to the source URL if info expires
  const auto info = jobInfo.contains(id) ? jobInfo.take(id) : (useCache ? extraction->cached(j["url"].toString()) : QVariantMap{});
  if (!info.isEmpty()) {
    auto source = j["source"].toMap(); source["musicMetadata"] = musicMetadata(info);
    for (const auto &key : {"id", "provider", "uploader", "title", "webpage_url"}) if (info.contains(key)) source[key] = info[key];
    j["source"] = source; m_jobs[index] = j;
  }
  bool reused = false;
  if (!info.isEmpty()) {
    auto file = new QTemporaryFile(QDir::tempPath() + "/sillyplayer-info-XXXXXX.json", p);
    if (file->open()) {
      const auto bytes = QJsonDocument::fromVariant(info).toJson(QJsonDocument::Compact);
      if (file->write(bytes) == bytes.size() && file->flush()) {
        args << "--load-info-json" << file->fileName();
        reused = true;
      }
      file->close();
    }
  }
  if (!reused) args << j["url"].toString();
  connect(p, &QProcess::readyReadStandardOutput, this, [this, p, id] {
    buffers[id] += p->readAllStandardOutput();
    auto &b = buffers[id];
    while (b.contains('\n')) {
      int n = b.indexOf('\n');
      auto line = b.left(n);
      b.remove(0, n + 1);
      updateLine(id, line.trimmed());
    }
  });
  connect(p, &QProcess::readyReadStandardError, this, [this, p, id] {
    int i = jobIndex(id);
    if (i < 0)
      return;
    auto &buffer = buffers[id + ":stderr"];
    buffer += p->readAllStandardError();
    while (buffer.contains('\n')) {
      int end = buffer.indexOf('\n');
      auto line = buffer.left(end).trimmed();
      buffer.remove(0, end + 1);
      if (line.startsWith("SP_"))
        updateLine(id, line);
      else {
        auto j = m_jobs[i].toMap();
        j["diagnostics"] =
            (j["diagnostics"].toString() + QString::fromUtf8(line) + "\n")
                .right(2000);
        m_jobs[i] = j;
      }
    }
  });
  connect(p, &QProcess::errorOccurred, this,
          [this, id, p](QProcess::ProcessError e) {
            if (e != QProcess::FailedToStart)
              return;
            int i = jobIndex(id);
            if (i >= 0) {
              auto j = m_jobs[i].toMap();
              j["state"] = "Failed";
              j["error"] = "Could not start bundled yt-dlp";
              m_jobs[i] = j;
            }
            workers.remove(id);
            p->deleteLater();
            persist();
            emit jobsChanged();
            pump();
          });
  connect(p, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this,
          [this, p, id, reused](int code, QProcess::ExitStatus) {
            const auto remaining=buffers.take(id);
            const auto stderrTail=buffers.take(id+":stderr");
            if (!remaining.isEmpty()) updateLine(id,remaining);
            workers.remove(id);
            int i = jobIndex(id);
            if (i < 0) {
              p->deleteLater();
              return;
            }
            auto j = m_jobs[i].toMap();
            if (!stderrTail.isEmpty())
              j["diagnostics"]=(j["diagnostics"].toString()+QString::fromUtf8(stderrTail)).right(2000);
            if (code != 0 || j["diagnostics"].toString().contains("trying with URL"))
              extraction->invalidate(j["url"].toString());
            QString requested = requestedStops.take(id);
            // --load-info-json refreshes DownloadError cases itself, but a rejected format probe raises ExtractorError and does not refresh retry that specific cached-input failure once using the source URL
            if (code != 0 && requested.isEmpty() && j.value("formatRefreshes", 0).toInt() < 1 &&
                (j["diagnostics"].toString().contains("HTTP Error 403") ||
                 (reused && j["diagnostics"].toString().contains("Requested format is not available"))) &&
                !QFileInfo(j["path"].toString()).isFile()) {
              j["path"] = "";
              j["formatRefreshes"] = 1;
              m_jobs[i] = j;
              p->deleteLater();
              startJob(i, false);
              return;
            }
            if (!requested.isEmpty())
              j["state"] = requested;
            else if (code == 0 && QFileInfo(j["path"].toString()).isFile()) {
              j["state"] = "Completed";
              j["progress"] = 100;
              if (j["kind"] == "MP3" || j["kind"] == "Original")
                publishDownloaded(id,j["path"].toString(), j["source"].toMap());
            } else {
              j["state"] = "Failed";
              j["error"] =
                  j["diagnostics"].toString().isEmpty()
                      ? "Download did not produce a verified output file"
                      : j["diagnostics"];
            }
            m_jobs[i] = j;
            if (j["state"] == "Completed" || j["state"] == "Cancelled")
              QDir(j["destination"].toString() + "/.sillyplayer/" + id)
                  .removeRecursively();
            persist();
            emit jobsChanged();
            p->deleteLater();
            pump();
          });
  p->start(tool("yt-dlp"), args);
}
void Online::updateLine(const QString &id, const QByteArray &line) {
  int i = jobIndex(id);
  if (i < 0)
    return;
  auto j = m_jobs[i].toMap();
  QString l = QString::fromUtf8(line);
  if (l.startsWith("SP_PROGRESS ")) {
    auto parts = l.split(' ');
    j["state"] = "Active";
    bool ok = false;
    double total = parts.value(2).toDouble(&ok);
    j["progress"] =
        ok && total > 0 ? parts.value(1).toDouble() / total * 100 : -1;
  } else if (l.startsWith("SP_STAGE ")) {
    j["state"] = "Post-processing";
    j["progress"] = -1;
  } else if (l.startsWith("SP_FILE "))
    j["path"] = l.mid(8).trimmed();
  const bool stageChanged = m_jobs[i].toMap().value("state") != j.value("state");
  m_jobs[i] = j;
  if(stageChanged || !l.startsWith("SP_PROGRESS ")) emit jobsChanged();
  else if(!progressNotifications.isActive()) progressNotifications.start();
}
void Online::cancel(const QString &id) {
  int i = jobIndex(id);
  if (i < 0)
    return;
  if (transfers.contains(id)) { transfers.value(id)->stop("Cancelled"); return; }
  if (jobRequests.contains(id)) extraction->cancel(jobRequests.take(id));
  if (auto p = workers.value(id)) {
    requestedStops[id] = "Cancelled";
    terminateGroup(p);
    QTimer::singleShot(2000, p, [p] {
      if (p->state() != QProcess::NotRunning)
        terminateGroup(p, true);
    });
  } else {
    auto j = m_jobs[i].toMap();
    j["state"] = "Cancelled";
    QDir(j["destination"].toString() + "/.sillyplayer/" + id)
        .removeRecursively();
    m_jobs[i] = j;
    persist();
    emit jobsChanged();
    pump();
  }
}
void Online::pause(const QString &id) {
  int i = jobIndex(id);
  if (i < 0)
    return;
  if (transfers.contains(id)) { transfers.value(id)->stop("Paused"); return; }
  auto state = m_jobs[i].toMap()["state"].toString();
  if (state != "Active" && state != "Resolving")
    return;
  if (jobRequests.contains(id)) {
    extraction->cancel(jobRequests.take(id)); auto j = m_jobs[i].toMap(); j["state"] = "Paused"; m_jobs[i] = j;
    persist(); emit jobsChanged(); pump(); return;
  }
  if (auto p = workers.value(id)) {
    requestedStops[id] = "Paused";
    terminateGroup(p);
    QTimer::singleShot(2000, p, [p] {
      if (p->state() != QProcess::NotRunning)
        terminateGroup(p, true);
    });
  }
}
void Online::retry(const QString &id) {
  int i = jobIndex(id);
  if (i < 0 || workers.contains(id) || transfers.contains(id) || jobRequests.contains(id))
    return;
  auto j = m_jobs[i].toMap();
  if (j["state"] == "Completed" || j["kind"] == "Video")
    return;
  j["formatRefreshes"] = 0;
  j["state"] = "Queued";
  j["progress"] = -1;
  j["error"] = "";
  m_jobs[i] = j;
  persist();
  emit jobsChanged();
  pump();
}
void Online::clearCompleted() {
  for (int i = m_jobs.size() - 1; i >= 0; --i)
    if (QStringList{"Completed", "Cancelled"}.contains(
            m_jobs[i].toMap()["state"].toString()))
      m_jobs.removeAt(i);
  persist();
  emit jobsChanged();
}
void Online::removeJob(const QString &id) {
  const int i = jobIndex(id);
  if (i < 0 || workers.contains(id) || transfers.contains(id) || jobRequests.contains(id)) return;
  const auto job = m_jobs[i].toMap();
  if (QStringList{"Queued", "Active", "Resolving", "Post-processing"}.contains(job["state"].toString())) return;
  QDir(job["destination"].toString()+"/.sillyplayer/"+id).removeRecursively();
  s->exec("DELETE FROM playlist_download_items WHERE job=? AND path=''",{id});
  m_jobs.removeAt(i); persist(); emit jobsChanged();
}
void Online::clearFailed() {
  for (int i = m_jobs.size()-1; i >= 0; --i)
    if (m_jobs[i].toMap()["state"] == "Failed" && !workers.contains(m_jobs[i].toMap()["id"].toString())) {
      const auto job=m_jobs[i].toMap(); QDir(job["destination"].toString()+"/.sillyplayer/"+job["id"].toString()).removeRecursively();
      s->exec("DELETE FROM playlist_download_items WHERE job=? AND path=''",{job["id"]}); m_jobs.removeAt(i);
    }
  persist(); emit jobsChanged();
}
void Online::shutdown() {
  if (closing)
    return;
  closing = true;
  playlist->shutdown();
  if (searchProcess) {
    disconnect(searchProcess, nullptr, this, nullptr);
    terminateGroup(searchProcess, true);
    searchProcess->waitForFinished(1500);
  }
  const auto direct=transfers;
  for (auto transfer:direct) transfer->stop("Interrupted");
  extraction->shutdown();
  for (const auto &id : jobRequests.keys()) {
    int i = jobIndex(id); if (i >= 0) { auto j = m_jobs[i].toMap(); j["state"] = "Interrupted"; m_jobs[i] = j; }
  }
  jobRequests.clear(); jobInfo.clear();
  detailRequest = downloadRequest = 0;
  auto active = workers;
  for (auto p : active) {
    disconnect(p, nullptr, this, nullptr);
    terminateGroup(p);
  }
  for (auto it = active.begin(); it != active.end(); ++it) {
    if (!it.value()->waitForFinished(1500)) {
      terminateGroup(it.value(), true);
      it.value()->waitForFinished(1000);
    }
    int i = jobIndex(it.key());
    if (i >= 0) {
      auto j = m_jobs[i].toMap();
      j["state"] = "Interrupted";
      m_jobs[i] = j;
    }
  }
  workers.clear();
  for (auto p : findChildren<QProcess *>()) {
    if (p->state() == QProcess::Starting)
      p->waitForStarted(500);
    if (p->state() != QProcess::NotRunning) {
      terminateGroup(p, true);
      p->waitForFinished(1000);
    }
  }
  persist();
}
