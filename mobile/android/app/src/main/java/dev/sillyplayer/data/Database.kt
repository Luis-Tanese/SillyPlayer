package dev.sillyplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM restore_ids WHERE origin=:origin AND kind=:kind AND remote=:remote")
    suspend fun restored(origin: String, kind: String, remote: String): RestoreId?

    @Query("SELECT * FROM restore_ids WHERE origin=:origin")
    suspend fun restoredForOrigin(origin: String): List<RestoreId>

    @Query("SELECT * FROM restore_ids")
    suspend fun restoreIds(): List<RestoreId>

    @Upsert suspend fun putRestored(value: RestoreId)

    @Query(
        "SELECT * FROM tracks WHERE deleted=0 AND mergedInto='' ORDER BY added DESC,title COLLATE NOCASE"
    )
    fun observeTracks(): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks WHERE deleted=0 AND mergedInto='' ORDER BY added DESC,title COLLATE NOCASE"
    )
    suspend fun tracks(): List<Track>

    @Query("SELECT * FROM tracks WHERE mergedInto=''") suspend fun allTracks(): List<Track>

    @Query("SELECT * FROM tracks") suspend fun recoveryTracks(): List<Track>

    @Query("SELECT * FROM tracks WHERE id IN (:ids) AND mergedInto=''")
    suspend fun tracksByIds(ids: List<String>): List<Track>

    @Query("SELECT * FROM tracks WHERE id=:id") suspend fun track(id: String): Track?

    @Query("SELECT * FROM tracks WHERE uri=:uri LIMIT 1") suspend fun byUri(uri: String): Track?

    @Query("SELECT * FROM tracks WHERE hash=:hash AND deleted=0 LIMIT 1")
    suspend fun byHash(hash: String): Track?

    @Query("SELECT * FROM tracks WHERE hash=:hash AND deleted=0 AND mergedInto='' AND available=0 LIMIT 1")
    suspend fun unavailableByHash(hash: String): Track?

    @Upsert suspend fun putTrack(value: Track)

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun observeCollections(): Flow<List<Collection>>

    @Query("SELECT * FROM collections") suspend fun collections(): List<Collection>

    @Query("SELECT * FROM collections WHERE id=:id") suspend fun collection(id: String): Collection?

    @Upsert suspend fun putCollection(value: Collection)

    @Query("DELETE FROM collections WHERE id=:id") suspend fun deleteCollection(id: String)

    @Query("SELECT * FROM members ORDER BY position,id") fun observeMembers(): Flow<List<Member>>

    @Query("SELECT * FROM members ORDER BY position,id") suspend fun members(): List<Member>

    @Query("SELECT * FROM members WHERE collection=:id ORDER BY position,id")
    suspend fun members(id: String): List<Member>

    @Upsert suspend fun putMember(value: Member)

    @Query("DELETE FROM members WHERE id=:id") suspend fun deleteMember(id: String)

    @Query("SELECT * FROM likes") fun observeLikes(): Flow<List<Like>>

    @Query("SELECT * FROM likes") suspend fun likes(): List<Like>

    @Query("SELECT EXISTS(SELECT 1 FROM likes WHERE track=:track)")
    suspend fun isLiked(track: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM likes WHERE track=:track)")
    fun observeLiked(track: String): Flow<Boolean>

    @Upsert suspend fun putLike(value: Like)

    @Query("DELETE FROM likes WHERE track=:track") suspend fun deleteLike(track: String)

    @Query("SELECT * FROM roots") fun observeRoots(): Flow<List<Root>>

    @Query("SELECT * FROM roots") suspend fun roots(): List<Root>

    @Upsert suspend fun putRoot(root: Root)

    @Query("DELETE FROM roots WHERE id=:id") suspend fun deleteRoot(id: String)

    @Query("SELECT * FROM jobs ORDER BY created DESC") fun observeJobs(): Flow<List<DownloadJob>>

    @Query("SELECT * FROM jobs ORDER BY created") suspend fun jobs(): List<DownloadJob>

    @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id: String): DownloadJob?

    @Upsert suspend fun putJob(job: DownloadJob)

    @Query("DELETE FROM jobs WHERE id=:id") suspend fun deleteJob(id: String)

    @Upsert suspend fun putDownloadMember(value: DownloadMember)

    @Query("SELECT * FROM download_members WHERE job=:id ORDER BY position")
    suspend fun attachments(id: String): List<DownloadMember>

    @Query("DELETE FROM download_members WHERE id=:id") suspend fun deleteAttachment(id: String)

    @Query("DELETE FROM download_members WHERE collection=:id")
    suspend fun deleteAttachments(id: String)

    @Query("SELECT * FROM searches ORDER BY used DESC LIMIT 60")
    fun observeSearches(): Flow<List<Search>>

    @Upsert suspend fun putSearch(value: Search)

    @Query("DELETE FROM searches WHERE provider=:provider AND query=:query")
    suspend fun deleteSearch(provider: String, query: String)

    @Query("DELETE FROM searches WHERE provider=:provider")
    suspend fun clearSearches(provider: String)

    @Query(
        "INSERT INTO plays(session,track,playedAt,heardMs,qualified) VALUES(:session,:track,:playedAt,:heardMs,:qualified) ON CONFLICT(session) DO UPDATE SET track=excluded.track,playedAt=MIN(playedAt,excluded.playedAt),heardMs=MAX(heardMs,excluded.heardMs),qualified=MAX(qualified,excluded.qualified)"
    )
    suspend fun mergePlay(
        session: String,
        track: String,
        playedAt: Long,
        heardMs: Long,
        qualified: Boolean,
    )

    suspend fun putPlay(value: Play) =
        mergePlay(value.session, value.track, value.playedAt, value.heardMs, value.qualified)

    @Query("SELECT * FROM members WHERE id=:id") suspend fun member(id: String): Member?

    @Query("SELECT * FROM plays WHERE qualified=1 ORDER BY playedAt DESC")
    fun observePlays(): Flow<List<Play>>

    @Query("SELECT track FROM plays WHERE qualified=1 GROUP BY track ORDER BY MAX(playedAt) DESC LIMIT 100")
    fun observeRecentTrackIds(): Flow<List<String>>

    @Query("SELECT * FROM plays WHERE qualified=1") suspend fun plays(): List<Play>

    @Query("SELECT * FROM counters") fun observeCounters(): Flow<List<Counter>>

    @Query("SELECT * FROM counters") suspend fun counters(): List<Counter>

    @Query("SELECT * FROM counters WHERE device=:device AND component=:track")
    suspend fun counter(device: String, track: String): Counter?

    @Query("SELECT * FROM counters WHERE device=:device AND component=:component")
    suspend fun counterComponent(device: String, component: String): Counter?

    @Upsert suspend fun putCounter(value: Counter)

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC") fun observeTrash(): Flow<List<TrashEntry>>

    @Query("SELECT * FROM trash") suspend fun trash(): List<TrashEntry>

    @Upsert suspend fun putTrash(value: TrashEntry)

    @Query("DELETE FROM trash WHERE track=:track") suspend fun deleteTrash(track: String)

    @Query("UPDATE outbox SET peer=:peer WHERE peer=''")
    suspend fun adoptUnpairedOutbox(peer: String)

    @Upsert suspend fun putOutbox(value: Outbox)

    @Query("SELECT * FROM outbox WHERE peer=:peer OR peer='' ORDER BY created,opId LIMIT 500")
    suspend fun outbox(peer: String): List<Outbox>

    // filter before LIMIT: reviewed records cannot starve later edits
    // parent entities also precede memberships even when their timestamps fall on different pages
    @Query("""SELECT o.* FROM outbox o WHERE (o.peer=:peer OR o.peer='')
        AND NOT EXISTS(SELECT 1 FROM conflicts c WHERE c.peer=:peer
            AND c.entity=o.entity AND c.`key`=o.`key`)
        ORDER BY CASE o.entity WHEN 'tracks' THEN 0 WHEN 'collections' THEN 1
            WHEN 'members' THEN 2 WHEN 'orders' THEN 3 WHEN 'likes' THEN 4
            WHEN 'counters' THEN 5 ELSE 6 END,o.created,o.opId LIMIT 100""")
    suspend fun syncBatch(peer: String): List<Outbox>

    @Query(
        "SELECT * FROM outbox WHERE peer=:peer AND entity=:entity AND `key`=:key ORDER BY created LIMIT 1"
    )
    suspend fun pending(peer: String, entity: String, key: String): Outbox?

    @Query("DELETE FROM outbox WHERE peer=:peer AND entity=:entity AND `key`=:key")
    suspend fun deletePending(peer: String, entity: String, key: String)

    @Query("DELETE FROM outbox WHERE opId=:id") suspend fun deleteOutbox(id: String)

    @Query("SELECT * FROM replicas WHERE peer=:peer AND entity=:entity AND `key`=:key")
    suspend fun replica(peer: String, entity: String, key: String): Replica?

    @Upsert suspend fun putReplica(value: Replica)

    @Query("SELECT * FROM peers") fun observePeers(): Flow<List<Peer>>

    @Query("SELECT * FROM peers") suspend fun peers(): List<Peer>

    @Upsert suspend fun putPeer(value: Peer)

    @Query("DELETE FROM peers WHERE id=:id") suspend fun deletePeer(id: String)

    @Query("DELETE FROM outbox WHERE peer=:id") suspend fun clearPeerOutbox(id: String)

    @Query("DELETE FROM replicas WHERE peer=:id") suspend fun clearPeerReplicas(id: String)

    @Query("DELETE FROM conflicts WHERE peer=:id") suspend fun clearPeerConflicts(id: String)

    @Query("SELECT * FROM conflicts WHERE peer=:peer") suspend fun conflictsForPeer(peer: String): List<SyncConflict>

    @Upsert suspend fun putConflict(value: SyncConflict)

    @Query("SELECT * FROM conflicts") fun observeConflicts(): Flow<List<SyncConflict>>

    @Query("DELETE FROM conflicts WHERE id=:id") suspend fun deleteConflict(id: String)

    @Query("DELETE FROM tracks WHERE id IN (:ids)") suspend fun deleteTracksByIds(ids: List<String>)

    @Query("DELETE FROM members WHERE track IN (:ids)") suspend fun deleteMembersByTrackIds(ids: List<String>)

    @Query("DELETE FROM likes WHERE track IN (:ids)") suspend fun deleteLikesByTrackIds(ids: List<String>)

    @Query("DELETE FROM trash WHERE track IN (:ids)") suspend fun deleteTrashByTrackIds(ids: List<String>)

    @Query("DELETE FROM outbox WHERE `key` IN (:keys)") suspend fun deleteOutboxForKeys(keys: List<String>)

    @Query("DELETE FROM replicas WHERE `key` IN (:keys)") suspend fun deleteReplicasForKeys(keys: List<String>)

    @Query("DELETE FROM conflicts WHERE `key` IN (:keys)") suspend fun deleteConflictsForKeys(keys: List<String>)

    @Query("UPDATE peers SET cursor=0") suspend fun resetPeerCursors()
}

@Database(
    entities =
        [
            Track::class,
            Collection::class,
            Member::class,
            Like::class,
            Root::class,
            DownloadJob::class,
            DownloadMember::class,
            Search::class,
            Play::class,
            Counter::class,
            TrashEntry::class,
            Outbox::class,
            Replica::class,
            Peer::class,
            SyncConflict::class,
            RestoreId::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
}
