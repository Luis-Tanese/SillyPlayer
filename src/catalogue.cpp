#include "catalogue.h"
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkRequest>
#include <QSet>
#include <QUrlQuery>

static QString credit(const QJsonArray &credits) {
  QString text;
  for (const auto &v : credits) {
    const auto c = v.toObject();
    text += c.value("name").toString(c.value("artist").toObject().value("name").toString());
    text += c.value("joinphrase").toString();
  }
  return text.trimmed();
}
Catalogue::Catalogue(const QUrl &url, QObject *parent) : QObject(parent), endpoint(url) {
  dispatch.setSingleShot(true);
  connect(&dispatch, &QTimer::timeout, this, &Catalogue::send);
}
void Catalogue::cancel() {
  dispatch.stop();
  if (reply) {
    disconnect(reply, nullptr, this, nullptr);
    reply->abort();
    reply->deleteLater();
    reply = nullptr;
  }
  loading = false;
  emit changed();
}
void Catalogue::search(const QString &title, const QString &artist) {
  cancel();
  matches.clear();
  message.clear();
  auto quoted = [](QString value) {
    value.replace('\\', "\\\\");
    value.replace('"', "\\\"");
    return '"' + value + '"';
  };
  const auto name = title.trimmed().left(250);
  if (name.isEmpty()) { message = "Enter a song title"; emit changed(); return; }
  query = "recording:" + quoted(name);
  if (!artist.trimmed().isEmpty() && artist.trimmed() != "Unknown artist")
    query += " AND artist:" + quoted(artist.trimmed().left(250));
  if (auto hit = cache.object(query)) {
    matches = *hit;
    message = matches.isEmpty() ? "No catalogue matches" : "Choose a match to fill the editor";
    emit changed();
    return;
  }
  loading = true;
  message = "Searching MusicBrainz…";
  emit changed();
  // MusicBrainz permits at most one request per second per application
  dispatch.start(lastRequest.isValid() ? int(qMax<qint64>(0, 1100 - lastRequest.elapsed())) : 0);
}
void Catalogue::send() {
  QUrl url = endpoint;
  QUrlQuery parameters;
  parameters.addQueryItem("query", query);
  parameters.addQueryItem("fmt", "json");
  parameters.addQueryItem("limit", "8");
  url.setQuery(parameters);
  QNetworkRequest request(url);
  request.setRawHeader("User-Agent", "SillyPlayer/" SILLYPLAYER_VERSION " (Qt desktop; user-initiated metadata search)");
  request.setRawHeader("Accept", "application/json");
  request.setTransferTimeout(15000);
  reply = network.get(request);
  auto current = reply;
  auto requestedQuery = query;
  lastRequest.start();
  QTimer::singleShot(15000, current, [current] {
    if (!current->isFinished()) current->abort();
  });
  connect(current, &QNetworkReply::readyRead, this, [current] {
    if (current->bytesAvailable() > 2 * 1024 * 1024) current->abort();
  });
  connect(current, &QNetworkReply::finished, this, [this, current, requestedQuery] {
    reply = nullptr;
    loading = false;
    bool valid = false;
    if (current->error() == QNetworkReply::NoError) {
      matches = parse(current->readAll(), &valid);
      if (valid) cache.insert(requestedQuery, new QVariantList(matches));
      message = !valid ? "MusicBrainz returned an invalid response"
                      : matches.isEmpty() ? "No catalogue matches" : "Choose a match to fill the editor";
    } else {
      const auto status = current->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
      message = status == 429 || status == 503 ? "MusicBrainz is busy. Try again later."
                 : "Metadata search unavailable: " + current->errorString();
    }
    current->deleteLater();
    emit changed();
  });
}
QVariantList Catalogue::parse(const QByteArray &json, bool *valid) {
  const auto doc = QJsonDocument::fromJson(json);
  const bool ok = doc.isObject() && doc.object().value("recordings").isArray();
  if (valid) *valid = ok;
  QVariantList output;
  QSet<QString> seen;
  if (!ok) return output;
  for (const auto &v : doc.object().value("recordings").toArray()) {
    const auto r = v.toObject();
    const auto title = r.value("title").toString();
    const auto artist = credit(r.value("artist-credit").toArray());
    if (title.isEmpty() || artist.isEmpty()) continue;
    auto releases = r.value("releases").toArray();
    if (releases.isEmpty()) releases.append(QJsonObject{});
    for (const auto &release : releases) {
      const auto album = release.toObject();
      const QString identity = r.value("id").toString() + ':' + album.value("id").toString();
      if (seen.contains(identity)) continue;
      seen.insert(identity);
      output << QVariantMap{{"id", identity}, {"title", title}, {"artist", artist},
                            {"album", album.value("title").toString()},
                            {"albumArtist", credit(album.value("artist-credit").toArray())},
                            {"version", r.value("disambiguation").toString()},
                            {"duration", r.value("length").toDouble() / 1000}};
      if (output.size() >= 24) return output;
    }
  }
  return output;
}
