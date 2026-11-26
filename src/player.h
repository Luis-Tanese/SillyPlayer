#pragma once
#include <QObject>
#include <QTimer>
#include <QElapsedTimer>
#include <QVariantMap>
#include <mpv/client.h>
#include <atomic>

class Player : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool seekPending READ seekPending NOTIFY changed)
  Q_PROPERTY(quint64 generation READ generation NOTIFY changed)
  Q_PROPERTY(double position READ position NOTIFY positionChanged)
  Q_PROPERTY(double duration READ duration NOTIFY changed)
  Q_PROPERTY(bool paused READ paused NOTIFY changed)
  Q_PROPERTY(bool pausing READ pausing NOTIFY changed)
  Q_PROPERTY(bool seekable READ seekable NOTIFY changed)
  Q_PROPERTY(bool buffering READ buffering NOTIFY changed)
  Q_PROPERTY(double volume READ volume WRITE setVolume NOTIFY volumeChanged)
  Q_PROPERTY(double trackVolume READ trackVolume NOTIFY trackVolumeChanged)
  Q_PROPERTY(double effectiveVolume READ effectiveVolume NOTIFY effectiveVolumeChanged)
  Q_PROPERTY(QString error READ error NOTIFY changed)
  Q_PROPERTY(bool loaded READ loaded NOTIFY changed)
public:
  explicit Player(bool video = false, QObject *parent = nullptr);
  ~Player();
  mpv_handle *handle() const { return m; }
  double position() const { return pendingSeek ? seekTarget : (resumePaused && heldPosition >= 0 ? heldPosition : (pos < resumeFloor ? resumeFloor : pos)); }
  bool seekPending() const { return pendingSeek; }
  quint64 generation() const { return mediaGeneration; }
  double playbackRate() const { return playbackSpeed; }
  double transportPosition() const { return pos; }
  quint64 seekGeneration() const { return seekSerial; }
  QString mediaPath() const { return sourcePath; }
  double duration() const { return dur; }
  bool requestedPause() const { return resumePaused; }
  bool paused() const { return !hasFile || pause; }
  bool pausing() const { return fadeTimer.isActive(); }
  bool seekable() const { return canSeek; }
  bool buffering() const { return stalled; }
  double volume() const { return vol; }
  double trackVolume() const { return songVolume; }
  double effectiveVolume() const;
  QString error() const { return err; }
  bool loaded() const { return hasFile; }
  void load(const QString &path, const QString &audio = {},
            const QVariantMap &headers = {}, double resume = 0,
            bool paused = false, bool fadeIn = false);
  Q_INVOKABLE void play();
  Q_INVOKABLE void pausePlayback();
  Q_INVOKABLE void pauseWithFade();
  Q_INVOKABLE void toggle();
  Q_INVOKABLE void seek(double seconds);
  Q_INVOKABLE void setVolume(double volume);
  void setTrackVolume(double percent);
  void setSpeed(double speed, bool nightcore);
  void setPauseFade(bool enabled, int milliseconds);
  void setCrossfadeMs(int milliseconds);
  int crossfadeMs() const { return transitionMs; }
  double transitionGainValue() const { return transitionGain; }
  void setEq(bool enabled, const QVariantList &gains);
  QVariantMap bufferStats() const;
  void stop();
  void shutdown();
signals:
  void changed();
  void positionChanged();
  void volumeChanged();
  void trackVolumeChanged();
  void effectiveVolumeChanged();
  void ended();
  void wantsPlay();
  void loadedFile();
  void failed(bool hadStarted);

private:
  mpv_handle *m = nullptr;
  QTimer timer;
  QTimer fadeTimer, transitionTimer, volumeTimer, speedTimer;
  int speedInFlight = 0, filtersInFlight = 0;
  bool startAfterSpeed = false;
  double sentSpeed = 1;
  bool sentPitch = false;
  static constexpr quint64 speedRequest = (quint64(1) << 63) | 2;
  static constexpr quint64 filtersRequest = (quint64(1) << 63) | 3;
  void flushSpeed();
  std::atomic_bool wakePending{false};
  bool volumeInFlight = false, volumeApplied = false;
  double sentVolume = 0, appliedVolume = 0;
  static constexpr quint64 volumeRequest = quint64(1) << 63;
  QElapsedTimer fadeClock, transitionClock;
  bool fadeEnabled = false;
  int fadeMilliseconds = 300;
  int transitionMs = 0;
  bool pendingFadeIn = false, fadingIn = false;
  double fadeGain = 1, transitionGain = 1, playbackSpeed = 1, songVolume = 100;
  double pos = 0, dur = 0, vol = 70, resumeAt = 0;
  bool pause = true, canSeek = false, stalled = false, hasFile = false,
       resumePaused = false;
  QString err, backendError, eqFilter, sourcePath;
  bool pitchFollows = false, pendingSeek = false, seekStarted = false;
  double seekTarget = 0, heldPosition = -1, resumeFloor = -1;
  quint64 mediaGeneration = 0, seekSerial = 0;
  QElapsedTimer seekClock, positionClock;
  double lastEmittedPos = -1.0;
  void updateFilters();
  void resetFade();
  void resetTransitionFade();
  void startTransitionFadeIn();
  void updateTransitionFadeOut();
  void writeVolume();
  void flushVolume();
  void poll();
  bool command(const QStringList &args);
};
