#pragma once
#include <QObject>
#include <QVariantMap>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QFile>
#include <QProcess>
#include <QElapsedTimer>

// owns only its job's staging directory, no source media is ever rewritten
class DirectTransfer : public QObject {
  Q_OBJECT
public:
  explicit DirectTransfer(QObject *parent=nullptr);
  ~DirectTransfer();
  void start(const QVariantMap &job);
  void stop(const QString &state);
signals:
  void updated(const QVariantMap &changes);
  void finished(const QString &state, const QString &path, const QString &error);
private:
  QNetworkAccessManager network;
  QNetworkReply *reply=nullptr;
  QProcess *probe=nullptr;
  QFile file;
  QElapsedTimer progressClock;
  QVariantMap job, checkpoint;
  QString stage, suggestedName, failure;
  qint64 offset=0;
  bool done=false, accepted=false, restarted=false, restartPending=false;
  void request(bool resume);
  bool acceptHeaders();
  void drain();
  void validate();
  void complete(const QString &state, const QString &path={}, const QString &error={});
};
