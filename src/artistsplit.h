#pragma once
#include <QRegularExpression>
#include <QSet>
#include <QStringList>

// split multi-artist metadata strings into individual artists while keeping single artist/band names intact
inline QStringList splitArtists(const QString &raw) {
  QString text = raw.trimmed();
  if (text.isEmpty())
    return {};
  // unwrap parenthetical featuring credits like "Artist A (feat. Artist B)"
  static const QRegularExpression parenFeat(
      R"(\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring)\s+([^\)\]]+)[\)\]])",
      QRegularExpression::CaseInsensitiveOption);
  text.replace(parenFeat, ", \\1");
  static const QRegularExpression separator(
      R"(\s*(?:,|;|\s/\s|\s+(?:feat\.?|ft\.?|featuring)\s+)\s*)",
      QRegularExpression::CaseInsensitiveOption);
  const QStringList parts = text.split(separator, Qt::SkipEmptyParts);
  QStringList out;
  QSet<QString> seen;
  for (QString part : parts) {
    part = part.trimmed();
    if (part.isEmpty())
      continue;
    const QString key = part.toLower();
    if (seen.contains(key))
      continue;
    seen.insert(key);
    out << part;
  }
  return out;
}
