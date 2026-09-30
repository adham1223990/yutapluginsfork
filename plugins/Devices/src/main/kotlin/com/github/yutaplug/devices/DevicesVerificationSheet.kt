package com.github.yutaplug.devices

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.discord.app.AppBottomSheet
import com.discord.utilities.color.ColorCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

class DevicesVerificationSheet : AppBottomSheet() {
    internal var heading = "Verify with your authenticator app"
    internal var inputHint = "6-digit authentication code"
    internal var passwordInput = false
    internal var numericInput = false
    internal var onSubmit: ((String) -> Unit)? = null
    internal var onAlternate: (() -> Unit)? = null

    override fun getContentViewResId() = Utils.getResId("widget_enable_two_factor_password_dialog", "layout")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // A restored sheet has no live logout operation attached to it.
        if (onSubmit == null) {
            dismissAllowingStateLoss()
            return
        }
        val body = view as LinearLayout
        val title = view.findViewById<TextView>(Utils.getResId("enable_two_factor_password_header", "id"))
        val alternate = view.findViewById<TextView>(Utils.getResId("enable_two_factor_password_body_text", "id"))
        val input = if (passwordInput) {
            view.findViewById<TextInputLayout>(Utils.getResId("enable_two_factor_password_view_input", "id"))
        } else {
            LayoutInflater.from(view.context).inflate(
                Utils.getResId("view_input_modal_text_no_suggestions", "layout"), body, false
            ) as TextInputLayout
        }
        for (child in listOf(title, alternate, input)) (child.parent as? ViewGroup)?.removeView(child)
        body.removeAllViews()
        // BottomSheetDialog hosts the content in a FrameLayout. Preserve its
        // layout parameter type, including margins, when changing the size.
        body.layoutParams = (body.layoutParams ?: FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val background = Utils.getResId("colorBackgroundSecondary", "attr")
        if (background != 0) body.setBackgroundColor(ColorCompat.getThemedColor(view.context, background))
        body.setPadding(0, 0, 0, dp(16))
        title.text = heading
        body.addView(title)
        input.hint = inputHint
        val edit = input.editText!!
        edit.inputType = when {
            passwordInput -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            numericInput -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (numericInput) edit.filters = arrayOf(InputFilter.LengthFilter(6))
        body.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(4), dp(16), dp(20))
        })
        val nativeSheet = LayoutInflater.from(view.context).inflate(
            Utils.getResId("widget_stage_start_event_bottom_sheet", "layout"), body, false
        ) as ViewGroup
        val confirm = nativeSheet.findViewById<MaterialButton>(Utils.getResId("start_stage_button", "id"))
        (confirm.parent as ViewGroup).removeView(confirm)
        confirm.text = "Confirm"
        body.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), 0, dp(16), 0) })
        confirm.setOnClickListener {
            val raw = edit.text?.toString().orEmpty()
            val value = if (passwordInput) raw else raw.trim()
            if (value.isEmpty() || (numericInput && (value.length != 6 || value.any { it !in '0'..'9' }))) {
                input.error = if (numericInput) "Enter a 6-digit authentication code" else "Required"
                return@setOnClickListener
            }
            val callback = onSubmit ?: return@setOnClickListener
            confirm.isEnabled = false
            dismiss()
            callback(value)
        }
        if (onAlternate != null) {
            alternate.text = "Use a backup code"
            val link = Utils.getResId("colorTextLink", "attr")
            if (link != 0) alternate.setTextColor(ColorCompat.getThemedColor(view.context, link))
            body.addView(alternate)
            alternate.setOnClickListener {
                val callback = onAlternate ?: return@setOnClickListener
                dismiss()
                callback()
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroyView() {
        onSubmit = null
        onAlternate = null
        super.onDestroyView()
    }
}
