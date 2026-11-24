#pragma once
#include "library.h"
#include "online.h"
#include "player.h"
#include "catalogue.h"
#include "listening.h"
#include "backup.h"
#include "sleeptimer.h"
#include "librarytools.h"
#include "sync.h"
#include "discordrpc.h"
#include <QNetworkAccessManager>
#include <QVariantList>

class App : public QObject {
  Q_OBJECT
  Q_PROPERTY(QVariantMap settings READ settings NOTIFY settingsChanged)
  Q_PROPERTY(
      QVariantList collections READ collections NOTIFY collectionsChanged)
  Q_PROPERTY(QVariantList queue READ queue NOTIFY queueChanged)
  Q_PROPERTY(QVariantMap current READ current NOTIFY currentChanged)
  Q_PROPERTY(bool currentLiked READ currentLiked NOTIFY currentLikeChanged)
  Q_PROPERTY(int recentCount READ recentCount NOTIFY recentChanged)
  Q_PROPERTY(QObject *statistics READ statistics CONSTANT)
  Q_PROPERTY(QObject *sleepTimer READ timerObject CONSTANT)
  Q_PROPERTY(QObject *backup READ backupObject CONSTANT)
  Q_PROPERTY(QObject *libraryTools READ toolsObject CONSTANT)
  Q_PROPERTY(QObject *sync READ syncObject CONSTANT)
  Q_PROPERTY(QObject *discord READ discordObject CONSTANT)
  Q_PROPERTY(int mode READ mode NOTIFY settingsChanged)
  Q_PROPERTY(QVariantMap sourceContext READ sourceContext NOTIFY queueChanged)
  Q_PROPERTY(QString context READ context NOTIFY queueChanged)
  Q_PROPERTY(QVariantMap updateInfo READ updateInfo NOTIFY updateChanged)
public:
  App(Store *store, const QString &cache, QObject *parent = nullptr);
  ~App();
  Library library;
  Player music, youtube;
  Online online;
  Catalogue catalogue;
  Listening listening;
  SleepTimer sleepTimer;
  Backup backup;
  LibraryTools libraryTools;
  SyncManager sync;
  DiscordRpc discord;
  QObject *discordObject() { return &discord; }
  QObject *syncObject(){return &sync;}
  QObject *toolsObject() { return &libraryTools; }
  QObject *timerObject() { return &sleepTimer; }
  QObject *backupObject() { return &backup; }
  QObject *statistics() { return &listening; }
  QVariantMap settings() const;
  QVariantList collections() const;
  QVariantList queue() const;
  QVariantMap current() const;
  bool currentLiked() const { return current().value("liked").toBool(); }
  bool hasQueue() const { return !entries.isEmpty(); }
  int recentCount() const;
  int mode() const { return m_mode; }
  QString context() const { return m_context; }
  QVariantMap sourceContext() const { return m_source; }
  QVariantMap updateInfo() const { return m_updateInfo; }
  Q_INVOKABLE void checkForUpdates(bool manual = false);
  Q_INVOKABLE void openReleasePage();
  Q_INVOKABLE void setSetting(const QString &key, const QVariant &value);
  Q_INVOKABLE QVariantList collectionTracks(const QString &id) const;
  Q_INVOKABLE QVariantMap folderView(const QString &path = {}) const;
  Q_INVOKABLE bool isPinned(const QString &kind, const QString &id) const;
  Q_INVOKABLE void togglePin(const QString &kind, const QString &id);
  Q_INVOKABLE void addFiles();
  Q_INVOKABLE void playTrack(const QString &id, const QVariantList &ids,
                             const QString &context, const QString &collection = {}, const QString &member = {});
  Q_INVOKABLE void next();
  Q_INVOKABLE void previous();
  Q_INVOKABLE void toggle();
  Q_INVOKABLE void cycleMode();
  Q_INVOKABLE void playNext(const QString &id);
  Q_INVOKABLE QVariantMap queueTracks(const QStringList &tracks, bool next);
  Q_INVOKABLE QVariantMap addTracksToCollection(const QString &collection, const QStringList &tracks, const QString &policy = "ask");
  Q_INVOKABLE QVariantMap removeMembers(const QString &collection, const QStringList &members);
  Q_INVOKABLE QVariantMap unlikeTracks(const QStringList &tracks);
  Q_INVOKABLE bool trashTracks(const QStringList &tracks);
  Q_INVOKABLE void queuePlay(const QString &entry);
  Q_INVOKABLE void removeQueue(const QString &entry);
  Q_INVOKABLE void moveQueue(int from, int to);
  Q_INVOKABLE void clearUpcoming();
  Q_INVOKABLE void like(const QString &id);
  Q_INVOKABLE QString createCollection(const QString &name,
                                       const QString &kind);
  Q_INVOKABLE void renameCollection(const QString &id, const QString &name);
  Q_INVOKABLE void deleteCollection(const QString &id);
  Q_INVOKABLE void coverCollection(const QString &id);
  Q_INVOKABLE QString addToCollection(const QString &collection,
                                   const QString &track, bool allowDuplicate = false);
  Q_INVOKABLE bool collectionContains(const QString &collection, const QString &track) const;
  Q_INVOKABLE void removeMember(const QString &collection, const QString &member);
  Q_INVOKABLE void moveMember(const QString &collection, const QString &member, int to);
  Q_INVOKABLE void removeFromCollection(const QString &collection,
                                        const QString &track);
  Q_INVOKABLE QString chooseFolder(const QString &initial = {});
  Q_INVOKABLE QString chooseImage();
  Q_INVOKABLE void openFile(const QString &path, bool folder = false);
  Q_INVOKABLE void applyTrackSpeed(const QString &id, double value, bool nightcore);
  Q_INVOKABLE void previewTrackSpeed(const QString &id, double value, bool nightcore);
  Q_INVOKABLE void applyTrackVolume(const QString &id, double percent);
  Q_INVOKABLE void previewTrackVolume(const QString &id, double percent);
  Q_INVOKABLE QString modifierLabel(const QVariantMap &track) const;
  Q_INVOKABLE QString sourceLink(const QString &id) const;
  Q_INVOKABLE QString youtubeLink(const QString &id) const;
  Q_INVOKABLE bool copyLink(const QString &id);
  Q_INVOKABLE void openLink(const QString &id);
  void forgetRemovedTrack(const QString &id);
  Q_INVOKABLE QVariantMap removeLibraryTracks(const QStringList &ids);
  Q_INVOKABLE void removeLibraryTrack(const QString &id);
  Q_INVOKABLE void trashTrack(const QString &id);
  Q_INVOKABLE QVariantMap clearMusicFolder(const QString &folderPath = {});
  Q_INVOKABLE QVariantMap exportPlaylistM3u(const QString &collectionId);
  Q_INVOKABLE QVariantMap importPlaylistM3u();
  Q_INVOKABLE QString cleanTitle(const QString &title) const;
  Q_INVOKABLE void openTrash();
  Q_INVOKABLE QString speedLabel(const QVariantMap &track) const;
  Q_INVOKABLE void applySpeed(double value, bool nightcore);
  Q_INVOKABLE void applyEq(bool enabled, const QVariantList &gains, const QString &preset = {});
  Q_INVOKABLE QString time(double value) const;
  void save();
  void shutdown();
signals:
  void settingsChanged();
  void collectionsChanged();
  void queueChanged();
  void currentChanged();
  void currentLikeChanged();
  void recentChanged();
  void error(const QString &message);
  void batchTrashFinished(const QVariantMap &result);
  void updateChanged();
  void updateAvailable(const QString &version, const QString &url);

private:
  Store *s;
  QString cache;
  QNetworkAccessManager updateNet;
  QVariantMap m_updateInfo{{"checking", false}, {"available", false}, {"version", ""}, {"url", ""}, {"status", ""}};
  QVariantList entries;
  QStringList normalQueueOrder;
  int index = -1, m_mode = 0;
  QString m_context;
  QVariantMap m_source;
  QVariantMap savedPlayback;
  mutable QVariantMap currentSnapshot;
  mutable QVariantMap settingsSnapshot;
  mutable quint64 settingsSerial=quint64(-1);
  mutable QString settingsTrack;
  mutable QString currentSnapshotId;
  mutable QVariantList collectionSnapshot,queueSnapshot,queueSnapshotEntries;
  mutable quint64 currentSerial=quint64(-1),collectionSerial=quint64(-1),queueSerial=quint64(-1);
  mutable int queueSnapshotIndex=-2;
  double onlineResume = 0;
  bool onlinePaused = false;
  bool closing = false, batchTrashing = false;
  QString lastDiscordTrackKey;
  bool lastDiscordPlaying = false;
  quint64 lastDiscordSeekGen = 0;
  void syncDiscordPresence(bool force = false);
  void removeTrashedFromQueue(const QStringList &tracks);
  QTimer saver;
  void playIndex(int i, bool force = false, bool autoTransition = false);
  void advance(bool autoTransition);
  void rebuildShuffle();
  void rememberQueueOrder();
  void reconcileQueueOrder();
  void restoreQueueOrder();
  void insertedQueueOrder(const QStringList &added, bool next);
};
