package com.github.yutaplug.devices

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet
import com.discord.utilities.color.ColorCompat
import org.json.JSONObject
import java.util.concurrent.Executors

class DevicesSheet : BottomSheet() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var api: SessionApi? = null
    private lateinit var status: TextView
    private lateinit var sessions: LinearLayout
    private var busy = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val token = SessionApi.token()
        if (token != null) api = SessionApi(token)
        linearLayout.setPadding(dp(16), dp(8), dp(16), dp(24))
        addView(label("Devices", 24f, "colorHeaderPrimary").apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(label("Manage where your Discord account is signed in.", 14f, "colorTextMuted").apply {
            setPadding(0, dp(4), 0, dp(16))
        })
        addView(action("Refresh devices", brand()).apply {
            setOnClickListener { refresh() }
        })
        status = label("", 14f, "colorTextMuted").apply {
            setPadding(dp(4), dp(14), dp(4), dp(14))
        }
        addView(status)
        sessions = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        addView(sessions)
        if (token == null) status.text = "Sign in to Discord to view devices."
        else refresh()
    }

    private fun refresh() {
        val active = api ?: return
        if (busy) return
        busy = true
        status.text = "Loading devices…"
        worker.execute {
            val result = runCatching { active.list() }
            main.post {
                if (!isAdded || view == null) return@post
                busy = false
                result.onSuccess { render(it) }
                    .onFailure { status.text = "Could not load devices: ${reason(it)}" }
            }
        }
    }

    private fun render(items: List<DeviceSession>) {
        sessions.removeAllViews()
        status.text = if (items.isEmpty()) "No devices found." else "${items.size} signed-in ${if (items.size == 1) "session" else "sessions"}"
        for (item in items) {
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(secondary(), 10)
            }
            card.addView(label(item.name + if (item.current) " · This device" else "", 17f, "colorHeaderPrimary").apply {
                setTypeface(typeface, Typeface.BOLD)
            })
            val details = listOfNotNull(item.location?.takeIf { it != "null" },
                item.lastUsed?.takeIf { it != "null" }?.let { "Last used: ${formatTime(it)}" }).joinToString("\n")
            if (details.isNotEmpty()) card.addView(label(details, 13f, "colorTextMuted").apply {
                setPadding(0, dp(6), 0, dp(4))
            })
            if (!item.current) card.addView(action("Log out this device", danger()).apply {
                setOnClickListener { confirmLogout(item) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(10)
            })
            sessions.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    private fun confirmLogout(item: DeviceSession) {
        showPrompt(
            title = "Log out ${item.name}?",
            message = "This device will need to sign in again. If Discord requires verification, you can enter it next.",
            confirmText = "Log out",
            confirmColor = danger(),
        ) { submitLogout(item) }
    }

    private fun submitLogout(item: DeviceSession, password: String? = null,
                             mfaToken: String? = null, legacyCode: String? = null) {
        val active = api ?: return
        if (busy) return
        busy = true
        status.text = "Logging out ${item.name}…"
        worker.execute {
            val result = runCatching { active.logout(listOf(item.idHash), password, mfaToken, legacyCode) }
            main.post {
                if (!isAdded || view == null) return@post
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
        showPrompt(
            title = "Confirm with your password",
            message = "Enter your Discord password to log out this device.",
            hint = "Password",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            trimInput = false,
        ) { value -> submitLogout(item, value) }
    }

    private fun requestMfa(item: DeviceSession, mfa: JSONObject, password: String?) {
        val ticket = mfa.optString("ticket")
        if (!hasText(ticket)) {
            status.text = "Discord requested 2FA without a challenge ticket."
            return
        }
        val methods = mfa.optJSONArray("methods")
        val options = mutableListOf("Authenticator code" to "totp")
        if (methods == null || (0 until methods.length()).any { index ->
                val method = methods.opt(index)
                method == "backup" || (method is JSONObject && method.optString("type") == "backup")
            }) options.add("Backup code" to "backup")
        showChoices("Two-factor authentication", "Choose a verification method.",
            options.map { (name, type) -> name to { requestMfaCode(item, ticket, type, password) } })
    }

    private fun requestMfaCode(item: DeviceSession, ticket: String, type: String, password: String?) {
        showPrompt(
            title = if (type == "backup") "Enter backup code" else "Enter authenticator code",
            message = if (type == "backup") "Use one of your saved backup codes."
                else "Enter the code from your authenticator app.",
            hint = if (type == "backup") "Backup code" else "6-digit code",
            inputType = if (type == "backup") InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER,
        ) { code -> finishMfa(item, ticket, type, code, password) }
    }

    private fun finishMfa(item: DeviceSession, ticket: String, type: String,
                          code: String, password: String?) {
        val active = api ?: return
        if (busy) return
        busy = true
        status.text = "Verifying code…"
        worker.execute {
            val result = runCatching { active.finishMfa(ticket, type, code) }
            main.post {
                if (!isAdded || view == null) return@post
                busy = false
                result.onSuccess { submitLogout(item, password, it) }
                    .onFailure { status.text = "Could not verify code: ${reason(it)}" }
            }
        }
    }

    private fun requestLegacyCode(item: DeviceSession, password: String?) {
        showPrompt(
            title = "Two-factor authentication",
            message = "Enter an authenticator or backup code to continue.",
            hint = "Authenticator or backup code",
            inputType = InputType.TYPE_CLASS_TEXT,
        ) { code -> submitLogout(item, password, legacyCode = code) }
    }

    private fun showPrompt(
        title: String,
        message: String,
        hint: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        trimInput: Boolean = true,
        confirmText: String = "Continue",
        confirmColor: Int = brand(),
        onConfirm: (String) -> Unit,
    ) {
        val dialog = Dialog(requireContext())
        val body = modalBody(dialog, title, message)
        val input = hint?.let { field ->
            EditText(requireContext()).apply {
                this.hint = field
                this.inputType = inputType
                setSingleLine(true)
                textSize = 16f
                setTextColor(themed("colorTextNormal", 0xFFDBDEE1.toInt()))
                setHintTextColor(themed("colorTextMuted", 0xFF949BA4.toInt()))
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(secondary(), 6)
                body.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    topMargin = dp(8)
                })
            }
        }
        modalFooter(body, dialog, confirmText, confirmColor) {
            val rawValue = input?.text?.toString() ?: ""
            val value = if (trimInput) trimWhitespace(rawValue) else rawValue
            if (input != null && value.isEmpty()) {
                input.error = "Required"
                return@modalFooter
            }
            dialog.dismiss()
            onConfirm(value)
        }
        showModal(dialog, body)
    }

    private fun showChoices(title: String, message: String, choices: List<Pair<String, () -> Unit>>) {
        val dialog = Dialog(requireContext())
        val body = modalBody(dialog, title, message)
        for ((name, selected) in choices) {
            body.addView(label(name, 16f, "colorTextNormal").apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = rounded(secondary(), 6)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    selected()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                topMargin = dp(8)
            })
        }
        modalFooter(body, dialog, null, brand(), null)
        showModal(dialog, body)
    }

    private fun modalBody(dialog: Dialog, title: String, message: String): LinearLayout {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(themed("colorBackgroundPrimary", 0xFF313338.toInt()), 12)
            addView(label(title, 20f, "colorHeaderPrimary").apply {
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(label(message, 14f, "colorTextNormal").apply {
                setPadding(0, dp(10), 0, dp(8))
            })
        }
    }

    private fun modalFooter(
        body: LinearLayout,
        dialog: Dialog,
        confirmText: String?,
        confirmColor: Int,
        onConfirm: (() -> Unit)?,
    ) {
        val footer = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(20)
        })
        footer.addView(action("Cancel", secondary()).apply {
            setTextColor(themed("colorTextNormal", 0xFFDBDEE1.toInt()))
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        if (confirmText != null && onConfirm != null) {
            footer.addView(action(confirmText, confirmColor).apply {
                setOnClickListener { onConfirm() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(8)
            })
        }
    }

    private fun showModal(dialog: Dialog, body: LinearLayout) {
        dialog.setContentView(body)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            minOf(resources.displayMetrics.widthPixels - dp(32), dp(420)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun requiresPassword(error: SessionFailure): Boolean =
        error.body.has("password") || error.message.orEmpty().contains("password", ignoreCase = true)

    private fun requiresLegacyCode(error: SessionFailure): Boolean =
        error.body.has("code") || error.message.orEmpty().contains("two-factor", ignoreCase = true) ||
            error.message.orEmpty().contains("2fa", ignoreCase = true)

    private fun reason(error: Throwable): String = error.message?.take(160) ?: error.javaClass.simpleName

    private fun trimWhitespace(value: String): String {
        var first = 0
        var last = value.length
        while (first < last && Character.isWhitespace(value[first])) first++
        while (last > first && Character.isWhitespace(value[last - 1])) last--
        return value.substring(first, last)
    }

    private fun formatTime(value: String): String = value.replace('T', ' ').removeSuffix("Z").take(19)

    private fun label(value: String, size: Float, attribute: String): TextView = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(themed(attribute, 0xFFE3E5E8.toInt()))
    }

    private fun action(value: String, fill: Int): TextView = label(value, 15f, "colorHeaderPrimary").apply {
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        background = rounded(fill, 8)
        isClickable = true
        isFocusable = true
    }

    private fun rounded(fill: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    private fun themed(attribute: String, fallback: Int): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(requireContext(), id)
    }

    private fun brand() = themed("colorBrand", 0xFF5865F2.toInt())
    private fun danger() = themed("colorStatusDanger", 0xFFDA373C.toInt())
    private fun secondary() = themed("colorBackgroundSecondary", 0xFF2B2D31.toInt())
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroyView() {
        worker.shutdownNow()
        api = null
        super.onDestroyView()
    }
}
