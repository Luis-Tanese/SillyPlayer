#include "discordrpc.h"
#include "sourcetags.h"
#include "tagcleanup.h"
#include <QBuffer>
#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QFutureWatcher>
#include <QHttpMultiPart>
#include <QImage>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QSet>
#include <QUrl>
#include <QUrlQuery>
#include <QUuid>
#include <QtConcurrent>
#include <QtEndian>
#include <cmath>

namespace {
constexpr quint32 kOpHandshake = 0;
constexpr quint32 kOpFrame = 1;
constexpr quint32 kOpClose = 2;
constexpr quint32 kOpPing = 3;
constexpr quint32 kOpPong = 4;
constexpr qint64 kNegativeCacheTtlMs = 7LL * 24 * 60 * 60 * 1000;
constexpr qint64 kLitterboxTtlMs = 60LL * 60 * 60 * 1000;

const QString kDefaultClientId = QStringLiteral("1574503545275678922");

QStringList tokenizeForMatch(const QString &input) {
  QString cleaned = cleanSongTitle(input).toLower();
  static const QRegularExpression nonWord(QStringLiteral(R"([^\p{L}\p{N}]+)"));
  cleaned.replace(nonWord, QStringLiteral(" "));
  const QStringList raw = cleaned.split(' ', Qt::SkipEmptyParts);
  QStringList out;
  for (const auto &w : raw) {
    if (w == QLatin1String("feat") || w == QLatin1String("ft") ||
        w == QLatin1String("featuring") || w == QLatin1String("the") ||
        w == QLatin1String("official") || w == QLatin1String("audio") ||
        w == QLatin1String("video"))
      continue;
    if (!out.contains(w)) out << w;
  }
  return out;
}

double tokenOverlapScore(const QString &wanted, const QString &candidate) {
  const auto a = tokenizeForMatch(wanted);
  const auto b = tokenizeForMatch(candidate);
  if (a.isEmpty() || b.isEmpty()) return 0.0;
  int hits = 0;
  for (const auto &tok : a) {
    if (b.contains(tok)) ++hits;
  }
  const double recall = double(hits) / double(a.size());
  const double precision = double(hits) / double(b.size());
  return recall * 0.75 + precision * 0.25;
}

double scoreCoverCandidate(const QString &wantedTitle, const QString &wantedArtist,
                           const QString &candTitle, const QString &candArtist) {
  const double titleScore = tokenOverlapScore(wantedTitle, candTitle);
  if (titleScore < 0.5) return 0.0;
  if (wantedArtist.isEmpty()) return titleScore;
  const double artistScore = tokenOverlapScore(wantedArtist, candArtist);
  if (artistScore < 0.35) return 0.0;
  return titleScore * 0.6 + artistScore * 0.4;
}

QString localArtPath(const QString &art) {
  const QString trimmed = art.trimmed();
  if (trimmed.isEmpty()) return {};
  const QUrl u(trimmed);
  return u.isLocalFile() ? u.toLocalFile() : trimmed;
}
} // namespace

DiscordRpc::DiscordRpc(Store *store, QObject *parent)
    : QObject(parent), s(store), socket(this), net(this), reconnectTimer(this),
      connectTimeout(this),
      m_clientId(kDefaultClientId),
      m_itunesBaseUrl(QStringLiteral("https://itunes.apple.com/search")),
      m_deezerBaseUrl(QStringLiteral("https://api.deezer.com/search")),
      m_uploadBaseUrl(QStringLiteral("https://litterbox.catbox.moe/resources/internals/api.php")) {
  reconnectTimer.setInterval(15000);
  connect(&reconnectTimer, &QTimer::timeout, this, &DiscordRpc::tryConnect);
  connectTimeout.setSingleShot(true);
  connectTimeout.setInterval(250);
  connect(&connectTimeout, &QTimer::timeout, this, [this] {
    if (m_closing || m_ready) return;
    socket.abort();
    connectNextPipe();
  });
  connect(&socket, &QLocalSocket::connected, this, [this] {
    connectTimeout.stop();
    pendingPipes.clear();
    sendHandshake();
  });
  connect(&socket, &QLocalSocket::readyRead, this, &DiscordRpc::onReadyRead);
  connect(&socket, &QLocalSocket::disconnected, this, [this] {
    connectTimeout.stop();
    const bool wasReady = m_ready;
    m_ready = false;
    readBuffer.clear();
    if (m_enabled && !m_closing) {
      setStatus("Waiting for Discord");
      if (!reconnectTimer.isActive())
        reconnectTimer.start();
    } else if (wasReady) {
      emit statusChanged();
    }
  });
  connect(&socket, &QLocalSocket::errorOccurred, this,
          [this](QLocalSocket::LocalSocketError) {
            if (!m_enabled || m_closing)
              return;
            connectTimeout.stop();
            m_ready = false;
            if (!pendingPipes.isEmpty()) {
              QTimer::singleShot(0, this, &DiscordRpc::connectNextPipe);
              return;
            }
            setStatus("Waiting for Discord");
            if (!reconnectTimer.isActive())
              reconnectTimer.start();
          });
}

