package dev.sillyplayer.data

import java.io.File

/** local reset only: never journal tombstones to another device. Keep recoverable Trash intact */
val LibraryResetStatements =
    listOf(
        "DELETE FROM download_members",
        "DELETE FROM jobs",
        "DELETE FROM members",
        "DELETE FROM likes",
        "DELETE FROM collections",
        "DELETE FROM plays",
        "DELETE FROM counters",
        "DELETE FROM tracks WHERE id NOT IN (SELECT track FROM trash)",
        "DELETE FROM roots",
        "DELETE FROM restore_ids",
        "DELETE FROM outbox",
        "DELETE FROM replicas",
        "DELETE FROM conflicts",
        "UPDATE peers SET cursor=0",
    )

/** never delete external originals or private files retained by recoverable Trash */
fun clearPrivateSyncMedia(filesDir: File, protectedFiles: Set<File>) {
    val protected = protectedFiles.map { it.canonicalPath }.toSet()
    for (relative in listOf("music/Synced", "art/sync", "sync")) {
        val root = File(filesDir, relative)
        val base = root.canonicalPath
        if (!root.exists()) continue
        for (file in root.walkBottomUp()) {
            val path = file.canonicalPath
            if (
                (path == base || path.startsWith(base + File.separator)) &&
                    protected.none { it == path || it.startsWith(path + File.separator) }
            ) {
                check(file.delete() || !file.exists()) {
                    "Some app-private copied media could not be removed"
                }
            }
        }
    }
}
