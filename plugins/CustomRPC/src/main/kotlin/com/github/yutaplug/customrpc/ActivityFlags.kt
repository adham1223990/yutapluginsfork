package com.github.yutaplug.customrpc

object ActivityFlags {
    val labels =
        arrayOf(
            "Instance",
            "Join",
            "Spectate",
            "Join request",
            "Sync",
            "Play",
            "Party privacy: friends",
            "Party privacy: voice channel",
            "Embedded",
        )
    val values = IntArray(labels.size) { 1 shl it }

    fun label(flags: Int) = labels.filterIndexed { index, _ -> flags and values[index] != 0 }.joinToString().ifEmpty {
        "None"
    }
}
