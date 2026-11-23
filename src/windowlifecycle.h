#pragma once
#include <QEvent>
#include <QPointer>
#include <QQuickWindow>
#include <QTimer>

// release scene nodes on hide, retaining the graphics device
// destroying the device too lets Qt 6.4 reuse cached image textures from the old device, producing black covers on some drivers
// playback lives separately
class WindowRenderLifecycle : public QObject {
public:
  explicit WindowRenderLifecycle(QQuickWindow *window) : QObject(window), window(window) {
    window->setPersistentGraphics(true);
    window->setPersistentSceneGraph(false);
    window->installEventFilter(this);
  }
protected:
  bool eventFilter(QObject *watched, QEvent *event) override {
    if (watched == window && (event->type() == QEvent::Expose ||
        event->type() == QEvent::WindowStateChange || event->type() == QEvent::Hide ||
        event->type() == QEvent::Show || event->type() == QEvent::PlatformSurface)) {
      if (!queued) {
        queued = true;
        QTimer::singleShot(0, this, [this] {
          queued = false;
          if (!window) return;
          const bool hidden = window->visibility() == QWindow::Minimized || !window->isVisible();
          if (hidden && !released) {
            released = true;
            window->releaseResources();
          } else if (!hidden && window->isExposed()) {
            released = false;
            window->update();
          }
        });
      }
    }
    return QObject::eventFilter(watched, event);
  }
private:
  QPointer<QQuickWindow> window;
  bool queued = false, released = false;
};