DiscordRpc::~DiscordRpc() { shutdown(); }

QByteArray DiscordRpc::encodeFrame(quint32 opcode, const QByteArray &jsonPayload) {
  QByteArray frame(8 + jsonPayload.size(), Qt::Uninitialized);
  qToLittleEndian<quint32>(opcode, reinterpret_cast<uchar *>(frame.data()));
  qToLittleEndian<quint32>(quint32(jsonPayload.size()),
                           reinterpret_cast<uchar *>(frame.data() + 4));
  memcpy(frame.data() + 8, jsonPayload.constData(), jsonPayload.size());
  return frame;
}

QPair<QString, QString> DiscordRpc::normalizeQueryArtistTitle(const QString &rawTitle,
                                                              const QString &rawArtist) {
  QString title = cleanSongTitle(rawTitle);
  QString artist = rawArtist.trimmed();
  if (artist.compare(QLatin1String("Unknown artist"), Qt::CaseInsensitive) == 0 ||
      artist.compare(QLatin1String("SillyPlayer"), Qt::CaseInsensitive) == 0) {
    artist.clear();
  }
  // split "Artist - Song Title" filenames when artist metadata is missing
  if (artist.isEmpty()) {
    static const QRegularExpression dashSplit(QStringLiteral(R"(^(.+?)\s+[-–—]\s+(.+)$)"));
    const auto m = dashSplit.match(title);
    if (m.hasMatch()) {
      artist = m.captured(1).trimmed();
      title = m.captured(2).trimmed();
    }
  }
  static const QRegularExpression featRe(
      QStringLiteral(R"(\s*(?:[\(\[]?\s*(?:feat\.?|ft\.?|featuring)\b.*)$)"),
      QRegularExpression::CaseInsensitiveOption);
  artist.replace(featRe, QString());
  artist = artist.trimmed();
  title = cleanSongTitle(title);
  return {artist, title};
}

QString DiscordRpc::parseItunesCoverUrl(const QByteArray &json, const QString &title,
                                        const QString &artist) {
  const auto doc = QJsonDocument::fromJson(json);
  if (!doc.isObject()) return {};
  const auto results = doc.object().value(QStringLiteral("results")).toArray();
  double bestScore = 0.0;
  QString bestUrl;
  for (const auto &v : results) {
    const auto obj = v.toObject();
    const QString candTitle = obj.value(QStringLiteral("trackName")).toString();
    const QString candArtist = obj.value(QStringLiteral("artistName")).toString();
    QString art = obj.value(QStringLiteral("artworkUrl100")).toString().trimmed();
    if (art.isEmpty()) art = obj.value(QStringLiteral("artworkUrl60")).toString().trimmed();
    if (!art.startsWith(QLatin1String("https://"), Qt::CaseInsensitive)) continue;
    const double score = scoreCoverCandidate(title, artist, candTitle, candArtist);
    if (score > bestScore) {
      bestScore = score;
      bestUrl = art;
    }
  }
  if (bestUrl.isEmpty()) return {};
  static const QRegularExpression sizeRe(QStringLiteral(R"(/\d+x\d+bb\.)"));
  bestUrl.replace(sizeRe, QStringLiteral("/600x600bb."));
  return bestUrl;
}

