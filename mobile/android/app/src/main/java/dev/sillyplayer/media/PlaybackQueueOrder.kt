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

private fun overlaps(a: IntArray, b: IntArray): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    for (x in a) {
        for (y in b) {
            if (x == y) return true
        }
    }
    return false
}

// spreads out same artist tracks and favors songs that havent played recently
// uses interned artist ids + fenwick weighted rejection sampling for O(N log N) runtime
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
    val poolEntries = entries.filter { it.id != currentEntry.id }
    val n = poolEntries.size
    if (n == 0) return listOf(currentEntry.id)

    val artistToId = HashMap<String, Int>(n)
    fun internArtists(raw: String): IntArray {
        val parts = splitArtists(raw)
        if (parts.isEmpty()) return IntArray(0)
        var tmp = IntArray(parts.size)
        var count = 0
        for (part in parts) {
            val key = part.trim().lowercase(Locale.ROOT)
            if (key.isEmpty() || key == "unknown artist") continue
            val id = artistToId.getOrPut(key) { artistToId.size }
            var dup = false
            for (k in 0 until count) {
                if (tmp[k] == id) {
                    dup = true
                    break
                }
            }
            if (!dup) tmp[count++] = id
        }
        return if (count == tmp.size) tmp else tmp.copyOf(count)
    }

    val entryIds = Array(n) { poolEntries[it].id }
    val entryArtists = Array(n) { internArtists(poolEntries[it].track.artist) }
    val weights = DoubleArray(n)
    val activePos = IntArray(n) { it }
    val activeList = IntArray(n) { it }
    var activeCount = n
    val bit = DoubleArray(n + 1)
    var bitTotal = 0.0

    for (i in 0 until n) {
        val last = lastPlayedByTrack[poolEntries[i].trackId] ?: 0L
        val w =
            if (last <= 0L) 3.0
            else {
                val ageDays = ((nowMs - last).coerceAtLeast(0L)).toDouble() / 86_400_000.0
                1.0 + 1.8 * minOf(1.0, ageDays / 7.0)
            }
        weights[i] = w
        bitTotal += w
    }

    // O(N) fenwick tree build
    for (i in 1..n) {
        bit[i] += weights[i - 1]
        val parent = i + (i and -i)
        if (parent <= n) bit[parent] += bit[i]
    }

    var prev1 = internArtists(currentEntry.track.artist)
    var prev2 = IntArray(0)

    val artistRemaining = IntArray(artistToId.size + 4)
    for (i in 0 until n) {
        for (a in entryArtists[i]) artistRemaining[a]++
    }

    var highestBit = 1
    while ((highestBit shl 1) <= n) highestBit = highestBit shl 1

    fun bitSample(): Int {
        if (bitTotal <= 0.0) return activeList[activeCount - 1]
        var target = random.nextDouble() * bitTotal
        var idx = 0
        var step = highestBit
        while (step > 0) {
            val next = idx + step
            if (next <= n && bit[next] < target) {
                idx = next
                target -= bit[next]
            }
            step = step shr 1
        }
        return if (idx < n && activePos[idx] >= 0) idx else activeList[activeCount - 1]
    }

    fun removeItem(slot: Int) {
        val w = weights[slot]
        bitTotal = (bitTotal - w).coerceAtLeast(0.0)
        var i = slot + 1
        while (i <= n) {
            bit[i] -= w
            i += i and -i
        }
        for (a in entryArtists[slot]) artistRemaining[a]--
        val pos = activePos[slot]
        val lastSlot = activeList[activeCount - 1]
        activeList[pos] = lastSlot
        activePos[lastSlot] = pos
        activeCount--
        activePos[slot] = -1
    }

    val tail = ArrayList<String>(n)
    while (activeCount > 0) {
        var chosen = -1
        var allBlocked = false
        for (a in prev1) {
            if (a < artistRemaining.size && artistRemaining[a] >= activeCount) {
                allBlocked = true
                break
            }
        }

        if (allBlocked) {
            for (attempt in 0 until 12) {
                val cand = bitSample()
                if (!overlaps(entryArtists[cand], prev2) || random.nextDouble() < 0.35) {
                    chosen = cand
                    break
                }
            }
        } else {
            for (attempt in 0 until 16) {
                val cand = bitSample()
                if (overlaps(entryArtists[cand], prev1)) continue
                if (overlaps(entryArtists[cand], prev2) && random.nextDouble() >= 0.35) continue
                chosen = cand
                break
            }
        }

        if (chosen < 0) {
            var eligibleTotal = 0.0
            var anyNonPrev1 = false
            for (k in 0 until activeCount) {
                val slot = activeList[k]
                if (!overlaps(entryArtists[slot], prev1)) {
                    anyNonPrev1 = true
                    var w = weights[slot]
                    if (overlaps(entryArtists[slot], prev2)) w *= 0.35
                    eligibleTotal += w
                }
            }
            if (!anyNonPrev1) {
                for (k in 0 until activeCount) {
                    val slot = activeList[k]
                    var w = weights[slot]
                    if (overlaps(entryArtists[slot], prev2)) w *= 0.35
                    eligibleTotal += w
                }
            }
            chosen = activeList[activeCount - 1]
            if (eligibleTotal > 0.0) {
                val pick = random.nextDouble() * eligibleTotal
                var acc = 0.0
                for (k in 0 until activeCount) {
                    val slot = activeList[k]
                    if (anyNonPrev1 && overlaps(entryArtists[slot], prev1)) continue
                    var w = weights[slot]
                    if (overlaps(entryArtists[slot], prev2)) w *= 0.35
                    acc += w
                    if (pick <= acc) {
                        chosen = slot
                        break
                    }
                }
            }
        }

        tail += entryIds[chosen]
        prev2 = prev1
        prev1 = entryArtists[chosen]
        removeItem(chosen)
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
