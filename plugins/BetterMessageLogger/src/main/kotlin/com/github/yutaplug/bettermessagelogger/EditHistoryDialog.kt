package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.aliucord.Utils
import java.text.DateFormat
import java.util.Date

internal object EditHistoryDialog {
    fun show(context: Context, record: MessageRecord) {
        val ui = LoggerUi(context)
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8)) }
        content.addView(
            ui
                .text(
                    "${record.authorName} · ${record.edits.size} saved ${if (record.edits.size == 1) "edit" else "edits"}",
                    14f,
                    ui.muted,
                ).apply { setPadding(0, 0, 0, ui.dp(16)) },
        )
        val list = ui.column()
        val availableHeight = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    availableHeight
                } else {
                    minOf(availableHeight, MeasureSpec.getSize(heightMeasureSpec))
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            addView(list, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        // WRAP_CONTENT with a maximum keeps small histories compact and long ones scrollable.
        content.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        fun version(label: String, date: String, body: String, current: Boolean = false) {
            val card = ui.card()
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(
                ui.text(label, 15f, if (current) ui.brand else ui.primary),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            header.addView(
                ui.smallButton("Copy") {
                    Utils.setClipboard("Message version", body)
                    Utils.showToast("Version copied")
                },
            )
            card.addView(header)
            card.addView(ui.text(date, 12f, ui.muted))
            card.addView(
                ui.text(body.ifEmpty { "No text content" }, 15f).apply {
                    setPadding(0, ui.dp(12), 0, ui.dp(4))
                    setLineSpacing(ui.dp(2).toFloat(), 1f)
                    setTextIsSelectable(true)
                },
            )
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        }

        version(
            if (record.deleted) "Final version · deleted" else "Current version",
            format.format(Date(record.editedTimestamp ?: record.timestamp)),
            record.content,
            true,
        )
        record.edits.withIndex().reversed().forEach { (index, edit) ->
            version(
                if (index == 0) "Original message" else "Previous version ${index + 1}",
                "Replaced ${format.format(Date(edit.timestamp))}",
                edit.content,
            )
        }
        val dialog = AlertDialog
            .Builder(
                context,
            ).setCustomTitle(DiscordSettingsUi.title(context, "Edit history"))
            .setView(content)
            .setPositiveButton("Close", null)
            .create()
        ui.style(dialog)
        dialog.show()
    }
}
