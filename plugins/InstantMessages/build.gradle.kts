version = "1.0.2"
description = "Shows outgoing messages instantly without chat-list animations."

aliucord {
    changelog.set("""
        # 1.0.2
        * Rewrote the plugin in Kotlin.
        * Fixed repeated scrolling and preserved chat position when switching channels.
        * Improved message acknowledgement matching and limited delayed chat updates.
        * Fixed compatibility with Android versions below 11.
        * Restored animations and pending-message opacity when disabling the plugin.

        # 1.0.0
        * Initial release.
    """.trimIndent())
}
