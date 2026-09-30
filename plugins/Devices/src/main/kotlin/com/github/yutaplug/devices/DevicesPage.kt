package com.github.yutaplug.devices

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.ImageView
import com.discord.app.AppFragment
import com.discord.widgets.settings.account.WidgetSettingsAccountChangePassword
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import org.json.JSONObject
import java.util.concurrent.Executors

class DevicesPage : AppFragment(Utils.getResId("widget_settings_account", "layout")) {
    private val main = Handler(Looper.getMainLooper())
    private var worker = Executors.newSingleThreadExecutor()
    private var boundView: View? = null
    private var generation = 0
    private var closed = false
    private var verificationSheet: DevicesVerificationSheet? = null
    private var knownSessions = emptyList<DeviceSession>()
    private var api: SessionApi? = null
    private lateinit var status: TextView
    private lateinit var sessions: LinearLayout
    private var busy = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (closed) {
            activity?.finish()
            return
        }
        boundView = view
        generation++
        busy = false
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        setActionBarTitle("Devices")
        setActionBarSubtitle("User Settings")
        setActionBarDisplayHomeAsUpEnabled()
        val scroll = view.findViewById<ViewGroup>(Utils.getResId("settings_account_scroll", "id"))
        val body = scroll.getChildAt(0) as LinearLayout
        body.removeAllViews()
        body.setPadding(0, dp(4), 0, dp(24))
        body.addView(styled("Here are all the devices that are currently logged in with your Discord account. You can log out of each one individually or all other devices.\n\nIf you see an entry you don't recognize, log out of that device and change your Discord account password immediately.", "UiKit_Settings_Item_Addition"))
        status = styled("Loading devices…", "UiKit_Settings_Item_Addition")
        body.addView(status)
        sessions = LinearLayout(view.context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(sessions)
        val token = SessionApi.token()
        api = token?.let(::SessionApi)
        if (token == null) status.text = "Sign in to Discord to view devices." else refresh()
        status.setOnClickListener { refresh() }
    }

    private fun styled(value: String, style: String): TextView {
        // Inflating Discord XML preserves the client's theme, font and spacing.
        val root = LayoutInflater.from(boundView!!.context).inflate(
            Utils.getResId("widget_settings_authorized_apps", "layout"), null) as ViewGroup
        val scroll = root.getChildAt(1) as ViewGroup
        val column = scroll.getChildAt(0) as ViewGroup
        val text = column.getChildAt(if (style == "UiKit_Settings_Item_Header") 0 else 1) as TextView
        column.removeView(text)
        text.text = value
        text.id = View.NO_ID
        text.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (style == "UiKit_Settings_Item") {
            text.setPadding(dp(16), dp(24), dp(16), dp(8))
            text.typeface = (column.getChildAt(0) as TextView).typeface
        }
        return text
    }
    private fun refresh() {
        val active = api ?: return
        if (busy) return
        busy = true
        status.text = "Loading devices…"
        val requestGeneration = generation
        status.visibility = View.VISIBLE
        worker.execute {
            val result = runCatching { active.list() }
            main.post {
                if (boundView == null || generation != requestGeneration) return@post
                busy = false
                result.onSuccess { render(it) }
                    .onFailure { status.text = "Could not load devices: ${reason(it)}" }
            }
        }
    }

