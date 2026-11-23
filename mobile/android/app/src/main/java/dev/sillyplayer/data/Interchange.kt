package dev.sillyplayer.data

import kotlinx.serialization.json.*

/** desktop SQLite exports flags as integers; Android uses JSON booleans */
fun decodeBackupTrack(fields: JsonObject): Track {
    val normalized = fields.toMutableMap()
    normalized["nightcore"] =
        JsonPrimitive(
            fields["nightcore"]?.jsonPrimitive?.booleanOrNull
                ?: (fields["nightcore"]?.jsonPrimitive?.intOrNull == 1)
        )
    return JsonCodec.decodeFromJsonElement<Track>(JsonObject(normalized))
}

/** a component survives track ID remapping, so repeated syncs merge by maxima */
fun mergeCounter(local: Counter?, remote: Counter): Counter {
    require(local == null || (local.device == remote.device && local.component == remote.component))
    return remote.copy(
        milliseconds = maxOf(remote.milliseconds, local?.milliseconds ?: 0),
        plays = maxOf(remote.plays, local?.plays ?: 0),
        lastPlayed = maxOf(remote.lastPlayed, local?.lastPlayed ?: 0),
    )
}

fun restoreQueue(saved: QueueState, indexed: Map<String, Track>): QueueState {
    val entries =
        saved.entries.mapNotNull { e ->
            var track = indexed[e.trackId]
            val seen = mutableSetOf<String>()
            while (track?.mergedInto?.isNotBlank() == true && seen.add(track.id)) track =
                indexed[track.mergedInto]
            track
                ?.takeIf { it.uri.isNotBlank() && !it.deleted }
                ?.let { e.copy(track = it, trackId = it.id) }
        }
    val current = saved.entries.getOrNull(saved.index)?.id
    val index = entries.indexOfFirst { it.id == current }
    return saved.copy(
        entries = entries,
        index = index.coerceAtLeast(0),
        position = if (index >= 0) saved.position else 0,
        shuffleOrder = if (saved.shuffleOrder.isEmpty()) emptyList()
            else dev.sillyplayer.media.normalizedQueueShuffle(saved.shuffleOrder, entries),
    )
}

fun normalizedOrder(requested: List<String>, current: List<Member>): List<Member> {
    require(requested.size <= 100000 && requested.distinct().size == requested.size) {
        "Invalid collection occurrence order"
    }
    val byId = current.associateBy { it.id }
    val chosen = requested.toSet()
    val ids = requested.filter { it in byId } + current.map { it.id }.filter { it !in chosen }
    return ids.mapIndexed { index, id -> byId.getValue(id).copy(position = index) }
}

private val parenFeatRegex = Regex("\\s*[\\(\\[]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+([^\\)\\]]+)[\\)\\]]", RegexOption.IGNORE_CASE)
private val artistSeparatorRegex = Regex("\\s*(?:,|;|\\s/\\s|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+)\\s*", RegexOption.IGNORE_CASE)

// split multi-artist tags into individual artists while keeping names like "AC/DC" or "Simon & Garfunkel" intact
fun splitArtists(raw: String): List<String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return listOf("Unknown artist")
    val normalized = trimmed.replace(parenFeatRegex, ", $1")
    val seen = HashSet<String>()
    val result = ArrayList<String>()
    for (segment in normalized.split(artistSeparatorRegex)) {
        val artist = segment.trim()
        if (artist.isEmpty()) continue
        if (seen.add(artist.lowercase(java.util.Locale.ROOT))) result += artist
    }
    return result.ifEmpty { listOf("Unknown artist") }
}

