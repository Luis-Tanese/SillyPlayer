#pragma once
#include "store.h"
#include <QFutureWatcher>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QSslConfiguration>
#include <QTcpServer>
#include <QUdpSocket>
#include <QTimer>
#include <QVariantMap>
#include <functional>
class QSslSocket;
class SyncServer : public QTcpServer {
public:
  using QTcpServer::QTcpServer;
  std::function<void(qintptr)> accept;

protected:
  void incomingConnection(qintptr descriptor) override { accept(descriptor); }
};
class SyncManager : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool enabled READ enabled NOTIFY changed)
  Q_PROPERTY(bool busy READ busy NOTIFY changed)
  Q_PROPERTY(double progress READ progress NOTIFY changed)
  Q_PROPERTY(QString status READ status NOTIFY changed)
  Q_PROPERTY(QString pairingLink READ pairingLink NOTIFY changed)
  Q_PROPERTY(QString pairingQr READ pairingQr NOTIFY changed)
  Q_PROPERTY(QStringList addresses READ addresses NOTIFY changed)
  Q_PROPERTY(QVariantList devices READ devices NOTIFY changed)
  Q_PROPERTY(QVariantList conflicts READ conflicts NOTIFY changed)
  Q_PROPERTY(QVariantMap resolutionSummary READ resolutionSummary NOTIFY resolutionProgressChanged)
public:
  explicit SyncManager(Store *store, QObject *parent = nullptr, quint16 port = 43817);
  ~SyncManager();
  bool enabled() const { return server.isListening(); }
  bool busy() const { return preparing || exchanging || resolving; }
  double progress() const { return progressValue; }
  QString status() const { return message; }
  QString pairingLink() const { return link; }
  QString pairingQr() const { return qr; }
  QStringList addresses() const;
  QVariantList devices() const;
  QVariantList conflicts() const;
  QVariantMap resolutionSummary() const { return resolutionResult; }
  Q_INVOKABLE void setEnabled(bool value);
  Q_INVOKABLE void pair(const QString &address, const QString &replacement = QString());
  Q_INVOKABLE void copyLink();
  Q_INVOKABLE void revoke(const QString &device);
  Q_INVOKABLE void resolve(const QString &id, bool usePhone, qint64 expectedRevision = -1, const QString &expectedPhone = QString());
  Q_INVOKABLE void resolveMany(const QVariantList &snapshots, bool usePhone);
  Q_INVOKABLE void cancelResolutions();
  Q_INVOKABLE void prepare();
  void shutdown();
  // protocol operations are public for isolated fixture checks, TLS routes call the same implementation
  static constexpr quint16 kDiscoveryPort = 43818;
  static QByteArray discoveryReplyPayload(const QString &deviceId, const QString &name,
                                          quint16 port, const QString &fingerprint);
  static QVariantList mergeEqPresets(const QVariantList &local, const QVariantList &remote);
  static QVariantMap mergeEqState(const QVariantMap &local, const QVariantMap &remote,
                                  const QVariantList &mergedPresets);
  void resetLocalState();
  QVariantMap exchange(const QString &peer, const QVariantList &operations);
  QVariantMap changes(qint64 cursor, int limit = 200) const;
  void markDirty();
  void setMaintenanceGuard(std::function<bool()> guard) { maintenanceGuard = std::move(guard); }
signals:
  void changed();
  void applied();
  void presetsChanged();
  void aboutToApply();
  void resolutionProgressChanged();
  void fileReceived(const QString &path, const QVariantMap &source);
  void error(const QString &message);

private:
  Store *s;
  SyncServer server;
  QUdpSocket discovery;
  QSslConfiguration tls;
  QString message = "Sync is off", link, qr, pairToken, fingerprint;
  QString certPath, keyPath, stateDir, pairReplacement;
  quint16 listenPort;
  qint64 pairUntil = 0, prepareTotal = 0;
  double progressValue = -1;
  bool preparing = false, exchanging = false, resolving = false, resolutionCancelled = false, closing = false;
  QVariantMap resolutionResult;
  std::function<bool()> maintenanceGuard;
  bool maintenance() const { return maintenanceGuard && maintenanceGuard(); }
  QVariantMap beginExchange();
  QVariantMap applyOperations(const QString &peer, const QVariantList &operations);
  void finishExchange();
  void exchangeAsync(QSslSocket *socket, const QString &peer, const QVariantList &operations);
  QVariantMap resolveConflict(const QString &id, bool usePhone, qint64 expectedRevision,
                              const QString &expectedPhone, const QString &peer = QString(), bool tracksOnly = false);
  void startResolutions(const QVariantList &snapshots, bool usePhone, const QString &peer,
                        QSslSocket *socket = nullptr);
  QTimer debounce, expiry;
  QFutureWatcher<QVariantMap> worker;
  QSet<QSslSocket *> sockets, blobSockets;
  int activeBlobReads = 0;
  QHash<QString, QPair<qint64, int>> pairingAttempts;
  void accept(qintptr descriptor);
  void startDiscovery();
  void stopDiscovery();
  void handleDiscoveryDatagram();
  void processRequest(QSslSocket *socket);
  void route(QSslSocket *socket, const QString &method, const QString &target,
             const QMap<QString, QString> &headers, const QByteArray &body);
  void reply(QSslSocket *socket, int status, const QVariantMap &json);
  void rawReply(QSslSocket *socket, int status, const QByteArray &body,
                const QByteArray &type = "application/json",
                const QByteArray &headers = {});
  void initializeTls();
  QVariantMap wire(const QString &entity, const QString &key,
                   const QVariantMap &hashes = {}) const;
  bool apply(const QString &entity, const QString &key, const QVariant &payload,
             QString *why);
  qint64 commitEntity(const QString &entity, const QString &key,
                      const QVariant &payload, const QString &author, bool force = false);
  void finishUpload(QSslSocket *socket, const QString &hash,
                    const QString &file);
  QString canonicalKey(const QString &peer, const QString &entity,
                       const QString &key) const;
  QVariantMap rewrite(const QString &peer, const QString &entity,
                      QVariantMap payload) const;
};
