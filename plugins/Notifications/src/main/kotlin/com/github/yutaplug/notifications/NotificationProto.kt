package com.github.yutaplug.notifications

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire handling that preserves fields we do not understand. */
internal object NotificationProto {
    private data class Field(
        val number: Int,
        val wire: Int,
        val value: ByteArray,
        val raw: ByteArray,
    )

    // Schema: discord-userdoccers/discord-protos, discord_users/v1/PreloadedUserSettings.proto.
    // notifications is top-level field 7, voice_and_video is field 5.
    // BoolValue.value is nested field 1.
    fun values(settings: ByteArray): Map<NotificationOption, Boolean> {
        return NotificationOption.values().associateWith { option ->
            val section = message(settings, option.section) ?: byteArrayOf()
            if (option.scalarValue != null) {
                scalar(section, option.field) == option.scalarValue.toLong()
            } else if (option == NotificationOption.STATUS) {
                scalar(section, option.field) != 2L
            } else {
                message(section, option.field)?.let { scalar(it, 1) != 0L } ?: true
            }
        }
    }

    fun dataVersion(settings: ByteArray): Long = scalar(message(settings, 1) ?: byteArrayOf(), 3)

    fun patch(settings: ByteArray, changes: Map<NotificationOption, Boolean>): ByteArray {
        val sections = linkedMapOf<Int, ByteArray>()
        for ((option, enabled) in changes) {
            val section = sections[option.section] ?: message(settings, option.section) ?: byteArrayOf()
            val replacement = if (option.scalarValue != null) {
                require(enabled) { "Select a reaction notification mode" }
                varint(option.field.toLong() shl 3) + varint(option.scalarValue.toLong())
            } else if (option == NotificationOption.STATUS) {
                varint(option.field.toLong() shl 3) + varint(if (enabled) 1L else 2L)
            } else {
                val wrapper = message(section, option.field) ?: byteArrayOf()
                bytes(option.field, replace(wrapper, 1, varint(8) + varint(if (enabled) 1L else 0L)))
            }
            sections[option.section] = replace(section, option.field, replacement)
        }
        // Discord replaces each submitted section. Preserve all its other fields,
        // and omit top-level sections which were not changed.
        return ByteArrayOutputStream()
            .apply {
                for ((number, section) in sections) write(bytes(number, section))
            }.toByteArray()
    }

    private fun message(data: ByteArray, number: Int): ByteArray? =
        fields(data).lastOrNull { it.number == number && it.wire == 2 }?.value

    private fun scalar(data: ByteArray, number: Int): Long =
        fields(data).lastOrNull { it.number == number && it.wire == 0 }?.value?.let {
            var result = 0L
            it.forEachIndexed { index, byte -> result = result or ((byte.toLong() and 127) shl (index * 7)) }
            result
        } ?: 0L

    private fun replace(data: ByteArray, number: Int, replacement: ByteArray): ByteArray = ByteArrayOutputStream()
        .apply {
            fields(data).filter { it.number != number }.forEach { write(it.raw) }
            write(replacement)
        }.toByteArray()

    private fun bytes(number: Int, value: ByteArray): ByteArray =
        varint((number.toLong() shl 3) or 2) + varint(value.size.toLong()) + value

    private fun varint(value: Long): ByteArray = ByteArrayOutputStream()
        .apply {
            var remaining = value
            do {
                val next = (remaining and 127).toInt()
                remaining = remaining ushr 7
                write(next or if (remaining != 0L) 128 else 0)
            } while (remaining != 0L)
        }.toByteArray()

    private fun fields(data: ByteArray): List<Field> {
        var position = 0

        fun readVarint(): Long {
            var value = 0L
            // Discord's Kotlin runtime renames IntProgression.Companion. Avoid
            // the stdlib step() call, which still references the original field.
            var shift = 0
            while (shift <= 63) {
                require(position < data.size) { "Truncated protobuf varint" }
                val byte = data[position++].toInt() and 255
                require(shift != 63 || byte <= 1) { "Invalid protobuf varint" }
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
                shift += 7
            }
            error("Invalid protobuf varint")
        }
        val result = ArrayList<Field>()
        while (position < data.size) {
            val start = position
            val tag = readVarint()
            require(tag >= 8 && tag <= 0xffffffffL) { "Invalid protobuf tag" }
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            val valueStart: Int
            when (wire) {
                0 -> {
                    valueStart = position
                    readVarint()
                }

                1, 5 -> {
                    valueStart = position
                    position += if (wire == 1) 8 else 4
                }

                2 -> {
                    val length = readVarint()
                    require(length >= 0 && length <= (data.size - position).toLong()) { "Invalid protobuf length" }
                    valueStart = position
                    position += length.toInt()
                }

                else -> {
                    error("Unsupported protobuf wire type: $wire")
                }
            }
            require(position <= data.size) { "Truncated protobuf field" }
            result.add(Field(number, wire, data.copyOfRange(valueStart, position), data.copyOfRange(start, position)))
        }
        return result
    }
}