private val titleBracketJunkRegex =
    Regex(
        """\s*[\(\[]\s*(?:(?:official\s+)?(?:music\s+|lyric\s+|lyrics\s+|hd\s+|hq\s+|4k\s+|full\s+)?(?:video|audio|visualizer|mv|m/v|clip)|(?:with\s+)?lyrics?|audio\s+only|full\s+audio|hd|hq|4k|1080p|720p|mv|m/v)\s*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
private val titleTrailingJunkRegex =
    Regex(
        """\s*(?:\||//|-|–|—)\s*(?:official\s+)?(?:(?:music|lyric|lyrics|hd|hq|4k|full)\s+)?(?:video|audio|visualizer|lyrics?|mv|m/v)\s*$""",
        RegexOption.IGNORE_CASE,
    )
private val titleMultiSpaceRegex = Regex("""\s{2,}""")
private val titleTrailingSepRegex = Regex("""\s*[\|\-–—/]+\s*$""")

fun cleanSongTitle(raw: String): String {
    val original = raw.trim()
    if (original.isEmpty()) return original
    var s = original.replace(titleBracketJunkRegex, "")
    s = s.replace(titleTrailingJunkRegex, "")
    s = s.replace(titleMultiSpaceRegex, " ").trim()
    s = s.replace(titleTrailingSepRegex, "").trim()
    return s.ifEmpty { original }
}

data class M3uEntry(
    val path: String,
    val title: String = "",
    val artist: String = "",
    val duration: Int = -1,
)

fun formatM3u(tracks: List<Track>): String {
    val sb = StringBuilder("#EXTM3U\n")
    for (track in tracks) {
        val loc = track.fileName.ifBlank { track.uri }.trim()
        if (loc.isEmpty()) continue
        val seconds = kotlin.math.round(track.duration).toInt().let { if (it > 0) it else -1 }
        val artist = track.artist.trim()
        val title = track.title.trim()
        val display =
            when {
                artist.isNotEmpty() && !artist.equals("Unknown artist", ignoreCase = true) && title.isNotEmpty() ->
                    "$artist - $title"
                title.isNotEmpty() -> title
                artist.isNotEmpty() -> artist
                else -> loc.substringAfterLast('/').substringBeforeLast('.')
            }
        sb.append("#EXTINF:").append(seconds).append(',').append(display).append('\n')
        sb.append(loc).append('\n')
    }
    return sb.toString()
}

fun parseM3u(content: String): List<M3uEntry> {
    val text = content.removePrefix("\uFEFF")
    val entries = ArrayList<M3uEntry>()
    var pendingTitle = ""
    var pendingArtist = ""
    var pendingDuration = -1

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF:", ignoreCase = true)) {
            val rest = line.substring(8)
            val comma = rest.indexOf(',')
            if (comma >= 0) {
                pendingDuration = rest.substring(0, comma).trim().toIntOrNull() ?: -1
                val display = rest.substring(comma + 1).trim()
                val dash = display.indexOf(" - ")
                if (dash > 0) {
                    pendingArtist = display.substring(0, dash).trim()
                    pendingTitle = display.substring(dash + 3).trim()
                } else {
                    pendingArtist = ""
                    pendingTitle = display
                }
            }
            continue
        }
        if (line.startsWith("#")) continue
        entries += M3uEntry(line, pendingTitle, pendingArtist, pendingDuration)
        pendingTitle = ""
        pendingArtist = ""
        pendingDuration = -1
    }
    return entries
}

data class ListeningBucket(val label: String, val count: Int)

data class ListeningInsights(
    val period: String,
    val totalPlays: Long,
    val uniqueSongs: Int,
    val uniqueArtists: Int,
    val activeDays: Int,
    val streakDays: Int,
    val topArtistName: String,
    val topArtistPlays: Long,
    val topTrackTitle: String,
    val topTrackPlays: Long,
    val peakSummary: String,
    val chartTitle: String,
    val maxBucket: Int,
    val buckets: List<ListeningBucket>,
)

