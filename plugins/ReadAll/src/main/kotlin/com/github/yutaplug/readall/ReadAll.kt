package com.github.yutaplug.readall

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.PopupMenu
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.stores.StoreStream
import com.discord.widgets.guilds.list.GuildListViewHolder
import com.discord.widgets.guilds.list.WidgetGuildListAdapter
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import rx.Subscriber

@AliucordPlugin
class ReadAll : Plugin() {
    private val rows = WeakHashMap<View, Boolean>()
    private val adapters = WeakHashMap<WidgetGuildListAdapter, Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private var popup: PopupMenu? = null
    private var worker = Executors.newSingleThreadExecutor()
    private var busy = AtomicBoolean(false)
    @Volatile private var started = false
    @Volatile private var session = 0
    @Volatile private var lastRun = 0L

    init {
        settingsTab = SettingsTab(ReadAllSettings::class.java, SettingsTab.Type.BOTTOM_SHEET)
            .withArgs(settings, this)
    }

    override fun start(context: Context) {
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        busy = AtomicBoolean(false)
        session++
        started = true
        patcher.patch(
            WidgetGuildListAdapter::class.java,
            "onBindViewHolder",
            arrayOf(GuildListViewHolder::class.java, Int::class.javaPrimitiveType!!),
            Hook { frame ->
                val adapter = frame.thisObject as WidgetGuildListAdapter
                adapters[adapter] = true
                if (adapter.getItemViewType(frame.args[1] as Int) != FRIENDS_TYPE) return@Hook
                val row = (frame.args[0] as? GuildListViewHolder)?.itemView ?: return@Hook
                if (settings.getBool(COMMAND_MODE, false)) removeLongPress(row) else addLongPress(row)
            },
        )
        updateMode(settings.getBool(COMMAND_MODE, false))
    }

    fun updateMode(commandMode: Boolean) {
        settings.setBool(COMMAND_MODE, commandMode)
        commands.unregisterAll()
        if (commandMode && started) {
            commands.registerCommand("readall", "Mark unread channels as read") {
                readAll()
                null
            }
        }
        if (commandMode) {
            popup?.dismiss()
            popup = null
            rows.keys.toList().forEach(::removeLongPress)
        }
        adapters.keys.toList().forEach { it.notifyDataSetChanged() }
    }

    private fun addLongPress(row: View) {
        if (rows.containsKey(row)) return
        rows[row] = row.isLongClickable
        row.setOnLongClickListener {
            try {
                popup?.dismiss()
                val menu = PopupMenu(row.context, row)
                menu.menu.add(if (settings.getBool(INCLUDE_DMS, false)) "Read all notifications" else "Read all server notifications")
                menu.setOnMenuItemClickListener {
                    readAll()
                    true
                }
                menu.setOnDismissListener { if (popup === menu) popup = null }
                menu.show()
                popup = menu
                true
            } catch (error: Throwable) {
                logger.error("ReadAll could not open the DM icon menu", error)
                false
            }
        }
    }

    private fun removeLongPress(row: View) {
        val wasLongClickable = rows.remove(row) ?: return
        row.setOnLongClickListener(null)
        row.isLongClickable = wasLongClickable
    }

    private fun readAll() {
        val gate = busy
        val runSession = session
        if (!started || !gate.compareAndSet(false, true)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRun < CLICK_DELAY_MS) {
            gate.set(false)
            return
        }
        try {
            val readStates = StoreStream.getReadStates()
            val channels = StoreStream.getChannels()
            val includeDms = settings.getBool(INCLUDE_DMS, false)
            readStates.unreadChannelIds.U(object : Subscriber<Set<Long>>() {
                override fun onNext(ids: Set<Long>) {
                    unsubscribe()
                    try {
                        worker.execute {
                            try {
                                var count = 0
                                for (id in ids) {
                                    if (!started || session != runSession) break
                                    val channel = channels.getChannel(id) ?: continue
                                    val guildId = channel.i()
                                    if (guildId == 0L && !includeDms) continue
                                    readStates.markAsRead(id)
                                    count++
                                }
                                if (started && session == runSession) {
                                    if (count > 0) lastRun = SystemClock.elapsedRealtime()
                                    main.post {
                                        if (started && session == runSession) Utils.showToast(if (count == 0) "No unread notifications" else "Marked $count channels as read")
                                    }
                                }
                            } catch (error: Throwable) {
                                reportError(error)
                            } finally {
                                gate.set(false)
                            }
                        }
                    } catch (error: Throwable) {
                        gate.set(false)
                        if (started) reportError(error)
                    }
                }

                override fun onError(error: Throwable) {
                    gate.set(false)
                    if (started) reportError(error)
                }

                override fun onCompleted() {}
            })
        } catch (error: Throwable) {
            gate.set(false)
            reportError(error)
        }
    }

    private fun reportError(error: Throwable) {
        logger.error("ReadAll could not mark channels as read", error)
        main.post { if (started) Utils.showToast("Could not mark channels as read") }
    }

    override fun stop(context: Context) {
        started = false
        session++
        patcher.unpatchAll()
        commands.unregisterAll()
        popup?.dismiss()
        popup = null
        rows.keys.toList().forEach(::removeLongPress)
        rows.clear()
        adapters.clear()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    companion object {
        const val COMMAND_MODE = "commandMode"
        const val INCLUDE_DMS = "includeDMs"
        private const val FRIENDS_TYPE = 0
        private const val CLICK_DELAY_MS = 1_000L
    }
}
