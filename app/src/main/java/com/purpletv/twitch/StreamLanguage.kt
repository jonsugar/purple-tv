package com.purpletv.twitch

enum class StreamLanguage(val code: String?) {
    ALL(null),
    ENGLISH("en");

    fun matches(stream: Stream): Boolean = code == null || code == stream.language

    fun filter(streams: List<Stream>): List<Stream> = if (this == ALL) streams else streams.filter(::matches)
}
