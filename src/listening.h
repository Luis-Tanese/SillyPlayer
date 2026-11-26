#pragma once
#include "store.h"
#include "player.h"
#include <QElapsedTimer>

// playback observations are separate from accounting so thresholds and clock boundaries can be tested without sleeping or manufacturing player events
struct ListeningSample {
  quint64 generation=0, seek=0;
  double position=0, duration=0;
  bool active=false;
  double rate=1;
};
class Listening : public QObject {
  Q_OBJECT
  Q_PROPERTY(QVariantMap summary READ summary NOTIFY summaryChanged)
public:
  Listening(Store *store,Player *player,QObject *parent=nullptr);
  ~Listening();
  QVariantMap summary() const;
  Q_INVOKABLE QVariantList mostPlayed(const QString &period,bool artists=false) const;
  Q_INVOKABLE QVariantMap insights(const QString &period) const;
  QVariantList recentTracks() const;
  static QString compactTime(qint64 milliseconds);
  static QString exactTime(qint64 milliseconds);
  void begin(const QString &track,bool restore=false);
  void observe(const ListeningSample &sample,qint64 monotonicMs,qint64 utcMs);
  void flush();
  void remapTracks(const QVariantMap &ids);
  void shutdown();
  void reload();
  void resetState();
signals:
  void summaryChanged();
  void rankingsChanged();
  void error(const QString &message);
private:
  Store *s;
  Player *player;
  QString track, path, session;
  double totalMs=0, realMs=0, mediaMs=0, wallBudget=0, audioBudget=0;
  qint64 started=0, qualifiedAt=0, lastObservation=0, lastSummary=0, lastPrune=0;
  struct PendingPlay { QString session, track; qint64 at; };
  QList<PendingPlay> pendingPlays;
  bool qualified=false, dirty=false, closed=false, reportedError=false;
  ListeningSample previous;
  QElapsedTimer clock;
  QTimer poller, checkpoint;
  void poll();
};
