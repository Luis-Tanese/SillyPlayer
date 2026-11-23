package dev.sillyplayer.media

fun playbackDiagnostic(code: String, failure: Throwable): String {
    val seen = mutableSetOf<Throwable>()
    val chain = generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.take(8).toList()
    val text = buildString {
        appendLine(code)
        for (cause in chain) {
            appendLine("${cause.javaClass.simpleName}: ${cause.message.orEmpty()}")
            cause.stackTrace.take(8).forEach { appendLine("  at $it") }
        }
    }
    // stream URLs can contain short-lived provider credentials
    // do not put them in shareable diagnostics
    return text.replace(Regex("https?://[^\\s)]+"), "<remote stream>").take(16000)
}
