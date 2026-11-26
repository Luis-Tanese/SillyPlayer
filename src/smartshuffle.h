#pragma once
#include "artistsplit.h"
#include <QDateTime>
#include <QHash>
#include <QSet>
#include <QString>
#include <QVarLengthArray>
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
// uses interned artist ids + fenwick weighted rejection sampling for O(N log N) runtime
template <typename Rng>
QVector<int> smartShuffleOrder(const QVector<SmartShuffleItem> &items,
                               const QString &currentArtist,
                               Rng &rng,
                               qint64 nowMs = QDateTime::currentMSecsSinceEpoch()) {
  const int n = items.size();
  QVector<int> ordered;
  ordered.reserve(n);
  if (n == 0) return ordered;

  QHash<QString, int> artistIds;
  artistIds.reserve(n);
  auto internTokens = [&](const QString &raw) {
    QVarLengthArray<int, 3> ids;
    for (const auto &part : splitArtists(raw)) {
      const QString key = part.trimmed().toLower();
      if (key.isEmpty() || key == QStringLiteral("unknown artist")) continue;
      auto it = artistIds.find(key);
      int id;
      if (it == artistIds.end()) {
        id = artistIds.size();
        artistIds.insert(key, id);
      } else {
        id = it.value();
      }
      if (!ids.contains(id)) ids.append(id);
    }
    return ids;
  };

  auto overlapIds = [](const QVarLengthArray<int, 3> &a, const QVarLengthArray<int, 3> &b) {
    if (a.isEmpty() || b.isEmpty()) return false;
    for (int x : a) {
      for (int y : b) {
        if (x == y) return true;
      }
    }
    return false;
  };

  struct PoolEntry {
    int index = 0;
    QVarLengthArray<int, 3> artists;
    double weight = 3.0;
    int activePos = -1;
  };

  QVector<PoolEntry> pool;
  pool.resize(n);
  QVector<int> activeList;
  activeList.resize(n);
  QVector<double> bit(n + 1, 0.0);
  double bitTotal = 0.0;

  for (int i = 0; i < n; ++i) {
    const auto &item = items[i];
    double weight = 3.0;
    if (item.lastPlayed > 0) {
      const double ageDays = std::max(0.0, double(nowMs - item.lastPlayed) / 86400000.0);
      weight = 1.0 + 1.8 * std::min(1.0, ageDays / 7.0);
    }
    pool[i] = {item.index, internTokens(item.artist), weight, i};
    activeList[i] = i;
    bitTotal += weight;
  }

  // O(N) fenwick tree build
  for (int i = 1; i <= n; ++i) {
    bit[i] += pool[i - 1].weight;
    const int parent = i + (i & -i);
    if (parent <= n) bit[parent] += bit[i];
  }

  QVector<int> artistRemaining(artistIds.size() + 4, 0);
  for (int i = 0; i < n; ++i) {
    for (int a : pool[i].artists) {
      if (a >= artistRemaining.size()) artistRemaining.resize(a + 1, 0);
      ++artistRemaining[a];
    }
  }

  QVarLengthArray<int, 3> prev1 = internTokens(currentArtist);
  QVarLengthArray<int, 3> prev2;
  std::uniform_real_distribution<double> unit(0.0, 1.0);

  int highestBit = 1;
  while ((highestBit << 1) <= n) highestBit <<= 1;

  auto bitSample = [&]() -> int {
    if (bitTotal <= 0.0) return activeList.last();
    double target = unit(rng) * bitTotal;
    int idx = 0;
    for (int step = highestBit; step > 0; step >>= 1) {
      const int next = idx + step;
      if (next <= n && bit[next] < target) {
        idx = next;
        target -= bit[next];
      }
    }
    int candidate = idx; // 0-based index is (idx + 1) - 1 = idx
    if (candidate >= n || pool[candidate].activePos < 0) {
      return activeList.last();
    }
    return candidate;
  };

  auto removePoolItem = [&](int slot) {
    const double w = pool[slot].weight;
    bitTotal = std::max(0.0, bitTotal - w);
    for (int i = slot + 1; i <= n; i += i & -i) bit[i] -= w;
    for (int a : pool[slot].artists) --artistRemaining[a];
    const int pos = pool[slot].activePos;
    const int lastSlot = activeList.last();
    activeList[pos] = lastSlot;
    pool[lastSlot].activePos = pos;
    activeList.pop_back();
    pool[slot].activePos = -1;
  };

  while (!activeList.isEmpty()) {
    const int remCount = activeList.size();
    int chosen = -1;

    // if every remaining song shares a prev1 artist, skip rejection sampling in O(1)
    bool allBlocked = false;
    for (int a : prev1) {
      if (a < artistRemaining.size() && artistRemaining[a] >= remCount) {
        allBlocked = true;
        break;
      }
    }

    if (allBlocked) {
      for (int attempt = 0; attempt < 12; ++attempt) {
        const int cand = bitSample();
        if (!overlapIds(pool[cand].artists, prev2) || unit(rng) < 0.35) {
          chosen = cand;
          break;
        }
      }
    } else {
      for (int attempt = 0; attempt < 16; ++attempt) {
        const int cand = bitSample();
        if (overlapIds(pool[cand].artists, prev1)) continue;
        if (overlapIds(pool[cand].artists, prev2) && unit(rng) >= 0.35) continue;
        chosen = cand;
        break;
      }
    }

    // exact fallback over active items when rejection sampling misses (only near the tail or extreme skew)
    if (chosen < 0) {
      double eligibleTotal = 0.0;
      bool anyNonPrev1 = false;
      for (int slot : activeList) {
        if (!overlapIds(pool[slot].artists, prev1)) {
          anyNonPrev1 = true;
          double w = pool[slot].weight;
          if (overlapIds(pool[slot].artists, prev2)) w *= 0.35;
          eligibleTotal += w;
        }
      }
      if (!anyNonPrev1) {
        for (int slot : activeList) {
          double w = pool[slot].weight;
          if (overlapIds(pool[slot].artists, prev2)) w *= 0.35;
          eligibleTotal += w;
        }
      }
      chosen = activeList.last();
      if (eligibleTotal > 0.0) {
        const double pick = unit(rng) * eligibleTotal;
        double acc = 0.0;
        for (int slot : activeList) {
          if (anyNonPrev1 && overlapIds(pool[slot].artists, prev1)) continue;
          double w = pool[slot].weight;
          if (overlapIds(pool[slot].artists, prev2)) w *= 0.35;
          acc += w;
          if (pick <= acc) {
            chosen = slot;
            break;
          }
        }
      }
    }

    ordered.append(pool[chosen].index);
    prev2 = prev1;
    prev1 = pool[chosen].artists;
    removePoolItem(chosen);
  }
  return ordered;
}
