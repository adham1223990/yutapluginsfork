version = "1.0.2"
description = "Recreates Discord desktop's IRC-style compact chat layout."

aliucord {
    changelog.set(
        """
        # 1.0.2
        * Make it more like desktop-style and fix spacing issues.
        # 1.0.1
        * Rewrite the plugin entirely in Kotlin.
        * Hide avatars by default and add a Show avatars setting.
        * Fix author and avatar alignment for grouped messages and replies.
        * Preserve themed author fonts and remove forced bold styling.
        * Align authors and message text on the same baseline.
        * Wrap long names and let message continuation lines flow beneath them.
        * Match reply and message spine thickness and stop the spine at the end of the author name.
        # 1.0.0
        * Add an IRC-style compact chat layout with inline authors, timestamps, avatars, and message spines.
        """.trimIndent(),
    )
}
