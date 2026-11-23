#pragma once
#include <QObject>
#include <QTimer>
#include <QDateTime>
#include <functional>

class SleepTimer : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool active READ active NOTIFY changed)
  Q_PROPERTY(int remainingSeconds READ remainingSeconds NOTIFY changed)
public:
  explicit SleepTimer(QObject *parent=nullptr, std::function<qint64()> clock={})
      : QObject(parent), now(clock ? std::move(clock) : [] { return QDateTime::currentMSecsSinceEpoch(); }) {
    timer.setInterval(1000);
    connect(&timer,&QTimer::timeout,this,&SleepTimer::check);
  }
  bool active() const { return deadline>0; }
  int remainingSeconds() const { return remaining; }
  Q_INVOKABLE void start(int minutes) {
    if(minutes<1 || minutes>1440) return;
    deadline=now()+minutes*60000LL; remaining=minutes*60;
    timer.start(); emit changed();
  }
  Q_INVOKABLE void cancel() {
    timer.stop(); deadline=0; remaining=0; emit changed();
  }
  void check() {
    if(!active()) return;
    const auto left=qMax<qint64>(0,(deadline-now()+999)/1000);
    if(left==0) { cancel(); emit expired(); }
    else if(remaining!=left) { remaining=int(left); emit changed(); }
  }
signals:
  void changed();
  void expired();
private:
  QTimer timer;
  std::function<qint64()> now;
  qint64 deadline=0;
  int remaining=0;
};
