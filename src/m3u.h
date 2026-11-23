#pragma once
#include <QDir>
#include <QFileInfo>
#include <QString>
#include <QStringList>
#include <QUrl>
#include <QVariantList>
#include <QVariantMap>
#include <cmath>

struct M3uEntry {
  QString path;
  QString title;
  QString artist;
  int duration = -1;
};

inline QString formatM3u(const QVariantList &tracks) {
  QString out = "#EXTM3U\n";
  for (const auto &v : tracks) {
    const auto t = v.toMap();
    const QString path = t.value("path").toString().trimmed();
    if (path.isEmpty())
      continue;
    const int duration = int(std::lround(t.value("duration").toDouble()));
    const QString artist = t.value("artist").toString().trimmed();
    const QString title = t.value("title").toString().trimmed();
    QString display = title;
    if (!artist.isEmpty() && artist.compare("Unknown artist", Qt::CaseInsensitive) != 0)
      display = title.isEmpty() ? artist : artist + " - " + title;
    if (display.isEmpty())
      display = QFileInfo(path).completeBaseName();
    out += QString("#EXTINF:%1,%2\n").arg(duration > 0 ? duration : -1).arg(display);
    out += path + "\n";
  }
  return out;
}

inline QList<M3uEntry> parseM3u(const QString &content, const QString &baseDir = {}) {
  QString text = content;
  if (text.startsWith(QChar(0xFEFF)))
    text.remove(0, 1);
  QList<M3uEntry> entries;
  QString pendingTitle;
  QString pendingArtist;
  int pendingDuration = -1;

  const QStringList lines = text.split('\n');
  for (QString rawLine : lines) {
    QString line = rawLine.trimmed();
    if (line.isEmpty())
      continue;
    if (line.startsWith("#EXTINF:", Qt::CaseInsensitive)) {
      const QString rest = line.mid(8);
      const int comma = rest.indexOf(',');
      if (comma >= 0) {
        bool ok = false;
        const int dur = rest.left(comma).trimmed().toInt(&ok);
        pendingDuration = ok ? dur : -1;
        const QString display = rest.mid(comma + 1).trimmed();
        const int dash = display.indexOf(" - ");
        if (dash > 0) {
          pendingArtist = display.left(dash).trimmed();
          pendingTitle = display.mid(dash + 3).trimmed();
        } else {
          pendingArtist.clear();
          pendingTitle = display;
        }
      }
      continue;
    }
    if (line.startsWith('#'))
      continue;

    QString resolved = line;
    if (resolved.startsWith("file://", Qt::CaseInsensitive)) {
      const QUrl url(resolved);
      if (url.isLocalFile())
        resolved = url.toLocalFile();
    } else if (!baseDir.isEmpty() && QDir::isRelativePath(resolved) &&
               !resolved.contains("://")) {
      resolved = QDir::cleanPath(QDir(baseDir).filePath(resolved));
    }

    M3uEntry entry;
    entry.path = resolved;
    entry.title = pendingTitle;
    entry.artist = pendingArtist;
    entry.duration = pendingDuration;
    entries << entry;

    pendingTitle.clear();
    pendingArtist.clear();
    pendingDuration = -1;
  }
  return entries;
}
