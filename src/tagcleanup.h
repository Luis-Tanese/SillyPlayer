#pragma once
#include <QRegularExpression>
#include <QString>

// strips common video/audio suffixes like "(Official Audio)" or "[Lyrics]" from titles
inline QString cleanSongTitle(const QString &raw) {
  const QString original = raw.trimmed();
  if (original.isEmpty())
    return original;

  QString s = original;
  static const QRegularExpression bracketJunk(
      R"(\s*[\(\[]\s*(?:(?:official\s+)?(?:music\s+|lyric\s+|lyrics\s+|hd\s+|hq\s+|4k\s+|full\s+)?(?:video|audio|visualizer|mv|m/v|clip)|(?:with\s+)?lyrics?|audio\s+only|full\s+audio|hd|hq|4k|1080p|720p|mv|m/v)\s*[\)\]])",
      QRegularExpression::CaseInsensitiveOption);
  s.replace(bracketJunk, "");

  static const QRegularExpression trailingJunk(
      R"(\s*(?:\||//|-|–|—)\s*(?:official\s+)?(?:(?:music|lyric|lyrics|hd|hq|4k|full)\s+)?(?:video|audio|visualizer|lyrics?|mv|m/v)\s*$)",
      QRegularExpression::CaseInsensitiveOption);
  s.replace(trailingJunk, "");

  static const QRegularExpression multiSpace(R"(\s{2,})");
  s.replace(multiSpace, " ");
  s = s.trimmed();
  static const QRegularExpression trailingSep(R"(\s*[\|\-–—/]+\s*$)");
  s.replace(trailingSep, "");
  s = s.trimmed();

  return s.isEmpty() ? original : s;
}
