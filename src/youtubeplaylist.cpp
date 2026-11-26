#include "youtubeplaylist.h"
#include "online.h"
#include "processutils.h"
#include "source.h"
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QUrlQuery>

YouTubePlaylist::YouTubePlaylist(Online *online):QObject(online),owner(online) {}
YouTubePlaylist::~YouTubePlaylist() { shutdown(); }
QString YouTubePlaylist::canonicalUrl(const QString &input) {
  const QUrl url(input.trimmed());
  if(!Source::http(input.trimmed()) || Source::providerForUrl(input.trimmed())!="youtube" ||
     (url.port()!=-1 && url.port()!=(url.scheme()=="https"?443:80))) return {};
  const QUrlQuery query(url); const auto list=query.queryItemValue("list");
  if(!QRegularExpression("^[A-Za-z0-9_-]{2,200}$").match(list).hasMatch()) return {};
  QString video=url.host().toLower()=="youtu.be" ? url.path().section('/',1,1) : query.queryItemValue("v");
  const bool seed=QRegularExpression("^[A-Za-z0-9_-]{11}$").match(video).hasMatch();
  QString path=url.path();
  while(path.size()>1 && path.endsWith('/')) path.chop(1);
  if(!QStringList{"/watch","/playlist"}.contains(path) && url.host().toLower()!="youtu.be") return {};
  QUrl canonical(seed && list.startsWith("RD")?"https://www.youtube.com/watch":"https://www.youtube.com/playlist");
  QUrlQuery out; if(seed && list.startsWith("RD")) out.addQueryItem("v",video); out.addQueryItem("list",list); canonical.setQuery(out);
  return canonical.toString(QUrl::FullyEncoded);
}
QVariantList YouTubePlaylist::entries() const {
  QVariantList result;
  for(auto v:rows) { auto item=v.toMap(); item["selected"]=selected.contains(item["key"].toString()); result<<item; }
  return result;
}
bool YouTubePlaylist::allSelected() const {
  int count=0;
  for(auto v:rows) if(v.toMap()["selectable"].toBool()) ++count;
  return count>0 && count==selected.size();
}
void YouTubePlaylist::show(const QString &input) {
  if(!input.trimmed().isEmpty()) load(input);
  emit opened();
}
void YouTubePlaylist::load(const QString &input) {
  cancel(); rows.clear(); selected.clear(); offset=0; more=false; name.clear();
  source=canonicalUrl(input); isMix=QUrlQuery(QUrl(source)).queryItemValue("list").startsWith("RD");
  if(source.isEmpty()) { status="Paste a YouTube playlist or mix link containing list=…"; emit changed(); return; }
  status.clear(); fetch();
}
void YouTubePlaylist::loadMore() { if(!loading() && !source.isEmpty() && (more || rows.isEmpty())) fetch(); }
void YouTubePlaylist::fetch() {
  auto p=new QProcess(this); process=p;
  configureToolProcess(p);
  connect(p,&QProcess::readyReadStandardOutput,this,[this,p] {
    auto buffer=p->property("output").toByteArray()+p->readAllStandardOutput();
    if(buffer.size()>16*1024*1024) { p->setProperty("tooLarge",true); terminateGroup(p,true); buffer.clear(); }
    p->setProperty("output",buffer);
  });
  connect(p,&QProcess::readyReadStandardError,this,[p] {
    p->setProperty("errors",(p->property("errors").toByteArray()+p->readAllStandardError()).right(2000));
  });
  connect(p,&QProcess::errorOccurred,this,[this,p](QProcess::ProcessError error) {
    if(error!=QProcess::FailedToStart || process!=p) return;
    process=nullptr; status="Could not start the bundled YouTube downloader"; p->deleteLater(); emit changed();
  });
  connect(p,qOverload<int,QProcess::ExitStatus>(&QProcess::finished),this,[this,p](int code,QProcess::ExitStatus) {
    if(process!=p) return;
    process=nullptr;
    const auto output=p->property("output").toByteArray()+p->readAllStandardOutput();
    const auto doc=QJsonDocument::fromJson(output);
    const auto data=doc.object(); const auto page=data.value("entries").toArray();
    const auto error=QString::fromUtf8(p->property("errors").toByteArray()+p->readAllStandardError()).trimmed().right(1200);
    if(p->property("tooLarge").toBool() || output.size()>16*1024*1024) status="Playlist response exceeded the safe page size";
    else if(p->property("timedOut").toBool()) status="Playlist lookup timed out. Retry loading this page.";
    else if(!doc.isObject() || !data.contains("entries") || (code && page.isEmpty())) status=error.isEmpty()?"YouTube did not return an accessible playlist or mix":error;
    else {
      const bool initial=offset==0;
      if(!data.value("title").toString().isEmpty()) name=data.value("title").toString();
      int count=0;
      for(const auto &v:page) {
        if(count>=50) break;
        const int position=offset+(++count);
        auto raw=v.toObject().toVariantMap(); raw["provider"]="youtube";
        const auto normalized=Online::normalizeResult(raw);
        const QString id=raw["id"].toString(),title=raw.value("title","Unavailable entry").toString(),availability=raw["availability"].toString();
        const bool valid=QRegularExpression("^[A-Za-z0-9_-]{11}$").match(id).hasMatch();
        const bool restricted=QStringList{"private","premium_only","subscriber_only","needs_auth","unavailable"}.contains(availability) || title.contains("[Private video]",Qt::CaseInsensitive) || title.contains("[Deleted video]",Qt::CaseInsensitive);
        const bool live=raw["is_live"].toBool() || QStringList{"is_live","is_upcoming"}.contains(raw["live_status"].toString());
        const bool selectable=valid && !restricted && !live;
        const QString key=QString::number(position)+":"+id;
        rows<<QVariantMap{{"key",key},{"id",id},{"provider","youtube"},{"title",title.isEmpty()?"Unavailable entry":title},
          {"uploader",raw.value("uploader",raw.value("channel"))},{"duration",raw["duration"]},{"thumbnail",normalized["thumbnail"]},{"thumbnailCandidates",normalized["thumbnailCandidates"]},
          {"webpage_url",valid?"https://www.youtube.com/watch?v="+id:QString()},
          {"position",position},{"selectable",selectable},{"reason",selectable?QString():live?"Live or upcoming audio is unavailable for this download":restricted?"Private, deleted or access-required":"Unavailable entry"}};
        if(initial && selectable) selected.insert(key);
      }
      offset+=count; more=page.size()>50;
      status=code ? (error.isEmpty()?"Some entries could not be listed":error) : rows.isEmpty()?"No accessible entries were returned":QString();
    }
    p->deleteLater(); emit changed();
  });
  auto args=Online::baseArgs(); args.removeAll("--no-playlist");
  args<<"--yes-playlist"<<"--flat-playlist"<<"--lazy-playlist"<<"--ignore-errors"<<"--dump-single-json"
      <<"--playlist-start"<<QString::number(offset+1)<<"--playlist-end"<<QString::number(offset+51)<<source;
  status="Loading playlist entries…"; emit changed();
  p->start(Online::tool("yt-dlp"),args); deadline(p,60000);
}
void YouTubePlaylist::toggle(const QString &key) {
  for(auto v:rows) { const auto item=v.toMap(); if(item["key"]==key && item["selectable"].toBool()) { if(selected.contains(key)) selected.remove(key); else selected.insert(key); emit changed(); return; } }
}
void YouTubePlaylist::toggleAll() {
  if(allSelected()) selected.clear();
  else for(auto v:rows) { const auto item=v.toMap(); if(item["selectable"].toBool()) selected.insert(item["key"].toString()); }
  emit changed();
}
QVariantMap YouTubePlaylist::download(const QString &kind,const QString &destination,bool again,const QString &playlistName) {
  if(loading()) return {{"error","Wait for playlist loading to finish"}};
  QVariantList chosen;
  for(auto v:rows) if(selected.contains(v.toMap()["key"].toString())) chosen<<v;
  if(chosen.isEmpty()) return {{"error","Select at least one song"}};
  auto result=owner->enqueueBatch(chosen,kind,destination,again,playlistName);
  if(result.contains("error")) status=result["error"].toString();
  else { selected.clear(); status=QString("%1 downloads queued · %2 duplicate or existing items skipped").arg(result["queued"].toInt()).arg(result["skipped"].toInt()); }
  if(!result["playlistId"].toString().isEmpty()) status="Created playlist: "+result["playlistName"].toString()+" · "+status;
  emit changed(); return result;
}
void YouTubePlaylist::dismiss() {
  cancel(); rows.clear(); selected.clear(); offset=0; more=false;
  status=source.isEmpty()?QString():QString("Load the link to choose songs"); emit changed();
}
void YouTubePlaylist::cancel() {
  if(process) { auto p=process; process=nullptr; discardProcess(p,this); status="Loading canceled. Retry to continue."; }
  emit changed();
}
void YouTubePlaylist::shutdown() {
  if(!process) return;
  auto p=process; process=nullptr; disconnect(p,nullptr,this,nullptr); terminateGroup(p,true); p->waitForFinished(1500); delete p;
}
