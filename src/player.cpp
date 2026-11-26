#include "player.h"
#include <QByteArray>
#include <QRegularExpression>
#include <algorithm>
#include <cmath>
#include <stdexcept>

Player::Player(bool video, QObject *parent) : QObject(parent) {
  m = mpv_create();
  if (!m)
    throw std::runtime_error("Cannot create mpv player");
  for (auto pair :
       {qMakePair("config", "no"), qMakePair("terminal", "no"),
        qMakePair("input-default-bindings", "no"),
        qMakePair("input-vo-keyboard", "no"), qMakePair("ytdl", "no"),
        qMakePair("idle", "yes"), qMakePair("keep-open", "no"),
        qMakePair("audio-display", "no"), qMakePair("replaygain", "no"),
        qMakePair("volume-max", "300")})
    mpv_set_option_string(m, pair.first, pair.second);
  mpv_set_option_string(m, "vo", video ? "libmpv" : "null");
  mpv_set_option_string(m, "vid", video ? "auto" : "no");
  if(!video) {
    mpv_set_option_string(m,"demuxer-max-bytes","16MiB");
    mpv_set_option_string(m,"demuxer-max-back-bytes","4MiB");
    mpv_set_option_string(m,"cache-secs","30");
  }
  if (qEnvironmentVariableIsSet("SILLYPLAYER_TEST_AUDIO"))
    mpv_set_option_string(m, "ao", "null");
  int rc = mpv_initialize(m);
  if (rc < 0) {
    mpv_terminate_destroy(m);
    m = nullptr;
    throw std::runtime_error(mpv_error_string(rc));
  }
  for (auto p : {"time-pos", "duration"})
    mpv_observe_property(m, 0, p, MPV_FORMAT_DOUBLE);
  for (auto p : {"pause", "seekable", "paused-for-cache"})
    mpv_observe_property(m, 0, p, MPV_FORMAT_FLAG);
  mpv_request_log_messages(m, "error");
  volumeTimer.setSingleShot(true);
  volumeTimer.setTimerType(Qt::PreciseTimer);
  volumeTimer.setInterval(16);
  connect(&volumeTimer, &QTimer::timeout, this, &Player::flushVolume);
  speedTimer.setSingleShot(true);
  speedTimer.setInterval(64);
  connect(&speedTimer, &QTimer::timeout, this, &Player::flushSpeed);
  writeVolume();
  // mpv wakes the Qt thread for events. The timer is only a seek-timeout fallback
  timer.setInterval(1000);
  connect(&timer, &QTimer::timeout, this, &Player::poll);
  timer.start();
  fadeTimer.setInterval(16);
  connect(&fadeTimer, &QTimer::timeout, this, [this] {
    if (fadeClock.elapsed() >= fadeMilliseconds) { pausePlayback(); return; }
    fadeGain = std::max(0.0, 1.0 - double(fadeClock.elapsed()) / fadeMilliseconds);
    writeVolume();
  });
  transitionTimer.setInterval(24);
  connect(&transitionTimer, &QTimer::timeout, this, [this] {
    if (!fadingIn || transitionMs <= 0) { resetTransitionFade(); return; }
    const int halfFade = std::clamp(transitionMs / 2, 250, 4000);
    const double elapsed = double(transitionClock.elapsed());
    transitionGain = std::clamp(elapsed / halfFade, 0.0, 1.0);
    writeVolume();
    if (elapsed >= halfFade) {
      fadingIn = false;
      transitionGain = 1.0;
      transitionTimer.stop();
      writeVolume();
    }
  });
  // wakeups only post work to the owning Qt thread
  // coalesce callbacks while keeping acknowledgements responsive enough for smooth 16 ms fades
  mpv_set_wakeup_callback(m, [](void *context) {
    auto self = static_cast<Player *>(context);
    if (!self->wakePending.exchange(true))
      QMetaObject::invokeMethod(self, [self] {
        self->wakePending = false;
        self->poll();
      }, Qt::QueuedConnection);
  }, this);
}
Player::~Player() { shutdown(); }
void Player::shutdown() {
  fadeTimer.stop();
  transitionTimer.stop();
  volumeTimer.stop();
  timer.stop();
  if (m) {
    mpv_set_wakeup_callback(m, nullptr, nullptr);
    mpv_terminate_destroy(m);
    m = nullptr;
  }
}
bool Player::command(const QStringList &args) {
  if (!m)
    return false;
  QList<QByteArray> bytes;
  for (auto &a : args)
    bytes << a.toUtf8();
  QList<const char *> pointers;
  for (auto &a : bytes)
    pointers << a.constData();
  pointers << nullptr;
  int rc = mpv_command(m, pointers.data());
  if (rc < 0) {
    err = QString::fromUtf8(mpv_error_string(rc));
    emit changed();
    return false;
  }
  return true;
}
void Player::load(const QString &path, const QString &audio,
                  const QVariantMap &headers, double resume, bool paused, bool fadeIn) {
  if (!m)
    return;
  ++mediaGeneration;
  sourcePath=path;
  resetFade();
  if (fadeIn && transitionMs > 0 && !paused) {
    transitionTimer.stop();
    fadingIn = false;
    pendingFadeIn = true;
    transitionGain = 0.0;
    writeVolume();
  } else {
    resetTransitionFade();
  }
  pendingSeek = false;
  if (!paused) emit wantsPlay();
  err.clear();
  backendError.clear();
  resumeAt = resume;
  resumePaused = paused;
  startAfterSpeed = false;
  heldPosition = paused ? resume : -1;
  resumeFloor = -1;
  pos = dur = 0;
  canSeek = false;
  hasFile = false;
  stalled = false;
  pause = true;
  emit changed();
  command({"set", "pause", "yes"});
  // a new file must never overtake debounced speed or volume writes; flush both before loadfile so the first frames match the new track
  speedTimer.stop();
  flushSpeed();
  volumeTimer.stop();
  flushVolume();
  QList<QByteArray> h;
  for (auto i = headers.begin(); i != headers.end(); ++i)
    h << (i.key() + ": " + i.value().toString()).toUtf8();
  QVector<mpv_node> headerValues(h.size());
  for (int i = 0; i < h.size(); ++i) {
    headerValues[i].format = MPV_FORMAT_STRING;
    headerValues[i].u.string = h[i].data();
  }
  mpv_node_list headerList{int(h.size()), headerValues.data(), nullptr};
  mpv_node headerNode{};
  headerNode.format = MPV_FORMAT_NODE_ARRAY;
  headerNode.u.list = &headerList;
  mpv_set_property(m, "http-header-fields", MPV_FORMAT_NODE, &headerNode);
  // a node map avoids comma escaping in signed stream URLs and works across loadfile API revisions
  QByteArray file = path.toUtf8(), audioBytes = audio.toUtf8();
  mpv_node values[4]{};
  char *keys[] = {const_cast<char *>("name"), const_cast<char *>("url"),
                  const_cast<char *>("flags"), const_cast<char *>("options")};
  values[0].format = MPV_FORMAT_STRING;
  values[0].u.string = const_cast<char *>("loadfile");
  values[1].format = MPV_FORMAT_STRING;
  values[1].u.string = file.data();
  values[2].format = MPV_FORMAT_STRING;
  values[2].u.string = const_cast<char *>("replace");
  mpv_node opts[1]{};
  opts[0].format = MPV_FORMAT_STRING;
  opts[0].u.string = audioBytes.data();
  char *optKeys[] = {const_cast<char *>("audio-file")};
  mpv_node_list optList{audio.isEmpty() ? 0 : 1, opts, optKeys};
  values[3].format = MPV_FORMAT_NODE_MAP;
  values[3].u.list = &optList;
  mpv_node_list list{4, values, keys};
  mpv_node node{};
  node.format = MPV_FORMAT_NODE_MAP;
  node.u.list = &list;
  int rc = mpv_command_node(m, &node, nullptr);
  if (rc < 0) {
    err = QString::fromUtf8(mpv_error_string(rc));
    emit changed();
  }
}
void Player::play() {
  if (!hasFile)
    return;
  resetFade();
  // audio output latency can briefly produce timestamps older than the paused/seek position on resume
  // hold that anchor until real PTS catches up
  if (resumePaused) resumeFloor = position();
  resumePaused = false;
  heldPosition = -1;
  emit wantsPlay();
  if(speedInFlight || filtersInFlight || speedTimer.isActive()) {
    startAfterSpeed=true;
    speedTimer.stop();flushSpeed();return;
  }
  if (command({"set", "pause", "no"})) { pause = false; emit changed(); }
}
void Player::pausePlayback() {
  heldPosition = position();
  resumePaused = true;
  if (command({"set", "pause", "yes"})) pause = true;
  resetFade();
  resetTransitionFade();
}
void Player::setPauseFade(bool enabled, int milliseconds) {
  fadeEnabled = enabled;
  fadeMilliseconds = std::clamp(milliseconds, 50, 5000);
  if (pausing()) pausePlayback();
}
void Player::setCrossfadeMs(int milliseconds) {
  transitionMs = std::clamp(milliseconds, 0, 8000);
  if (transitionMs == 0) resetTransitionFade();
}
void Player::pauseWithFade() {
  if (!fadeEnabled || !hasFile || pause || effectiveVolume() <= 0) { pausePlayback(); return; }
  if (pausing()) return;
  fadeGain = 1;
  fadeClock.restart();
  fadeTimer.start();
  emit changed();
}
void Player::writeVolume() {
  if (!m || volumeInFlight || volumeTimer.isActive()) return;
  const double output = effectiveVolume() * fadeGain * transitionGain;
  if (volumeApplied && std::abs(output - appliedVolume) < 0.000001) return;
  volumeTimer.start();
}
void Player::flushVolume() {
  if (!m || volumeInFlight) return;
  sentVolume = effectiveVolume() * fadeGain * transitionGain;
  if (volumeApplied && std::abs(sentVolume - appliedVolume) < 0.000001) return;
  // one request in flight: newer user volume/fade gain replaces pending work, never queues stale gain writes behind a resume or mute operation
  volumeInFlight = true;
  const int rc = mpv_set_property_async(m, volumeRequest, "volume", MPV_FORMAT_DOUBLE, &sentVolume);
  if (rc < 0) {
    volumeInFlight = volumeApplied = false;
    err = QString::fromUtf8(mpv_error_string(rc));
    emit changed();
  }
}
void Player::resetFade() {
  const bool active = pausing();
  fadeTimer.stop();
  if (fadeGain != 1) { fadeGain = 1; writeVolume(); }
  if (active) emit changed();
}
void Player::resetTransitionFade() {
  transitionTimer.stop();
  pendingFadeIn = false;
  fadingIn = false;
  if (transitionGain != 1.0) { transitionGain = 1.0; writeVolume(); }
}
void Player::startTransitionFadeIn() {
  if (transitionMs <= 0) { resetTransitionFade(); return; }
  pendingFadeIn = false;
  fadingIn = true;
  transitionGain = 0.0;
  transitionClock.restart();
  transitionTimer.start();
  writeVolume();
}
void Player::updateTransitionFadeOut() {
  if (transitionMs <= 0 || !hasFile || pause || pausing()) {
    if (!fadingIn && !pendingFadeIn && transitionGain != 1.0) resetTransitionFade();
    return;
  }
  if (dur * 1000.0 <= transitionMs) return;
  const double halfFadeSec = std::clamp(transitionMs / 2000.0, 0.25, 4.0);
  const double remaining = dur - pos;
  if (remaining > 0.0 && remaining <= halfFadeSec) {
    transitionTimer.stop();
    fadingIn = false;
    pendingFadeIn = false;
    transitionGain = std::clamp(remaining / halfFadeSec, 0.0, 1.0);
    writeVolume();
  } else if (!fadingIn && !pendingFadeIn && transitionGain != 1.0) {
    transitionGain = 1.0;
    writeVolume();
  }
}
void Player::toggle() {
  if (pause || pausing())
    play();
  else
    pauseWithFade();
}
void Player::seek(double seconds) {
  if (!m || !canSeek || dur <= 0 || !std::isfinite(seconds)) return;
  resetTransitionFade();
  seekTarget = std::clamp(seconds, 0.0, dur);
  resumeFloor = -1;
  const auto target = QString::number(seekTarget, 'f', 6).toUtf8();
  const char *args[] = {"seek", target.constData(), "absolute+exact", nullptr};
  const int rc = mpv_command_async(m, ++seekSerial, args);
  if (rc < 0) err = mpv_error_string(rc);
  else { pendingSeek = true; seekStarted = false; if (resumePaused) heldPosition = seekTarget; seekClock.restart(); }
  emit changed();
}
void Player::setVolume(double volume) {
  if (!std::isfinite(volume)) return;
  volume = std::clamp(volume, 0.0, 100.0);
  if (std::abs(vol - volume) < 0.000001) return;
  vol = volume;
  writeVolume();
  emit volumeChanged();
  emit effectiveVolumeChanged();
}
double Player::effectiveVolume() const {
  // let per-song volume boost past 100 using the 0-100 main slider as the baseline like mobile does
  return std::clamp(vol * songVolume / 100.0, 0.0, 300.0);
}
void Player::setTrackVolume(double percent) {
  if(!std::isfinite(percent)) return;
  percent=std::clamp(percent,0.0,300.0);
  if(std::abs(songVolume-percent)<0.000001) return;
  songVolume=percent;
  writeVolume();
  emit trackVolumeChanged();
  emit effectiveVolumeChanged();
}
void Player::updateFilters() {
  // the label change forces mpv 0.37 to rebuild speed filters when only the pitch-correction option changes
  // keep the equalizer in the same chain
  QString filters = QString("@pitch_%1:lavfi=[anull]").arg(pitchFollows ? "follow" : "preserve");
  if (!eqFilter.isEmpty()) filters += "," + eqFilter;
  const auto bytes = filters.toUtf8();
  const char *value = bytes.constData();
  const int rc = mpv_set_property_async(m, filtersRequest, "af", MPV_FORMAT_STRING, &value);
  if(rc < 0) { err=mpv_error_string(rc); emit changed(); }
  else ++filtersInFlight;
}
void Player::setSpeed(double speed, bool nightcore) {
  if (!m || !std::isfinite(speed)) return;
  speed = std::clamp(speed, 0.5, 2.0);
  if (std::abs(playbackSpeed-speed)<0.0001 && pitchFollows==nightcore) return;
  playbackSpeed = speed;
  pitchFollows = nightcore;
  if(!speedInFlight && !speedTimer.isActive()) speedTimer.start();
}
void Player::flushSpeed() {
  if(!m || speedInFlight) return;
  const bool pitchChanged = sentPitch != pitchFollows;
  sentPitch = pitchFollows;
  sentSpeed = playbackSpeed;
  int preserve = !sentPitch;
  auto submit = [this](const char *name, mpv_format format, void *value) {
    const int rc=mpv_set_property_async(m,speedRequest,name,format,value);
    if(rc>=0) ++speedInFlight;
    else {err=mpv_error_string(rc);emit changed();}
  };
  if(pitchChanged) submit("audio-pitch-correction",MPV_FORMAT_FLAG,&preserve);
  submit("speed",MPV_FORMAT_DOUBLE,&sentSpeed);
  if(pitchChanged) updateFilters();
}
QVariantMap Player::bufferStats() const {
  QVariantMap result; if(!m) return result;
  for(const auto &name: {"demuxer-max-bytes","demuxer-max-back-bytes"}) {
    int64_t bytes=0;const auto property=QByteArray("options/")+name;
    if(mpv_get_property(m,property.constData(),MPV_FORMAT_INT64,&bytes)>=0) result[name]=qlonglong(bytes);
  }
  double seconds=0;if(mpv_get_property(m,"options/cache-secs",MPV_FORMAT_DOUBLE,&seconds)>=0) result["cache-secs"]=seconds;
  return result;
}
void Player::setEq(bool enabled, const QVariantList &gains) {
  eqFilter.clear();
  if (enabled) {
    QStringList filters;
    double peak = 0;
    int freqs[] = {31,62,125,250,500,1000,2000,4000,8000,16000};
    for (int i = 0; i < 10; ++i) {
      double g = std::clamp(gains.value(i).toDouble(), -12.0, 12.0);
      peak = std::max(peak, g);
      filters << QString("equalizer=f=%1:t=o:w=1:g=%2").arg(freqs[i]).arg(g);
    }
    filters.prepend(QString("volume=%1dB").arg(-peak));
    eqFilter = "lavfi=[" + filters.join(',') + "]";
  }
  updateFilters();
}
void Player::stop() {
  pausePlayback();
  startAfterSpeed=false;
  ++mediaGeneration;
  pendingSeek = false;
  command({"stop"});
  hasFile = false;
  pos = dur = resumeAt = 0;
  heldPosition = resumeFloor = -1;
  canSeek = stalled = false;
  pause = true;
  resumePaused = true;
  err.clear();
  backendError.clear();
  emit changed();
}
void Player::poll() {
  if (!m)
    return;
  bool dirty = false;
  while (auto e = mpv_wait_event(m, 0)) {
    if (e->event_id == MPV_EVENT_NONE)
      break;
    if (e->event_id == MPV_EVENT_SET_PROPERTY_REPLY && e->reply_userdata == speedRequest) {
      if(speedInFlight) --speedInFlight;
      if(e->error<0) {err=mpv_error_string(e->error);dirty=true;}
      if(!speedInFlight) {
        if(sentPitch!=pitchFollows || std::abs(sentSpeed-playbackSpeed)>=0.0001) flushSpeed();
        else if(!startAfterSpeed && hasFile && resumePaused && canSeek) seek(position());
      }
    } else if (e->event_id == MPV_EVENT_SET_PROPERTY_REPLY && e->reply_userdata == filtersRequest) {
      if(filtersInFlight) --filtersInFlight;
      if(e->error<0) {err=mpv_error_string(e->error);dirty=true;}
    } else if (e->event_id == MPV_EVENT_SET_PROPERTY_REPLY && e->reply_userdata == volumeRequest) {
      volumeInFlight = false;
      volumeApplied = e->error >= 0;
      if (volumeApplied) {
        appliedVolume = sentVolume;
        writeVolume();
      } else {
        err = QString::fromUtf8(mpv_error_string(e->error));
        dirty = true;
      }
    } else if (e->event_id == MPV_EVENT_COMMAND_REPLY && e->reply_userdata == seekSerial && e->error < 0) {
      pendingSeek = false; heldPosition = -1; err = mpv_error_string(e->error); dirty = true;
    } else if (e->event_id == MPV_EVENT_SEEK) {
      if (pendingSeek) seekStarted = true;
    } else if (e->event_id == MPV_EVENT_PLAYBACK_RESTART) {
      if (pendingSeek && seekStarted) {
        mpv_get_property(m, "time-pos", MPV_FORMAT_DOUBLE, &pos);
        if (std::abs(pos - seekTarget) < 0.75) {
          pendingSeek = false;
          if (!resumePaused && pos < seekTarget) resumeFloor = seekTarget;
        }
        dirty = true;
      }
    } else if (e->event_id == MPV_EVENT_LOG_MESSAGE) {
      const auto message = static_cast<mpv_event_log_message *>(e->data);
      auto text = QString::fromUtf8(message->text).trimmed();
      // keep useful backend errors without exposing signed URLs in the UI
      text.replace(QRegularExpression("https?://[^\\s\\\"'<>]+"), "media stream");
      if (!text.isEmpty()) backendError = (backendError + " " + text).trimmed().right(800);
    } else if (e->event_id == MPV_EVENT_PROPERTY_CHANGE) {
      auto p = static_cast<mpv_event_property *>(e->data);
      if (!p->data)
        continue;
      QString n = p->name;
      if (p->format == MPV_FORMAT_DOUBLE) {
        double v = *static_cast<double *>(p->data);
        if (n == "time-pos" && hasFile) {
          pos = v;
          if (!pause && pos >= resumeFloor) resumeFloor = -1;
          updateTransitionFadeOut();
        } else if (n == "duration" && hasFile)
          dur = v;
      }
      if (p->format == MPV_FORMAT_FLAG) {
        bool v = *static_cast<int *>(p->data);
        if (n == "pause")
          pause = v;
        else if (n == "seekable")
          canSeek = v;
        else if (n == "paused-for-cache")
          stalled = v;
      }
      dirty = true;
    } else if (e->event_id == MPV_EVENT_FILE_LOADED) {
      hasFile = true;
      mpv_get_property(m, "duration", MPV_FORMAT_DOUBLE, &dur);
      int seekable = 0;
      mpv_get_property(m, "seekable", MPV_FORMAT_FLAG, &seekable);
      canSeek = seekable;
      if (resumeAt > 0)
        command({"seek", QString::number(resumeAt), "absolute+exact"});
      if(speedInFlight || filtersInFlight || volumeInFlight || speedTimer.isActive() || volumeTimer.isActive() || sentPitch!=pitchFollows || std::abs(sentSpeed-playbackSpeed)>=0.0001) {
        startAfterSpeed=true;
        speedTimer.stop();
        flushSpeed();
        volumeTimer.stop();
        flushVolume();
      } else {
        command({"set", "pause", resumePaused ? "yes" : "no"});
        if (!resumePaused && pendingFadeIn) startTransitionFadeIn();
      }
      emit loadedFile();
      dirty = true;
    } else if (e->event_id == MPV_EVENT_END_FILE) {
      resetFade();
      resetTransitionFade();
      auto end = static_cast<mpv_event_end_file *>(e->data);
      const bool hadStarted = hasFile && pos > 0.05;
      pendingSeek = false;
      resumeFloor = -1;
      hasFile = false;
      pause = true;
      dirty = true;
      if (end->reason == MPV_END_FILE_REASON_ERROR) {
        err = mpv_error_string(end->error);
        if (!backendError.isEmpty()) err += ": " + backendError;
        emit failed(hadStarted);
      } else if (end->reason == MPV_END_FILE_REASON_EOF)
        emit ended();
    }
  }
  if(hasFile && startAfterSpeed && !speedInFlight && !filtersInFlight && !volumeInFlight && !speedTimer.isActive() && !volumeTimer.isActive() && sentPitch==pitchFollows && std::abs(sentSpeed-playbackSpeed)<0.0001) {
    startAfterSpeed=false;
    command({"set","pause",resumePaused?"yes":"no"});
    if (!resumePaused && pendingFadeIn) startTransitionFadeIn();
  }
  if (pendingSeek && seekClock.elapsed() > 5000) {
    pendingSeek = false; heldPosition = -1; err = "Seek timed out"; dirty = true;
  }
  if (dirty)
    emit changed();
}
