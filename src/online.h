#pragma once
#include "store.h"
#include <QObject>
#include <QProcess>
#include <QTimer>
#include <QNetworkAccessManager>
#include <QNetworkReply>
class ExtractionService;
class DirectTransfer;
class YouTubePlaylist;

class Online : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString activeProvider READ activeProvider NOTIFY providerChanged)
  Q_PROPERTY(QString providerName READ providerName NOTIFY providerChanged)
  Q_PROPERTY(QVariantList results READ results NOTIFY searchChanged)
  Q_PROPERTY(QVariantList history READ history NOTIFY searchChanged)
  Q_PROPERTY(QVariantMap details READ details NOTIFY detailsChanged)
  Q_PROPERTY(QVariantList jobs READ jobs NOTIFY jobsChanged)
  Q_PROPERTY(bool busy READ busy NOTIFY searchChanged)
  Q_PROPERTY(QString status READ status NOTIFY searchChanged)
  Q_PROPERTY(bool resolving READ resolving NOTIFY detailsChanged)
  Q_PROPERTY(bool downloadResolving READ downloadResolving NOTIFY
                 downloadLookupChanged)
  Q_PROPERTY(QString downloadLookupUrl READ downloadLookupUrl NOTIFY
                 downloadLookupChanged)
  Q_PROPERTY(QString lookupError READ lookupError NOTIFY detailsChanged)
  Q_PROPERTY(QObject *playlist READ playlistObject CONSTANT)
public:
  Online(Store *store, QObject *parent = nullptr);
  ~Online();
  QVariantList results() const { return m_results; }
  QVariantList history() const;
  QVariantMap details() const { return m_details; }
  QVariantList jobs() const { return m_jobs; }
  bool busy() const { return searching; }
  bool resolving() const { return extracting; }
  bool downloadResolving() const { return downloadRequest != 0; }
  QString downloadLookupUrl() const { return downloadUrl; }
  QString lookupError() const { return detailError; }
  QString status() const { return m_status; }
  QString activeProvider() const { return m_provider; }
  QString providerName() const;
  QObject *playlistObject() const;
  QVariantMap enqueueBatch(const QVariantList &entries,const QString &kind,const QString &destination,bool again=false,const QString &playlistName={});
  void restorePlaylistDownloads();
  void attachPlaylistDownloads(const QString &path,const QString &track);
  Q_INVOKABLE void setActiveProvider(const QString &provider);
  Q_INVOKABLE void prepareDirect(const QString &url);
  Q_INVOKABLE void search(const QString &query, bool more = false);
  Q_INVOKABLE void removeHistory(const QString &query);
  Q_INVOKABLE void clearHistory();
  Q_INVOKABLE void resolve(const QString &url, bool play, bool verifyFormats = false);
  Q_INVOKABLE void cancelResolution();
  Q_INVOKABLE void retryVideo();
  bool refreshPlayback();
  void invalidatePlayback();
  Q_INVOKABLE QString enqueue(const QVariantMap &details, const QString &kind,
                              const QString &quality,
                              const QString &destination, bool again = false);
  Q_INVOKABLE void cancel(const QString &id);
  Q_INVOKABLE void retry(const QString &id);
  Q_INVOKABLE void pause(const QString &id);
  Q_INVOKABLE void clearCompleted();
  Q_INVOKABLE void removeJob(const QString &id);
  Q_INVOKABLE void clearFailed();
  Q_INVOKABLE QString streamQualityLabel(const QVariantMap &details) const;
  Q_INVOKABLE QVariantMap repairDownloadedSongs();
  void resetJobs();
  void shutdown();
  static QString tool(const QString &name);
  static QVariantMap normalizeResult(QVariantMap result);
  static QStringList baseArgs();
  static QVariantMap musicMetadata(const QVariantMap &details);
signals:
  void providerChanged();
  void searchChanged();
  void detailsChanged();
  void downloadLookupChanged();
  void playbackRequested(bool refresh);
  void jobsChanged();
  void error(const QString &message);
  void streamReady(const QString &url, const QString &audio,
                   const QVariantMap &headers);
  void downloadReady(const QVariantMap &details);
  void musicDownloaded(const QString &path, const QVariantMap &source);
  void playlistsChanged();
  void lookupMeasured(const QString &operation, bool cacheHit, qint64 elapsedMs);

private:
  Store *s;
  QString m_provider="youtube";
  struct SearchState { QVariantList results; QString query, status; int limit=20; };
  QHash<QString,SearchState> searchStates;
  QHash<QString,DirectTransfer *> transfers;
  QVariantList m_results, m_jobs;
  QHash<QString, QVariantMap> savedJobs;
  QTimer progressNotifications;
  QVariantMap m_details;
  QString m_status, lastQuery, downloadUrl, detailError;
  int limit = 20;
  bool searching = false, extracting = false, closing = false;
  bool deferringJobs=false;
  YouTubePlaylist *playlist;
  QProcess *searchProcess = nullptr;
  ExtractionService *extraction;
  quint64 detailRequest = 0, downloadRequest = 0;
  bool verifiedPlayback = true;
  QString playbackUrl;
  QNetworkAccessManager images;
  QNetworkReply *imageReply = nullptr;
  quint64 imageGeneration = 0;
  void cacheArtwork(const QVariantMap &details, int attempt = 0);
  QHash<QString, QProcess *> workers;
  QHash<QString, quint64> jobRequests;
  QHash<QString, QVariantMap> jobInfo;
  QHash<QString, QByteArray> buffers;
  QHash<QString, QString> requestedStops;
  void startJob(int index, bool useCache = true);
  void pump();
  bool persist(bool inTransaction=false);
  void publishDownloaded(const QString &job,const QString &path,const QVariantMap &source);
  int jobIndex(const QString &id) const;
  void updateLine(const QString &id, const QByteArray &line);
};
