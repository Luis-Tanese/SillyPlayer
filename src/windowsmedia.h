#pragma once
#ifdef Q_OS_WIN
#include "app.h"
#include <QAbstractNativeEventFilter>
#include <windows.h>
#include <windowsx.h>

struct WindowsInstallLock {
  HANDLE handle = CreateMutexW(nullptr, FALSE, L"Local\\SillyPlayer.ProgramRunning");
  ~WindowsInstallLock() { if (handle) CloseHandle(handle); }
};

class WindowsMediaKeys : public QAbstractNativeEventFilter {
  App *app;
public:
  explicit WindowsMediaKeys(App *value) : app(value) {}
  bool nativeEventFilter(const QByteArray &, void *message, qintptr *result) override {
    const auto event = static_cast<MSG *>(message);
    if (event->message != WM_APPCOMMAND) return false;
    switch (GET_APPCOMMAND_LPARAM(event->lParam)) {
      case APPCOMMAND_MEDIA_PLAY_PAUSE: app->toggle(); break;
      case APPCOMMAND_MEDIA_NEXTTRACK: app->next(); break;
      case APPCOMMAND_MEDIA_PREVIOUSTRACK: app->previous(); break;
      case APPCOMMAND_MEDIA_PAUSE: app->music.pauseWithFade(); break;
      case APPCOMMAND_MEDIA_PLAY: if(app->music.loaded()) app->music.play(); else app->toggle(); break;
      case APPCOMMAND_MEDIA_STOP: app->music.pausePlayback(); app->music.seek(0); break;
      default: return false;
    }
    *result = 1;
    return true;
  }
};
#endif
