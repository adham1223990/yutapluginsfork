package com.github.yutaplug.devices

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.LinearLayoutCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.settings.WidgetSettings
import java.util.WeakHashMap

@AliucordPlugin
class Devices : Plugin() {
    private val rows = WeakHashMap<WidgetSettings, TextView>()

    override fun start(context: Context) {
        patcher.patch(
            WidgetSettings::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook { frame -> insertRow(frame.thisObject as WidgetSettings) },
        )
        patcher.patch(WidgetSettings::class.java, "onViewBoundOrOnResume", emptyArray(), Hook { frame ->
            insertRow(frame.thisObject as WidgetSettings)
        })
    }

    private fun insertRow(settings: WidgetSettings) {
        val page = settings.view ?: return
        val anchor = page.findViewById<TextView>(Utils.getResId("authorized_apps", "id")) ?: return
        val parent = anchor.parent as? LinearLayoutCompat ?: return
        if (parent.findViewWithTag<View>(ROW_TAG) != null) return
        val row = TextView(anchor.context).apply {
            tag = ROW_TAG
            text = "Devices"
            contentDescription = "Devices, manage signed-in sessions"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.textSize)
            setTextColor(anchor.textColors)
            typeface = anchor.typeface
            gravity = anchor.gravity
            textAlignment = anchor.textAlignment
            layoutDirection = anchor.layoutDirection
            includeFontPadding = anchor.includeFontPadding
            minHeight = anchor.minimumHeight
            setPaddingRelative(anchor.paddingStart, anchor.paddingTop, anchor.paddingEnd, anchor.paddingBottom)
            compoundDrawablePadding = anchor.compoundDrawablePadding
            background = anchor.background?.constantState?.newDrawable(anchor.resources)?.mutate()
            isClickable = true
            isFocusable = true
            val iconId = Utils.getResId("ic_security_24dp", "drawable")
            val icon = if (iconId != 0) AppCompatResources.getDrawable(context, iconId)?.mutate() else null
            val tintAttr = Utils.getResId("colorInteractiveNormal", "attr")
            if (tintAttr != 0) icon?.setTintList(ColorStateList.valueOf(ColorCompat.getThemedColor(context, tintAttr)))
            icon?.setBounds(0, 0, (24 * resources.displayMetrics.density + 0.5f).toInt(),
                (24 * resources.displayMetrics.density + 0.5f).toInt())
            setCompoundDrawablesRelative(icon, null, null, null)
            setOnClickListener {
                if (settings.isAdded && settings.parentFragmentManager.findFragmentByTag("DevicesSheet") == null) {
                    DevicesSheet().show(settings.parentFragmentManager, "DevicesSheet")
                }
            }
        }
        parent.addView(row, parent.indexOfChild(anchor) + 1,
            LinearLayoutCompat.LayoutParams(anchor.layoutParams))
        rows[settings] = row
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        rows.values.forEach { row -> (row.parent as? ViewGroup)?.removeView(row) }
        rows.clear()
    }

    private companion object {
        const val ROW_TAG = "devices_settings_row"
    }
}
