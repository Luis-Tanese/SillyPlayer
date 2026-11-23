#pragma once
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QStorageInfo>
#include <QVariantMap>

// store UUIDs when Linux exposes them and retain legacy device IDs for compat
inline QString storageIdentity(const QString &path) {
    const QStorageInfo storage(path);
#ifdef Q_OS_LINUX
    const auto device=QFileInfo(QString::fromUtf8(storage.device())).canonicalFilePath();
    if (!device.isEmpty()) for (const auto &uuid:QDir("/dev/disk/by-uuid").entryInfoList(QDir::System|QDir::Files))
        if (uuid.canonicalFilePath()==device) return "uuid:"+uuid.fileName();
#endif
    return QString::fromUtf8(storage.device());
}
inline bool storageMatches(const QVariantMap &root) {
    const auto path=root["path"].toString(), expected=root["device"].toString();
    const QStorageInfo storage(path);
    return QFileInfo(path).isDir() && QFileInfo(path).isReadable() && storage.isReady() &&
        !expected.isEmpty() && (expected==storageIdentity(path) || expected==QString::fromUtf8(storage.device()));
}
inline QString fileAvailability(const QVariantMap &track,const QVariantList &roots) {
    if(track["deleted"].toBool() || !track["mergedInto"].toString().isEmpty()) return "Excluded";
    const auto path=track["path"].toString();
    if(path.isEmpty() || path.contains("://")) return "Unresolved";
    QFile file(path);
    if(QFileInfo(path).isFile() && file.open(QIODevice::ReadOnly)) return "Available";
    if(roots.isEmpty()) return "Unresolved";
    for(const auto &root:roots) if(!storageMatches(root.toMap())) return "Storage unavailable";
    if(QFileInfo(path).exists()) return "Unresolved";
    // a missing intermediate directory might be a disconnected nested mount (yuckers)
    const QFileInfo parent(QFileInfo(path).absolutePath());
    return parent.isDir() && parent.isReadable() ? "Missing" : "Unresolved";
}
