package com.github.yutaplug.notifications

// Avoid compiler-generated enumEntries calls, absent from Discord 126.21's Kotlin runtime.
internal class NotificationOption private constructor(
    val field: Int,
    val title: String,
    val description: String,
    val section: Int = 7,
    val scalarValue: Int? = null,
) {
    companion object {
        val REACTION_ALL = NotificationOption(7, "All Messages", "", scalarValue = 0)
        val REACTION_DMS = NotificationOption(7, "Only Direct Messages", "", scalarValue = 1)
        val REACTION_NONE = NotificationOption(7, "Never", "", scalarValue = 2)
        val SHARE_ONLINE = NotificationOption(
            25,
            "Share when I come online",
            "Allow friends to receive a push notification when you come online.",
        )
        val SHARE_PROFILE = NotificationOption(
            24,
            "Share when I update my profile",
            "Allow friends to receive a push notification when you update your profile.",
        )
        val STREAM = NotificationOption(
            7,
            "Get notifications when your friends stream",
            "",
            section = 5,
        )
        val ANNIVERSARY = NotificationOption(
            14,
            "Friendship Anniversary",
            "Receive a notification when you and a friend reach a friendship anniversary, so you can celebrate together.",
        )
        val VOICE = NotificationOption(
            11,
            "Voice Activity Notifications",
            "Receive a notification when your closest friends join voice channels in servers with 200 members or fewer.",
        )
        val ONLINE =
            NotificationOption(
                12,
                "Friends Online",
                "Receive a notification when your friend comes online " +
                    "for more than a few minutes.",
            )
        val STATUS = NotificationOption(9, "Status Notifications", "Status updates from people you talk to.")
        val GAMING =
            NotificationOption(
                21,
                "Friend Gaming Activity",
                "Receive notifications about your friends' gaming activity.",
            )
        val PROFILE =
            NotificationOption(16, "Profile Updates", "Receive a notification when a friend updates their profile.")

        val EVENTS = NotificationOption(
            22,
            "Upcoming Server Events",
            "Receive a notification when a server you are in has an upcoming event.",
        )

        fun reactions(): Array<NotificationOption> = arrayOf(REACTION_ALL, REACTION_DMS, REACTION_NONE)

        fun other(): Array<NotificationOption> =
            arrayOf(STREAM, ANNIVERSARY, VOICE, ONLINE, STATUS, GAMING, PROFILE, EVENTS)

        fun values(): Array<NotificationOption> = reactions() + other() + arrayOf(SHARE_ONLINE, SHARE_PROFILE)
    }
}
