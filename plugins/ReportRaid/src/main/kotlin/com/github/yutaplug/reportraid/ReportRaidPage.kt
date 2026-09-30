package com.github.yutaplug.reportraid

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.aliucord.Utils
import com.aliucord.utils.ReflectUtils
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.views.CheckedSetting
import com.discord.views.LoadingButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ReportRaidPage : AppFragment(Utils.getResId("widget_mobile_reports", "layout")) {
    internal val guildId: Long get() = arguments?.getLong("guild_id") ?: 0L
    private val selected = linkedSetOf<String>()
    private val choices = mutableListOf<CheckedSetting>()
    private val main = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var pending: Future<*>? = null
    private var generation = 0
    private var busy = false
    private var submitted = false
    private var closed = false
    // Aliucord's FragmentProxy forwards onViewBound without populating this
    // fragment's mView. Track the actual view delivered by the proxy instead.
    private var boundView: View? = null
    internal val isOpen: Boolean get() = !closed && boundView != null
    private var status: TextView? = null
    private var submit: LoadingButton? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (closed) {
            activity?.finish()
            return
        }
        generation++
        boundView = view
        worker = Executors.newSingleThreadExecutor()
        setActionBarTitle("Report a Raid")
        setActionBarDisplayHomeAsUpEnabled(false)?.apply {
            val closeIcon = Utils.getResId("ic_close_24dp", "drawable")
            if (closeIcon != 0) setNavigationIcon(closeIcon)
            navigationContentDescription = "Close"
            setNavigationOnClickListener { close() }
        }
        fun <T : View> find(root: View, name: String): T = root.findViewById(Utils.getResId(name, "id"))
        find<View>(view, "mobile_reports_progress_bar").visibility = View.GONE
        val holder = find<FrameLayout>(view, "menu_reports_node_view_holder")
        val inflater = LayoutInflater.from(view.context)
        val node = inflater.inflate(Utils.getResId("view_reports_menu_node", "layout"), holder, false)
        holder.addView(node)
        // Reuse the target client's report layout, including its pinned footer.
        for (name in arrayOf("mobile_reports_node_header", "mobile_reports_node_info_box",
            "mobile_reports_node_message_preview", "mobile_reports_node_channel_preview",
            "mobile_reports_node_directory_channel_preview_title", "mobile_reports_node_directory_channel_preview",
            "mobile_reports_node_breadcrumbs")) {
            find<View>(node, name).visibility = View.GONE
        }
        val help = "Learn more about raids"
        val prompt = "Which behavior best describes how this raid is currently disrupting your server? $help"
        find<TextView>(node, "mobile_reports_node_subheader").apply {
            text = SpannableString(prompt).apply {
                setSpan(URLSpan(HELP_URL), prompt.length - help.length, prompt.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            movementMethod = LinkMovementMethod.getInstance()
        }
        val list = find<LinearLayout>(node, "mobile_reports_node_child_list")
        val cardTemplate = inflater.inflate(Utils.getResId("view_mobile_reports_child", "layout"), list, false)
        val card = find<CardView>(cardTemplate, "mobile_reports_child")
        card.removeAllViews()
        val rows = LinearLayout(view.context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(rows)
        list.addView(cardTemplate)
        for ((value, label) in BEHAVIORS) {
            val choice = inflater.inflate(Utils.getResId("view_mobile_reports_multicheck_item", "layout"), rows, false)
                as CheckedSetting
            choice.setText(label)
            choice.isChecked = value in selected
            choice.isEnabled = !submitted
            choice.setOnCheckedListener { checked ->
                choice.isChecked = checked
                if (checked) selected.add(value) else selected.remove(value)
                submit?.isEnabled = selected.isNotEmpty() && !busy
            }
            rows.addView(choice)
            choices.add(choice)
        }
        status = find<TextView>(node, "report_node_bottom_button_error_text")
        submit = find<LoadingButton>(node, "report_node_bottom_button").apply {
            // The native layout starts with its ProgressBar visible.
            setIsLoading(false)
            setText(if (submitted) "Done" else "Submit")
            isEnabled = submitted || selected.isNotEmpty()
            setOnClickListener { if (submitted) close() else send() }
        }
        if (submitted) showSuccess(view)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList("raid_behaviors")?.let { selected.addAll(it) }
        submitted = savedInstanceState?.getBoolean("raid_submitted") ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("raid_behaviors", ArrayList(selected))
        outState.putBoolean("raid_submitted", submitted)
        super.onSaveInstanceState(outState)
    }

    private fun send() {
        if (busy || closed || submitted || selected.isEmpty()) return
        val root = boundView ?: return
        val executor = worker ?: run {
            showStatus("Reopen Report Raid before submitting.")
            return
        }
        if (!ReportRaid.canReport(guildId)) {
            showStatus("You no longer have permission to report a raid in this server.")
            return
        }
        val authToken = RestAPI.AppHeadersProvider.INSTANCE.authToken
        if (authToken == null || !hasText(authToken)) {
            showStatus("Sign in to Discord before reporting a raid.")
            return
        }
        // Verified private field in 126.21: handleConnectionOpen stores the
        // READY analytics_token here even while NoTrack disables the tracker.
        val analyticsToken = runCatching {
            ReflectUtils.getField(StoreStream.getAnalytics(), "analyticsToken") as? String
        }.getOrNull()
        if (analyticsToken == null || !hasText(analyticsToken)) {
            showStatus("Reconnect to Discord before reporting a raid.")
            return
        }
        if (StoreStream.getUsers().me.id != arguments?.getLong("reporter_id")) {
            showStatus("Discord account changed. Reopen Report Raid.")
            return
        }
        busy = true
        submit?.setIsLoading(true)
        submit?.isEnabled = false
        choices.forEach { it.isEnabled = false }
        status?.visibility = View.GONE
        val currentGeneration = generation
        val behaviors = selected.toList()
        try {
            pending = executor.submit {
                val result = runCatching { RaidApi.submit(guildId, behaviors, authToken, analyticsToken) }
                main.post {
                    if (generation != currentGeneration || closed || boundView !== root) return@post
                    pending = null
                    busy = false
                    submit?.setIsLoading(false)
                    submit?.isEnabled = true
                    result.onSuccess {
                        submitted = true
                        showSuccess(root)
                    }.onFailure {
                        choices.forEach { choice -> choice.isEnabled = true }
                        showStatus("Could not submit raid report: ${it.message ?: "Please try again."}")
                    }
                }
            }
        } catch (error: Exception) {
            busy = false
            submit?.setIsLoading(false)
            submit?.isEnabled = true
            choices.forEach { it.isEnabled = true }
            showStatus("Could not start raid report: ${error.message ?: "Please try again."}")
        }
    }

    private fun showStatus(message: String) {
        status?.text = message
        status?.visibility = View.VISIBLE
    }

    private fun showSuccess(root: View) {
        root.findViewById<View>(Utils.getResId("mobile_reports_node_child_list", "id"))?.visibility = View.GONE
        root.findViewById<View>(Utils.getResId("mobile_reports_node_success_shield", "id"))?.visibility = View.VISIBLE
        root.findViewById<TextView>(Utils.getResId("mobile_reports_node_subheader", "id"))?.apply {
            movementMethod = null
            text = "Raid reported to Discord. Thanks for helping keep your server safe."
        }
        status?.visibility = View.GONE
        submit?.setText("Done")
    }

    internal fun close() {
        closed = true
        release()
        activity?.finish()
    }

    private fun release() {
        generation++
        boundView = null
        pending?.cancel(true)
        pending = null
        worker?.shutdownNow()
        worker = null
        main.removeCallbacksAndMessages(null)
        choices.forEach { it.setOnCheckedListener(null) }
        choices.clear()
        submit?.setOnClickListener(null)
        submit = null
        status = null
        busy = false
    }

    override fun onDestroyView() {
        release()
        super.onDestroyView()
    }

    companion object {
        private const val HELP_URL = "https://discord.com/community/securing-your-server"
        internal val BEHAVIORS = linkedMapOf(
            "MESSAGE_SPAM" to "Spamming channels",
            "DM_SPAM" to "Spamming DMs",
            "MENTION_SPAM" to "Spamming mentions",
            "SUSPICIOUS_USERS" to "Nothing yet, but suspicious new members",
            "SETTINGS_SPAM" to "Changing server and channel settings",
        )

        internal fun create(guildId: Long) = ReportRaidPage().apply {
            arguments = Bundle().apply {
                putLong("guild_id", guildId)
                putLong("reporter_id", StoreStream.getUsers().me.id)
            }
        }
    }
}
