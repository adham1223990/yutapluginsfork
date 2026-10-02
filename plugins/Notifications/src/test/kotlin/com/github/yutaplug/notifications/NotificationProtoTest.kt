package com.github.yutaplug.notifications

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationProtoTest {
    @Test
    fun missingPreferencesUseDiscordDefaults() {
        val values = NotificationProto.values(byteArrayOf())
        assertTrue(values.filterKeys { it.scalarValue == null }.values.all { it })
        assertTrue(values.getValue(NotificationOption.REACTION_ALL))
        assertFalse(values.getValue(NotificationOption.REACTION_DMS))
        assertFalse(values.getValue(NotificationOption.REACTION_NONE))
    }

    @Test
    fun explicitFalseWrappersAndDisabledStatusAreRead() {
        // notifications { voice: BoolValue(false), online: BoolValue(true), status: DISABLED }
        val values = NotificationProto.values(hex("3a 0a 5a 02 08 00 62 02 08 01 48 02"))
        assertFalse(values.getValue(NotificationOption.VOICE))
        assertTrue(values.getValue(NotificationOption.ONLINE))
        assertFalse(values.getValue(NotificationOption.STATUS))
        // An empty BoolValue is explicit false, unlike an absent wrapper.
        assertFalse(NotificationProto.values(hex("3a 02 72 00")).getValue(NotificationOption.ANNIVERSARY))
    }

    @Test
    fun patchPreservesUnknownRepeatedAndFixedWidthFields() {
        val settings = hex("0a 02 18 07 3a 13 19 01 02 03 04 05 06 07 08 a0 06 03 a0 06 04 5a 02 08 01 6a 00")
        val actual = NotificationProto.patch(settings, mapOf(NotificationOption.VOICE to false))
        // Only notifications is sent; its fixed64 and repeated unknown fields survive verbatim.
        assertArrayEquals(hex("3a 13 19 01 02 03 04 05 06 07 08 a0 06 03 a0 06 04 5a 02 08 00"), actual)
        assertEquals(7L, NotificationProto.dataVersion(settings))
    }

    @Test
    fun batchEncodesWrapperAndEnumAndTwoByteTags() {
        assertArrayEquals(
            hex("3a 09 72 00 aa 01 02 08 00 48 01"),
            NotificationProto.patch(
                hex("3a 02 72 00"),
                linkedMapOf(
                    NotificationOption.GAMING to false,
                    NotificationOption.STATUS to true,
                ),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedSectionIsRejected() {
        NotificationProto.values(hex("3a 04 5a 02"))
    }

    @Test
    fun streamAndEventsReadFromTheirOwnSections() {
        val values = NotificationProto.values(hex("2a 04 3a 02 08 00 3a 05 b2 01 02 08 00"))
        assertFalse(values.getValue(NotificationOption.STREAM))
        assertFalse(values.getValue(NotificationOption.EVENTS))
        assertTrue(values.getValue(NotificationOption.ANNIVERSARY))
    }

    @Test
    fun streamAndEventsPatchPreservesBothSections() {
        // Keep voice/video's afk timeout and notification settings' quiet mode.
        val original = hex("2a 08 32 02 08 3c 3a 02 08 00 3a 09 2a 02 08 01 b2 01 02 08 00")
        assertArrayEquals(
            hex("2a 08 32 02 08 3c 3a 02 08 01 3a 09 2a 02 08 01 b2 01 02 08 01"),
            NotificationProto.patch(
                original,
                linkedMapOf(
                    NotificationOption.STREAM to true,
                    NotificationOption.EVENTS to true,
                ),
            ),
        )
        assertArrayEquals(
            hex("3a 09 2a 02 08 01 b2 01 02 08 01"),
            NotificationProto.patch(original, mapOf(NotificationOption.EVENTS to true)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun overflowingVarintIsRejected() {
        NotificationProto.values(hex("80 80 80 80 80 80 80 80 80 02"))
    }

    @Test
    fun reactionAndSharingSettingsUseCorrectWireTypes() {
        val patch = NotificationProto.patch(
            byteArrayOf(),
            linkedMapOf(
                NotificationOption.REACTION_DMS to true,
                NotificationOption.SHARE_ONLINE to false,
                NotificationOption.SHARE_PROFILE to false,
            ),
        )
        assertArrayEquals(hex("3a 0c 38 01 ca 01 02 08 00 c2 01 02 08 00"), patch)
        val values = NotificationProto.values(patch)
        assertFalse(values.getValue(NotificationOption.REACTION_ALL))
        assertTrue(values.getValue(NotificationOption.REACTION_DMS))
        assertFalse(values.getValue(NotificationOption.REACTION_NONE))
        assertFalse(values.getValue(NotificationOption.SHARE_ONLINE))
        assertFalse(values.getValue(NotificationOption.SHARE_PROFILE))
    }

    @Test
    fun badgeChangesPreserveUnrelatedFlags() {
        assertEquals(0xb5, BadgePreferences.changedFlags(0x85, mapOf(16 to true, 32 to true)))
        assertEquals(0xc5, BadgePreferences.changedFlags(0xf5, mapOf(16 to false, 32 to false)))
    }

    private fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