QString DiscordRpc::parseDeezerCoverUrl(const QByteArray &json, const QString &title,
                                        const QString &artist) {
  const auto doc = QJsonDocument::fromJson(json);
  if (!doc.isObject()) return {};
  const auto results = doc.object().value(QStringLiteral("data")).toArray();
  double bestScore = 0.0;
  QString bestUrl;
  for (const auto &v : results) {
    const auto obj = v.toObject();
    const QString candTitle = obj.value(QStringLiteral("title")).toString();
    const QString candArtist = obj.value(QStringLiteral("artist")).toObject().value(QStringLiteral("name")).toString();
    const auto album = obj.value(QStringLiteral("album")).toObject();
    QString art = album.value(QStringLiteral("cover_big")).toString().trimmed();
    if (art.isEmpty()) art = album.value(QStringLiteral("cover_medium")).toString().trimmed();
    if (art.isEmpty()) art = album.value(QStringLiteral("cover")).toString().trimmed();
    if (!art.startsWith(QLatin1String("https://"), Qt::CaseInsensitive)) continue;
    const double score = scoreCoverCandidate(title, artist, candTitle, candArtist);
    if (score > bestScore) {
      bestScore = score;
      bestUrl = art;
    }
  }
  return bestUrl;
}

bool DiscordRpc::hasDirectCoverSource(const QVariantMap &track) {
  static const QRegularExpression ytIdRe(QStringLiteral("^[A-Za-z0-9_-]{11}$"));
  if (ytIdRe.match(track.value("videoId").toString().trimmed()).hasMatch()) return true;
  if (!extractYouTubeVideoIdFromUrl(track.value("sourceUrl").toString()).isEmpty()) return true;
  const QString rpcUrl = track.value("rpcCoverUrl").toString().trimmed();
  return rpcUrl.startsWith(QLatin1String("https://"), Qt::CaseInsensitive) ||
         rpcUrl.startsWith(QLatin1String("http://"), Qt::CaseInsensitive);
}

QVariantMap DiscordRpc::buildActivityPayload(const QVariantMap &track, bool playing,
                                             double positionSec, double durationSec,
                                             qint64 nowEpochMs, qint64 pid,
                                             const QString &nonce) {
  QVariantMap args{{"pid", pid}};
  const QString title = track.value("title").toString().trimmed();
  if (!title.isEmpty()) {
    const QString artist = track.value("artist").toString().trimmed();
    const QString album = track.value("album").toString().trimmed();
    const double speed = track.value("speed", 1.0).toDouble();
    const bool nightcore = track.value("nightcore", false).toBool();

    QStringList stateParts;
    if (!artist.isEmpty() && artist.compare("Unknown artist", Qt::CaseInsensitive) != 0)
      stateParts << artist;
    if (std::isfinite(speed) && (std::abs(speed - 1.0) >= 0.005 || nightcore)) {
      QString mod = QString::number(speed, 'f', 2) + QString::fromUtf8("×");
      if (nightcore)
        mod += (speed < 1.0 ? QStringLiteral(" Daycore") : QStringLiteral(" Nightcore"));
      stateParts << mod;
    }
    if (!playing)
      stateParts << QStringLiteral("Paused");
    const QString state = stateParts.join(QStringLiteral(" · "));

    // discord needs details/state strings to be 2..128 chars when present
    auto fitField = [](QString text) {
      if (text.size() == 1)
        text += ' ';
      if (text.size() > 128)
        text = text.left(127) + QChar(0x2026);
      return text;
    };

    QVariantMap activity{
        {"type", 2}, // listening
        {"status_display_type", 2}, // show song title next to the music icon in member list
        {"details", fitField(title)},
    };
    if (!state.isEmpty())
      activity["state"] = fitField(state);

    // prefer youtube thumbnail, then cached/looked-up public cover url, then fallback cat icon
    static const QRegularExpression ytIdRe("^[A-Za-z0-9_-]{11}$");
    QString videoId = track.value("videoId").toString().trimmed();
    const QString sourceUrl = track.value("sourceUrl").toString().trimmed();
    if (!ytIdRe.match(videoId).hasMatch() && !sourceUrl.isEmpty()) {
      videoId = extractYouTubeVideoIdFromUrl(sourceUrl);
    }
    const QString rpcCoverUrl = track.value("rpcCoverUrl").toString().trimmed();
    QString largeImage = QStringLiteral("sillyplayer");
    if (ytIdRe.match(videoId).hasMatch()) {
      largeImage = QStringLiteral("https://i.ytimg.com/vi/%1/hqdefault.jpg").arg(videoId);
    } else if (rpcCoverUrl.startsWith(QLatin1String("https://"), Qt::CaseInsensitive) ||
               rpcCoverUrl.startsWith(QLatin1String("http://"), Qt::CaseInsensitive)) {
      largeImage = rpcCoverUrl;
    }

    QVariantMap assets{
        {"large_image", largeImage},
        {"large_text", fitField(album.isEmpty() ? QStringLiteral("SillyPlayer") : album)},
    };
    activity["assets"] = assets;

    // clickable "Listen on ..." button if we have a public source link
    QString listenUrl;
    if (sourceUrl.startsWith("https://", Qt::CaseInsensitive) ||
        sourceUrl.startsWith("http://", Qt::CaseInsensitive)) {
      listenUrl = sourceUrl;
    } else if (ytIdRe.match(videoId).hasMatch()) {
      listenUrl = QStringLiteral("https://www.youtube.com/watch?v=%1").arg(videoId);
    }
    if (!listenUrl.isEmpty() && listenUrl.size() <= 512) {
      const QString host = QUrl(listenUrl).host().toLower();
      QString label = QStringLiteral("Listen to source");
      if (host.contains("youtube.com") || host == "youtu.be")
        label = QStringLiteral("Listen on YouTube");
      else if (host.contains("soundcloud.com"))
        label = QStringLiteral("Listen on SoundCloud");
      activity["buttons"] = QVariantList{
          QVariantMap{{"label", label}, {"url", listenUrl}},
      };
    }

    if (playing && std::isfinite(positionSec) && positionSec >= 0.0) {
      const qint64 startMs = nowEpochMs - qint64(std::llround(positionSec * 1000.0));
      QVariantMap timestamps{{"start", startMs}};
      if (std::isfinite(durationSec) && durationSec > 0.0 && durationSec >= positionSec) {
        timestamps["end"] = startMs + qint64(std::llround(durationSec * 1000.0));
      }
      activity["timestamps"] = timestamps;
    }
    args["activity"] = activity;
  } else {
    args["activity"] = QVariant();
  }
  return {
      {"cmd", "SET_ACTIVITY"},
      {"args", args},
      {"nonce", nonce},
  };
}

