#pragma once
#include <QCryptographicHash>
#include <QRegularExpression>
#include <QUrlQuery>
#include <QVariantMap>

namespace Source {
inline bool http(const QString &input) {
  const QUrl u(input);
  return u.isValid() && !u.host().isEmpty() && u.userInfo().isEmpty() && (u.scheme()=="http" || u.scheme()=="https");
}
inline QString providerForUrl(const QString &input) {
  const auto host=QUrl(input).host().toLower();
  if (QStringList{"youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be"}.contains(host)) return "youtube";
  if (QStringList{"soundcloud.com","www.soundcloud.com","m.soundcloud.com","api.soundcloud.com","api-v2.soundcloud.com","on.soundcloud.com","snd.sc"}.contains(host)) return "soundcloud";
  return "unknown";
}
inline QString provider(const QVariantMap &d) {
  auto value=d.value("provider").toString();
  if (QStringList{"youtube","soundcloud","direct","unknown"}.contains(value)) return value;
  value=d.value("extractor_key",d.value("ie_key",d.value("extractor"))).toString().toLower();
  if (value.startsWith("soundcloud")) return "soundcloud";
  if (value.startsWith("youtube")) return "youtube";
  return providerForUrl(d.value("webpage_url",d.value("url")).toString());
}
inline QString directId(const QString &input) {
  QUrl url(input); url.setFragment({});
  return QString::fromLatin1(QCryptographicHash::hash(url.toEncoded(),QCryptographicHash::Sha256).toHex());
}
inline QString identity(const QVariantMap &d) {
  return provider(d)+":"+d.value("id",d.value("sourceItemId")).toString();
}
inline QString name(const QString &provider) {
  return provider=="youtube" ? "YouTube" : provider=="soundcloud" ? "SoundCloud" : provider=="direct" ? "Direct Link" : "Online";
}
}
