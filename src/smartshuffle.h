#pragma once
#include "artistsplit.h"
#include <QDateTime>
#include <QSet>
#include <QString>
#include <QVector>
#include <algorithm>
#include <cmath>
#include <random>

struct SmartShuffleItem {
  int index = 0;
  QString artist;
  qint64 lastPlayed = 0;
};

inline QSet<QString> normalizedArtistTokens(const QString &raw) {
  QSet<QString> out;
  for (const auto &part : splitArtists(raw)) {
    const QString key = part.trimmed().toLower();
    if (!key.isEmpty() && key != QStringLiteral("unknown artist"))
      out.insert(key);
  }
  return out;
}

inline bool artistSetsOverlap(const QSet<QString> &a, const QSet<QString> &b) {
  if (a.isEmpty() || b.isEmpty()) return false;
  for (const auto &token : a)
    if (b.contains(token)) return true;
  return false;
}

// picks less recently heard songs first while avoiding back to back same artist
template <typename Rng>
QVector<int> smartShuffleOrder(const QVector<SmartShuffleItem> &items,
                               const QString &currentArtist,
                               Rng &rng,
                               qint64 nowMs = QDateTime::currentMSecsSinceEpoch()) {
  struct PoolEntry {
    int index;
    QSet<QString> artists;
    double weight;
  };
  QVector<PoolEntry> remaining;
  remaining.reserve(items.size());
  for (const auto &item : items) {
    double weight = 3.0;
    if (item.lastPlayed > 0) {
      const double ageDays = std::max(0.0, double(nowMs - item.lastPlayed) / 86400000.0);
      weight = 1.0 + 1.8 * std::min(1.0, ageDays / 7.0);
    }
    remaining.append({item.index, normalizedArtistTokens(item.artist), weight});
  }

  QVector<int> ordered;
  ordered.reserve(items.size());
  QSet<QString> prev1 = normalizedArtistTokens(currentArtist);
  QSet<QString> prev2;
  std::uniform_real_distribution<double> unit(0.0, 1.0);

  while (!remaining.isEmpty()) {
    QVector<int> eligible;
    eligible.reserve(remaining.size());
    for (int i = 0; i < remaining.size(); ++i) {
      if (!artistSetsOverlap(remaining[i].artists, prev1))
        eligible.append(i);
    }
    if (eligible.isEmpty()) {
      for (int i = 0; i < remaining.size(); ++i) eligible.append(i);
    }

    double total = 0.0;
    QVector<double> weights;
    weights.reserve(eligible.size());
    for (int pos : eligible) {
      double w = remaining[pos].weight;
      if (artistSetsOverlap(remaining[pos].artists, prev2)) w *= 0.35;
      weights.append(w);
      total += w;
    }

    int chosenSlot = eligible.last();
    if (total > 0.0) {
      double pick = unit(rng) * total;
      double acc = 0.0;
      for (int k = 0; k < eligible.size(); ++k) {
        acc += weights[k];
        if (pick <= acc) {
          chosenSlot = eligible[k];
          break;
        }
      }
    }

    const auto picked = remaining.takeAt(chosenSlot);
    ordered.append(picked.index);
    prev2 = std::move(prev1);
    prev1 = picked.artists;
  }
  return ordered;
}