void DiscordRpc::setStatus(const QString &text) {
  if (m_status == text)
    return;
  m_status = text;
  emit statusChanged();
}

void DiscordRpc::setEnabled(bool enabled) {
  if (m_enabled == enabled)
    return;
  m_enabled = enabled;
  if (!m_enabled) {
    ++lookupGen;
    if (activeReply) { activeReply->abort(); activeReply = nullptr; }
    reconnectTimer.stop();
    if (m_ready)
      clearPresence();
    disconnectSocket();
    setStatus("Off");
    return;
  }
  if (m_clientId.isEmpty()) {
    setStatus("Set a Discord Application ID to connect");
    return;
  }
  setStatus("Connecting to Discord…");
  tryConnect();
  if (!m_ready && !reconnectTimer.isActive())
    reconnectTimer.start();
}

void DiscordRpc::setLookupCovers(bool enabled) {
  if (m_lookupCovers == enabled) return;
  m_lookupCovers = enabled;
  if (m_enabled && m_ready && hasActivity && !hasDirectCoverSource(lastTrack))
    resolveCoverNow(lastTrack);
}

void DiscordRpc::setUploadCovers(bool enabled) {
  if (m_uploadCovers == enabled) return;
  m_uploadCovers = enabled;
  if (m_enabled && m_ready && hasActivity && !hasDirectCoverSource(lastTrack))
    resolveCoverNow(lastTrack);
}

void DiscordRpc::setClientId(const QString &clientId) {
  const QString trimmed = clientId.trimmed();
  const QString effective = trimmed.isEmpty() ? kDefaultClientId : trimmed;
  if (effective == m_clientId)
    return;
  m_clientId = effective;
  if (m_enabled) {
    disconnectSocket();
    setStatus("Connecting to Discord…");
    tryConnect();
    if (!m_ready && !reconnectTimer.isActive())
      reconnectTimer.start();
  }
}

void DiscordRpc::setCoverEndpointsForTesting(const QString &itunesUrl, const QString &deezerUrl,
                                             const QString &uploadUrl) {
  if (!itunesUrl.isEmpty()) m_itunesBaseUrl = itunesUrl;
  if (!deezerUrl.isEmpty()) m_deezerBaseUrl = deezerUrl;
  if (!uploadUrl.isEmpty()) m_uploadBaseUrl = uploadUrl;
}

