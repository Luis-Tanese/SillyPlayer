#pragma once
#include "library.h"
#include "player.h"
#include <QFutureWatcher>
#include <atomic>
#include <memory>
#include <functional>

class LibraryTools : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool busy READ busy NOTIFY changed)
  Q_PROPERTY(bool canCancel READ canCancel NOTIFY changed)
  Q_PROPERTY(QString message READ message NOTIFY changed)
  Q_PROPERTY(double progress READ progress NOTIFY changed)
  Q_PROPERTY(QString view READ view NOTIFY changed)
  Q_PROPERTY(QVariantList missing READ missing NOTIFY changed)
  Q_PROPERTY(QVariantList candidates READ candidates NOTIFY changed)
  Q_PROPERTY(QVariantList groups READ groups NOTIFY changed)
public:
  LibraryTools(Store *store,Library *library,Player *player,QObject *parent=nullptr);
  ~LibraryTools();
  bool busy() const { return held; }
  bool canCancel() const { return held && !finishing; }
  QString message() const { return status; }
  double progress() const { return fraction; }
  QString view() const { return mode; }
  QVariantList missing() const { return missingRows; }
  QVariantList candidates() const { return matches; }
  QVariantList groups() const { return duplicates; }
  Q_INVOKABLE void findMissing();
  Q_INVOKABLE void findDuplicates();
  Q_INVOKABLE void chooseFile(const QString &id);
  Q_INVOKABLE void chooseFolder(const QString &root);
  Q_INVOKABLE void toggleCandidate(const QString &id);
  Q_INVOKABLE void selectCandidates();
  Q_INVOKABLE void recover();
  Q_INVOKABLE void consolidate(const QString &hash,const QString &keep);
  Q_INVOKABLE void cancel();
  void prepareFile(const QString &id,const QString &path);
  void prepareFolder(const QString &root,const QString &path);
  void shutdown();
  void setCurrentTrack(std::function<QString()> current) { currentTrack=std::move(current); }
signals:
  void opened();
  void changed();
  void beforeMerge();
  void remapped(const QVariantMap &ids);
private:
  Store *s;
  Library *lib;
  Player *player;
  QFutureWatcher<QVariantMap> worker;
  std::shared_ptr<std::atomic_bool> stopped;
  QString status,mode="missing",operation,rootId,newRoot;
  double fraction=0;
  bool held=false,closing=false,finishing=false;
  QVariantList missingRows,matches,duplicates;
  QVariantMap mergePlan;
  std::function<QString()> currentTrack;
  bool begin();
  void launch(const QString &kind,std::function<QVariantMap()> task);
  void completed();
  void release();
  void report(qint64 done,qint64 total,const QString &text);
  QVariantMap cached(const QVariantMap &track) const;
  bool commitRecovery(const QVariantList &rows,QString *error);
  bool commitMerge(const QString &keep,const QStringList &remove,QString *error);
  void mapReferences(const QString &keep,const QStringList &remove);
};
