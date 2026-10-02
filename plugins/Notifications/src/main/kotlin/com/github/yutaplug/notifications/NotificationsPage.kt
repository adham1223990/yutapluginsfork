package com.github.yutaplug.notifications

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import rx.Subscription

internal class NotificationsPage(
    private val preferences: NotificationPreferences,
    private val badges: BadgePreferences,
) : AppFragment(Utils.getResId("widget_settings_account", "layout")) {
    private val controls = linkedMapOf<NotificationOption, CheckedSetting>()
    private val badgeControls = linkedMapOf<Int, CheckedSetting>()
    private val deviceControls = linkedMapOf<CheckedSetting, () -> Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private var deviceSubscription: Subscription? = null
    private var badgeStatus: TextView? = null
    private var boundView: View? = null
    private var statusView: TextView? = null
    private var closed = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (closed) {
            activity?.finish()
            return
        }
        // FragmentProxy forwards lifecycle callbacks without assigning this fragment's
        // mView. Track the supplied view instead of relying on Fragment.getView().
        boundView = view
        setActionBarTitle("Notifications")
        setActionBarSubtitle("User Settings")
        setActionBarDisplayHomeAsUpEnabled()
        val scroll = view.findViewById<ViewGroup>(Utils.getResId("settings_account_scroll", "id"))
        val body = scroll.getChildAt(0) as LinearLayout
        body.removeAllViews()
        body.setPadding(0, dp(16), 0, dp(24))
        controls.clear()
        badgeControls.clear()
        deviceControls.clear()
        deviceSubscription?.unsubscribe()
        statusView = text("Loading notification settings...")
            .apply {
                setOnClickListener { preferences.refresh() }
            }.also { body.addView(it) }
        val native = StoreStream.getNotifications()
        heading(body, "IN-APP NOTIFICATIONS")
        device(body, "Get notifications within Discord", { DeviceNotifications.current().isEnabledInApp }) {
            native.setEnabledInApp(it, true)
        }
        heading(body, "SYSTEM NOTIFICATIONS", divider = true)
        device(body, "Get notifications outside of Discord", { DeviceNotifications.current().isEnabled }) {
            native.setEnabled(it)
        }
        heading(body, "BEHAVIOR")
        device(body, "Disable notifications light", { DeviceNotifications.current().isDisableBlink }) {
            native.setNotificationLightDisabled(it)
        }
        device(body, "Disable notifications vibration", { DeviceNotifications.current().isDisableVibrate }) {
            native.setNotificationsVibrateDisabled(it)
        }
        device(
            body,
            "Wake screen for notifications",
            { DeviceNotifications.current().isWake },
            DeviceNotifications::wake,
        )
        heading(body, "SOUNDS", divider = true)
        device(body, "Disable Sounds", { DeviceNotifications.current().isDisableSound }) {
            native.setNotificationSoundDisabled(it)
        }
        heading(body, "REACTION NOTIFICATIONS", divider = true)
        body.addView(text("Receive notifications when your messages are reacted to."))
        for (option in NotificationOption.reactions()) accountControl(body, option)
        heading(body, "OTHER NOTIFICATIONS")
        for (option in NotificationOption.other()) accountControl(body, option)
        heading(body, "WHAT FRIENDS ARE TOLD")
        accountControl(body, NotificationOption.SHARE_ONLINE)
        accountControl(body, NotificationOption.SHARE_PROFILE)
        heading(body, "BADGES")
        badgeStatus = text("").also { body.addView(it) }
        badge(
            body,
            16,
            "Experimental Unreads",
            "Allows you to pick which channels are most important in a server.",
        )
        badge(
            body,
            32,
            "Mention on all messages",
            "Increment the mention counter on all messages in channels with notification level of All Messages.",
        )
        renderDevice()
        deviceSubscription = native.settings.W({
            main.post { if (boundView != null && !closed) renderDevice() }
        }, { error ->
            main.post { if (!closed) statusView?.text = "Could not load device settings: ${error.message?.take(120)}" }
        })
        preferences.attach(this)
        badges.attach(this)
    }

    private fun accountControl(body: LinearLayout, option: NotificationOption) {
        val type = if (option.scalarValue == null) CheckedSetting.ViewType.SWITCH else CheckedSetting.ViewType.RADIO
        val control = Utils.createCheckedSetting(requireContext(), type, option.title, option.description).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnCheckedListener { checked ->
                if (option.scalarValue == null || checked) {
                    preferences.choose(option, checked)
                } else {
                    isChecked = true
                }
            }
        }
        controls[option] = control
        body.addView(control)
    }

    private fun device(body: LinearLayout, title: String, read: () -> Boolean, write: (Boolean) -> Unit) {
        val control = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, null).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnCheckedListener {
                write(it)
                renderDevice()
            }
        }
        deviceControls[control] = read
        body.addView(control)
    }

    private fun renderDevice() {
        for ((control, read) in deviceControls) control.isChecked = read()
    }

    private fun badge(body: LinearLayout, bit: Int, title: String, description: String) {
        val control = Utils
            .createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                title,
                description,
            ).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setLabelTagText(Utils.getResId("beta", "string"))
                setLabelTagVisibility(true)
                setOnCheckedListener { badges.choose(bit, it) }
            }
        badgeControls[bit] = control
        body.addView(control)
    }

    fun renderBadges(flags: Int?, status: String) {
        if (boundView == null || closed) return
        badgeStatus?.text = status
        badgeStatus?.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        for ((bit, control) in badgeControls) {
            control.isChecked = flags != null && flags and bit != 0
            enableTree(control, flags != null)
        }
    }

    private fun heading(body: LinearLayout, title: String, divider: Boolean = false) {
        if (divider) {
            body.addView(
                View(requireContext()).apply {
                    setBackgroundColor(
                        ColorCompat.getThemedColor(context, Utils.getResId("colorBackgroundModifierAccent", "attr")),
                    )
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                },
            )
        }
        body.addView(
            text(title).apply {
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ColorCompat.getThemedColor(context, Utils.getResId("colorTextMuted", "attr")))
                setPadding(dp(16), dp(24), dp(16), dp(12))
            },
        )
    }

    fun render(values: Map<NotificationOption, Boolean>?, status: String) {
        if (boundView == null || closed) return
        statusView?.text = status
        statusView?.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        for ((option, control) in controls) {
            control.isChecked = values?.get(option) ?: false
            // CheckedSetting's child switch must also be disabled before the account loads.
            enableTree(control, values != null)
        }
    }

    private fun enableTree(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            var index = 0
            while (index < view.childCount) enableTree(view.getChildAt(index++), enabled)
        }
    }

    private fun text(value: String): TextView = TextView(requireContext()).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(ColorCompat.getThemedColor(context, Utils.getResId("colorTextNormal", "attr")))
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (!closed && boundView != null) preferences.refresh()
    }

    override fun onDestroyView() {
        preferences.detach(this)
        badges.detach(this)
        deviceSubscription?.unsubscribe()
        deviceSubscription = null
        boundView = null
        controls.clear()
        badgeControls.clear()
        deviceControls.clear()
        badgeStatus = null
        statusView = null
        super.onDestroyView()
    }

    fun close() {
        closed = true
        if (boundView != null) activity?.finish()
    }
}
