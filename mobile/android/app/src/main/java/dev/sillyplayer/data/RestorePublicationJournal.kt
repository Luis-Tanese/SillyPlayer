package dev.sillyplayer.data

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.Reader
import java.io.StringReader
import kotlinx.serialization.json.*

private const val MAX_JOURNAL_BYTES = 16 * 1024 * 1024
private const val MAX_JOURNAL_RECORDS = 100000
private const val MAX_JOURNAL_LINE = 64 * 1024

internal data class RestorePublications(val uris: List<String>, val problem: String? = null)

/** the record is durable before publication starts copying bytes into the new document */
internal fun appendRestorePublication(file: File, uri: String) {
    val record = (JsonPrimitive(uri).toString() + "\n").toByteArray()
    require(record.size <= MAX_JOURNAL_LINE && file.length() + record.size <= MAX_JOURNAL_BYTES) {
        "Restore publication journal limit exceeded"
    }
    FileOutputStream(file, true).use { output ->
        output.write(record)
        output.flush()
        output.fd.sync()
    }
}

internal fun readRestorePublications(file: File): RestorePublications {
    require(file.length() <= MAX_JOURNAL_BYTES) { "Restore publication journal is too large" }
    return file.bufferedReader().use(::parseRestorePublications)
}

internal fun parseRestorePublications(text: String): RestorePublications =
    StringReader(text).buffered().use(::parseRestorePublications)

private fun publicationUri(value: JsonElement): String {
    val primitive = value as? JsonPrimitive
    require(primitive != null && primitive.isString && primitive.content.isNotBlank()) {
        "Invalid restore publication URI"
    }
    require(primitive.content.length <= MAX_JOURNAL_LINE) { "Restore publication URI is too long" }
    return primitive.content
}

/** accept old arrays and new JSON-string lines, retaining diagnostics for damaged complete records */
private fun parseRestorePublications(source: Reader): RestorePublications {
    val reader = if (source is BufferedReader) source else source.buffered()
    var characters = 0
    fun next(): Int {
        val value = reader.read()
        if (value >= 0) require(++characters <= MAX_JOURNAL_BYTES) { "Restore publication journal is too large" }
        return value
    }
    var first = next()
    var lineNumber = 1
    while (first >= 0 && first.toChar().isWhitespace()) {
        if (first == '\n'.code) lineNumber++
        first = next()
    }
    if (first < 0) return RestorePublications(emptyList())
    if (first == '['.code) {
        val legacy = StringBuilder().append('[')
        var value = next()
        while (value >= 0) { legacy.append(value.toChar()); value = next() }
        val entries = JsonCodec.parseToJsonElement(legacy.toString()).jsonArray
        require(entries.size <= MAX_JOURNAL_RECORDS) { "Too many restore publication records" }
        return RestorePublications(entries.map(::publicationUri))
    }
    val uris = mutableListOf<String>()
    var problem: String? = null
    var value = first
    while (value >= 0) {
        val line = StringBuilder()
        var tooLong = false
        while (value >= 0 && value != '\n'.code) {
            if (line.length < MAX_JOURNAL_LINE) line.append(value.toChar()) else tooLong = true
            value = next()
        }
        val terminated = value == '\n'.code
        if (line.isNotBlank() || tooLong) {
            val parsed = runCatching {
                require(!tooLong) { "Restore publication record is too long" }
                publicationUri(JsonCodec.parseToJsonElement(line.toString()))
            }
            if (parsed.isSuccess) {
                require(uris.size < MAX_JOURNAL_RECORDS) { "Too many restore publication records" }
                uris += parsed.getOrThrow()
            } else if (terminated && problem == null) {
                problem = "Invalid restore publication record on line $lineNumber: ${parsed.exceptionOrNull()?.message}"
            }
            // a torn final append has no newline, earlier complete entries remain recoverable
        }
        lineNumber++
        value = if (terminated) next() else -1
    }
    return RestorePublications(uris, problem)
}