void DiscordRpc::clearCoverCache() {
  ++lookupGen;
  if (activeReply) { activeReply->abort(); activeReply = nullptr; }
  if (s) s->exec("DELETE FROM rpc_covers");
  lastTrack.remove("rpcCoverUrl");
  if (m_enabled && m_ready && hasActivity) {
    sendCurrentActivity();
    if (!hasDirectCoverSource(lastTrack)) resolveCoverNow(lastTrack);
  }
}

QString DiscordRpc::cachedCoverUrl(const QString &trackId) const {
  if (!s || trackId.isEmpty()) return {};
  const auto rows = s->rows("SELECT url,provider,checkedAt FROM rpc_covers WHERE track=?", {trackId});
  if (rows.isEmpty()) return {};
  const auto row = rows.first().toMap();
  const QString url = row.value("url").toString().trimmed();
  if (url.isEmpty()) return {};
  const QString provider = row.value("provider").toString();
  const qint64 checkedAt = row.value("checkedAt").toLongLong();
  if (provider == QLatin1String("litterbox") &&
      QDateTime::currentMSecsSinceEpoch() - checkedAt > kLitterboxTtlMs) {
    return {};
  }
  return url;
}

void DiscordRpc::storeCachedCover(const QString &trackId, const QString &url,
                                  const QString &provider) {
  if (!s || trackId.isEmpty()) return;
  const qint64 now = QDateTime::currentMSecsSinceEpoch();
  s->exec("INSERT OR REPLACE INTO rpc_covers(track,url,provider,checkedAt) VALUES(?,?,?,?)",
          {trackId, url, provider, now});
}

void DiscordRpc::resolveCoverNow(const QVariantMap &track) {
  if (m_closing || (!m_lookupCovers && !m_uploadCovers)) return;
  const QString trackId = track.value("id").toString().trimmed();
  if (trackId.isEmpty() || hasDirectCoverSource(track)) return;

  const qint64 now = QDateTime::currentMSecsSinceEpoch();
  if (s) {
    const auto rows = s->rows("SELECT url,provider,checkedAt FROM rpc_covers WHERE track=?", {trackId});
    if (!rows.isEmpty()) {
      const auto row = rows.first().toMap();
      const QString url = row.value("url").toString().trimmed();
      const QString provider = row.value("provider").toString();
      const qint64 checkedAt = row.value("checkedAt").toLongLong();
      if (!url.isEmpty()) {
        if (provider != QLatin1String("litterbox") || (now - checkedAt) <= kLitterboxTtlMs) {
          if (lastTrack.value("id").toString() == trackId) {
            lastTrack["rpcCoverUrl"] = url;
            if (m_ready) sendCurrentActivity();
          }
          emit coverResolved(trackId, url);
          return;
        }
      } else {
        // skip repeating recent misses unless upload fallback got turned on since the last lookup
        const bool canTryUpload = m_uploadCovers && provider != QLatin1String("upload-miss") &&
                                  QFileInfo::exists(localArtPath(track.value("art").toString()));
        if (!canTryUpload && (now - checkedAt) < kNegativeCacheTtlMs) return;
      }
    }
  }

  ++lookupGen;
  const quint64 gen = lookupGen;
  if (activeReply) {
    activeReply->abort();
    activeReply = nullptr;
  }

  const auto [artist, title] = normalizeQueryArtistTitle(track.value("title").toString(),
                                                         track.value("artist").toString());
  if (m_lookupCovers && !title.isEmpty()) {
    startItunesLookup(gen, track, artist, title);
  } else if (m_uploadCovers) {
    startUploadFallback(gen, track);
  }
}

