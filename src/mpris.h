#pragma once
#include "app.h"
#include <QCoreApplication>
#include <QDBusAbstractAdaptor>
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusObjectPath>
#include <QUrl>
#include <QTimer>

class MprisRoot : public QDBusAbstractAdaptor {
  Q_OBJECT
  Q_CLASSINFO("D-Bus Interface", "org.mpris.MediaPlayer2")
  Q_PROPERTY(bool CanQuit READ yes CONSTANT)
  Q_PROPERTY(bool CanRaise READ yes CONSTANT)
  Q_PROPERTY(bool HasTrackList READ no CONSTANT)
  Q_PROPERTY(QString Identity READ identity CONSTANT)
  Q_PROPERTY(QString DesktopEntry READ desktopEntry CONSTANT)
  Q_PROPERTY(QStringList SupportedUriSchemes READ schemes CONSTANT)
  Q_PROPERTY(QStringList SupportedMimeTypes READ types CONSTANT)
public:
  explicit MprisRoot(QObject *parent) : QDBusAbstractAdaptor(parent) {}
  bool yes() const { return true; }
  bool no() const { return false; }
  QString identity() const { return "SillyPlayer"; }
  QString desktopEntry() const { return "sillyplayer"; }
  QStringList schemes() const { return {"file"}; }
  QStringList types() const {
    return {"audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4",
            "audio/x-wav"};
  }
public slots:
  void Raise() { emit raiseRequested(); }
  void Quit() { QCoreApplication::quit(); }
signals:
  void raiseRequested();
};

