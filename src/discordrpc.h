#pragma once
#include "store.h"
#include <QLocalSocket>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QObject>
#include <QPair>
#include <QPointer>
#include <QTimer>
#include <QVariantMap>

class DiscordRpc : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool enabled READ enabled NOTIFY statusChanged)
  Q_PROPERTY(bool connected READ connected NOTIFY statusChanged)
  Q_PROPERTY(QString status READ status NOTIFY statusChanged)
public:
  explicit DiscordRpc(Store *store = nullptr, QObject *parent = nullptr);
  ~DiscordRpc() override;

  bool enabled() const { return m_enabled; }
  bool connected() const { return m_ready; }
  QString status() const { return m_status; }

  void setEnabled(bool enabled);
  void setLookupCovers(bool enabled);
  void setUploadCovers(bool enabled);
  void setClientId(const QString &clientId);
  void updatePresence(const QVariantMap &track, bool playing, double positionSec,
                      double durationSec);
  void clearPresence();
  void shutdown();

  Q_INVOKABLE void clearCoverCache();
  QString cachedCoverUrl(const QString &trackId) const;
  void resolveCoverNow(const QVariantMap &track);
  void setCoverEndpointsForTesting(const QString &itunesUrl, const QString &deezerUrl,
                                   const QString &uploadUrl);

  static QByteArray encodeFrame(quint32 opcode, const QByteArray &jsonPayload);
  static QVariantMap buildActivityPayload(const QVariantMap &track, bool playing,
                                          double positionSec, double durationSec,
                                          qint64 nowEpochMs, qint64 pid,
                                          const QString &nonce);
  static QPair<QString, QString> normalizeQueryArtistTitle(const QString &rawTitle,
                                                           const QString &rawArtist);
  static QString parseItunesCoverUrl(const QByteArray &json, const QString &title,
                                     const QString &artist);
  static QString parseDeezerCoverUrl(const QByteArray &json, const QString &title,
                                     const QString &artist);

signals:
  void statusChanged();
  void coverResolved(const QString &trackId, const QString &url);

private:
  Store *s = nullptr;
  QLocalSocket socket;
  QNetworkAccessManager net;
  QPointer<QNetworkReply> activeReply;
  QTimer reconnectTimer;
  QByteArray readBuffer;
  bool m_enabled = false;
  bool m_lookupCovers = true;
  bool m_uploadCovers = false;
  bool m_ready = false;
  bool m_closing = false;
  QString m_clientId;
  QString m_status = "Off";
  QString m_itunesBaseUrl;
  QString m_deezerBaseUrl;
  QString m_uploadBaseUrl;
  QVariantMap lastTrack;
  bool lastPlaying = false;
  double lastPosition = 0.0;
  double lastDuration = 0.0;
  bool hasActivity = false;
  quint64 lookupGen = 0;

  void tryConnect();
  void disconnectSocket();
  void sendHandshake();
  void sendCurrentActivity();
  void sendFrame(quint32 opcode, const QVariantMap &payload);
  void onReadyRead();
  void setStatus(const QString &text);
  static QStringList candidatePipePaths();
  static bool hasDirectCoverSource(const QVariantMap &track);
  void startItunesLookup(quint64 gen, const QVariantMap &track, const QString &artist,
                         const QString &title);
  void startDeezerLookup(quint64 gen, const QVariantMap &track, const QString &artist,
                         const QString &title);
  void startUploadFallback(quint64 gen, const QVariantMap &track);
  void finishCoverResolution(quint64 gen, const QString &trackId, const QString &url,
                             const QString &provider);
  void storeCachedCover(const QString &trackId, const QString &url, const QString &provider);
};
