package com.discord.api.botuikit

data class LabelComponent(
    private val type: ComponentType,
    val label: String,
    val description: String?,
    val component: Component,
) : LayoutComponent() {
    override fun getType() = type
    override fun a() = listOf(component)
}
