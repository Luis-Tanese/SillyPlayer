#pragma once
#include <QLocalSocket>
#include <QObject>
#include <QTimer>
#include <QVariantMap>

class DiscordRpc : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool enabled READ enabled NOTIFY statusChanged)
  Q_PROPERTY(bool connected READ connected NOTIFY statusChanged)
  Q_PROPERTY(QString status READ status NOTIFY statusChanged)
public:
  explicit DiscordRpc(QObject *parent = nullptr);
  ~DiscordRpc() override;

  bool enabled() const { return m_enabled; }
  bool connected() const { return m_ready; }
  QString status() const { return m_status; }

  void setEnabled(bool enabled);
  void setClientId(const QString &clientId);
  void updatePresence(const QVariantMap &track, bool playing, double positionSec,
                      double durationSec);
  void clearPresence();
  void shutdown();

  static QByteArray encodeFrame(quint32 opcode, const QByteArray &jsonPayload);
  static QVariantMap buildActivityPayload(const QVariantMap &track, bool playing,
                                          double positionSec, double durationSec,
                                          qint64 nowEpochMs, qint64 pid,
                                          const QString &nonce);

signals:
  void statusChanged();

private:
  QLocalSocket socket;
  QTimer reconnectTimer;
  QByteArray readBuffer;
  bool m_enabled = false;
  bool m_ready = false;
  bool m_closing = false;
  QString m_clientId;
  QString m_status = "Off";
  QVariantMap lastTrack;
  bool lastPlaying = false;
  double lastPosition = 0.0;
  double lastDuration = 0.0;
  bool hasActivity = false;

  void tryConnect();
  void disconnectSocket();
  void sendHandshake();
  void sendCurrentActivity();
  void sendFrame(quint32 opcode, const QVariantMap &payload);
  void onReadyRead();
  void setStatus(const QString &text);
  static QStringList candidatePipePaths();
};
