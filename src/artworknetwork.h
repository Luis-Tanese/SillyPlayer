#pragma once
#include <QQmlNetworkAccessManagerFactory>
#include <QNetworkAccessManager>
#include <QNetworkDiskCache>
#include <QNetworkReply>
#include <QDebug>

// QML images retain decoded images in memory; persist HTTP responses across views/restarts
class ArtworkNetworkFactory final : public QQmlNetworkAccessManagerFactory {
public:
  explicit ArtworkNetworkFactory(QString directory) : directory(std::move(directory)) {}
  QNetworkAccessManager *create(QObject *parent) override {
    auto manager = new QNetworkAccessManager(parent);
    manager->setTransferTimeout(5000);
    QObject::connect(manager,&QNetworkAccessManager::finished,manager,[](QNetworkReply *reply) {
      if(reply->error()!=QNetworkReply::NoError)
        qWarning() << "Artwork network failure" << reply->url().host() << int(reply->error())
                   << reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    });
    auto cache = new QNetworkDiskCache(manager);
    cache->setCacheDirectory(directory);
    cache->setMaximumCacheSize(100 * 1024 * 1024);
    manager->setCache(cache);
    return manager;
  }
private:
  QString directory;
};
