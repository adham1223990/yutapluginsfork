package com.github.yutaplug.imagedescriptions

/** Avoid text helpers whose range iterators differ in Discord's obfuscated Kotlin runtime. */
internal object DescriptionText {
    fun hasText(value: String?): Boolean {
        if (value == null) return false
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
            index++
        }
        return false
    }
}
