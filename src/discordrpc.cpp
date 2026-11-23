#include "discordrpc.h"
#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QUrl>
#include <QUrlQuery>
#include <QUuid>
#include <QtEndian>
#include <cmath>

namespace {
constexpr quint32 kOpHandshake = 0;
constexpr quint32 kOpFrame = 1;
constexpr quint32 kOpClose = 2;
constexpr quint32 kOpPing = 3;
constexpr quint32 kOpPong = 4;

const QString kDefaultClientId = QStringLiteral("1574503545275678922");
// todo: let users customize rpc text/fields/toggles in settings later
} // namespace

DiscordRpc::DiscordRpc(QObject *parent)
    : QObject(parent), socket(this), reconnectTimer(this),
      m_clientId(kDefaultClientId) {
  reconnectTimer.setInterval(15000);
  connect(&reconnectTimer, &QTimer::timeout, this, &DiscordRpc::tryConnect);
  connect(&socket, &QLocalSocket::connected, this, &DiscordRpc::sendHandshake);
  connect(&socket, &QLocalSocket::readyRead, this, &DiscordRpc::onReadyRead);
  connect(&socket, &QLocalSocket::disconnected, this, [this] {
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
            m_ready = false;
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

    // use youtube cover art when available, otherwise fall back to the SillyPlayer app icon asset
    static const QRegularExpression ytIdRe("^[A-Za-z0-9_-]{11}$");
    QString videoId = track.value("videoId").toString().trimmed();
    const QString sourceUrl = track.value("sourceUrl").toString().trimmed();
    if (!ytIdRe.match(videoId).hasMatch() && !sourceUrl.isEmpty()) {
      const QUrl u(sourceUrl);
      const QString host = u.host().toLower();
      if (host.contains("youtube.com")) {
        const QString v = QUrlQuery(u).queryItemValue("v");
        if (ytIdRe.match(v).hasMatch())
          videoId = v;
      } else if (host == "youtu.be") {
        const QString seg = u.path().mid(1).section('/', 0, 0);
        if (ytIdRe.match(seg).hasMatch())
          videoId = seg;
      }
    }
    const QString largeImage =
        ytIdRe.match(videoId).hasMatch()
            ? QStringLiteral("https://i.ytimg.com/vi/%1/hqdefault.jpg").arg(videoId)
            : QStringLiteral("sillyplayer");

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

void DiscordRpc::updatePresence(const QVariantMap &track, bool playing,
                                double positionSec, double durationSec) {
  lastTrack = track;
  lastPlaying = playing;
  lastPosition = positionSec;
  lastDuration = durationSec;
  hasActivity = !track.value("title").toString().trimmed().isEmpty();
  if (!m_enabled || !m_ready)
    return;
  sendCurrentActivity();
}

void DiscordRpc::clearPresence() {
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
      socket.state() == QLocalSocket::ConnectingState)
    return;
  const auto pipes = candidatePipePaths();
  for (const auto &pipe : pipes) {
    socket.abort();
    readBuffer.clear();
    socket.connectToServer(pipe, QIODevice::ReadWrite);
    if (socket.waitForConnected(250)) {
      return;
    }
  }
  setStatus("Waiting for Discord");
}

void DiscordRpc::disconnectSocket() {
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
    if (readBuffer.size() < 8 + qsizetype(len))
      return;
    const QByteArray body = readBuffer.mid(8, qsizetype(len));
    readBuffer.remove(0, 8 + qsizetype(len));

    if (op == kOpPing) {
      socket.write(encodeFrame(kOpPong, body));
      socket.flush();
      continue;
    }
    if (op == kOpClose) {
      const auto msg = QJsonDocument::fromJson(body).object().value("message").toString();
      disconnectSocket();
      setStatus(msg.isEmpty() ? QStringLiteral("Waiting for Discord")
                              : QStringLiteral("Discord: ") + msg);
      return;
    }
    if (op == kOpFrame) {
      const auto obj = QJsonDocument::fromJson(body).object();
      const QString evt = obj.value("evt").toString();
      if (evt == "READY") {
        m_ready = true;
        reconnectTimer.stop();
        setStatus("Connected to Discord");
        if (hasActivity)
          sendCurrentActivity();
      } else if (evt == "ERROR") {
        const QString msg = obj.value("data").toObject().value("message").toString();
        if (!msg.isEmpty())
          setStatus("Discord: " + msg);
      }
    }
  }
}
