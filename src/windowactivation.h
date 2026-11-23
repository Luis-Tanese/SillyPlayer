#pragma once
#include <QGuiApplication>
#include <QWindow>
#ifdef SILLYPLAYER_X11
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#endif

// called only for explicit user requests (launcher, tray, MPRIS Raise) Qt's requestActivate alone uses this process's old user timestamp on X11, so Cinnamon can mark the window urgent instead of selecting it
inline void activatePlayerWindow(QWindow *window) {
  const auto states=window->windowStates();
  if (states.testFlag(Qt::WindowFullScreen)) window->showFullScreen();
  else if (states.testFlag(Qt::WindowMaximized)) window->showMaximized();
  else window->showNormal();
  window->raise();
  window->requestActivate();
#ifdef SILLYPLAYER_X11
  if (QGuiApplication::platformName()!=QStringLiteral("xcb")) return;
  auto display=XOpenDisplay(nullptr);
  if (!display) return;
  const auto root=DefaultRootWindow(display);
  // obtain a current server timestamp without altering Qt's event stream or relying on the desktop's uptime/clock relationship
  const auto stampWindow=XCreateSimpleWindow(display,root,0,0,1,1,0,0,0);
  const auto stampAtom=XInternAtom(display,"_SILLYPLAYER_ACTIVATION_TIME",False);
  XSelectInput(display,stampWindow,PropertyChangeMask);
  const unsigned char value=0;
  XChangeProperty(display,stampWindow,stampAtom,XA_INTEGER,8,PropModeReplace,&value,1);
  XEvent timestamp{};
  XWindowEvent(display,stampWindow,PropertyChangeMask,&timestamp);
  const auto target=static_cast<Window>(window->winId());
  XMapRaised(display,target);
  XEvent event{};
  event.xclient.type=ClientMessage;
  event.xclient.display=display;
  event.xclient.window=target;
  event.xclient.format=32;
  event.xclient.message_type=XInternAtom(display,"_NET_ACTIVE_WINDOW",False);
  event.xclient.data.l[0]=2; // acting on a user's launcher/tray selection
  event.xclient.data.l[1]=timestamp.xproperty.time;
  XSendEvent(display,root,False,SubstructureRedirectMask|SubstructureNotifyMask,&event);
  XDestroyWindow(display,stampWindow);
  XFlush(display);
  XCloseDisplay(display);
#endif
}