void DiscordRpc::startItunesLookup(quint64 gen, const QVariantMap &track, const QString &artist,
                                   const QString &title) {
  QUrl url(m_itunesBaseUrl);
  QUrlQuery q;
  q.addQueryItem(QStringLiteral("term"), artist.isEmpty() ? title : (artist + QStringLiteral(" ") + title));
  q.addQueryItem(QStringLiteral("media"), QStringLiteral("music"));
  q.addQueryItem(QStringLiteral("entity"), QStringLiteral("song"));
  q.addQueryItem(QStringLiteral("limit"), QStringLiteral("5"));
  url.setQuery(q);

  QNetworkRequest req(url);
  req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("SillyPlayer/1.2"));
  req.setTransferTimeout(6000);
  auto *reply = net.get(req);
  activeReply = reply;
  connect(reply, &QNetworkReply::finished, this, [this, reply, gen, track, artist, title] {
    reply->deleteLater();
    if (activeReply == reply) activeReply = nullptr;
    if (m_closing || gen != lookupGen) return;
    if (reply->error() == QNetworkReply::NoError) {
      const QString cover = parseItunesCoverUrl(reply->readAll(), title, artist);
      if (!cover.isEmpty()) {
        finishCoverResolution(gen, track.value("id").toString(), cover, QStringLiteral("itunes"));
        return;
      }
    }
    startDeezerLookup(gen, track, artist, title);
  });
}

void DiscordRpc::startDeezerLookup(quint64 gen, const QVariantMap &track, const QString &artist,
                                   const QString &title) {
  QUrl url(m_deezerBaseUrl);
  QUrlQuery q;
  q.addQueryItem(QStringLiteral("q"), artist.isEmpty() ? title : (artist + QStringLiteral(" ") + title));
  q.addQueryItem(QStringLiteral("limit"), QStringLiteral("5"));
  url.setQuery(q);

  QNetworkRequest req(url);
  req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("SillyPlayer/1.2"));
  req.setTransferTimeout(6000);
  auto *reply = net.get(req);
  activeReply = reply;
  connect(reply, &QNetworkReply::finished, this, [this, reply, gen, track, artist, title] {
    reply->deleteLater();
    if (activeReply == reply) activeReply = nullptr;
    if (m_closing || gen != lookupGen) return;
    if (reply->error() == QNetworkReply::NoError) {
      const QString cover = parseDeezerCoverUrl(reply->readAll(), title, artist);
      if (!cover.isEmpty()) {
        finishCoverResolution(gen, track.value("id").toString(), cover, QStringLiteral("deezer"));
        return;
      }
    }
    if (m_uploadCovers) {
      startUploadFallback(gen, track);
    } else {
      finishCoverResolution(gen, track.value("id").toString(), QString(), QStringLiteral("lookup-miss"));
    }
  });
}

void DiscordRpc::startUploadFallback(quint64 gen, const QVariantMap &track) {
  const QString trackId = track.value("id").toString();
  const QString path = localArtPath(track.value("art").toString());
  if (path.isEmpty() || !QFileInfo::exists(path)) {
    finishCoverResolution(gen, trackId, QString(), QStringLiteral("upload-miss"));
    return;
  }
  // scale and jpeg-encode off the UI thread so large covers never hitch playback
  auto *watcher = new QFutureWatcher<QByteArray>(this);
  connect(watcher, &QFutureWatcher<QByteArray>::finished, this, [this, watcher, gen, trackId] {
    const QByteArray jpeg = watcher->result();
    watcher->deleteLater();
    if (m_closing || gen != lookupGen) return;
    if (jpeg.isEmpty()) {
      finishCoverResolution(gen, trackId, QString(), QStringLiteral("upload-miss"));
      return;
    }

    auto *multi = new QHttpMultiPart(QHttpMultiPart::FormDataType);
    QHttpPart reqType;
    reqType.setHeader(QNetworkRequest::ContentDispositionHeader, QVariant("form-data; name=\"reqtype\""));
    reqType.setBody("fileupload");
    multi->append(reqType);

    QHttpPart expiry;
    expiry.setHeader(QNetworkRequest::ContentDispositionHeader, QVariant("form-data; name=\"time\""));
    expiry.setBody("72h");
    multi->append(expiry);

    QHttpPart filePart;
    filePart.setHeader(QNetworkRequest::ContentTypeHeader, QVariant("image/jpeg"));
    filePart.setHeader(QNetworkRequest::ContentDispositionHeader,
                       QVariant("form-data; name=\"fileToUpload\"; filename=\"cover.jpg\""));
    filePart.setBody(jpeg);
    multi->append(filePart);

    QNetworkRequest req{QUrl(m_uploadBaseUrl)};
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("SillyPlayer/1.2"));
    req.setTransferTimeout(10000);
    auto *reply = net.post(req, multi);
    multi->setParent(reply);
    activeReply = reply;
    connect(reply, &QNetworkReply::finished, this, [this, reply, gen, trackId] {
      reply->deleteLater();
      if (activeReply == reply) activeReply = nullptr;
      if (m_closing || gen != lookupGen) return;
      QString uploaded;
      if (reply->error() == QNetworkReply::NoError) {
        const QString body = QString::fromUtf8(reply->readAll()).trimmed();
        if (body.startsWith(QLatin1String("https://"), Qt::CaseInsensitive) ||
            body.startsWith(QLatin1String("http://"), Qt::CaseInsensitive)) {
          uploaded = body;
        }
      }
      finishCoverResolution(gen, trackId, uploaded,
                            uploaded.isEmpty() ? QStringLiteral("upload-miss") : QStringLiteral("litterbox"));
    });
  });
  watcher->setFuture(QtConcurrent::run([path]() -> QByteArray {
    QImage img(path);
    if (img.isNull()) return {};
    QByteArray jpeg;
    QBuffer buf(&jpeg);
    buf.open(QIODevice::WriteOnly);
    img.scaled(512, 512, Qt::KeepAspectRatio, Qt::SmoothTransformation).save(&buf, "JPG", 85);
    return jpeg;
  }));
}