class MprisPlayer : public QDBusAbstractAdaptor {
  Q_OBJECT
  Q_CLASSINFO("D-Bus Interface", "org.mpris.MediaPlayer2.Player")
  Q_PROPERTY(QString PlaybackStatus READ playbackStatus)
  Q_PROPERTY(QString LoopStatus READ loopStatus WRITE setLoopStatus)
  Q_PROPERTY(double Rate READ rate WRITE setRate)
  Q_PROPERTY(bool Shuffle READ shuffle WRITE setShuffle)
  Q_PROPERTY(QVariantMap Metadata READ metadata)
  Q_PROPERTY(double Volume READ volume WRITE setVolume)
  Q_PROPERTY(qlonglong Position READ position)
  Q_PROPERTY(double MinimumRate READ minimumRate CONSTANT)
  Q_PROPERTY(double MaximumRate READ maximumRate CONSTANT)
  Q_PROPERTY(bool CanGoNext READ canGo)
  Q_PROPERTY(bool CanGoPrevious READ canGo)
  Q_PROPERTY(bool CanPlay READ canPlay)
  Q_PROPERTY(bool CanPause READ canPlay)
  Q_PROPERTY(bool CanSeek READ canSeek)
  Q_PROPERTY(bool CanControl READ yes CONSTANT)
public:
  MprisPlayer(QObject *parent, App *app)
      : QDBusAbstractAdaptor(parent), a(app) {
    last = properties();
    notifications.setSingleShot(true);
    notifications.setInterval(33);
    connect(&notifications, &QTimer::timeout, this, &MprisPlayer::notify);
    const auto schedule = [this] { if (!notifications.isActive()) notifications.start(); };
    // position notifications do not change these MPRIS properties
    // clients extrapolate playback position and query Position/receive Seeked for seeks
    connect(&a->music,&Player::changed,this,[this,schedule]{
      const int state=(a->music.loaded()?1:0)|(a->music.paused()?2:0)|(a->music.seekable()?4:0);
      if(state!=transportState){transportState=state;schedule();}
    });
    connect(&a->music, &Player::volumeChanged, this, schedule);
    connect(a, &App::currentChanged, this, schedule);
    connect(a, &App::settingsChanged, this, schedule);
    connect(a, &App::queueChanged, this, schedule);
  }
  QString playbackStatus() const {
    return !a->music.loaded()  ? "Stopped"
           : a->music.paused() ? "Paused"
                               : "Playing";
  }
  QString loopStatus() const { return a->mode() == 1 ? "Track" : "Playlist"; }
  void setLoopStatus(const QString &s) {
    int desired = s == "Track" ? 1 : 0;
    while (a->mode() != desired)
      a->cycleMode();
  }
  bool shuffle() const { return a->mode() == 2; }
  void setShuffle(bool s) {
    if (s) {
      while (a->mode() != 2)
        a->cycleMode();
    } else if (a->mode() == 2)
      a->cycleMode();
  }
  double rate() const { return a->current().value("speed",1.0).toDouble(); }
  void setRate(double r) {
    a->applySpeed(r, a->settings()["nightcore"].toBool());
  }
  double volume() const { return a->music.volume() / 100; }
  void setVolume(double v) { a->music.setVolume(v * 100); }
  qlonglong position() const {
    return qlonglong(a->music.position() * 1000000);
  }
  double minimumRate() const { return 0.5; }
  double maximumRate() const { return 2; }
  bool canGo() const { return a->hasQueue(); }
  bool canPlay() const { return !a->current().isEmpty(); }
  bool canSeek() const { return a->music.seekable(); }
  bool yes() const { return true; }
  QVariantMap metadata() const {
    auto t = a->current();
    if (t.isEmpty())
      return {};
    QString id = t["id"].toString().replace('-', '_');
    return {
        {"mpris:trackid", QVariant::fromValue(QDBusObjectPath(
                              "/org/mpris/MediaPlayer2/track/" + id))},
        {"mpris:length", qlonglong(t["duration"].toDouble() * 1000000)},
        {"mpris:artUrl", t["art"]},
        {"xesam:title", t["title"]},
        {"xesam:artist", QStringList{t["artist"].toString()}},
        {"xesam:album", t["album"]},
        {"xesam:url", QUrl::fromLocalFile(t["path"].toString()).toString()}};
  }
public slots:
  void Next() { a->next(); }
  void Previous() { a->previous(); }
  void Pause() { a->music.pauseWithFade(); }
  void PlayPause() { a->toggle(); }
  void Stop() {
    a->music.pausePlayback();
    a->music.seek(0);
  }
  void Play() {
    if (a->music.loaded())
      a->music.play();
    else
      a->toggle();
  }
  void Seek(qlonglong offset) {
    a->music.seek(a->music.position() + double(offset) / 1000000);
    emit Seeked(position());
  }
  void SetPosition(const QDBusObjectPath &id, qlonglong pos) {
    if (metadata().value("mpris:trackid").value<QDBusObjectPath>().path() ==
        id.path()) {
      a->music.seek(double(pos) / 1000000);
      emit Seeked(pos);
    }
  }
  void OpenUri(const QString &uri) {
    QString path = QUrl(uri).toLocalFile();
    auto tracks = a->library.tracks();
    for (auto v : tracks) {
      auto t = v.toMap();
      if (t["path"] == path) {
        a->playTrack(t["id"].toString(), tracks, "Songs");
        return;
      }
    }
    emit a->error(
        "Import the file's folder before opening it through media controls");
  }
signals:
  void Seeked(qlonglong position);

private:
  App *a;
  QVariantMap last;
  QTimer notifications;
  int transportState=-1;
  QVariantMap properties() const {
    return QVariantMap{{"PlaybackStatus", playbackStatus()},
                      {"Metadata", metadata()},
                      {"Volume", volume()},
                      {"Rate", rate()},
                      {"Shuffle", shuffle()},
                      {"LoopStatus", loopStatus()}};
  }
  void notify() {
    const auto props = properties();
    QVariantMap changed;
    for (auto it = props.cbegin(); it != props.cend(); ++it)
      if (last.value(it.key()) != it.value()) changed.insert(it.key(), it.value());
    if (changed.isEmpty()) return;
    last = props;
    auto msg = QDBusMessage::createSignal("/org/mpris/MediaPlayer2",
                                          "org.freedesktop.DBus.Properties",
                                          "PropertiesChanged");
    msg << "org.mpris.MediaPlayer2.Player" << changed << QStringList{};
    QDBusConnection::sessionBus().send(msg);
  }
};
