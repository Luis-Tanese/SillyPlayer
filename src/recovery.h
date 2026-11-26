#pragma once
#include "store.h"
#include <QFutureWatcher>
#include <QObject>
#include <QTimer>
#include <QVariantMap>
#include <functional>

class DesktopRecovery : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString status READ status NOTIFY changed)
  Q_PROPERTY(qint64 lastSaved READ lastSaved NOTIFY changed)
  Q_PROPERTY(bool saving READ saving NOTIFY changed)
public:
  explicit DesktopRecovery(Store *store, QObject *parent = nullptr);
  ~DesktopRecovery();

  QString status() const { return m_status; }
  qint64 lastSaved() const { return m_lastSaved; }
  bool saving() const { return m_saving; }

  void setFolderProvider(std::function<QString()> provider) { folderProvider = std::move(provider); }
  void setBusyGuard(std::function<bool()> guard) { busyGuard = std::move(guard); }

  void inspectFolder(const QString &folder);
  void requestSave();
  Q_INVOKABLE QVariantMap saveNow(bool allowEmpty = false);
  Q_INVOKABLE QVariantMap recoverFromFolder(const QString &folder = {});
  void shutdown();

signals:
  void changed();
  void recovered();

private:
  Store *s;
  std::function<QString()> folderProvider;
  std::function<bool()> busyGuard;
  QString m_status;
  QString inspectedFolder;
  qint64 m_lastSaved = 0;
  int newestSlot = -1;
  bool m_saving = false;
  bool saveAgain = false;
  bool closing = false;
  QTimer debounce;
  QFutureWatcher<QVariantMap> worker;

  QVariantMap saveInternal(bool allowEmpty, bool createMissingDir);
  void startAsyncSave();
  static QVariantMap runSnapshotWorker(const QString &sourceDbPath, const QString &folder,
                                       int prevSlot, qint64 prevSaved, bool allowEmpty,
                                       bool createMissingDir);
  static QVariantMap readMeta(const QString &dbFile, bool verifyIntegrity = true);
};
