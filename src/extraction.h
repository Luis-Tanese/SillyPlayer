#pragma once
#include <QCache>
#include <QElapsedTimer>
#include <QObject>
#include <QProcess>
#include <QSet>
#include <QVariantMap>
#include <QTimer>
#include <functional>

// short lived transport data stays in memory, separate from library metadata
class ExtractionService : public QObject {
public:
  using Completion = std::function<void(const QVariantMap &, const QString &, bool)>;
  explicit ExtractionService(QObject *parent = nullptr);
  ~ExtractionService();
  quint64 request(const QString &url, Completion completion, bool verifyFormats = false);
  void cancel(quint64 token);
  QVariantMap cached(const QString &url);
  void invalidate(const QString &url);
  void shutdown();
  static QString key(const QString &url);
  static qint64 validUntil(const QVariantMap &details, qint64 now);
  int cachedEntryCount() const { return cache.size(); }
  int cachedMemoryCost() const { return cache.totalCost(); }

private:
  struct Cached {
    QVariantMap details;
    qint64 until, inserted;
  };
  struct Consumer { QString key; Completion complete; };
  struct Pending { QProcess *process; QSet<quint64> consumers; };
  QCache<QString, Cached> cache{16 * 1024 * 1024};
  QHash<QString, Pending> pending;
  QHash<quint64, Consumer> consumers;
  QElapsedTimer clock;
  quint64 nextToken = 0;
  bool closing = false;
  QTimer expiry;
  void pruneExpired();
  void finish(const QString &key, const QVariantMap &details, const QString &error);
  void deliver(quint64 token, const QVariantMap &details, const QString &error, bool hit);
};
