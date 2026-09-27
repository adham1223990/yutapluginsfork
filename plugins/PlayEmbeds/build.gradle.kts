version = "1.0.1"
description = "Plays direct video and audio link embeds in Discord's built-in media player."

aliucord {
    changelog.set(
        """
        # 1.0.1
        * Improve YouTube player loading and error reporting.
        * Play hosted media inside the original image area at its default size, preserving the embed layout.
        * Prevent panel swipes from interrupting the inline YouTube seekbar.
        * Fix search-result clicks playing embeds instead of jumping to the message.

        # 1.0.0
        * Play direct video and audio link embeds in Discord's built-in media player.
        """.trimIndent(),
    )
}