fun computeListeningInsights(
    period: String,
    plays: List<Play>,
    counters: List<Counter>,
    tracks: List<Track>,
    nowMs: Long = System.currentTimeMillis(),
    timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): ListeningInsights {
    val cal = java.util.Calendar.getInstance(timeZone).apply {
        timeInMillis = nowMs
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val todayStart = cal.timeInMillis
    val cutoff =
        when (period) {
            "day" -> todayStart
            "week" -> todayStart - 6L * 86400000L
            "month" -> todayStart - 29L * 86400000L
            else -> 0L
        }

    val trackMap = tracks.associateBy { it.id }
    val countsByTrack: Map<String, Long> =
        if (period == "all") {
            counters.groupBy { it.track }.mapValues { it.value.sumOf { c -> c.plays } }
        } else {
            plays
                .filter { it.playedAt >= cutoff && it.track in trackMap }
                .groupingBy { it.track }
                .eachCount()
                .mapValues { it.value.toLong() }
        }

    val activeTracks = tracks.filter { (countsByTrack[it.id] ?: 0L) > 0L }
    val totalPlays = activeTracks.sumOf { countsByTrack[it.id] ?: 0L }
    val topTrack = activeTracks.maxByOrNull { countsByTrack[it.id] ?: 0L }

    val artistTotals = LinkedHashMap<String, Pair<String, Long>>()
    for (t in activeTracks) {
        val c = countsByTrack[t.id] ?: 0L
        for (artist in splitArtists(t.artist)) {
            if (artist.equals("Unknown artist", ignoreCase = true)) continue
            val key = artist.lowercase(java.util.Locale.ROOT)
            val prev = artistTotals[key]
            artistTotals[key] = (prev?.first ?: artist) to ((prev?.second ?: 0L) + c)
        }
    }
    val topArtist = artistTotals.values.maxByOrNull { it.second }

    fun dayKey(ms: Long): Long {
        val c = java.util.Calendar.getInstance(timeZone).apply {
            timeInMillis = ms
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    val allPlayDays = HashSet<Long>()
    val periodActiveDays = HashSet<Long>()
    val dailyCounts = HashMap<Long, Int>()
    val timeBlocks = IntArray(6)
    val weekdayCounts = IntArray(7) // Mon(0)..Sun(6)

    for (p in plays) {
        if (p.track !in trackMap) continue
        val dk = dayKey(p.playedAt)
        allPlayDays += dk
        if (p.playedAt >= cutoff) {
            periodActiveDays += dk
            dailyCounts[dk] = (dailyCounts[dk] ?: 0) + 1
            val c = java.util.Calendar.getInstance(timeZone).apply { timeInMillis = p.playedAt }
            val hour = c.get(java.util.Calendar.HOUR_OF_DAY)
            timeBlocks[(hour / 4).coerceIn(0, 5)]++
            val dow = when (c.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> 0
                java.util.Calendar.TUESDAY -> 1
                java.util.Calendar.WEDNESDAY -> 2
                java.util.Calendar.THURSDAY -> 3
                java.util.Calendar.FRIDAY -> 4
                java.util.Calendar.SATURDAY -> 5
                else -> 6
            }
            weekdayCounts[dow]++
        }
    }

    var streakDays = 0
    val streakCal = java.util.Calendar.getInstance(timeZone).apply { timeInMillis = todayStart }
    if (streakCal.timeInMillis !in allPlayDays) {
        streakCal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    while (streakCal.timeInMillis in allPlayDays) {
        streakDays++
        streakCal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    }

    val blockLabels = listOf("12a", "4a", "8a", "12p", "4p", "8p")
    val blockNames =
        listOf(
            "Late night (12–4 AM)",
            "Early morning (4–8 AM)",
            "Morning (8 AM–12 PM)",
            "Afternoon (12–4 PM)",
            "Evening (4–8 PM)",
            "Night (8 PM–12 AM)",
        )
    val weekShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val weekFull = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    val peakBlock = timeBlocks.indices.maxByOrNull { timeBlocks[it] }?.takeIf { timeBlocks[it] > 0 } ?: -1
    val peakDay = weekdayCounts.indices.maxByOrNull { weekdayCounts[it] }?.takeIf { weekdayCounts[it] > 0 } ?: -1

    val buckets =
        when (period) {
            "day" -> blockLabels.mapIndexed { idx, lbl -> ListeningBucket(lbl, timeBlocks[idx]) }
            "week" -> {
                (6 downTo 0).map { offset ->
                    val dCal = java.util.Calendar.getInstance(timeZone).apply {
                        timeInMillis = todayStart
                        add(java.util.Calendar.DAY_OF_YEAR, -offset)
                    }
                    val dow = when (dCal.get(java.util.Calendar.DAY_OF_WEEK)) {
                        java.util.Calendar.MONDAY -> 0
                        java.util.Calendar.TUESDAY -> 1
                        java.util.Calendar.WEDNESDAY -> 2
                        java.util.Calendar.THURSDAY -> 3
                        java.util.Calendar.FRIDAY -> 4
                        java.util.Calendar.SATURDAY -> 5
                        else -> 6
                    }
                    val label = if (offset == 0) "Today" else weekShort[dow]
                    ListeningBucket(label, dailyCounts[dCal.timeInMillis] ?: 0)
                }
            }
            "month" -> {
                val fmt = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).apply {
                    this.timeZone = timeZone
                }
                (29 downTo 0).map { offset ->
                    val dCal = java.util.Calendar.getInstance(timeZone).apply {
                        timeInMillis = todayStart
                        add(java.util.Calendar.DAY_OF_YEAR, -offset)
                    }
                    val label =
                        if (offset == 29 || offset == 15 || offset == 0) fmt.format(dCal.time) else ""
                    ListeningBucket(label, dailyCounts[dCal.timeInMillis] ?: 0)
                }
            }
            else -> weekShort.mapIndexed { idx, lbl -> ListeningBucket(lbl, weekdayCounts[idx]) }
        }

    val peakSummary =
        when {
            period == "day" && peakBlock >= 0 -> blockNames[peakBlock]
            period == "day" -> "No plays today yet"
            peakDay >= 0 && peakBlock >= 0 -> "${weekFull[peakDay]} · ${blockNames[peakBlock]}"
            peakDay >= 0 -> weekFull[peakDay]
            else -> "No recent timestamps yet"
        }

    val chartTitle =
        when (period) {
            "day" -> "Today by time of day"
            "week" -> "Last 7 days"
            "month" -> "Last 30 days"
            else -> "Recent listening by weekday"
        }

    return ListeningInsights(
        period = period,
        totalPlays = totalPlays,
        uniqueSongs = activeTracks.size,
        uniqueArtists = artistTotals.size,
        activeDays = periodActiveDays.size,
        streakDays = streakDays,
        topArtistName = topArtist?.first ?: "—",
        topArtistPlays = topArtist?.second ?: 0L,
        topTrackTitle = topTrack?.title ?: "—",
        topTrackPlays = topTrack?.let { countsByTrack[it.id] ?: 0L } ?: 0L,
        peakSummary = peakSummary,
        chartTitle = chartTitle,
        maxBucket = buckets.maxOfOrNull { it.count } ?: 0,
        buckets = buckets,
    )
}
