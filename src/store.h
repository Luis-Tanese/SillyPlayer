#pragma once
#include <QSqlDatabase>
#include <QVariant>
#include <QVariantList>
#include <QVariantMap>

class Store {
public:
  explicit Store(const QString &path);
  ~Store();
  QVariantList rows(const QString &sql, const QVariantList &args = {}) const;
  bool exec(const QString &sql, const QVariantList &args = {});
  QVariant setting(const QString &key, const QVariant &fallback = {}) const;
  QVariantMap allSettings() const;
  void setSetting(const QString &key, const QVariant &value);
  QString error() const { return m_error; }
  int lastAffected() const { return m_affected; }
  QSqlDatabase db() const { return m_db; }
  quint64 writeSerial() const { return serial; }
  quint64 catalogueSerial() const { return catalogue; }
  quint64 tracksSerial() const { return tracksRev; }
  quint64 rootsSerial() const { return rootsRev; }
  quint64 settingsSerial() const { return settingsRev; }

private:
  QSqlDatabase m_db;
  mutable QString m_error;
  int m_affected = 0;
  quint64 serial = 0;
  quint64 catalogue = 0;
  quint64 tracksRev = 0;
  quint64 rootsRev = 0;
  quint64 settingsRev = 0;
};
