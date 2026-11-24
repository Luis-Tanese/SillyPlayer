#pragma once
#include <QDir>
#include <QFileInfo>
#include <QHash>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QVariantList>
#include <QVariantMap>
#include <algorithm>

inline QString normalizeFolderPath(const QString &raw) {
  QString p = QDir::cleanPath(QDir::fromNativeSeparators(raw.trimmed()));
  while (p.size() > 1 && p.endsWith('/') && !(p.size() == 3 && p[1] == ':'))
    p.chop(1);
  return p;
}

inline QString parentFolderPath(const QString &filePath) {
  const QString cleaned = normalizeFolderPath(filePath);
  const int slash = cleaned.lastIndexOf('/');
  if (slash < 0) return {};
  if (slash == 0) return QStringLiteral("/");
  if (slash == 2 && cleaned[1] == ':') return cleaned.left(3);
  return cleaned.left(slash);
}

inline QString folderDisplayName(const QString &dirPath) {
  const QString cleaned = normalizeFolderPath(dirPath);
  if (cleaned.isEmpty() || cleaned == QStringLiteral("/")) return cleaned;
  const int slash = cleaned.lastIndexOf('/');
  return slash >= 0 ? cleaned.mid(slash + 1) : cleaned;
}

inline bool pathIsWithinFolder(const QString &dirPath, const QString &rootPath) {
  if (rootPath.isEmpty()) return false;
  if (dirPath == rootPath) return true;
  if (rootPath.endsWith('/')) return dirPath.startsWith(rootPath);
  return dirPath.startsWith(rootPath + '/');
}

