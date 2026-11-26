package dev.sillyplayer.media

/** JNI owns only duplicated descriptors, call all methods off the UI thread */
object NativeMedia {
    init {
        System.loadLibrary("sillymedia")
    }


    external fun tags(fd: Int, name: String): String

    external fun inspect(fd: Int, name: String, outArt: Array<ByteArray?>): String

    external fun artwork(fd: Int, name: String): ByteArray?

    external fun edit(path: String, tags: String, cover: String): Boolean

    external fun convert(
        input: String,
        output: String,
        mp3: Boolean,
        certificates: String,
        headers: String,
        callback: Progress,
    ): Boolean

    interface Progress {
        fun update(done: Long, total: Long): Boolean
    }
}
