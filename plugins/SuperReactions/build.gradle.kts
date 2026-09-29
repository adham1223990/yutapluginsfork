version = "1.0.7"
description = "Backport super reactions."
aliucord {
    changelog.set(
        """
        # 1.0.6
        * Fully rewrite the plugin in Kotlin
        * Improve emoji-colored shine, selection, and pending feedback
        * Fix repeated taps and reaction types on messages with many reactions
        * Queue requests, prioritize taps, and honor Discord rate-limit cooldowns
        * Fix super-reaction ownership and keep normal and super reactions separate
        * Fix stale reaction counts, member lists, and cleanup when disabling the plugin
        # 1.0.5
        * Reduce reaction metadata requests to avoid Discord rate limits on messages with many reactions
        * Add rate-limit backoff and stop retrying alternate send endpoints after HTTP 429
        * Fix existing super-reaction counts resetting after sending a new super reaction
        # 1.0.4
        * Fix shine not applying sometimes
        * Fix not being able to remove a super-reaction sent from official Discord
        * Move the context menu button to the bottom
        # 1.0.3
        * Fix member list
        # 1.0.2
        * Fixes
        # 1.0.1
        * Match shine color to emoji
        """.trimIndent(),
    )
}
