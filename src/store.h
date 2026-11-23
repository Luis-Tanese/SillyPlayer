#pragma once
#include <QSqlDatabase>
#include <QVariant>
#include <QVariantList>

class Store {
public:
  explicit Store(const QString &path);
  ~Store();
  QVariantList rows(const QString &sql, const QVariantList &args = {}) const;
  bool exec(const QString &sql, const QVariantList &args = {});
  QVariant setting(const QString &key, const QVariant &fallback = {}) const;
  void setSetting(const QString &key, const QVariant &value);
  QString error() const { return m_error; }
  QSqlDatabase db() const { return m_db; }
  quint64 writeSerial() const { return serial; }
  quint64 catalogueSerial() const { return catalogue; }

private:
  QSqlDatabase m_db;
  mutable QString m_error;
  quint64 serial = 0;
  quint64 catalogue = 0;
};