void DiscordRpc::finishCoverResolution(quint64 gen, const QString &trackId, const QString &url,
                                       const QString &provider) {
  if (m_closing || gen != lookupGen || trackId.isEmpty()) return;
  storeCachedCover(trackId, url, provider);
  if (!url.isEmpty()) {
    if (lastTrack.value("id").toString() == trackId) {
      lastTrack["rpcCoverUrl"] = url;
      if (m_ready) sendCurrentActivity();
    }
    emit coverResolved(trackId, url);
  }
}

void DiscordRpc::updatePresence(const QVariantMap &track, bool playing,
                                double positionSec, double durationSec) {
  lastTrack = track;
  const QString trackId = lastTrack.value("id").toString().trimmed();
  if (!hasDirectCoverSource(lastTrack) && !trackId.isEmpty()) {
    const QString cached = cachedCoverUrl(trackId);
    if (!cached.isEmpty()) lastTrack["rpcCoverUrl"] = cached;
  }
  lastPlaying = playing;
  lastPosition = positionSec;
  lastDuration = durationSec;
  hasActivity = !lastTrack.value("title").toString().trimmed().isEmpty();
  if (!m_enabled || !m_ready)
    return;
  sendCurrentActivity();
  if (hasActivity && !hasDirectCoverSource(lastTrack)) {
    resolveCoverNow(lastTrack);
  }
}

void DiscordRpc::clearPresence() {
  ++lookupGen;
  if (activeReply) { activeReply->abort(); activeReply = nullptr; }
  lastTrack.clear();
  lastPlaying = false;
  lastPosition = 0.0;
  lastDuration = 0.0;
  hasActivity = false;
  if (!m_ready)
    return;
  sendFrame(kOpFrame, buildActivityPayload({}, false, 0, 0,
                                           QDateTime::currentMSecsSinceEpoch(),
                                           QCoreApplication::applicationPid(),
                                           QUuid::createUuid().toString(QUuid::WithoutBraces)));
}

void DiscordRpc::shutdown() {
  if (m_closing)
    return;
  m_closing = true;
  ++lookupGen;
  if (activeReply) { activeReply->abort(); activeReply = nullptr; }
  reconnectTimer.stop();
  if (m_ready) {
    clearPresence();
    socket.waitForBytesWritten(150);
  }
  disconnectSocket();
}

QStringList DiscordRpc::candidatePipePaths() {
  QStringList paths;
#ifdef Q_OS_WIN
  for (int i = 0; i < 10; ++i)
    paths << QString("\\\\.\\pipe\\discord-ipc-%1").arg(i);
#else
  QStringList dirs;
  const QString xdg = qEnvironmentVariable("XDG_RUNTIME_DIR");
  if (!xdg.isEmpty()) {
    dirs << xdg;
    dirs << xdg + "/app/com.discordapp.Discord";
    dirs << xdg + "/snap.discord";
    dirs << xdg + "/app/dev.vencord.Vesktop";
  }
  const QString tmpdir = qEnvironmentVariable("TMPDIR");
  if (!tmpdir.isEmpty() && !dirs.contains(tmpdir))
    dirs << tmpdir;
  if (!dirs.contains("/tmp"))
    dirs << "/tmp";
  for (const auto &dir : dirs) {
    for (int i = 0; i < 10; ++i) {
      const QString candidate = dir + QString("/discord-ipc-%1").arg(i);
      if (QFileInfo::exists(candidate))
        paths << candidate;
    }
  }
#endif
  return paths;
}

