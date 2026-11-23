package dev.sillyplayer.data

import kotlinx.serialization.json.*

fun Track.isRemovalTombstone(): Boolean = deleted && mergedInto == "sync-deleted"

fun Track.removedFromLibrary(): Track = copy(deleted = true, available = false, mergedInto = "sync-deleted")

fun Track.removeWithoutAudio(readable: Boolean): Boolean = !available || uri.isBlank() || !readable

/** an older remote edit must not undo a local deletion which is still awaiting sync */
fun visibleSyncPayload(remote: JsonElement?, base: JsonObject, pending: Outbox?): JsonElement? {
    if (pending != null && pending.payload == null) return null
    if (remote !is JsonObject || pending?.payload == null) return remote
    val local = JsonCodec.parseToJsonElement(pending.payload).jsonObject
    val merged = remote.toMutableMap()
    for ((field, value) in local) if (value != base[field]) merged[field] = value
    return JsonObject(merged)
}

private val reviewedTrackFields = listOf("title", "artist", "album", "albumArtist", "genre", "year", "number", "duration", "speed", "nightcore", "volumePercent", "sourceUrl", "uploader", "videoTitle", "sourceProvider", "sourceItemId", "audioHash", "artHash")

fun changedTrackFields(a: JsonElement?, b: JsonElement?): List<String> {
    val left = a as? JsonObject
    val right = b as? JsonObject
    if (left == null || right == null) return if (left == right) emptyList() else listOf("deletion")
    return reviewedTrackFields.filter { key ->
        val x = left[key]; val y = right[key]
        val xn = (x as? JsonPrimitive)?.doubleOrNull
        val yn = (y as? JsonPrimitive)?.doubleOrNull
        if (xn != null && yn != null) kotlin.math.abs(xn - yn) > .000001 else x != y
    }
}

fun needsDesktopReview(base: JsonElement?, desktop: JsonElement?, phone: JsonElement?): Boolean =
    changedTrackFields(base, desktop).isNotEmpty() && changedTrackFields(phone, desktop).isNotEmpty()
