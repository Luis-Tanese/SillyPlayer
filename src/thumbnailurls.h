#pragma once
#include <QStringList>
#include <QRegularExpression>

inline QStringList thumbnailCandidates(QString url, const QString &youtubeId = {}) {
  url=url.trimmed();
  if(url.startsWith("//"))url.prepend("https:");
  static const QRegularExpression youtube("https?://(?:i[0-9]?\\.ytimg\\.com|img\\.youtube\\.com)/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/");
  static const QRegularExpression validId("^[A-Za-z0-9_-]{11}$");
  const auto match=youtube.match(url);
  const auto id=match.hasMatch()?match.captured(1):youtubeId;
  QStringList result;
  if(validId.match(id).hasMatch()) {
    const auto base="https://i.ytimg.com/vi/"+id;
    result<<base+"/hqdefault.jpg";
    if(url.startsWith("https://") || url.startsWith("http://")) result<<url;
    result<<base+"/mqdefault.jpg"<<base+"/default.jpg";
  } else if(QRegularExpression("https?://[^/]*sndcdn\\.com/").match(url).hasMatch()) {
    if(url.startsWith("http:"))url.replace(0,5,"https:");
    auto sized=url;
    sized.replace(QRegularExpression("-(?:original|t[0-9]+x[0-9]+|large|crop|small)(?=\\.[a-zA-Z]+(?:\\?|$))"),"-t300x300");
    result<<sized<<url<<QString(sized).replace("-t300x300.","-large.");
  } else if(!url.isEmpty())result<<url;
  result.removeDuplicates();return result;
}
