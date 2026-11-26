#pragma once
#include "source.h"
#include <QFileInfo>
#include <QRegularExpression>
#include <QStringList>
#include <QUrl>
#include <QUrlQuery>
#include <QVariantMap>

// pull youtube/soundcloud source links back out of embedded tags or yt-dlp filenames
// lets older local downloads regain their videoId and covers without re-downloading
inline QString extractYouTubeVideoIdFromUrl(const QString &input) {
  static const QRegularExpression ytIdRe(QStringLiteral("^[A-Za-z0-9_-]{11}$"));
  const QUrl u(input.trimmed());
  if (!u.isValid()) return {};
  const QString host = u.host().toLower();
  if (host == QLatin1String("youtu.be")) {
    const QString seg = u.path().mid(1).section('/', 0, 0);
    return ytIdRe.match(seg).hasMatch() ? seg : QString();
  }
  if (host.contains(QLatin1String("youtube.com"))) {
    const QString v = QUrlQuery(u).queryItemValue(QStringLiteral("v"));
    if (ytIdRe.match(v).hasMatch()) return v;
    const QString path = u.path();
    static const QRegularExpression pathIdRe(QStringLiteral("^/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})(?:/|$)"));
    const auto m = pathIdRe.match(path);
    if (m.hasMatch()) return m.captured(1);
  }
  return {};
}

inline QVariantMap extractSourceMetadata(const QStringList &tagValues, const QString &fileName) {
  QString sourceUrl, videoId, sourceProvider, sourceItemId, uploader, videoTitle;

  static const QRegularExpression sillyCommentRe(
      QStringLiteral(R"(^(https?://\S+)\s*·\s*Uploader:\s*(.+?)\s*·\s*Original title:\s*(.+)$)"));
  static const QRegularExpression urlRe(QStringLiteral(R"((https?://[^\s<>"'\)\]]+))"));

  for (const QString &raw : tagValues) {
    const QString text = raw.trimmed();
    if (text.isEmpty()) continue;

    QString candidateUrl;
    const auto sillyMatch = sillyCommentRe.match(text);
    if (sillyMatch.hasMatch()) {
      candidateUrl = sillyMatch.captured(1).trimmed();
      const QString u = sillyMatch.captured(2).trimmed();
      const QString t = sillyMatch.captured(3).trimmed();
      if (uploader.isEmpty() && !u.isEmpty() && u.compare(QLatin1String("Unknown"), Qt::CaseInsensitive) != 0)
        uploader = u;
      if (videoTitle.isEmpty() && !t.isEmpty())
        videoTitle = t;
    } else {
      const auto um = urlRe.match(text);
      if (um.hasMatch()) candidateUrl = um.captured(1).trimmed();
    }

    if (candidateUrl.isEmpty() || !Source::http(candidateUrl)) continue;
    const QString prov = Source::providerForUrl(candidateUrl);
    if (prov == QLatin1String("youtube")) {
      const QString vid = extractYouTubeVideoIdFromUrl(candidateUrl);
      if (!vid.isEmpty()) {
        videoId = vid;
        sourceItemId = vid;
        sourceProvider = QStringLiteral("youtube");
        sourceUrl = QStringLiteral("https://www.youtube.com/watch?v=%1").arg(vid);
        break;
      }
    } else if (prov == QLatin1String("soundcloud") && sourceUrl.isEmpty()) {
      sourceUrl = candidateUrl;
      sourceProvider = QStringLiteral("soundcloud");
      sourceItemId = Source::directId(candidateUrl);
    }
  }

  // fallback to bracketed 11-char youtube id in yt-dlp / sillyplayer filenames like "Title [dQw4w9WgXcQ] jobid.opus"
  if (videoId.isEmpty() && sourceUrl.isEmpty() && !fileName.trimmed().isEmpty()) {
    const QString stem = QFileInfo(fileName).completeBaseName();
    static const QRegularExpression fileIdRe(
        QStringLiteral(R"(\[([A-Za-z0-9_-]{11})\](?:\s+[A-Za-z0-9-]{6,40})?$)"));
    const auto fm = fileIdRe.match(stem);
    if (fm.hasMatch()) {
      videoId = fm.captured(1);
      sourceItemId = videoId;
      sourceProvider = QStringLiteral("youtube");
      sourceUrl = QStringLiteral("https://www.youtube.com/watch?v=%1").arg(videoId);
    }
  }

  if (sourceUrl.isEmpty() && videoId.isEmpty()) return {};
  return {
      {"webpage_url", sourceUrl},
      {"sourceUrl", sourceUrl},
      {"videoId", videoId},
      {"id", sourceItemId},
      {"sourceItemId", sourceItemId},
      {"provider", sourceProvider},
      {"sourceProvider", sourceProvider},
      {"uploader", uploader},
      {"title", videoTitle},
      {"videoTitle", videoTitle},
  };
}
