version = "1.0.3"
description = "Backports Discord's evolving Nitro and current profile badges to mobile profile sheets."

aliucord {
    changelog.set(
        """
        # 1.0.3
        * Fully rewritten in Kotlin.
        * Use Discord's visible badge list to support badge hiding and current profile badges.
        * Remove redundant profile requests that delayed Nitro badges, including Silver.
        * Add image fallbacks, share badge downloads, and prevent stale images in reused badge views.
        * Fix compatibility with Discord's obfuscated Kotlin runtime.
        # 1.0.2
        * Revert rows to normal
        * Fix flickering
        # 1.0.1
        * Fixed duplicated evolving Nitro badges and backport gifting badges.
        """.trimIndent(),
    )
}
