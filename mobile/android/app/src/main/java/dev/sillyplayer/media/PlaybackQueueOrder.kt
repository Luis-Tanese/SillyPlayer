package dev.sillyplayer.media

import dev.sillyplayer.data.QueueEntry

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
