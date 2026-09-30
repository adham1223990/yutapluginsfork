version = "1.0.2"
description = "Backports the Devices settings page with session management."
aliucord {
    changelog.set(
        """
        # 1.0.2
        * Use a dedicated native Discord settings page for Devices
        * Replace logout pop-ups with native verification bottom sheets
        * Fix verification sheet activity and layout crashes
        * Use a distinct monitor icon in User Settings
        # 1.0.1
        * Add indicator for current session
        """.trimIndent(),
    )
}
