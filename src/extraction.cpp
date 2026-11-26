#include "extraction.h"
#include "online.h"
#include "source.h"
#include "processutils.h"
#include <QDateTime>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QUrlQuery>

namespace {
// account for decoded UTF-16 strings and QVariant/container nodes, rather than the compact JSON size against an in-memory cache budget otherwise songs won't transfer properly
int memoryCost(const QVariant &value,int depth=0) {
  constexpr qint64 cap=2*1024*1024+1;
  qint64 cost=sizeof(QVariant);
  if(depth>64) return cap;
  if(value.typeId()==QMetaType::QVariantMap) {
    const auto map=value.toMap();
    for(auto it=map.cbegin();it!=map.cend();++it) {
      cost+=64+it.key().size()*2+memoryCost(it.value(),depth+1);
      if(cost>=cap) return cap;
    }
  } else if(value.typeId()==QMetaType::QVariantList || value.typeId()==QMetaType::QStringList) {
    for(const auto &entry:value.toList()) {cost+=memoryCost(entry,depth+1);if(cost>=cap)return cap;}
  } else if(value.typeId()==QMetaType::QString) cost+=32+value.toString().size()*2;
  else if(value.typeId()==QMetaType::QByteArray) cost+=32+value.toByteArray().size();
  return int(qMin(cap,cost));
}
}
ExtractionService::ExtractionService(QObject *parent) : QObject(parent) {
  clock.start(); expiry.setInterval(5000);
  // run expiry timer only while cached extractions exist
  connect(&expiry,&QTimer::timeout,this,&ExtractionService::pruneExpired);
}
void ExtractionService::pruneExpired() {
  const auto now=QDateTime::currentMSecsSinceEpoch();
  for(const auto &key:cache.keys()) {auto entry=cache.object(key);if(entry && (entry->until<=now || clock.elapsed()-entry->inserted>=120000))cache.remove(key);}
  if (cache.isEmpty() && expiry.isActive()) expiry.stop();
}
ExtractionService::~ExtractionService() { shutdown(); }
QString ExtractionService::key(const QString &input) {
  const QUrl url(input);
  const QString host = url.host().toLower();
  QString id;
  if (host == "youtu.be") id = url.path().section('/', 1, 1);
  else if (QStringList{"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"}.contains(host)) {
    if (url.path() == "/watch") id = QUrlQuery(url).queryItemValue("v");
    else if (QStringList{"shorts", "live", "embed"}.contains(url.path().section('/', 1, 1)))
      id = url.path().section('/', 2, 2);
  }
  static const QRegularExpression videoId("^[A-Za-z0-9_-]{11}$");
  const bool standardPort = url.port() == -1 || (url.scheme() == "https" && url.port() == 443) || (url.scheme() == "http" && url.port() == 80);
  if ((url.scheme() == "https" || url.scheme() == "http") && standardPort && url.userInfo().isEmpty() && videoId.match(id).hasMatch())
    return "youtube:" + id;
  if (Source::providerForUrl(input)=="soundcloud") {
    QUrl canonical(input); canonical.setFragment({});
    return "soundcloud:"+canonical.toString(QUrl::FullyEncoded);
  }
  return input; // do not collapse arbitrary hosts, query strings or credentials
}
qint64 ExtractionService::validUntil(const QVariantMap &d, qint64 now) {
  if (d.value("is_live").toBool() || d.value("live_status").toString() == "is_live") return 0;
  qint64 until = now + 120000;
  bool found = false;
  auto inspect = [&](const QString &value) {
    if (value.isEmpty()) return true;
    found = true;
    const QUrl url(value);
    auto expiry = QUrlQuery(url).queryItemValue("expire");
    if (expiry.isEmpty()) {
      // DASH/HLS manifests encode the same expiry as /expire/<unix-seconds>/
      static const QRegularExpression pathExpiry("/expire/([0-9]+)(?:/|$)");
      const auto match = pathExpiry.match(url.path());
      if (match.hasMatch()) expiry = match.captured(1);
    }
    if (!expiry.isEmpty()) {
      bool ok = false;
      auto seconds = expiry.toLongLong(&ok);
      if (!ok || seconds <= now / 1000 + 60 || seconds > 32503680000LL) return false;
      until = qMin(until, seconds * 1000 - 60000);
    } else if (url.host().endsWith(".googlevideo.com") || url.host() == "googlevideo.com") {
      return false; // a signed youtube stream without a known expiry is unsafe to reuse
    } else if (url.scheme() == "http" || url.scheme() == "https") {
      until = qMin(until, now + 30000); // brief reuse for public, unsigned media
    }
    return true;
  };
  if (!inspect(d.value("url").toString()) || !inspect(d.value("manifest_url").toString())) return 0;
  for (const auto &field : {"formats", "requested_formats"})
    for (const auto &v : d.value(field).toList()) {
      const auto f = v.toMap();
      if (!inspect(f.value("url").toString()) || !inspect(f.value("manifest_url").toString())) return 0;
    }
  return found ? until : 0;
}
QVariantMap ExtractionService::cached(const QString &url) {
  pruneExpired();
  const auto k = key(url);
  auto entry = cache.object(k);
  if (!entry) return {};
  if (entry->until <= QDateTime::currentMSecsSinceEpoch() || clock.elapsed() - entry->inserted >= 120000) {
    cache.remove(k);
    return {};
  }
  return entry->details;
}
void ExtractionService::invalidate(const QString &url) { cache.remove(key(url)); }
quint64 ExtractionService::request(const QString &url, Completion complete, bool verifyFormats) {
  if (closing) return 0;
  const auto identity = key(url);
  const auto k = verifyFormats ? "verified:" + identity : identity;
  const auto token = ++nextToken;
  consumers.insert(token, {k, std::move(complete)});
  const auto inputUrl=QUrl(url);
  const auto parts=inputUrl.path().split('/',Qt::SkipEmptyParts);
  if (QStringList{"soundcloud.com","www.soundcloud.com","m.soundcloud.com"}.contains(inputUrl.host().toLower()) &&
      (parts.size()!=2 || parts.contains("sets"))) {
    QTimer::singleShot(0,this,[this,token] { deliver(token,{},"Use an individual SoundCloud track link; profiles and collections are unsupported",false); });
    return token;
  }
  const auto hit = verifyFormats ? QVariantMap{} : cached(url);
  if (!hit.isEmpty()) {
    // always async: cancellation and state clearing also apply to cache hits
    QTimer::singleShot(0, this, [this, token, hit] { deliver(token, hit, {}, true); });
    return token;
  }
  auto existing = pending.find(k);
  if (existing != pending.end()) {
    existing->consumers.insert(token);
    return token;
  }
  auto p = new QProcess(this);
  configureToolProcess(p);
  pending.insert(k, {p, {token}});
  connect(p, &QProcess::errorOccurred, this, [this, k, p](auto error) {
    if (error == QProcess::FailedToStart) {
      finish(k, {}, "yt-dlp could not start. Check bundled tools.");
      p->deleteLater();
    }
  });
  connect(p, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this,
          [this, k, identity, p](int code, QProcess::ExitStatus) {
    auto doc = QJsonDocument::fromJson(p->readAllStandardOutput());
    QString error;
    if (code || !doc.isObject()) {
      error = p->property("timedOut").toBool() ? "Video lookup timed out"
        : QString::fromUtf8(p->readAllStandardError()).trimmed().right(1500);
      if (error.isEmpty()) error = "Video lookup did not return usable information";
    }
    auto details = Online::normalizeResult(doc.object().toVariantMap());
    if (error.isEmpty() && identity.startsWith("youtube:") && details.value("id").toString() != identity.mid(8))
      error = "Video lookup returned information for a different video";
    if (details.value("_type").toString()=="playlist" || details.contains("entries")) error="Use an individual track link; collections are not supported";
    if (Source::provider(details)=="soundcloud") {
      const auto format=details.value("format_id").toString();
      if (format.contains("preview",Qt::CaseInsensitive)) error="Only a SoundCloud preview is available; the full track requires access";
    }
    finish(k, error.isEmpty() ? details : QVariantMap{}, error);
    p->deleteLater();
  });
  auto args = Online::baseArgs();
  if (verifyFormats) args << "--check-formats";
  args << "--dump-single-json" << "--skip-download" << "-f" << "bestaudio/best" << url;
  p->start(Online::tool("yt-dlp"), args);
  deadline(p, 60000);
  return token;
}
void ExtractionService::deliver(quint64 token, const QVariantMap &d, const QString &error, bool hit) {
  auto it = consumers.find(token);
  if (it == consumers.end()) return;
  auto complete = std::move(it->complete);
  consumers.erase(it);
  complete(d, error, hit);
}
void ExtractionService::finish(const QString &k, const QVariantMap &d, const QString &error) {
  auto operation = pending.take(k);
  if (error.isEmpty()) {
    auto until = validUntil(d, QDateTime::currentMSecsSinceEpoch());
    pruneExpired();
    const auto bytes = memoryCost(d);
    if (until > 0 && bytes <= 2 * 1024 * 1024) {
      if (cache.size() >= 32) cache.remove(cache.keys().last());
      cache.insert(k.startsWith("verified:") ? k.mid(9) : k,
                   new Cached{d, until, clock.elapsed()}, qMax(1, int(bytes)));
      if (!expiry.isActive()) expiry.start();
    }
  }
  for (auto token : operation.consumers) deliver(token, d, error, false);
}
void ExtractionService::cancel(quint64 token) {
  auto it = consumers.find(token);
  if (it == consumers.end()) return;
  const auto k = it->key;
  consumers.erase(it);
  auto operation = pending.find(k);
  if (operation == pending.end()) return;
  operation->consumers.remove(token);
  if (operation->consumers.isEmpty()) {
    auto p = operation->process;
    pending.erase(operation);
    discardProcess(p, this);
  }
}
void ExtractionService::shutdown() {
  if (closing) return;
  closing = true;
  expiry.stop();
  consumers.clear();
  pending.clear();
  cache.clear();
  for (auto p : findChildren<QProcess *>()) {
    disconnect(p, nullptr, this, nullptr);
    if (p->state() == QProcess::Starting) p->waitForStarted(500);
    if (p->state() != QProcess::NotRunning) {
      terminateGroup(p, true);
      p->waitForFinished(1500);
    }
  }
}
