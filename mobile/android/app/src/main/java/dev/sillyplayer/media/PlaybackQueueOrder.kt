package dev.sillyplayer.media

import dev.sillyplayer.data.QueueEntry
import dev.sillyplayer.data.splitArtists
import java.util.Locale

/** occurrence IDs keep duplicate songs independent and survive track-ID remapping */
internal fun normalizedQueueShuffle(requested: List<String>, entries: List<QueueEntry>): List<String> {
    val available = entries.mapTo(HashSet()) { it.id }
    val retained = requested.filter { it in available }.distinct()
    val included = retained.toHashSet()
    return retained + entries.asSequence().map { it.id }.filter { it !in included }.toList()
}

/** one pass per transport/order change, never per position tick. Current always starts the view */
internal fun upcomingPlaybackQueue(entries: List<QueueEntry>, index: Int, mode: Int,
    shuffle: List<String>): List<QueueEntry> {
    if (entries.isEmpty()) return emptyList()
    val current = entries[index.coerceIn(entries.indices)]
    val order = if (mode == 2) normalizedQueueShuffle(shuffle, entries) else entries.map { it.id }
    val at = order.indexOf(current.id).coerceAtLeast(0)
    val byId = entries.associateBy { it.id }
    return List(order.size) { offset -> byId.getValue(order[(at + offset) % order.size]) }
}

/** start a fresh shuffle on every selection, keeping the current occurrence first */
internal fun shuffledQueueOrder(entries: List<QueueEntry>, index: Int,
    previous: List<String>, random: kotlin.random.Random = kotlin.random.Random.Default): List<String> {
    if (entries.isEmpty()) return emptyList()
    val current = entries[index.coerceIn(entries.indices)].id
    val tail = entries.map { it.id }.filter { it != current }.shuffled(random).toMutableList()
    val old = upcomingPlaybackQueue(entries, index, 2, previous).map { it.id }
    if (tail.size > 1 && listOf(current) + tail == old) tail.add(tail.removeAt(0))
    return listOf(current) + tail
}

private fun normalizedArtists(raw: String): Set<String> =
    splitArtists(raw)
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() && it != "unknown artist" }
        .toSet()

// spreads out same artist tracks and favors songs that havent played recently
internal fun smartShuffledQueueOrder(
    entries: List<QueueEntry>,
    index: Int,
    previous: List<String>,
    lastPlayedByTrack: Map<String, Long>,
    nowMs: Long = System.currentTimeMillis(),
    random: kotlin.random.Random = kotlin.random.Random.Default,
): List<String> {
    if (entries.isEmpty()) return emptyList()
    val currentEntry = entries[index.coerceIn(entries.indices)]
    val remaining =
        entries
            .filter { it.id != currentEntry.id }
            .map { entry ->
                val last = lastPlayedByTrack[entry.trackId] ?: 0L
                val weight =
                    if (last <= 0L) 3.0
                    else {
                        val ageDays = ((nowMs - last).coerceAtLeast(0L)).toDouble() / 86_400_000.0
                        1.0 + 1.8 * minOf(1.0, ageDays / 7.0)
                    }
                Triple(entry.id, normalizedArtists(entry.track.artist), weight)
            }
            .toMutableList()
    val tail = ArrayList<String>(remaining.size)
    var prev1 = normalizedArtists(currentEntry.track.artist)
    var prev2 = emptySet<String>()
    while (remaining.isNotEmpty()) {
        val eligible =
            if (prev1.isEmpty()) remaining.indices.toList()
            else remaining.indices.filter { idx -> remaining[idx].second.none { it in prev1 } }
                .ifEmpty { remaining.indices.toList() }
        val weights = DoubleArray(eligible.size)
        var total = 0.0
        for (k in eligible.indices) {
            val item = remaining[eligible[k]]
            val penalty = if (prev2.isNotEmpty() && item.second.any { it in prev2 }) 0.35 else 1.0
            val w = item.third * penalty
            weights[k] = w
            total += w
        }
        var chosenSlot = eligible.last()
        if (total > 0.0) {
            val pick = random.nextDouble() * total
            var acc = 0.0
            for (k in eligible.indices) {
                acc += weights[k]
                if (pick <= acc) {
                    chosenSlot = eligible[k]
                    break
                }
            }
        }
        val picked = remaining.removeAt(chosenSlot)
        tail += picked.first
        prev2 = prev1
        prev1 = picked.second
    }
    val old = upcomingPlaybackQueue(entries, index, 2, previous).map { it.id }
    if (tail.size > 1 && listOf(currentEntry.id) + tail == old) tail.add(tail.removeAt(0))
    return listOf(currentEntry.id) + tail
}

/** translate an adjacent move in the wrapped upcoming view into canonical queue indices */
internal fun upcomingMoveDestination(fromCanonical: Int, targetCanonical: Int, movingDown: Boolean): Int {
    val insertion = targetCanonical + if (movingDown) 1 else 0
    return insertion - if (fromCanonical < insertion) 1 else 0
}

/** resolve an occurrence before filtering so a stale index must never select a different song */
internal fun playableSelection(context: List<dev.sillyplayer.data.Track>, track: dev.sillyplayer.data.Track,
    selectedIndex: Int): Pair<List<dev.sillyplayer.data.Track>, Int>? {
    if (!track.available || track.deleted || track.uri.isBlank()) return null
    val occurrence = selectedIndex.takeIf { context.getOrNull(it)?.id == track.id }
        ?: context.indexOfFirst { it.id == track.id }
    val rows = context.withIndex().filter { it.value.available && !it.value.deleted && it.value.uri.isNotBlank() }
    val selected = rows.indexOfFirst { it.index == occurrence }
    return if (selected < 0) null else rows.map { it.value } to selected
}
