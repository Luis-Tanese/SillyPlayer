#include "app.h"
#include "artworknetwork.h"
#ifdef Q_OS_LINUX
#include "mpris.h"
#endif
#include "stablemodel.h"
#include "windowsmedia.h"
#include <QApplication>
#include <QCommandLineParser>
#include <QCryptographicHash>
#include <QDir>
#include <QElapsedTimer>
#include <QLocalServer>
#include <QLocalSocket>
#include <QLockFile>
#include <QMenu>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QStandardPaths>
#include <QSystemTrayIcon>
#include <QTimer>
#include <clocale>
#include <cstdio>
#include "windowlifecycle.h"
#include "windowactivation.h"

int main(int argc, char **argv) {
  QElapsedTimer startup;
  startup.start();
#ifdef Q_OS_WIN
  if (qEnvironmentVariableIsSet("SILLYPLAYER_TEST_SOFTWARE") ||
      qEnvironmentVariable("QT_QUICK_BACKEND") == "software")
    QQuickWindow::setGraphicsApi(QSGRendererInterface::Software);
#else
  QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);
#endif
#ifdef Q_OS_LINUX
  if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM") &&
      (!qEnvironmentVariableIsEmpty("WAYLAND_DISPLAY") || qEnvironmentVariable("XDG_SESSION_TYPE") == "wayland"))
    qputenv("QT_QPA_PLATFORM", "wayland;xcb");
#endif
#ifdef Q_OS_WIN
  if (qEnvironmentVariableIsSet("SILLYPLAYER_TEST_LOG")) {
    qInstallMessageHandler([](QtMsgType, const QMessageLogContext &, const QString &message) {
      const auto bytes = message.toUtf8();
      std::fprintf(stderr, "%s\n", bytes.constData());
      std::fflush(stderr);
    });
    qInfo("Startup: creating QApplication");
  }
#endif
  QApplication application(argc, argv);
#ifdef Q_OS_WIN
  if (qEnvironmentVariableIsSet("SILLYPLAYER_TEST_LOG")) qInfo("Startup: QApplication ready");