void DiscordRpc::tryConnect() {
  if (!m_enabled || m_closing)
    return;
  if (m_clientId.isEmpty()) {
    setStatus("Set a Discord Application ID to connect");
    return;
  }
  if (socket.state() == QLocalSocket::ConnectedState ||
      socket.state() == QLocalSocket::ConnectingState ||
      !pendingPipes.isEmpty())
    return;
  // probe candidate discord ipc pipes asynchronously so the UI thread never blocks on waitForConnected
  pendingPipes = candidatePipePaths();
  if (pendingPipes.isEmpty()) {
    setStatus("Waiting for Discord");
    return;
  }
  connectNextPipe();
}

void DiscordRpc::connectNextPipe() {
  if (!m_enabled || m_closing || m_ready) {
    pendingPipes.clear();
    connectTimeout.stop();
    return;
  }
  if (pendingPipes.isEmpty()) {
    connectTimeout.stop();
    setStatus("Waiting for Discord");
    if (!reconnectTimer.isActive())
      reconnectTimer.start();
    return;
  }
  const QString pipe = pendingPipes.takeFirst();
  socket.abort();
  readBuffer.clear();
  connectTimeout.start();
  socket.connectToServer(pipe, QIODevice::ReadWrite);
}

void DiscordRpc::disconnectSocket() {
  connectTimeout.stop();
  pendingPipes.clear();
  m_ready = false;
  readBuffer.clear();
  if (socket.state() != QLocalSocket::UnconnectedState) {
    socket.disconnectFromServer();
    if (socket.state() != QLocalSocket::UnconnectedState)
      socket.abort();
  }
}

void DiscordRpc::sendHandshake() {
  sendFrame(kOpHandshake, {{"v", 1}, {"client_id", m_clientId}});
}

void DiscordRpc::sendCurrentActivity() {
  if (!m_ready)
    return;
  const auto payload = buildActivityPayload(
      hasActivity ? lastTrack : QVariantMap{}, lastPlaying, lastPosition, lastDuration,
      QDateTime::currentMSecsSinceEpoch(), QCoreApplication::applicationPid(),
      QUuid::createUuid().toString(QUuid::WithoutBraces));
  sendFrame(kOpFrame, payload);
}

void DiscordRpc::sendFrame(quint32 opcode, const QVariantMap &payload) {
  if (socket.state() != QLocalSocket::ConnectedState)
    return;
  const QByteArray body = QJsonDocument::fromVariant(payload).toJson(QJsonDocument::Compact);
  socket.write(encodeFrame(opcode, body));
  socket.flush();
}

void DiscordRpc::onReadyRead() {
  readBuffer.append(socket.readAll());
  while (readBuffer.size() >= 8) {
    const quint32 op = qFromLittleEndian<quint32>(
        reinterpret_cast<const uchar *>(readBuffer.constData()));
    const quint32 len = qFromLittleEndian<quint32>(
        reinterpret_cast<const uchar *>(readBuffer.constData() + 4));
    if (len > 1024 * 1024) {
      disconnectSocket();
      return;
    }
    if (quint32(readBuffer.size()) < 8 + len)
      return;
    const QByteArray payload = readBuffer.mid(8, len);
    readBuffer.remove(0, 8 + len);

    if (op == kOpPing) {
      QByteArray pong = encodeFrame(kOpPong, payload);
      socket.write(pong);
      socket.flush();
      continue;
    }
    if (op == kOpClose) {
      disconnectSocket();
      return;
    }
    if (op == kOpFrame) {
      const auto obj = QJsonDocument::fromJson(payload).object();
      if (obj.value("evt").toString() == "READY") {
        m_ready = true;
        reconnectTimer.stop();
        setStatus("Connected");
        if (hasActivity) {
          sendCurrentActivity();
          if (!hasDirectCoverSource(lastTrack)) resolveCoverNow(lastTrack);
        }
      }
    }
  }
}
