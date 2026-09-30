package com.discord.api.user

import java.io.Serializable

/** Binary-compatible model for Aliucord's Decorations core plugin. */
data class AvatarDecoration(
    val asset: String,
    val skuId: Long,
    val expiresAt: Int?,
) : Serializable
