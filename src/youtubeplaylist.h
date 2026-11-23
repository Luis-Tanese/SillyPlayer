#pragma once
#include <QObject>
#include <QProcess>
#include <QSet>
#include <QVariantList>
class Online;

class YouTubePlaylist : public QObject {
  Q_OBJECT
  Q_PROPERTY(QVariantList entries READ entries NOTIFY changed)
  Q_PROPERTY(QString url READ url NOTIFY changed)
  Q_PROPERTY(QString title READ title NOTIFY changed)
  Q_PROPERTY(QString message READ message NOTIFY changed)
  Q_PROPERTY(bool loading READ loading NOTIFY changed)
  Q_PROPERTY(bool hasMore READ hasMore NOTIFY changed)
  Q_PROPERTY(bool mix READ mix NOTIFY changed)
  Q_PROPERTY(int selectedCount READ selectedCount NOTIFY changed)
  Q_PROPERTY(bool allSelected READ allSelected NOTIFY changed)
public:
  explicit YouTubePlaylist(Online *online);
  ~YouTubePlaylist();
  static QString canonicalUrl(const QString &input);
  QVariantList entries() const;
  QString url() const { return source; }
  QString title() const { return name; }
  QString message() const { return status; }
  bool loading() const { return process!=nullptr; }
  bool hasMore() const { return more; }
  bool mix() const { return isMix; }
  int selectedCount() const { return selected.size(); }
  bool allSelected() const;
  Q_INVOKABLE void show(const QString &input = {});
  Q_INVOKABLE void load(const QString &input);
  Q_INVOKABLE void loadMore();
  Q_INVOKABLE void toggle(const QString &key);
  Q_INVOKABLE void toggleAll();
  Q_INVOKABLE QVariantMap download(const QString &kind,const QString &destination,bool again=false,const QString &playlistName={});
  Q_INVOKABLE void dismiss();
  Q_INVOKABLE void cancel();
  void shutdown();
signals:
  void changed();
  void opened();
private:
  Online *owner;
  QProcess *process=nullptr;
  QVariantList rows;
  QSet<QString> selected;
  QString source,name,status;
  int offset=0;
  bool more=false,isMix=false;
  void fetch();
};