    private fun render(items: List<DeviceSession>) {
        knownSessions = items
        sessions.removeAllViews()
        status.text = ""
        status.visibility = View.GONE
        fun section(title: String, devices: List<DeviceSession>) {
            sessions.addView(styled(title, "UiKit_Settings_Item_Header"))
            if (devices.isEmpty()) sessions.addView(styled("No devices found.", "UiKit_Settings_Item_Addition"))
            for (item in devices) {
                val template = LayoutInflater.from(requireContext()).inflate(
                    Utils.getResId("widget_settings_authorized_apps_list_item", "layout"), sessions, false) as ViewGroup
                val column = template.getChildAt(0) as ViewGroup
                val row = column.getChildAt(0) as ViewGroup
                column.removeView(row)
                val name = row.findViewById<TextView>(Utils.getResId("oauth_application_name_tv", "id"))
                val index = row.indexOfChild(name)
                val params = name.layoutParams
                row.removeView(name)
                val details = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                details.addView(name.apply {
                    text = item.name
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(Utils.getResId("uikit_settings_item_text_size", "dimen")))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                details.addView(styled(item.location ?: "Unknown location", "UiKit_Settings_Item_SubText").apply { setPadding(0, dp(4), 0, 0) })
                if (!item.current) item.lastUsed?.let { value ->
                    val relative = runCatching {
                        val age = System.currentTimeMillis() - java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
                        when {
                            age < 3600000 -> "less than an hour ago"
                            age < 86400000 -> "${age / 3600000} hours ago"
                            else -> "${age / 86400000} days ago"
                        }
                    }.getOrElse { formatTime(value) }
                    details.addView(styled(relative, "UiKit_Settings_Item_SubText").apply { setPadding(0, dp(4), 0, 0) })
                }
                row.addView(details, index, params)
                val icon = row.findViewById<ImageView>(Utils.getResId("oauth_application_icon_iv", "id"))
                val mobile = item.name.contains("Android", true) || item.name.contains("iOS", true) || item.name.contains("iPhone", true)
                val drawable = Utils.getResId(if (mobile) "ic_mobile" else "ic_monitor_white_24dp", "drawable")
                if (drawable != 0) icon.setImageResource(drawable)
                icon.setColorFilter(themed("colorHeaderPrimary", Color.WHITE))
                icon.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themed("colorBackgroundSecondaryAlt", secondary()))
                }
                icon.setPadding(dp(10), dp(10), dp(10), dp(10))
                val remove = row.findViewById<View>(Utils.getResId("oauth_application_deauthorize_btn", "id"))
                remove.visibility = if (item.current) View.GONE else View.VISIBLE
                remove.contentDescription = "Log out ${item.name}"
                remove.setOnClickListener { submitLogout(item) }
                row.setPadding(dp(16), dp(12), dp(16), dp(12))
                remove.layoutParams = (remove.layoutParams as LinearLayout.LayoutParams).apply { gravity = Gravity.CENTER_VERTICAL }
                sessions.addView(row)
            }
        }
        section("CURRENT DEVICE", items.filter { it.current })
        section("OTHER DEVICES", items.filterNot { it.current })
        sessions.addView(styled("Some older devices may not be shown here", "UiKit_Settings_Item").apply {
            setTextColor(themed("colorHeaderPrimary", Color.WHITE))
        })
        sessions.addView(styled("To log them out, please change your password", "UiKit_Settings_Item_Addition").apply {
            setTextColor(themed("colorTextLink", 0xFF00AFF4.toInt()))
            setOnClickListener { Utils.openPageWithProxy(context, WidgetSettingsAccountChangePassword()) }
        })
        sessions.addView(styled("Log Out All Known Devices", "UiKit_Settings_Item").apply {
            setTextColor(danger())
            setOnClickListener {
                if (knownSessions.any { !it.current }) submitLogout(DeviceSession("", "all other devices", null, null, false))
            }
        })
        sessions.addView(styled("You'll have to log back in on all logged out devices.", "UiKit_Settings_Item_Addition"))
    }
    private fun submitLogout(item: DeviceSession, password: String? = null,
                             mfaToken: String? = null, legacyCode: String? = null) {
        val active = api ?: return
        if (busy) return
        busy = true
        status.text = "Logging out ${item.name}…"
        val requestGeneration = generation
        status.visibility = View.VISIBLE
        val hashes = if (item.idHash.isEmpty()) knownSessions.filterNot { it.current }.map { it.idHash } else listOf(item.idHash)
        worker.execute {
            val result = runCatching { active.logout(hashes, password, mfaToken, legacyCode) }
            main.post {
                if (boundView == null || generation != requestGeneration) return@post
                busy = false
                result.onSuccess {
                    status.text = "Device logged out."
                    refresh()
                }.onFailure { error ->
                    when {
                        error is SessionFailure && error.body.optJSONObject("mfa") != null && mfaToken == null ->
                            requestMfa(item, error.body.getJSONObject("mfa"), password)
                        error is SessionFailure && requiresPassword(error) && password == null ->
                            requestPassword(item)
                        error is SessionFailure && requiresLegacyCode(error) && legacyCode == null ->
                            requestLegacyCode(item, password)
                        else -> status.text = "Could not log out device: ${reason(error)}"
                    }
                }
            }
        }
    }

    private fun requestPassword(item: DeviceSession) {
        showVerification("Confirm with your password", "Password", password = true) { value ->
            submitLogout(item, value)
        }
    }

    private fun requestMfa(item: DeviceSession, mfa: JSONObject, password: String?) {
        val ticket = mfa.optString("ticket")
        if (!hasText(ticket)) {
            status.text = "Discord requested 2FA without a challenge ticket."
            return
        }
        val methods = mfa.optJSONArray("methods")
        fun supports(type: String): Boolean = methods == null || (0 until methods.length()).any { index ->
            val method = methods.opt(index)
            method == type || (method is JSONObject && method.optString("type") == type)
        }
        if (supports("totp")) requestMfaCode(item, ticket, "totp", password, supports("backup"))
        else if (supports("backup")) requestMfaCode(item, ticket, "backup", password)
        else status.text = "Discord requested an unsupported verification method."
    }

    private fun requestMfaCode(item: DeviceSession, ticket: String, type: String, password: String?,
                               allowBackup: Boolean = false) {
        showVerification(
            if (type == "backup") "Verify with a backup code" else "Verify with your authenticator app",
            if (type == "backup") "Backup code" else "6-digit authentication code",
            numeric = type == "totp",
            alternate = if (allowBackup) ({ requestMfaCode(item, ticket, "backup", password) }) else null,
        ) { code -> finishMfa(item, ticket, type, code, password) }
    }
    private fun finishMfa(item: DeviceSession, ticket: String, type: String,
                          code: String, password: String?) {
        val active = api ?: return
        if (busy) return
        busy = true
        status.text = "Verifying code…"
        val requestGeneration = generation
        status.visibility = View.VISIBLE
        worker.execute {
            val result = runCatching { active.finishMfa(ticket, type, code) }
            main.post {
                if (boundView == null || generation != requestGeneration) return@post
                busy = false
                result.onSuccess { submitLogout(item, password, it) }
                    .onFailure { status.text = "Could not verify code: ${reason(it)}" }
            }
        }
    }

    private fun requestLegacyCode(item: DeviceSession, password: String?) {
        showVerification("Verify with your authenticator app", "6-digit authentication code",
            numeric = true,
            alternate = {
                showVerification("Verify with a backup code", "Backup code") { code ->
                    submitLogout(item, password, legacyCode = code)
                }
            },
        ) { code -> submitLogout(item, password, legacyCode = code) }
    }

    private fun showVerification(title: String, hint: String, password: Boolean = false,
                                 numeric: Boolean = false, alternate: (() -> Unit)? = null,
                                 onConfirm: (String) -> Unit) {
        if (boundView == null || closed) return
        val host = activity ?: return
        val manager = host.supportFragmentManager
        if (host.isFinishing || host.isDestroyed || manager.isStateSaved || manager.isDestroyed) return
        verificationSheet?.dismissAllowingStateLoss()
        val sheet = DevicesVerificationSheet().apply {
            heading = title
            inputHint = hint
            passwordInput = password
            numericInput = numeric
            onAlternate = alternate
            onSubmit = { value ->
                if (boundView != null && !closed) onConfirm(value)
            }
        }
        verificationSheet = sheet
        sheet.show(manager, "DevicesVerification")
        status.visibility = View.GONE
    }
    private fun requiresPassword(error: SessionFailure): Boolean =
        error.body.has("password") || error.message.orEmpty().contains("password", ignoreCase = true)

    private fun requiresLegacyCode(error: SessionFailure): Boolean =
        error.body.has("code") || error.message.orEmpty().contains("two-factor", ignoreCase = true) ||
            error.message.orEmpty().contains("2fa", ignoreCase = true)

    private fun reason(error: Throwable): String = error.message?.take(160) ?: error.javaClass.simpleName

    private fun formatTime(value: String): String = value.replace('T', ' ').removeSuffix("Z").take(19)

    private fun themed(attribute: String, fallback: Int): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(requireContext(), id)
    }


    private fun danger() = themed("colorStatusDanger", 0xFFDA373C.toInt())
    private fun secondary() = themed("colorBackgroundSecondary", 0xFF2B2D31.toInt())
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    internal fun close() {
        closed = true
        cleanup()
        activity?.finish()
    }

    private fun cleanup() {
        boundView = null
        generation++
        main.removeCallbacksAndMessages(null)
        verificationSheet?.dismissAllowingStateLoss()
        verificationSheet = null
        knownSessions = emptyList()
        worker.shutdownNow()
        api = null
    }

    override fun onDestroyView() {
        cleanup()
        super.onDestroyView()
    }
}