// builds one level of the local music folder tree with breadcrumbs and direct songs
inline QVariantMap buildFolderView(const QVariantList &tracks,
                                   const QVariantList &roots,
                                   const QString &musicDir,
                                   const QString &requestedPath) {
  const QString current = normalizeFolderPath(requestedPath);

  QStringList candidateRoots;
  for (const auto &v : roots) {
    const QString p = normalizeFolderPath(v.toMap().value("path").toString());
    if (!p.isEmpty() && !candidateRoots.contains(p)) candidateRoots << p;
  }
  const QString cleanMusicDir = normalizeFolderPath(musicDir);

  struct IndexedTrack {
    QVariantMap map;
    QString dir;
    QString art;
  };
  QVector<IndexedTrack> items;
  items.reserve(tracks.size());
  for (const auto &v : tracks) {
    const auto m = v.toMap();
    const QString p = m.value("path").toString();
    if (p.isEmpty() || p.startsWith(QStringLiteral("sync://"))) continue;
    const QString dir = parentFolderPath(p);
    if (dir.isEmpty()) continue;
    items.append({m, dir, m.value("art").toString()});
  }

  // make sure standalone imported tracks or downloads outside watched roots still show up
  for (const auto &item : items) {
    bool covered = false;
    for (const auto &r : candidateRoots) {
      if (pathIsWithinFolder(item.dir, r)) { covered = true; break; }
    }
    if (!covered && !cleanMusicDir.isEmpty() && pathIsWithinFolder(item.dir, cleanMusicDir)) {
      candidateRoots << cleanMusicDir;
      covered = true;
    }
    if (!covered && !candidateRoots.contains(item.dir)) {
      candidateRoots << item.dir;
    }
  }

  // keep only top level roots when one watched folder sits inside another
  std::sort(candidateRoots.begin(), candidateRoots.end(), [](const QString &a, const QString &b) {
    return a.size() < b.size();
  });
  QStringList topRoots;
  for (const auto &r : candidateRoots) {
    bool nested = false;
    for (const auto &parent : topRoots) {
      if (pathIsWithinFolder(r, parent)) { nested = true; break; }
    }
    if (!nested) topRoots << r;
  }
  std::sort(topRoots.begin(), topRoots.end(), [](const QString &a, const QString &b) {
    return folderDisplayName(a).compare(folderDisplayName(b), Qt::CaseInsensitive) < 0;
  });

  auto summarizeFolder = [&](const QString &folderPath) {
    int trackCount = 0;
    QSet<QString> directSubs;
    QString cover;
    for (const auto &item : items) {
      if (!pathIsWithinFolder(item.dir, folderPath)) continue;
      ++trackCount;
      if (cover.isEmpty() && !item.art.isEmpty()) cover = item.art;
      if (item.dir != folderPath) {
        const QString rel = folderPath.endsWith('/')
            ? item.dir.mid(folderPath.size())
            : item.dir.mid(folderPath.size() + 1);
        const QString firstSeg = rel.section('/', 0, 0);
        if (!firstSeg.isEmpty()) directSubs.insert(firstSeg);
      }
    }
    return QVariantMap{
      {"id", "folder:" + folderPath},
      {"path", folderPath},
      {"name", folderDisplayName(folderPath)},
      {"trackCount", trackCount},
      {"subfolderCount", directSubs.size()},
      {"cover", cover},
    };
  };

  if (current.isEmpty()) {
    QVariantList folderRows;
    for (const auto &r : topRoots) {
      const auto info = summarizeFolder(r);
      if (info.value("trackCount").toInt() > 0 || roots.size() > 0)
        folderRows << info;
    }
    QVariantList allTracks;
    allTracks.reserve(items.size());
    for (const auto &item : items) allTracks << item.map;
    return {
      {"path", QString()},
      {"name", QStringLiteral("Folders")},
      {"parentPath", QString()},
      {"breadcrumbs", QVariantList{}},
      {"folders", folderRows},
      {"tracks", QVariantList{}},
      {"allTracks", allTracks},
    };
  }

  QString matchedRoot;
  for (const auto &r : topRoots) {
    if (pathIsWithinFolder(current, r)) { matchedRoot = r; break; }
  }

  QVariantList breadcrumbs;
  breadcrumbs << QVariantMap{{"name", QStringLiteral("Folders")}, {"path", QString()}};
  if (!matchedRoot.isEmpty()) {
    breadcrumbs << QVariantMap{{"name", folderDisplayName(matchedRoot)}, {"path", matchedRoot}};
    if (current != matchedRoot) {
      const QString rel = matchedRoot.endsWith('/')
          ? current.mid(matchedRoot.size())
          : current.mid(matchedRoot.size() + 1);
      QString cursor = matchedRoot;
      for (const auto &seg : rel.split('/', Qt::SkipEmptyParts)) {
        cursor = cursor.endsWith('/') ? (cursor + seg) : (cursor + '/' + seg);
        breadcrumbs << QVariantMap{{"name", seg}, {"path", cursor}};
      }
    }
  } else {
    breadcrumbs << QVariantMap{{"name", folderDisplayName(current)}, {"path", current}};
  }
  const QString parentPath = breadcrumbs.size() >= 2
      ? breadcrumbs[breadcrumbs.size() - 2].toMap().value("path").toString()
      : QString();

  struct ChildBucket {
    QString path;
    QString name;
    int trackCount = 0;
    QSet<QString> nestedSubs;
    QString cover;
  };
  QHash<QString, ChildBucket> children;
  QVariantList directTracks;
  QVariantList allTracks;

  for (const auto &item : items) {
    if (!pathIsWithinFolder(item.dir, current)) continue;
    allTracks << item.map;
    if (item.dir == current) {
      directTracks << item.map;
    } else {
      const QString rel = current.endsWith('/')
          ? item.dir.mid(current.size())
          : item.dir.mid(current.size() + 1);
      const QString seg = rel.section('/', 0, 0);
      if (seg.isEmpty()) continue;
      const QString childPath = current.endsWith('/') ? (current + seg) : (current + '/' + seg);
      auto &bucket = children[childPath];
      if (bucket.path.isEmpty()) {
        bucket.path = childPath;
        bucket.name = seg;
      }
      bucket.trackCount++;
      if (bucket.cover.isEmpty() && !item.art.isEmpty()) bucket.cover = item.art;
      const QString rest = rel.section('/', 1);
      if (!rest.isEmpty()) bucket.nestedSubs.insert(rest.section('/', 0, 0));
    }
  }

  QVariantList folderRows;
  folderRows.reserve(children.size());
  for (auto it = children.cbegin(); it != children.cend(); ++it) {
    folderRows << QVariantMap{
      {"id", "folder:" + it.value().path},
      {"path", it.value().path},
      {"name", it.value().name},
      {"trackCount", it.value().trackCount},
      {"subfolderCount", it.value().nestedSubs.size()},
      {"cover", it.value().cover},
    };
  }
  std::sort(folderRows.begin(), folderRows.end(), [](const QVariant &a, const QVariant &b) {
    return a.toMap()["name"].toString().compare(b.toMap()["name"].toString(), Qt::CaseInsensitive) < 0;
  });
  std::sort(directTracks.begin(), directTracks.end(), [](const QVariant &a, const QVariant &b) {
    return a.toMap()["title"].toString().compare(b.toMap()["title"].toString(), Qt::CaseInsensitive) < 0;
  });

  return {
    {"path", current},
    {"name", folderDisplayName(current)},
    {"parentPath", parentPath},
    {"breadcrumbs", breadcrumbs},
    {"folders", folderRows},
    {"tracks", directTracks},
    {"allTracks", allTracks},
  };
}
