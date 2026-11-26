#pragma once
#include "store.h"
#include <QFileSystemWatcher>
#include <QFutureWatcher>
#include <QObject>
#include <QTimer>
#include <QHash>

class Library : public QObject {
  Q_OBJECT
  Q_PROPERTY(QVariantList tracks READ tracks NOTIFY changed)
  Q_PROPERTY(QVariantList folders READ folders NOTIFY changed)
  Q_PROPERTY(int likedCount READ likedCount NOTIFY likesChanged)
  Q_PROPERTY(bool trashing READ trashing NOTIFY statusChanged)
  Q_PROPERTY(bool scanning READ scanning NOTIFY statusChanged)
  Q_PROPERTY(QString status READ status NOTIFY statusChanged)
public:
  Library(Store *store, const QString &cache, QObject *parent = nullptr);
  ~Library();
  QVariantList tracks() const;
  QVariantList folders() const;
  int likedCount() const;
  void patchTracks(const QVariantMap &patches);
  bool scanning() const { return m_busy; }
  QString status() const { return m_status; }
  Q_INVOKABLE void addFolder(const QString &url);
  Q_INVOKABLE void removeFolder(const QString &id);
  Q_INVOKABLE void rescan();
  Q_INVOKABLE QVariantMap track(const QString &id) const;
  Q_INVOKABLE bool removeRecords(const QStringList &ids);
  QVariantMap reinstateFolder(const QString &folderPath);
  bool trashFile(const QString &id);
  bool trashing() const { return m_trashing; }
  bool trashFiles(const QStringList &ids);
  void finishTrash();
  bool inMaintenance() const { return maintenance; }
  bool beginMaintenance();
  void endMaintenance();
  void importFile(const QString &path, const QVariantMap &source = {});
  Q_INVOKABLE void editTags(const QString &id, const QVariantMap &tags);
  Q_INVOKABLE bool editTagsBatch(const QStringList &ids, const QVariantMap &tags);
  Q_INVOKABLE bool cleanTitles(const QStringList &ids);
signals:
  void changed();
  void tracksUpdated(const QVariantMap &patches);
  void likesChanged();
  void statusChanged();
  void error(const QString &message);
  void trashFinished(const QVariantMap &result);
  void batchEditFinished(const QVariantMap &result);
  void fileIndexed(const QString &path,const QString &track);

private:
  Store *s;
  QString cache, m_status;
  mutable bool tracksValid=false,foldersValid=false;
  mutable int cachedLikedCount=-1;
  mutable QVariantList cachedTracks,cachedFolders;
  mutable QHash<QString,int> trackPositions;
  mutable quint64 tracksSerial=0,tracksCatalogueSerial=0,foldersSerial=0;
  bool m_busy = false, pending = false, m_trashing = false;
  bool maintenance=false;
  QList<QPair<QString,QVariantMap>> deferredImports;
  QFutureWatcher<QVariantMap> trashWorker;
  bool pendingForce = false;
  QFileSystemWatcher watches;
  QTimer debounce, periodic;
  QFutureWatcher<QVariantMap> scan;
  QList<QFutureWatcher<QVariantMap> *> edits;
  void startScan(bool force);
  bool applyTrack(const QVariantMap &data, const QString &root = {},
                  bool managed = false, const QVariantMap &source = {});
};
