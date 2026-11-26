#include "listening.h"
#include "artistsplit.h"
#include <QDateTime>
#include <QJsonDocument>
#include <QFileInfo>
#include <QUuid>
#include <algorithm>
#include <cmath>

Listening::Listening(Store *store,Player *backend,QObject *parent):QObject(parent),s(store),player(backend) {
  const auto totals=s->rows("SELECT * FROM listening_totals WHERE id=1").value(0).toMap();
  totalMs=totals.value("milliseconds").toDouble(); started=totals.value("started").toLongLong();
  clock.start();
  if(player) {
    connect(player,&Player::changed,this,[this]{
      poll();
      const bool active=player->loaded() && !player->paused();
      if(active && !poller.isActive())poller.start();
      else if(!active && poller.isActive()){poller.stop();flush();}
    });
    poller.setInterval(1000);connect(&poller,&QTimer::timeout,this,&Listening::poll);
  }
  checkpoint.setInterval(5000); connect(&checkpoint,&QTimer::timeout,this,&Listening::flush); checkpoint.start();
  // todo: maybe bump this to 90 days if we ever add a yearly/quarterly view
  s->exec("DELETE FROM listening_plays WHERE playedAt<?",{QDateTime::currentMSecsSinceEpoch()-30LL*86400000});
  lastPrune=QDateTime::currentMSecsSinceEpoch();
}
Listening::~Listening() { shutdown(); }
QString Listening::exactTime(qint64 milliseconds) {
  const auto minutes=qMax(qint64(0),milliseconds)/60000;
  return QString::number(minutes/60)+"h"+QString::number(minutes%60)+"m";
}
QString Listening::compactTime(qint64 milliseconds) {
  const auto minutes=qMax(qint64(0),milliseconds)/60000;
  if(minutes>=1440) return QString::number(minutes/1440)+"d"+QString::number(minutes/60%24)+"h";
  return minutes>=60 ? exactTime(milliseconds) : QString::number(minutes)+"m";
}
QVariantMap Listening::summary() const {
  return {{"milliseconds",qint64(totalMs)},{"compact",compactTime(qint64(totalMs))},
    {"exact",exactTime(qint64(totalMs))},{"since",QDateTime::fromMSecsSinceEpoch(started).date().toString(Qt::ISODate)}};
}
void Listening::begin(const QString &id,bool restore) {
  poll(); flush();
  const auto saved=s->setting("listeningSession").toMap();
  track=id; path=s->rows("SELECT path FROM tracks WHERE id=? AND deleted=0",{id}).value(0).toMap().value("path").toString();
  if(path.isEmpty()) track.clear();
  const bool resume=restore && !track.isEmpty() && saved.value("track").toString()==track && !saved.value("session").toString().isEmpty();
  session=resume ? saved.value("session").toString() : QUuid::createUuid().toString(QUuid::WithoutBraces);
  realMs=resume ? saved.value("realMs").toDouble() : 0;
  mediaMs=resume ? saved.value("mediaMs").toDouble() : 0;
  qualified=resume && saved.value("qualified").toBool(); qualifiedAt=0;
  previous={}; wallBudget=audioBudget=0; lastObservation=clock.elapsed(); dirty=true;
  flush(); // persist a new occurrence even when it repeats the same track
}
void Listening::poll() {
  if(!player || closed || track.isEmpty()) return;
  ListeningSample sample;
  sample.generation=player->generation(); sample.seek=player->seekGeneration();
  sample.position=player->transportPosition(); sample.duration=player->duration(); sample.rate=player->playbackRate();
  sample.active=player->mediaPath()==path && player->loaded() && !player->paused() && !player->buffering() && !player->seekPending() && player->error().isEmpty();
  observe(sample,clock.elapsed(),QDateTime::currentMSecsSinceEpoch());
}
void Listening::observe(const ListeningSample &sample,qint64 monotonicMs,qint64 utcMs) {
  if(closed || track.isEmpty()) return;
  const double elapsed=monotonicMs-lastObservation;
  const double media=(sample.position-previous.position)*1000;
  const bool continuous=previous.active && sample.active && previous.generation==sample.generation && previous.seek==sample.seek &&
    elapsed>0 && std::isfinite(media) && media>=0 && media<=elapsed*2.0+250;
  // keep the last advancing anchor between identical backend timestamps
  // this counts real output time without deriving hours from position or seek jumps
  if(continuous && media==0) return;
  if(continuous) {
    // account only confirmed audio advancement, capped by monotonic time
    // carry small PTS/clock differences between samples so jitter cannot lose time, while an unreported output stall cannot manufacture listening hours
    const double rate=qBound(0.5,(previous.rate+sample.rate)/2.0,2.0);
    wallBudget+=elapsed; audioBudget+=media/rate;
    const double heard=qMin(wallBudget,audioBudget);
    wallBudget-=heard; audioBudget-=heard;
    totalMs+=heard; realMs+=heard; mediaMs+=media; dirty=true;
    if(!qualified && (realMs>=30000 || (sample.duration>0 && mediaMs>=sample.duration*500))) {
      qualified=true; qualifiedAt=utcMs;
      pendingPlays.append({session,track,qualifiedAt});
      flush(); // count, recent ordering and restart checkpoint commit together
    }
    if(monotonicMs-lastSummary>=1000) { lastSummary=monotonicMs; emit summaryChanged(); }
  }
  if(!continuous) wallBudget=audioBudget=0;
  previous=sample; lastObservation=monotonicMs;
}
void Listening::remapTracks(const QVariantMap &ids) {
  if(ids.contains(track)) {
    track=ids[track].toString();
    path=s->rows("SELECT path FROM tracks WHERE id=?",{track}).value(0).toMap()["path"].toString();
  }
  for(auto &play:pendingPlays) if(ids.contains(play.track)) play.track=ids[play.track].toString();
  dirty=true; flush(); emit rankingsChanged();
}
void Listening::flush() {
  if(!dirty || closed) return;
  bool ok=s->db().transaction();
  const bool newPlay=!pendingPlays.isEmpty();
  decltype(pendingPlays) newlyCounted;
  for(const auto &play:pendingPlays) {
    if(!ok) break;
    ok=s->exec("INSERT OR IGNORE INTO listening_plays(session,track,playedAt) VALUES(?,?,?)",{play.session,play.track,play.at});
    if(ok && s->rows("SELECT changes() AS n").value(0).toMap().value("n").toInt()>0) {
      newlyCounted.append(play);
      ok=s->exec("INSERT INTO track_listens(track,plays,lastPlayed) VALUES(?,1,?) ON CONFLICT(track) DO UPDATE SET plays=plays+1,lastPlayed=excluded.lastPlayed",{play.track,play.at});
    }
  }
  if(ok) {
    const auto before=s->rows("SELECT milliseconds FROM listening_totals WHERE id=1").value(0).toMap()["milliseconds"].toDouble();
    const auto device=s->setting("libraryIdentity").toString();const qint64 added=qMax(qint64(0),qint64(totalMs-before));
    if(added>0)ok=s->exec("INSERT INTO sync_counters VALUES(?,?,?,?,0,0) ON CONFLICT(device,component) DO UPDATE SET milliseconds=milliseconds+excluded.milliseconds",{device,track.isNull()?QString(""):track,track.isNull()?QString(""):track,added});
    for(const auto &play:newlyCounted)if(ok)ok=s->exec("INSERT INTO sync_counters VALUES(?,?,?,0,1,?) ON CONFLICT(device,component) DO UPDATE SET plays=plays+1,lastPlayed=MAX(lastPlayed,excluded.lastPlayed)",{device,play.track,play.track,play.at});
  }
  if(ok) ok=s->exec("UPDATE listening_totals SET milliseconds=? WHERE id=1",{totalMs});
  if(ok) ok=s->exec("INSERT INTO settings(key,value) VALUES('listeningSession',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
    {QString::fromUtf8(QJsonDocument::fromVariant(QVariantList{QVariantMap{{"track",track},{"session",session},{"realMs",realMs},{"mediaMs",mediaMs},{"qualified",qualified}}}).toJson(QJsonDocument::Compact))});
  const auto now=QDateTime::currentMSecsSinceEpoch();
  if(ok && now-lastPrune>=3600000) ok=s->exec("DELETE FROM listening_plays WHERE playedAt<?",{now-30LL*86400000});
  if(!ok || !s->db().commit()) {
    s->db().rollback();
    if(!reportedError) { reportedError=true; emit error("Listening statistics could not be saved"); }
    return;
  }
  if(now-lastPrune>=3600000) lastPrune=now;
  dirty=false; pendingPlays.clear(); reportedError=false;
  if(newPlay) emit rankingsChanged();
}
QVariantList Listening::recentTracks() const {
  return s->rows("SELECT t.*,EXISTS(SELECT 1 FROM likes WHERE track=t.id) AS liked FROM track_listens l JOIN tracks t ON t.id=l.track WHERE t.deleted=0 ORDER BY l.lastPlayed DESC,t.id LIMIT 100");
}
QVariantList Listening::mostPlayed(const QString &period,bool artists) const {
  // match the calendar-midnight cutoffs used by insights so totalPlays and chart sums actually agree
  const QDate today=QDate::currentDate();
  qint64 cutoff=QDateTime(today.addDays(-29),QTime(0,0)).toMSecsSinceEpoch();
  if(period=="day") cutoff=QDateTime(today,QTime(0,0)).toMSecsSinceEpoch();
  else if(period=="week") cutoff=QDateTime(today.addDays(-6),QTime(0,0)).toMSecsSinceEpoch();
  const bool all=period=="all";
  const QString source=all ? "SELECT track,plays,lastPlayed FROM track_listens" : "SELECT track,COUNT(*) AS plays,MAX(playedAt) AS lastPlayed FROM listening_plays WHERE playedAt>=? GROUP BY track";
  const QVariantList args=all ? QVariantList{} : QVariantList{cutoff};
  if(artists) {
    struct StatBucket { QString name; QString cover; qint64 plays = 0; qint64 lastPlayed = 0; int count = 0; };
    QHash<QString, StatBucket> buckets;
    const auto trackRows=s->rows("SELECT t.artist,t.art,l.plays,l.lastPlayed FROM ("+source+") l JOIN tracks t ON t.id=l.track WHERE t.deleted=0 AND TRIM(t.artist)<>''",args);
    for(const auto &v : trackRows) {
      const auto row=v.toMap();
      const qint64 plays=row["plays"].toLongLong();
      const qint64 last=row["lastPlayed"].toLongLong();
      const QString cover=row["art"].toString();
      for(const auto &artistName : splitArtists(row["artist"].toString())) {
        const QString key=artistName.toLower();
        auto &b=buckets[key];
        if(b.name.isEmpty()) b.name=artistName;
        if(b.cover.isEmpty() && !cover.isEmpty()) b.cover=cover;
        b.plays+=plays;
        if(last>b.lastPlayed) b.lastPlayed=last;
        b.count++;
      }
    }
    QVariantList rows;
    rows.reserve(buckets.size());
    for(auto it=buckets.cbegin(); it!=buckets.cend(); ++it) {
      rows << QVariantMap{{"id","artist:"+it.key()},{"name",it.value().name},{"cover",it.value().cover},
                          {"playCount",it.value().plays},{"lastPlayed",it.value().lastPlayed},{"count",it.value().count}};
    }
    std::sort(rows.begin(), rows.end(), [](const QVariant &a, const QVariant &b) {
      const auto ma=a.toMap(), mb=b.toMap();
      if(ma["playCount"].toLongLong()!=mb["playCount"].toLongLong())
        return ma["playCount"].toLongLong()>mb["playCount"].toLongLong();
      if(ma["lastPlayed"].toLongLong()!=mb["lastPlayed"].toLongLong())
        return ma["lastPlayed"].toLongLong()>mb["lastPlayed"].toLongLong();
      return ma["name"].toString().compare(mb["name"].toString(), Qt::CaseInsensitive)<0;
    });
    return rows;
  }
  return s->rows("SELECT t.*,l.plays AS playCount,l.lastPlayed,EXISTS(SELECT 1 FROM likes WHERE track=t.id) AS liked FROM ("+source+") l JOIN tracks t ON t.id=l.track WHERE t.deleted=0 ORDER BY playCount DESC,lastPlayed DESC,t.title COLLATE NOCASE,t.id",args);
}
QVariantMap Listening::insights(const QString &period) const {
  const QDate today=QDate::currentDate();
  const qint64 dayStart=QDateTime(today,QTime(0,0)).toMSecsSinceEpoch();
  qint64 cutoff=0;
  if(period=="day") cutoff=dayStart;
  else if(period=="week") cutoff=QDateTime(today.addDays(-6),QTime(0,0)).toMSecsSinceEpoch();
  else if(period=="month") cutoff=QDateTime(today.addDays(-29),QTime(0,0)).toMSecsSinceEpoch();

  const auto topSongs=mostPlayed(period,false);
  const auto topArtists=mostPlayed(period,true);
  qint64 totalPlays=0;
  for(const auto &v : topSongs) totalPlays+=v.toMap()["playCount"].toLongLong();

  const auto recentPlays=s->rows("SELECT p.playedAt FROM listening_plays p JOIN tracks t ON t.id=p.track WHERE t.deleted=0 AND p.playedAt>=?",{cutoff});
  const auto allRecentDays=s->rows("SELECT p.playedAt FROM listening_plays p JOIN tracks t ON t.id=p.track WHERE t.deleted=0");
  QSet<QDate> streakDates;
  for(const auto &v : allRecentDays) {
    streakDates.insert(QDateTime::fromMSecsSinceEpoch(v.toMap()["playedAt"].toLongLong()).date());
  }
  int streakDays=0;
  QDate cursorDate=streakDates.contains(today) ? today : today.addDays(-1);
  while(streakDates.contains(cursorDate)) {
    streakDays++;
    cursorDate=cursorDate.addDays(-1);
  }

  QSet<QDate> activeDaySet;
  int timeBlocks[6]={0,0,0,0,0,0}; // 0-3, 4-7, 8-11, 12-15, 16-19, 20-23
  int weekdayCounts[7]={0,0,0,0,0,0,0}; // Mon(0)..Sun(6)
  QHash<QDate,int> dailyCounts;
  for(const auto &v : recentPlays) {
    const auto dt=QDateTime::fromMSecsSinceEpoch(v.toMap()["playedAt"].toLongLong());
    const auto d=dt.date();
    activeDaySet.insert(d);
    dailyCounts[d]+=1;
    const int hour=dt.time().hour();
    timeBlocks[qBound(0,hour/4,5)]+=1;
    const int dow=d.dayOfWeek()-1; // 1=Mon..7=Sun -> 0..6
    if(dow>=0 && dow<7) weekdayCounts[dow]+=1;
  }

  static const char *kBlockLabels[6]={"12a","4a","8a","12p","4p","8p"};
  static const char *kBlockNames[6]={"Late night (12–4 AM)","Early morning (4–8 AM)","Morning (8 AM–12 PM)","Afternoon (12–4 PM)","Evening (4–8 PM)","Night (8 PM–12 AM)"};
  static const char *kWeekShort[7]={"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};
  static const char *kWeekFull[7]={"Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"};

  int peakBlock=-1, peakBlockCount=0;
  for(int i=0;i<6;++i) if(timeBlocks[i]>peakBlockCount) { peakBlockCount=timeBlocks[i]; peakBlock=i; }
  int peakDay=-1, peakDayCount=0;
  for(int i=0;i<7;++i) if(weekdayCounts[i]>peakDayCount) { peakDayCount=weekdayCounts[i]; peakDay=i; }

  QVariantList buckets;
  int maxBucket=0;
  if(period=="day") {
    for(int i=0;i<6;++i) {
      maxBucket=qMax(maxBucket,timeBlocks[i]);
      buckets << QVariantMap{{"label",QString::fromLatin1(kBlockLabels[i])},{"count",timeBlocks[i]}};
    }
  } else if(period=="week") {
    for(int i=6;i>=0;--i) {
      const QDate d=today.addDays(-i);
      const int c=dailyCounts.value(d,0);
      maxBucket=qMax(maxBucket,c);
      const QString lbl=i==0 ? QStringLiteral("Today") : QString::fromLatin1(kWeekShort[d.dayOfWeek()-1]);
      buckets << QVariantMap{{"label",lbl},{"count",c}};
    }
  } else if(period=="month") {
    for(int i=29;i>=0;--i) {
      const QDate d=today.addDays(-i);
      const int c=dailyCounts.value(d,0);
      maxBucket=qMax(maxBucket,c);
      const QString lbl=(i==29 || i==20 || i==10 || i==0) ? d.toString("MMM d") : QString();
      buckets << QVariantMap{{"label",lbl},{"tooltip",d.toString("ddd, MMM d")},{"count",c}};
    }
  } else {
    for(int i=0;i<7;++i) {
      maxBucket=qMax(maxBucket,weekdayCounts[i]);
      buckets << QVariantMap{{"label",QString::fromLatin1(kWeekShort[i])},{"count",weekdayCounts[i]}};
    }
  }

  QString peakSummary;
  if(period=="day") {
    peakSummary=peakBlock>=0 ? QString::fromLatin1(kBlockNames[peakBlock]) : QStringLiteral("No plays today yet");
  } else if(peakDay>=0 && peakBlock>=0) {
    peakSummary=QString("%1 · %2").arg(kWeekFull[peakDay],kBlockNames[peakBlock]);
  } else if(peakDay>=0) {
    peakSummary=QString::fromLatin1(kWeekFull[peakDay]);
  } else {
    peakSummary=QStringLiteral("No recent timestamps yet");
  }

  const QVariantMap topArtist=topArtists.isEmpty() ? QVariantMap{} : QVariantMap{
    {"name",topArtists.first().toMap()["name"]},{"playCount",topArtists.first().toMap()["playCount"]}};
  const QVariantMap topTrack=topSongs.isEmpty() ? QVariantMap{} : QVariantMap{
    {"title",topSongs.first().toMap()["title"]},{"artist",topSongs.first().toMap()["artist"]},{"playCount",topSongs.first().toMap()["playCount"]}};

  return {
    {"period",period},
    {"totalPlays",totalPlays},
    {"uniqueSongs",topSongs.size()},
    {"uniqueArtists",topArtists.size()},
    {"activeDays",activeDaySet.size()},
    {"streakDays",streakDays},
    {"topArtist",topArtist},
    {"topTrack",topTrack},
    {"peakSummary",peakSummary},
    {"maxBucket",maxBucket},
    {"buckets",buckets},
    {"chartTitle",period=="day" ? "Today by time of day" : period=="week" ? "Last 7 days" : period=="month" ? "Last 30 days" : "Recent listening by weekday"}
  };
}
void Listening::reload() {
  const auto value=s->rows("SELECT milliseconds FROM listening_totals WHERE id=1").value(0).toMap()["milliseconds"].toDouble();
  totalMs=value;emit summaryChanged();emit rankingsChanged();
}
void Listening::resetState() {
  poller.stop();
  track.clear(); path.clear(); session.clear();
  totalMs=realMs=mediaMs=wallBudget=audioBudget=0;
  qualified=false; dirty=false; pendingPlays.clear(); previous={};
  const auto totals=s->rows("SELECT * FROM listening_totals WHERE id=1").value(0).toMap();
  started=totals.value("started").toLongLong();
  emit summaryChanged(); emit rankingsChanged();
}
void Listening::shutdown() {
  if(closed) return;
  poll(); flush(); closed=true; poller.stop(); checkpoint.stop();
}
