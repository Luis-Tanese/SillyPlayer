#include "processutils.h"
#include "directtransfer.h"
#include "online.h"
#include "source.h"
#include <QDir>
#include <QJsonDocument>
#include <QSaveFile>
#include <QRegularExpression>
#include <QTimer>
#include <QUuid>

DirectTransfer::DirectTransfer(QObject *parent):QObject(parent) {}
DirectTransfer::~DirectTransfer() {
  if (reply) { reply->disconnect(this); reply->abort(); }
  if (probe && probe->state()!=QProcess::NotRunning) { probe->kill(); probe->waitForFinished(1000); }
}
void DirectTransfer::start(const QVariantMap &value) {
  job=value;
  stage=job["destination"].toString()+"/.sillyplayer/"+job["id"].toString();
  if (!Source::http(job["url"].toString())) { complete("Failed",{},"Use an HTTP or HTTPS audio file URL without embedded credentials"); return; }
  if (!QDir().mkpath(stage)) { complete("Failed",{},"Cannot create download staging directory"); return; }
  file.setFileName(stage+"/payload.part");
  QFile saved(stage+"/resume.json");
  if (saved.open(QIODevice::ReadOnly)) checkpoint=QJsonDocument::fromJson(saved.readAll()).toVariant().toMap();
  request(true);
}
void DirectTransfer::request(bool resume) {
  accepted=false; restartPending=false; failure.clear();
  offset=resume && checkpoint["url"]==job["url"] && !checkpoint["validator"].toString().isEmpty() ? QFileInfo(file).size() : 0;
  if (offset<0) offset=0;
  QNetworkRequest req(QUrl(job["url"].toString()));
  req.setTransferTimeout(30000); // inactivity, never the total length of a song
  req.setMaximumRedirectsAllowed(8);
  req.setAttribute(QNetworkRequest::RedirectPolicyAttribute,QNetworkRequest::NoLessSafeRedirectPolicy);
  req.setRawHeader("User-Agent","SillyPlayer/0.6");
  req.setRawHeader("Accept-Encoding","identity");
  if (offset>0) {
    req.setRawHeader("Range","bytes="+QByteArray::number(offset)+"-");
    req.setRawHeader("If-Range",checkpoint["validator"].toByteArray());
  }
  reply=network.get(req);
  reply->setReadBufferSize(256*1024);
  auto current=reply;
  connect(current,&QNetworkReply::readyRead,this,&DirectTransfer::drain);
  connect(current,&QNetworkReply::finished,this,[this,current] {
    if (done) return;
    drain();
    const auto error=current->error();
    const QString message=current->errorString();
    reply=nullptr;
    current->deleteLater();
    if (file.isOpen()) { file.flush(); file.close(); }
    if (restartPending) { checkpoint.clear(); request(false); return; }
    if (!failure.isEmpty()) { complete("Failed",{},failure); return; }
    if (error!=QNetworkReply::NoError || !accepted) {
      complete("Failed",{},"Audio transfer failed: "+message); return;
    }
    validate();
  });
  emit updated({{"state","Active"},{"progress",-1},{"receivedBytes",offset},{"totalBytes",0},{"error",""},{"stage",""}});
}
bool DirectTransfer::acceptHeaders() {
  if (accepted) return true;
  const int status=reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
  if (!status || (status>=300 && status<400)) return false;
  auto reject=[this](const QString &message) { failure=message; reply->abort(); return false; };
  if (offset>0 && (status==416 || status==206)) {
    const auto match=QRegularExpression("^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$").match(QString::fromLatin1(reply->rawHeader("Content-Range")));
    const auto validator=checkpoint["validator"].toByteArray();
    const bool matchingValidator=validator==reply->rawHeader(checkpoint["header"].toByteArray());
    if (status==416 || !match.hasMatch() || match.captured(1).toLongLong()!=offset || !matchingValidator) {
      if (restarted) return reject("Server returned an unsafe partial audio response");
      restarted=true; restartPending=true; reply->abort(); return false;
    }
  }
  if (status!=200 && status!=206) return reject(status==401 || status==403 ? "This audio URL requires authorization or is unavailable (HTTP "+QString::number(status)+")" : "Audio URL returned HTTP "+QString::number(status));
  if (status==206 && offset==0) return reject("Unexpected partial audio response");
  if (status==200) offset=0; // If-Range unsupported or resource changed: restart safely otherwise goes kaboom
  const QByteArray type=reply->header(QNetworkRequest::ContentTypeHeader).toByteArray().toLower();
  const QString extension=QFileInfo(reply->url().path()).suffix().toLower();
  if (type.startsWith("text/") || type.contains("html") || type.contains("json") || type.contains("mpegurl") || type.contains("dash+xml") || type.contains("scpls") ||
      QStringList{"m3u","m3u8","pls","mpd"}.contains(extension) || reply->hasRawHeader("icy-name") || reply->hasRawHeader("icy-metaint"))
    return reject("Use a finite audio file URL; web pages, playlists and radio streams are unsupported");
  if (!file.open(QIODevice::WriteOnly | (offset>0 ? QIODevice::Append : QIODevice::Truncate))) return reject("Cannot write download: "+file.errorString());
  QByteArray validator=reply->rawHeader("ETag"), header="ETag";
  if (validator.startsWith("W/")) validator.clear();
  if (validator.isEmpty()) { header="Last-Modified"; validator=reply->rawHeader(header); }
  checkpoint={{"url",job["url"]},{"header",QString::fromLatin1(header)},{"validator",QString::fromLatin1(validator)}};
  QSaveFile saved(stage+"/resume.json");
  if (!saved.open(QIODevice::WriteOnly) || saved.write(QJsonDocument::fromVariant(checkpoint).toJson())<0 || !saved.commit()) return reject("Cannot save download checkpoint");
  suggestedName=QFileInfo(reply->url().path()).fileName();
  const QString disposition=QString::fromLatin1(reply->rawHeader("Content-Disposition"));
  auto match=QRegularExpression("filename\\*\\s*=\\s*UTF-8''([^;]+)",QRegularExpression::CaseInsensitiveOption).match(disposition);
  if (match.hasMatch()) suggestedName=QUrl::fromPercentEncoding(match.captured(1).trimmed().toLatin1());
  else {
    match=QRegularExpression("filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))",QRegularExpression::CaseInsensitiveOption).match(disposition);
    if (match.hasMatch()) suggestedName=match.captured(1).isEmpty() ? match.captured(2).trimmed() : match.captured(1);
  }
  accepted=true;
  return true;
}
void DirectTransfer::drain() {
  if (done || !reply || restartPending || !failure.isEmpty() || !acceptHeaders()) return;
  while (reply->bytesAvailable()>0) {
    const auto bytes=reply->read(64*1024);
    if (bytes.isEmpty()) break;
    if (file.write(bytes)!=bytes.size()) { failure="Cannot write audio: "+file.errorString(); reply->abort(); return; }
  }
  bool known=false;
  const auto remaining=reply->header(QNetworkRequest::ContentLengthHeader).toLongLong(&known);
  const auto total=known && remaining>0 ? offset+remaining : 0;
  const auto received=file.pos();
  if (progressClock.isValid() && progressClock.elapsed()<100 && received!=total) return;
  progressClock.start();
  emit updated({{"state","Active"},{"receivedBytes",received},{"totalBytes",total},{"progress",total>0 ? qMin(100.0,100.0*received/total) : -1.0}});
}
void DirectTransfer::validate() {
  emit updated({{"state","Post-processing"},{"progress",-1},{"stage","Checking audio"}});
  probe=new QProcess(this);
#ifdef Q_OS_WIN
  configureToolProcess(probe);
#endif
  connect(probe,&QProcess::errorOccurred,this,[this](auto error) {
    if (error==QProcess::FailedToStart) complete("Failed",{},"Cannot start bundled ffprobe to verify audio");
  });
  connect(probe,qOverload<int,QProcess::ExitStatus>(&QProcess::finished),this,[this](int code,QProcess::ExitStatus) {
    if (done) return;
    const auto info=QJsonDocument::fromJson(probe->readAllStandardOutput()).toVariant().toMap();
    bool audio=false, video=false;
    QString codec;
    for (const auto &value:info["streams"].toList()) {
      const auto stream=value.toMap();
      if (stream["codec_type"]=="audio") { audio=true; codec=stream["codec_name"].toString(); }
      if (stream["codec_type"]=="video" && !stream["disposition"].toMap()["attached_pic"].toBool()) video=true;
    }
    const auto format=info["format"].toMap()["format_name"].toString().split(',');
    QString ext;
    if (format.contains("mp3")) ext="mp3";
    else if (format.contains("flac")) ext="flac";
    else if (format.contains("ogg") && (codec=="opus" || codec=="vorbis" || codec=="flac")) ext=codec=="opus" ? "opus" : "ogg";
    else if (format.contains("wav")) ext="wav";
    else if (format.contains("aac")) ext="aac";
    else if (format.contains("mov") && QStringList{"aac","alac","mp3"}.contains(codec)) ext="m4a";
    if (code || !audio || video || ext.isEmpty()) { complete("Failed",{},"The downloaded file is not a supported audio file (web pages, video and streams are unsupported)"); return; }
    QString name=suggestedName;
    name.replace(QRegularExpression("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]"),"_");
    name=name.trimmed();
    name.remove(QRegularExpression("^[.\\s]+|[.\\s]+$"));
    if (name.isEmpty() || name=="." || name=="..") name="Audio download";
    const auto suffix=QFileInfo(name).suffix();
    if (!suffix.isEmpty()) name.chop(suffix.size()+1);
    name=name.left(120).trimmed();
    if (name.isEmpty()) name="Audio download";
    QString path=job["destination"].toString()+"/"+name+"."+ext;
    if (QFile::exists(path)) path=job["destination"].toString()+"/"+name+" "+job["id"].toString()+"."+ext;
    if (!QFile::rename(file.fileName(),path)) { complete("Failed",{},"Cannot save verified audio file without overwriting an existing file"); return; }
    complete("Completed",path);
  });
  probe->start(Online::tool("ffprobe"),{"-v","error","-show_format","-show_streams","-of","json",file.fileName()});
  QTimer::singleShot(15000,probe,[this] {
    if (!done && probe->state()!=QProcess::NotRunning) { probe->kill(); complete("Failed",{},"Audio verification timed out"); }
  });
}
void DirectTransfer::stop(const QString &state) {
  if (done) return;
  done=true; // Abort signals must not accidentally validate an incomplete file.
  if (reply) { reply->disconnect(this); reply->abort(); reply->deleteLater(); reply=nullptr; }
  if (probe) probe->kill();
  if (file.isOpen()) { file.flush(); file.close(); }
  if (state=="Cancelled") QDir(stage).removeRecursively();
  emit finished(state,{},{});
}
void DirectTransfer::complete(const QString &state,const QString &path,const QString &error) {
  if (done) return;
  done=true;
  file.close();
  if (state=="Completed" || state=="Cancelled") QDir(stage).removeRecursively();
  emit finished(state,path,error);
}
