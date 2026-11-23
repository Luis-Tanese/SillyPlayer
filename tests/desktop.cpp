#include "app.h"
#include "artistsplit.h"
#include "m3u.h"
#include "tagcleanup.h"
#include "discordrpc.h"
#include "fileavailability.h"
#include "artworknetwork.h"
#include "thumbnailurls.h"
#include "stablemodel.h"
#include "directtransfer.h"
#include <QBuffer>
#include <QDateTime>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QEventLoop>
#include <QTcpServer>
#include <QTcpSocket>
#include <QFileInfo>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QApplication>
#include <QTemporaryDir>
#include <QQmlEngine>
#include <QQmlComponent>
#include <QElapsedTimer>
#include <QThread>
#include <QImage>
#include <QJSValue>
#include <clocale>
#include <cstdio>
#define CHECK(x) do { if (!(x)) { qFatal("Check failed at line %d: %s", __LINE__, #x); } } while (0)
int main(int argc, char **argv) {
  QApplication gui(argc, argv);
  std::setlocale(LC_NUMERIC, "C");
  CHECK(splitArtists("ANGUISH, EXILED, Elfass") == (QStringList{"ANGUISH", "EXILED", "Elfass"}));
  CHECK(splitArtists("Artist A (feat. Artist B, Artist C)") == (QStringList{"Artist A", "Artist B", "Artist C"}));
  CHECK(splitArtists("Artist A / Artist B") == (QStringList{"Artist A", "Artist B"}));
  CHECK(splitArtists("AC/DC") == (QStringList{"AC/DC"}));
  CHECK(splitArtists("Simon & Garfunkel") == (QStringList{"Simon & Garfunkel"}));
  CHECK(splitArtists("ANGUISH, anguish") == (QStringList{"ANGUISH"}));
  CHECK(cleanSongTitle("Midnight City (Official Audio)") == "Midnight City");
  CHECK(cleanSongTitle("Midnight City (Official Music Video) [HD]") == "Midnight City");
  CHECK(cleanSongTitle("Midnight City [Lyrics]") == "Midnight City");
  CHECK(cleanSongTitle("Midnight City | Official Video") == "Midnight City");
  CHECK(cleanSongTitle("Midnight City (Remix) (Official Audio)") == "Midnight City (Remix)");
  CHECK(cleanSongTitle("(Official Video)") == "(Official Video)");
  {
    const QVariantList sample{
        QVariantMap{{"path", "/music/one.mp3"}, {"title", "One"}, {"artist", "Artist A"}, {"duration", 123.6}},
        QVariantMap{{"path", "/music/two.flac"}, {"title", "Two"}, {"artist", "Unknown artist"}, {"duration", 200.0}},
    };
    const QString m3u = formatM3u(sample);
    CHECK(m3u.startsWith("#EXTM3U\n"));
    const auto parsed = parseM3u(QString(QChar(0xFEFF)) + m3u, "/music");
    CHECK(parsed.size() == 2);
    CHECK(parsed[0].path == "/music/one.mp3");
    CHECK(parsed[0].artist == "Artist A");
    CHECK(parsed[0].title == "One");
    CHECK(parsed[0].duration == 124);
    CHECK(parsed[1].path == "/music/two.flac");
    CHECK(parsed[1].artist.isEmpty());
    CHECK(parsed[1].title == "Two");
  }
  {
    const QByteArray frame = DiscordRpc::encodeFrame(1, R"({"ok":true})");
    CHECK(frame.size() == 8 + 11);
    const auto playingPayload = DiscordRpc::buildActivityPayload(
        {{"title", "Song A"}, {"artist", "Artist B"}, {"album", "Album C"},
         {"videoId", "dQw4w9WgXcQ"}, {"speed", 1.25}, {"nightcore", true}},
        true, 30.0, 180.0, 1000000, 42, "n1");
    const auto activity = playingPayload["args"].toMap()["activity"].toMap();
    CHECK(activity["type"].toInt() == 2);
    CHECK(activity["status_display_type"].toInt() == 2);
    CHECK(activity["details"].toString() == "Song A");
    CHECK(activity["state"].toString() == QString::fromUtf8("Artist B · 1.25× Nightcore"));
    CHECK(activity["assets"].toMap()["large_image"].toString() == "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
    CHECK(activity["buttons"].toList().size() == 1);
    CHECK(activity["buttons"].toList().first().toMap()["label"].toString() == "Listen on YouTube");
    CHECK(activity["timestamps"].toMap()["start"].toLongLong() == 970000);
    CHECK(activity["timestamps"].toMap()["end"].toLongLong() == 1150000);
    const auto pausedPayload = DiscordRpc::buildActivityPayload(
        {{"title", "Song A"}, {"artist", "Artist B"}}, false, 30.0, 180.0, 1000000, 42, "n2");
    const auto pausedAct = pausedPayload["args"].toMap()["activity"].toMap();
    CHECK(!pausedAct.contains("timestamps"));
    CHECK(pausedAct["state"].toString().contains("Paused"));
    CHECK(pausedAct["assets"].toMap()["large_image"].toString() == "sillyplayer");
    CHECK(!pausedAct.contains("buttons"));
  }
  {
    const QByteArray payload = SyncManager::discoveryReplyPayload("desk-1", "MyPC", 43817, QString(64, 'a'));
    const auto obj = QJsonDocument::fromJson(payload).object();
    CHECK(obj["service"].toString() == "sillyplayer-sync");
    CHECK(obj["v"].toInt() == 1);
    CHECK(obj["port"].toInt() == 43817);
    CHECK(obj["fp"].toString() == QString(64, 'a'));
  }
  if(argc==4 && QString::fromLocal8Bit(argv[1])=="--art-cache-probe") {
    ArtworkNetworkFactory factory(QString::fromLocal8Bit(argv[2]));
    QScopedPointer<QNetworkAccessManager> manager(factory.create(nullptr));
    auto reply=manager->get(QNetworkRequest(QUrl(QString::fromLocal8Bit(argv[3]))));
    QEventLoop loop; QObject::connect(reply,&QNetworkReply::finished,&loop,&QEventLoop::quit);
    QTimer::singleShot(10000,&loop,&QEventLoop::quit); loop.exec();
    return reply->isFinished() && reply->error()==QNetworkReply::NoError &&
      reply->attribute(QNetworkRequest::SourceIsFromCacheAttribute).toBool() &&
      !QImage::fromData(reply->readAll()).isNull() ? 0 : 1;
  }
  QTemporaryDir dir;
  CHECK(dir.isValid());
  const auto database = dir.path()+"/library.sqlite3";
  {
    const auto folder=dir.path()+"/music"; CHECK(QDir().mkpath(folder));
    const auto path=folder+"/existing.mp3";
    QFile audio(path); CHECK(audio.open(QIODevice::WriteOnly)); audio.write("fixture"); audio.close();
    QVariantMap root{{"path",folder},{"device",storageIdentity(folder)}};
    QVariantMap track{{"path",path},{"deleted",false},{"mergedInto",""}};
    CHECK(fileAvailability(track,{root})=="Available");
    track["path"]=folder+"/absent.mp3";
    CHECK(fileAvailability(track,{root})=="Missing");
    root["device"]="different-volume";
    CHECK(fileAvailability(track,{root})=="Storage unavailable");
    track["path"]=path;
    CHECK(fileAvailability(track,{root})=="Available");
    track["deleted"]=true; CHECK(fileAvailability(track,{root})=="Excluded");
    track["deleted"]=false;track["path"]="sync://remote";
    CHECK(fileAvailability(track,{root})=="Unresolved");
    Store store(dir.path()+"/removal.sqlite3"); Library library(&store,dir.path()+"/removal-cache");
    CHECK(store.exec("INSERT INTO tracks(id,path,title,managed) VALUES('keep',?,'Keep',1),('absent','/offline/song.mp3','Absent',1)",{path}));
    CHECK(store.exec("INSERT INTO likes VALUES('keep')"));
    CHECK(store.exec("CREATE TRIGGER refuse_removal BEFORE UPDATE ON tracks WHEN NEW.id='absent' BEGIN SELECT RAISE(ABORT,'fixture'); END"));
    CHECK(!library.removeRecords({"keep","absent"}));
    CHECK(!library.track("keep")["deleted"].toBool());
    CHECK(store.exec("DROP TRIGGER refuse_removal"));
    CHECK(library.removeRecords({"keep","absent","unknown"}));
    CHECK(library.tracks().isEmpty()); CHECK(QFileInfo(path).isFile());
    CHECK(store.rows("SELECT * FROM likes").size()==1);
  }
  {
    const auto folder=dir.path()+"/scan", nested=folder+"/SillyPlayer";
    CHECK(QDir().mkpath(nested));
    QProcess ffmpeg; ffmpeg.start("ffmpeg",{"-v","error","-f","lavfi","-i","sine=frequency=440:duration=0.1",nested+"/tone.wav"});
    CHECK(ffmpeg.waitForFinished(10000) && ffmpeg.exitCode()==0);
    Store store(dir.path()+"/scan.sqlite3");
    CHECK(store.exec("INSERT INTO roots(id,path,device) VALUES('outer',?,?),('inner',?,?)",{folder,storageIdentity(folder),nested,storageIdentity(nested)}));
    Library library(&store,dir.path()+"/scan-cache");
    auto scanDone=[&] { QElapsedTimer timer;timer.start(); do {gui.processEvents();QThread::msleep(5);} while((library.scanning() || library.status().isEmpty()) && timer.elapsed()<15000); CHECK(!library.scanning()); };
    scanDone(); CHECK(library.tracks().size()==1); CHECK(library.status().contains("1 refreshed, 0 unchanged"));
    const auto id=library.tracks().first().toMap()["id"].toString();
    CHECK(store.exec("UPDATE tracks SET managed=1 WHERE id=?",{id}));
    library.rescan();scanDone(); CHECK(library.tracks().size()==1);CHECK(library.status().contains("1 refreshed, 0 unchanged"));
    CHECK(store.exec("CREATE TRIGGER fail_scan BEFORE UPDATE ON tracks BEGIN SELECT RAISE(ABORT,'scan failure'); END"));
    const auto before=store.rows("SELECT * FROM tracks");
    library.rescan();scanDone();
    CHECK(library.status().startsWith("Scan not saved")); CHECK(store.rows("SELECT * FROM tracks")==before);
    CHECK(store.exec("DROP TRIGGER fail_scan"));
    CHECK(QFile::rename(nested+"/tone.wav",nested+"/renamed.wav"));
    library.rescan();scanDone(); CHECK(library.track(id)["available"].toBool());
    CHECK(library.track(id)["path"].toString()==nested+"/renamed.wav");
    CHECK(QFile::rename(nested+"/renamed.wav",dir.path()+"/temporarily-away.wav"));
    library.rescan();scanDone(); CHECK(!library.track(id)["deleted"].toBool());
    CHECK(library.status().contains("1 missing"));
    CHECK(!store.rows("SELECT * FROM track_roots WHERE track=?",{id}).isEmpty());
    CHECK(store.exec("UPDATE roots SET device='different-volume'"));
    library.rescan();scanDone(); CHECK(!library.track(id)["deleted"].toBool());CHECK(library.status().contains("1 storage unavailable"));
    CHECK(library.removeRecords({id})); library.rescan();scanDone(); CHECK(library.tracks().isEmpty()); CHECK(QFileInfo(dir.path()+"/temporarily-away.wav").isFile());
  }
  QString playlist;
  {
    Store store(database);
    CHECK(store.exec("INSERT INTO tracks(id,path,title) VALUES('a','/fixture/a','First'),('b','/fixture/b','Second')"));
    App app(&store, dir.path()+"/cache");
    playlist=app.createCollection("Test", "playlist");
    CHECK(!playlist.isEmpty());
    int notifications=0;
    QObject::connect(&app,&App::collectionsChanged,[&]{++notifications;});
    CHECK(app.addToCollection(playlist,"a",false)=="added");
    CHECK(app.collectionTracks(playlist).size()==1);
    CHECK(app.addToCollection(playlist,"a",false)=="duplicate");
    auto result=app.addTracksToCollection(playlist,{"a","b","b"},"skip");
    CHECK(result["added"].toInt()==1 && result["skipped"].toInt()==2);
    CHECK(app.collectionTracks(playlist)[1].toMap()["id"]=="b");
    CHECK(app.collections()[0].toMap()["count"].toInt()==2);
    CHECK(notifications==2);
    CHECK(app.addToCollection("liked","a",false)=="added");
    CHECK(app.addToCollection("liked","a",false)=="added");
    CHECK(store.exec("CREATE TRIGGER reject_collection BEFORE INSERT ON collections WHEN NEW.name='Reject' BEGIN SELECT RAISE(ABORT,'fixture'); END"));
    CHECK(app.createCollection("Reject","playlist").isEmpty());
    CHECK(app.addTracksToCollection("missing",{"a"},"skip")["status"]=="error");
    CHECK(app.addToCollection("missing","a",false)=="error");
    // write failure needs to rollback
    CHECK(store.exec("CREATE TRIGGER reject_member BEFORE INSERT ON members WHEN NEW.track='b' BEGIN SELECT RAISE(ABORT,'fixture'); END"));
    const auto empty=app.createCollection("Rollback", "playlist");
    CHECK(app.addTracksToCollection(empty,{"a","b"},"skip")["status"]=="error");
    CHECK(app.collectionTracks(empty).isEmpty());
    CHECK(store.exec("DROP TRIGGER reject_member"));
  }
  {
    Store store(database);
    App app(&store,dir.path()+"/cache");
    CHECK(app.collectionTracks(playlist).size()==2);
  }
  {
    Store store(dir.path()+"/bulk-removal.sqlite3");
    CHECK(store.exec("INSERT INTO tracks(id,path,title) VALUES('a','/offline/a','A'),('b','/offline/b','B')"));
    App app(&store,dir.path()+"/bulk-cache");
    const auto collection=app.createCollection("Preserve","playlist");
    CHECK(app.addToCollection(collection,"a",false)=="added");
    CHECK(app.addToCollection(collection,"b",false)=="added");
    CHECK(app.addToCollection("liked","a",false)=="added");
    auto removed=app.removeLibraryTracks({"a","unknown","a"});
    CHECK(removed["removed"].toInt()==1 && removed["failures"].toList().size()==1);
    CHECK(store.rows("SELECT * FROM members WHERE track='a'").size()==1);
    CHECK(store.rows("SELECT * FROM likes WHERE track='a'").size()==1);
    CHECK(app.collectionTracks(collection).size()==1);
    CHECK(store.exec("INSERT INTO tracks(id,path,title,mergedInto) VALUES('alias','/offline/alias','Alias','b')"));
    app.libraryTools.findMissing(); QElapsedTimer check;check.start();
    while(app.libraryTools.busy() && check.elapsed()<10000) {gui.processEvents();QThread::msleep(5);}
    CHECK(!app.libraryTools.busy());CHECK(app.libraryTools.missing().size()==1);
    CHECK(app.libraryTools.missing().first().toMap()["id"]=="b");
  }
  CHECK(thumbnailCandidates("broken metadata", "dQw4w9WgXcQ").first().contains("hqdefault.jpg"));
  CHECK(thumbnailCandidates("", "dQw4w9WgXcQ").size()==3);
  CHECK(thumbnailCandidates("//i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg").first().startsWith("https:"));
  CHECK(thumbnailCandidates("https://example.org/image.jpg").size()==1);
  const auto signedCover = QString("https://example.org/cover.jpg?signature=keep");
  const auto result = Online::normalizeResult({{"provider","youtube"},{"id","dQw4w9WgXcQ"},{"thumbnail",signedCover}});
  const auto candidates = result.value("thumbnailCandidates").toStringList();
  CHECK(candidates.first().endsWith("/hqdefault.jpg") && candidates.contains(signedCover));
  ArtworkNetworkFactory network(dir.path()+"/http-cache");
  QQmlEngine engine;
  engine.setNetworkAccessManagerFactory(&network);
  QQmlComponent component(&engine, QUrl("qrc:/qml/RemoteThumbnail.qml"));
  CHECK(component.isReady());
  QScopedPointer<QObject> image(component.create());
  CHECK(image);
  image->setProperty("width",120); image->setProperty("height",80);
  auto waitStatus=[&](int status) {
    QElapsedTimer timer;timer.start();
    while(image->property("status").toInt()!=status && timer.elapsed()<20000) {
      gui.processEvents();QThread::msleep(5);
    }
    return image->property("status").toInt()==status;
  };
  QImage fixture(16,16,QImage::Format_RGB32);fixture.fill(Qt::red);
  const auto file=dir.path()+"/art.png";CHECK(fixture.save(file));
  image->setProperty("candidates",QStringList{QUrl::fromLocalFile(dir.path()+"/missing.png").toString(),QUrl::fromLocalFile(file).toString()});
  CHECK(waitStatus(1));
  image->setProperty("candidates",QStringList{QUrl::fromLocalFile(dir.path()+"/missing-again.png").toString()});
  CHECK(waitStatus(3));
  image->setProperty("candidates",QStringList{QUrl::fromLocalFile(file).toString()});
  CHECK(waitStatus(1));
  {
    QTcpServer server; CHECK(server.listen(QHostAddress::LocalHost));
    int cacheRequests=0, concurrent=0, peakConcurrent=0;
    QByteArray png; QBuffer buffer(&png); buffer.open(QIODevice::WriteOnly); CHECK(fixture.save(&buffer,"PNG"));
    QObject::connect(&server,&QTcpServer::newConnection,[&] {
      while(server.hasPendingConnections()) {
        auto socket=server.nextPendingConnection();
        QObject::connect(socket,&QTcpSocket::readyRead,socket,[socket,png,&cacheRequests,&concurrent,&peakConcurrent] {
          const auto request=socket->readAll();
          if(request.contains("/cache")) ++cacheRequests;
          if(request.contains("/slow")) return;
          if(request.contains("/parallel")) {
            ++concurrent; peakConcurrent=std::max(peakConcurrent,concurrent);
            QTimer::singleShot(100,socket,[socket,png,&concurrent] {
              socket->write("HTTP/1.1 200 OK\r\nContent-Length: "+QByteArray::number(png.size())+"\r\nConnection: close\r\n\r\n"+png);
              --concurrent;socket->disconnectFromHost();
            }); return;
          }
          if(request.contains("/redirect")) socket->write("HTTP/1.1 302 Found\r\nLocation: /ok\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
          else if(request.contains("/404")) socket->write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
          else { const auto body=request.contains("/invalid")?QByteArray("invalid"):png; socket->write("HTTP/1.1 200 OK\r\nCache-Control: public, max-age=3600\r\nContent-Length: "+QByteArray::number(body.size())+"\r\nConnection: close\r\n\r\n"+body); }
          socket->disconnectFromHost();
        });
        QObject::connect(socket,&QTcpSocket::disconnected,socket,&QObject::deleteLater);
      }
    });
    const auto base=QString("http://127.0.0.1:%1").arg(server.serverPort());
    for(const auto &endpoint:QStringList{"/ok","/redirect","/404","/invalid","/slow"}) {
      image->setProperty("candidates",QStringList{base+endpoint,QUrl::fromLocalFile(file).toString()});
      CHECK(waitStatus(1));
    }
    image->setProperty("candidates",QStringList{base+"/cache"}); CHECK(waitStatus(1));
    CHECK(cacheRequests==1);
    QProcess probe; probe.start(QCoreApplication::applicationFilePath(),{"--art-cache-probe",dir.path()+"/http-cache",base+"/cache"});
    QElapsedTimer deadline;deadline.start();
    while(probe.state()!=QProcess::NotRunning && deadline.elapsed()<15000) {gui.processEvents();QThread::msleep(5);}
    CHECK(probe.state()==QProcess::NotRunning && probe.exitCode()==0); CHECK(cacheRequests==1);
    QList<QObject*> simultaneous;
    for(int i=0;i<12;++i) { auto row=component.create();CHECK(row); row->setProperty("candidates",QStringList{base+"/parallel?row="+QString::number(i)}); simultaneous<<row; }
    deadline.restart(); bool ready=false;
    while(!ready && deadline.elapsed()<15000) {gui.processEvents();QThread::msleep(5); ready=true;for(auto row:simultaneous)ready=ready && row->property("status").toInt()==1;}
    CHECK(ready);CHECK(peakConcurrent>=2 && peakConcurrent<=6);qDeleteAll(simultaneous);
    image->setProperty("candidates",QStringList{"http://127.0.0.1:1/unreachable",QUrl::fromLocalFile(file).toString()});CHECK(waitStatus(1));
    image->setProperty("candidates",QStringList{QString(base).replace("http:","https:")+"/tls",QUrl::fromLocalFile(file).toString()});CHECK(waitStatus(1));
    image->setProperty("candidates",QStringList{base+"/slow?obsolete"});
    image->setProperty("candidates",QStringList{base+"/ok?latest"}); CHECK(waitStatus(1));
  }
  if(qEnvironmentVariableIsSet("SILLYPLAYER_LIVE_ART_TEST")) {
    image->setProperty("candidates",thumbnailCandidates(qEnvironmentVariable("SILLYPLAYER_LIVE_ART_URL"), "dQw4w9WgXcQ"));
    CHECK(waitStatus(1));
  }
  {
    Store store(database);
    App app(&store,dir.path()+"/cache");
    CHECK(app.library.beginMaintenance());
    QQuickStyle::setStyle("Basic");
    qmlRegisterType<StableModel>("SillyPlayer",1,0,"StableListModel");
    qmlRegisterUncreatableType<Player>("SillyPlayer",1,0,"MediaBackend","Provided by app");
    QQmlApplicationEngine scene;
    scene.rootContext()->setContextProperty("app",&app);
    scene.rootContext()->setContextProperty("library",&app.library);
    scene.rootContext()->setContextProperty("music",&app.music);
    scene.rootContext()->setContextProperty("youtubePlayer",&app.youtube);
    scene.rootContext()->setContextProperty("online",&app.online);
    scene.rootContext()->setContextProperty("catalogue",&app.catalogue);
    scene.rootContext()->setContextProperty("trayAvailable",false);
    scene.load(QUrl("qrc:/qml/Main.qml"));
    CHECK(!scene.rootObjects().isEmpty());
    auto window=qobject_cast<QQuickWindow*>(scene.rootObjects().first());CHECK(window);
    auto settle=[&] { QElapsedTimer timer;timer.start();while(timer.elapsed()<300){gui.processEvents();QThread::msleep(5);} };
    const auto liveUrl = qEnvironmentVariable("SILLYPLAYER_LIVE_PLAYBACK_URL");
    if (!liveUrl.isEmpty()) {
      window->setProperty("page", "Browse");
      for (int attempt = 0; attempt < 2; ++attempt) {
        QElapsedTimer elapsed;
        elapsed.start();
        app.online.resolve(liveUrl, true);
        while (app.youtube.position() <= 0 && elapsed.elapsed() < 65000) {
          gui.processEvents();
          QThread::msleep(5);
        }
        qInfo("Online playback attempt %d reached %.3f seconds after %lld ms", attempt + 1,
              app.youtube.position(), static_cast<long long>(elapsed.elapsed()));
        CHECK(app.youtube.position() > 0);
        while (app.online.details().value("artwork").toString().isEmpty() && elapsed.elapsed() < 65000) {
          gui.processEvents();
          QThread::msleep(5);
        }
        CHECK(!app.online.details().value("artwork").toString().isEmpty());
      }
      app.youtube.stop();
    }
    window->setProperty("page","Settings");settle();
    for(auto object:window->findChildren<QObject*>()) if(object->metaObject()->indexOfProperty("category")>=0 && object->metaObject()->indexOfProperty("categories")>=0) object->setProperty("category","About");
    settle();
    if(qEnvironmentVariableIsSet("SILLYPLAYER_QA_DIR")) window->grabWindow().save(qEnvironmentVariable("SILLYPLAYER_QA_DIR")+"/about-reliability.png");
    if(qEnvironmentVariableIsSet("SILLYPLAYER_QA_DIR")) window->grabWindow().save(qEnvironmentVariable("SILLYPLAYER_QA_DIR")+"/settings.png");
    window->setWidth(680);settle();
    if(qEnvironmentVariableIsSet("SILLYPLAYER_QA_DIR")) window->grabWindow().save(qEnvironmentVariable("SILLYPLAYER_QA_DIR")+"/settings-narrow.png");
    window->setWidth(1160);window->setProperty("page","Music");
    const auto uiPlaylist=app.createCollection("Picker test","playlist");
    CHECK(QMetaObject::invokeMethod(window,"addSongs",Q_ARG(QVariant,QVariant(uiPlaylist))));settle();
    auto picker=window->findChild<QObject*>("addSongsDialog");CHECK(picker && picker->property("visible").toBool());
    CHECK(QMetaObject::invokeMethod(picker,"toggle",Q_ARG(QVariant,QVariant(0)),Q_ARG(QVariant,QVariant(0))));
    CHECK(picker->property("selectedIds").value<QJSValue>().toVariant().toMap().size()==1);
    auto search=window->findChild<QObject*>("addSongSearch");CHECK(search);
    search->setProperty("text","no matching fixture");settle();
    CHECK(picker->property("selectedIds").value<QJSValue>().toVariant().toMap().size()==1);
    search->setProperty("text","");settle();
    if(qEnvironmentVariableIsSet("SILLYPLAYER_QA_DIR")) {settle();window->grabWindow().save(qEnvironmentVariable("SILLYPLAYER_QA_DIR")+"/song-picker.png");}
    CHECK(QMetaObject::invokeMethod(picker,"toggle",Q_ARG(QVariant,QVariant(1)),Q_ARG(QVariant,QVariant(int(Qt::ShiftModifier)))));
    CHECK(picker->property("selectedIds").value<QJSValue>().toVariant().toMap().size()==2);
    auto confirm=window->findChild<QObject*>("confirmAddSongs");CHECK(confirm);
    CHECK(QMetaObject::invokeMethod(confirm,"clicked"));
    CHECK(app.collectionTracks(uiPlaylist).size()==2);
    CHECK(picker->property("selectedIds").value<QJSValue>().toVariant().toMap().isEmpty());
    CHECK(QMetaObject::invokeMethod(picker,"reject"));
    CHECK(!picker->property("visible").toBool());
    const qint64 nowMs = QDateTime::currentMSecsSinceEpoch();
    const QString firstTrackId = app.library.tracks().first().toMap()["id"].toString();
    CHECK(store.exec("INSERT OR IGNORE INTO listening_plays(session,track,playedAt) VALUES('ins-1',?,?)", {firstTrackId, nowMs}));
    CHECK(store.exec("INSERT INTO track_listens(track,plays,lastPlayed) VALUES(?,1,?) ON CONFLICT(track) DO UPDATE SET plays=plays+1,lastPlayed=excluded.lastPlayed", {firstTrackId, nowMs}));
    const auto dayIns = app.listening.insights("day");
    CHECK(dayIns["totalPlays"].toLongLong() >= 1);
    CHECK(dayIns["buckets"].toList().size() == 6);
    CHECK(dayIns["streakDays"].toInt() >= 1);
    const auto weekIns = app.listening.insights("week");
    CHECK(weekIns["buckets"].toList().size() == 7);
    const auto monthIns = app.listening.insights("month");
    CHECK(monthIns["buckets"].toList().size() == 30);
  }
  {
    QTcpServer server;CHECK(server.listen(QHostAddress::LocalHost));
    const QByteArray wave = QByteArray::fromHex("52494646a43e000057415645666d74201000000001000100401f0000803e00000200100064617461803e0000") + QByteArray(16000, '\0');
    QObject::connect(&server,&QTcpServer::newConnection,[&] {
      auto socket=server.nextPendingConnection();
      QObject::connect(socket,&QTcpSocket::readyRead,socket,[socket,wave] {
        const auto request=socket->readAll();
        const bool missing=request.contains("/missing");
        const QByteArray body=missing ? QByteArray("Unavailable") : wave;
        socket->write((missing ? QByteArray("HTTP/1.1 404 Not Found\r\n") : QByteArray("HTTP/1.1 200 OK\r\n"))+"Content-Type: audio/wav\r\nContent-Length: "+QByteArray::number(body.size())+"\r\nConnection: close\r\n\r\n"+body);
        socket->disconnectFromHost();
      });
      QObject::connect(socket,&QTcpSocket::disconnected,socket,&QObject::deleteLater);
    });
    for (const auto &action:QStringList{"audio","missing","cancel"}) {
      DirectTransfer transfer;
      QString state,path,message;
      QObject::connect(&transfer,&DirectTransfer::finished,[&](const QString &status,const QString &file,const QString &error){state=status;path=file;message=error;});
      transfer.start({{"id",action},{"destination",dir.path()+"/downloads"},{"url",QString("http://127.0.0.1:%1/%2").arg(server.serverPort()).arg(action)}});
      if(action=="cancel") transfer.stop("Cancelled");
      QElapsedTimer timer;timer.start();
      while(state.isEmpty() && timer.elapsed()<20000){gui.processEvents();QThread::msleep(5);}
      if(action=="audio") {CHECK(state=="Completed");CHECK(QFileInfo(path).size()==wave.size());}
      else if(action=="missing") {CHECK(state=="Failed");CHECK(!message.isEmpty());}
      else CHECK(state=="Cancelled");
    }
  }
  puts("Desktop regression checks passed");
}
