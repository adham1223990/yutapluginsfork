version = "1.0.2"
description = "Backports community onboarding and Channels & Roles to Discord 126.21."

aliucord {
    changelog.set(
        """
        # 1.0.2
        * Use Discord 126.21's native toolbar, tabs, fonts, and controls.
        * Add channel search and expanded category groups with Follow Category controls.
        * Show channel icons, topics, and recent activity.
        * Fix category controls becoming unresponsive after a failed update.
        """.trimIndent(),
    )
}

android {
    namespace = "com.github.yutaplug.onboarding"
}