#endif
  // QApplication adopts the user's locale, libmpv requires C numeric parsing so reset this category before starting either player or any worker threads
  std::setlocale(LC_NUMERIC, "C");
  application.setQuitOnLastWindowClosed(false);
  application.setApplicationName("SillyPlayer");
  application.setDesktopFileName("sillyplayer");
  application.setOrganizationName("SillyPlayer");
  application.setApplicationVersion(SILLYPLAYER_VERSION);
  // explicit raster sizes also work with GTK platform icon engines that do not expose sizes for resource-backed filename icons
  QIcon appIcon;
  const QImage logo(":/assets/sillyplayer.png");
  for (const int size : {32,48,64,128,256,512})
    appIcon.addPixmap(QPixmap::fromImage(logo.scaled(size,size,Qt::KeepAspectRatio,Qt::SmoothTransformation)));
  application.setWindowIcon(appIcon);
  QCommandLineParser parser;
  parser.setApplicationDescription(
      "Local-first desktop music player and media browser");
  parser.addHelpOption();
  parser.addVersionOption();
  parser.addOptions(
      {{"portable", "Store user data beside the executable"},
       {"benchmark", "Print elapsed time to the first rendered frame"},
       {"data-dir", "Override user data location (testing/portable use)",
        "path"},
       {"quit-after", "Exit after N milliseconds (smoke testing)",
        "milliseconds"},
       {"screenshot", "Save window screenshot before timed exit", "path"},
       {"import-folder", "Register a music folder", "path"},
       {"test-play", "Play a local test file without adding it to the library",
        "path"},
       {"test-video", "Play local test media in the YouTube audio player",
        "path"},
       {"youtube", "Open a YouTube URL in the integrated player", "url"}});
  parser.process(application);
  QString data = parser.value("data-dir");
  if (data.isEmpty())
    data =
        parser.isSet("portable")
            ? application.applicationDirPath() + "/data"
            : QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
  if (!QDir().mkpath(data) || !QFileInfo(data).isWritable()) {
    fprintf(stderr, "Cannot write application data directory\n");
    return 1;
  }
  QString cache = data + "/cache";
  QDir().mkpath(cache);
  QString socketName =
      "sillyplayer-" + QString(QCryptographicHash::hash(
                                   QFileInfo(data).absoluteFilePath().toUtf8(),
                                   QCryptographicHash::Sha256)
                                   .toHex()
                                   .left(20));
  QLockFile lock(data + "/instance.lock");
  if (!lock.tryLock(100)) {
    QLocalSocket socket;
    socket.connectToServer(socketName);
    if (socket.waitForConnected(1500)) {
      socket.write("restore");
      socket.waitForBytesWritten(1000);
      return 0;
    }
    fprintf(stderr, "Another instance owns this data directory\n");
    return 1;
  }
  QLocalServer::removeServer(socketName);
  QLocalServer server;
  server.listen(socketName);
  try {
#ifdef Q_OS_WIN
    if (parser.isSet("benchmark")) qInfo("Startup: opening library");
#endif
    Store store(data + "/library.sqlite3");
    App app(&store, cache);
#ifdef Q_OS_WIN
    if (parser.isSet("benchmark")) qInfo("Startup: playback and library ready");
#endif
#ifdef Q_OS_WIN
    WindowsInstallLock installLock;
    WindowsMediaKeys mediaKeys(&app);
    application.installNativeEventFilter(&mediaKeys);
#endif
    QQuickStyle::setStyle("Basic");
    qmlRegisterType<StableModel>("SillyPlayer", 1, 0, "StableListModel");
    qmlRegisterUncreatableType<Player>("SillyPlayer", 1, 0, "MediaBackend",
                                       "Provided by application");
    ArtworkNetworkFactory artworkNetwork(cache + "/remote-art");
    QQmlApplicationEngine engine;
    engine.setNetworkAccessManagerFactory(&artworkNetwork);
    engine.rootContext()->setContextProperty("app", &app);
    engine.rootContext()->setContextProperty("library", &app.library);
    engine.rootContext()->setContextProperty("music", &app.music);
    engine.rootContext()->setContextProperty("youtubePlayer", &app.youtube);
    engine.rootContext()->setContextProperty("online", &app.online);
    engine.rootContext()->setContextProperty("catalogue", &app.catalogue);
    bool trayAvailable = QSystemTrayIcon::isSystemTrayAvailable();
    engine.rootContext()->setContextProperty("trayAvailable", trayAvailable);
#ifdef Q_OS_WIN
    if (parser.isSet("benchmark")) qInfo("Startup: loading interface");
#endif
    engine.load(QUrl("qrc:/qml/Main.qml"));
    if (engine.rootObjects().isEmpty())
      return 1;
    auto window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
    if (!window)
      return 1;
    new WindowRenderLifecycle(window);
    window->setIcon(appIcon);
    if (parser.isSet("benchmark")) {
      qInfo("Platform: %s", qPrintable(QGuiApplication::platformName()));
      qInfo("Window icon: %d raster sizes", int(appIcon.availableSizes().size()));
    }
    if (parser.isSet("benchmark"))
      QObject::connect(window, &QQuickWindow::frameSwapped, &application,
                       [&startup, first = true]() mutable {
                         if (first) {
                           first = false;
                           qInfo("First frame: %lld ms", startup.elapsed());
                         }
                       });
    auto restore = [window] { activatePlayerWindow(window); };
#ifdef Q_OS_LINUX
    QObject mediaService;
    auto mediaRoot = new MprisRoot(&mediaService);
    new MprisPlayer(&mediaService, &app);
    QObject::connect(mediaRoot, &MprisRoot::raiseRequested, &application,
                     restore);
    auto bus = QDBusConnection::sessionBus();
    if (bus.registerService("org.mpris.MediaPlayer2.sillyplayer"))
      bus.registerObject("/org/mpris/MediaPlayer2", &mediaService,
                         QDBusConnection::ExportAdaptors);
#endif
    QObject::connect(&server, &QLocalServer::newConnection, &application,
                     [&server, restore] {
                       while (auto socket = server.nextPendingConnection()) {
                         restore();
                         socket->disconnectFromServer();
                         socket->deleteLater();
                       }
                     });
    QMenu menu;
    menu.addAction("Open", restore);
    menu.addAction("Play / Pause", &app, &App::toggle);
    menu.addAction("Previous", &app, &App::previous);
    menu.addAction("Next", &app, &App::next);
    menu.addSeparator();
    menu.addAction("Exit", &application, &QApplication::quit);
    QSystemTrayIcon tray(application.windowIcon());
    tray.setToolTip("SillyPlayer");
    tray.setContextMenu(&menu);
    if (trayAvailable)
      tray.show();
    QObject::connect(&tray, &QSystemTrayIcon::activated, &application,
                     [restore](auto reason) {
                       if (reason == QSystemTrayIcon::Trigger)
                         restore();
                     });
    QObject::connect(&application, &QCoreApplication::aboutToQuit, &app,
                     &App::shutdown);
    if (parser.isSet("import-folder"))
      app.library.addFolder(parser.value("import-folder"));
    if (parser.isSet("test-play"))
      QTimer::singleShot(500, &app,
                         [&] { app.music.load(parser.value("test-play")); });
    if (parser.isSet("test-video"))
      QTimer::singleShot(500, &app, [&] {
        window->setProperty("page", "Browse");
        window->setProperty("videoOpen", true);
        app.youtube.load(parser.value("test-video"));
      });
    if (parser.isSet("youtube"))
      QTimer::singleShot(500, &app, [&] {
        window->setProperty("page", "Browse");
        window->setProperty("videoOpen", true);
        app.online.resolve(parser.value("youtube"), true);
      });
    if (parser.isSet("quit-after")) {
      int delay = parser.value("quit-after").toInt();
#ifdef Q_OS_WIN
      if (parser.isSet("benchmark") && parser.isSet("test-play"))
        QTimer::singleShot(std::max(100, delay - 500), &application, [&] {
          qInfo("Playback: position=%.3f duration=%.3f error=%s", app.music.position(),
                app.music.duration(), qPrintable(app.music.error()));
        });
#endif
      if (parser.isSet("screenshot"))
        QTimer::singleShot(std::max(100, delay - 300), &application, [&] {
          window->grabWindow().save(parser.value("screenshot"));
        });
      QTimer::singleShot(delay, &application, &QCoreApplication::quit);
    }
    return application.exec();
  } catch (const std::exception &e) {
    fprintf(stderr, "SillyPlayer: %s\n", e.what());
    return 1;
  }
}
