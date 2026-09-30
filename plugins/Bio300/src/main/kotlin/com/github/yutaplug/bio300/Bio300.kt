package com.github.yutaplug.bio300

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.View
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.widgets.settings.profile.WidgetEditUserOrGuildMemberProfile
import com.google.android.material.textfield.TextInputLayout
import java.util.WeakHashMap

@AliucordPlugin
class Bio300 : Plugin() {
    private data class OriginalState(val filters: Array<InputFilter>, val counterMaxLength: Int)

    private val editors = WeakHashMap<TextInputLayout, OriginalState>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun start(context: Context) {
        patcher.patch(
            WidgetEditUserOrGuildMemberProfile::class.java.getDeclaredMethod("onViewBound", View::class.java),
            Hook { frame ->
                val view = frame.args[0] as View
                val id = view.resources.getIdentifier("bio_editor_text_input_field_wrap", "id", "com.discord")
                if (id == 0) return@Hook
                val layout = view.findViewById<TextInputLayout>(id) ?: return@Hook
                val input = layout.editText ?: return@Hook
                if (editors.containsKey(layout)) return@Hook

                // Keep every other native filter and the existing Material counter.
                editors[layout] = OriginalState(input.filters, layout.counterMaxLength)
                input.filters = input.filters.filterNot { it is InputFilter.LengthFilter }
                    .plus(InputFilter.LengthFilter(300)).toTypedArray()
                layout.counterMaxLength = 300
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        val restore = Runnable {
            for ((layout, original) in editors) {
                layout.editText?.filters = original.filters
                layout.counterMaxLength = original.counterMaxLength
            }
            editors.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) restore.run() else mainHandler.post(restore)
    }
}
