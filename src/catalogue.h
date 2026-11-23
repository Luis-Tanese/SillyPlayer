#pragma once
#include <QCache>
#include <QElapsedTimer>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QObject>
#include <QTimer>
#include <QVariantList>

// optional, user-initiated metadata suggestions
class Catalogue : public QObject {
  Q_OBJECT
  Q_PROPERTY(QVariantList results READ results NOTIFY changed)
  Q_PROPERTY(bool busy READ busy NOTIFY changed)
  Q_PROPERTY(QString status READ status NOTIFY changed)
public:
  explicit Catalogue(const QUrl &endpoint = QUrl("https://musicbrainz.org/ws/2/recording"), QObject *parent = nullptr);
  ~Catalogue() override { cancel(); }
  QVariantList results() const { return matches; }
  bool busy() const { return loading; }
  QString status() const { return message; }
  Q_INVOKABLE void search(const QString &title, const QString &artist = {});
  Q_INVOKABLE void cancel();
  static QVariantList parse(const QByteArray &json, bool *valid = nullptr);
signals:
  void changed();
private:
  QUrl endpoint;
  QNetworkAccessManager network;
  QNetworkReply *reply = nullptr;
  QTimer dispatch;
  QElapsedTimer lastRequest;
  QCache<QString, QVariantList> cache{24};
  QVariantList matches;
  QString message, query;
  bool loading = false;
  void send();
};
