#pragma once
#include "library.h"
#include <QFutureWatcher>
#include <atomic>
#include <memory>
#include <functional>

class Backup : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString state READ state NOTIFY changed)
  Q_PROPERTY(QString message READ message NOTIFY changed)
  Q_PROPERTY(double progress READ progress NOTIFY changed)
  Q_PROPERTY(bool busy READ busy NOTIFY changed)
  Q_PROPERTY(QVariantMap preview READ preview NOTIFY changed)
public:
  Backup(Store *store,Library *library,QObject *parent=nullptr);
  ~Backup();
  QString state() const { return phase; }
  QString message() const { return text; }
  double progress() const { return fraction; }
  bool busy() const { return held; }
  QVariantMap preview() const { return details; }
  Q_INVOKABLE void chooseBackup();
  Q_INVOKABLE void chooseRestore(const QString &musicDirectory);
  Q_INVOKABLE void confirm();
  Q_INVOKABLE void cancel();
  void prepareBackup(const QString &path);
  void prepareRestore(const QString &path,const QString &musicDirectory);
  void shutdown();
signals:
  void changed();
private:
  Store *s;
  Library *lib;
  QFutureWatcher<QVariantMap> worker;
  std::shared_ptr<std::atomic_bool> stopped;
  QString phase="Idle",text;
  double fraction=0;
  bool held=false,closing=false;
  QVariantMap details,pending;
  QString destination;
  using Task=std::function<QVariantMap()>;
  void launch(const QString &state,Task task);
  void completed();
  void finish(const QString &state,const QString &message);
  void cleanup();
  void notifyProgress(qint64 done,qint64 total,const QString &label);
  bool merge(const QVariantMap &prepared,QString *summary);
};
