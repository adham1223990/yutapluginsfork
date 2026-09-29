package com.github.yutaplug.onboarding

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.aliucord.Utils
import com.discord.stores.StoreStream
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.facebook.drawee.view.SimpleDraweeView
import java.util.concurrent.Executors

/** Member-facing community onboarding and Channels & Roles for the 126.21 view system. */
internal class OnboardingScreen(
    private val activity: Activity,
    private val guildId: Long,
    private val firstRun: Boolean,
    personalized: Boolean,
    private val onConfigChanged: (OnboardingConfig) -> Unit,
    private val onInitialComplete: () -> Unit,
    private val onPersonalizedChanged: (Boolean) -> Unit,
    private val onChannelsChanged: (Map<Long, Boolean>) -> Unit,
    private val onClosed: () -> Unit,
) {
    private enum class Tab { CUSTOMIZE, BROWSE }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val dialog = Dialog(activity)
    private val primary = color("colorBackgroundPrimary", 0xff313338.toInt())
    private val secondary = color("colorBackgroundSecondary", 0xff2b2d31.toInt())
    private val normal = color("colorTextNormal", 0xfff2f3f5.toInt())
    private val muted = color("colorTextMuted", 0xffb5bac1.toInt())
    private val brand = color("colorBrand", 0xff5865f2.toInt())
    private val guildName = StoreStream.getGuilds().getGuild(guildId)?.name ?: "Server"
    private var config: OnboardingConfig? = null
    private var saved: Set<String> = emptySet()
    private val selected = linkedSetOf<String>()
    private var channels: List<BrowseChannel> = emptyList()
    private val flags = mutableMapOf<Long, Int>()
    private var tab = Tab.CUSTOMIZE
    private var initial = firstRun
    private var personalized = personalized
    private var promptIndex = 0
    private var expandedCategoryId: Long? = null
    private var loading = true
    private var browseError: String? = null
    private var saving = false
    private var closed = false
    private var generation = 0
    private lateinit var title: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var footer: LinearLayout

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(createView())
        dialog.setOnDismissListener {
            closed = true
            generation++
            worker.shutdownNow()
            main.removeCallbacksAndMessages(null)
            onClosed()
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (initial && promptIndex > 0) {
                    promptIndex--
                    render()
                } else closeWithConfirmation()
                true
            } else false
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(primary))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = primary
            navigationBarColor = primary
        }
        load()
    }

    fun dismiss() = dialog.dismiss()

    private fun createView(): View {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primary)
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), 0)
            setBackgroundColor(secondary)
        }
        title = TextView(activity).apply {
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(normal)
            text = "Channels & Roles"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        header.addView(TextView(activity).apply {
            text = "✕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(muted)
            contentDescription = "Close"
            isClickable = true
            isFocusable = true
            setOnClickListener { closeWithConfirmation() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(header)

        tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(secondary)
        }
        page.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        scroll = ScrollView(activity).apply { isFillViewport = true }
        content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(secondary)
        }
        page.addView(footer)
        render()
        return page
    }

    private fun load() {
        val task = ++generation
        worker.execute {
            val result = runCatching {
                val api = OnboardingApi(OnboardingApi.currentToken())
                Triple(runCatching { api.getConfig(guildId) }, runCatching { api.getChannels(guildId) }, api)
            }
            main.post {
                if (closed || task != generation) return@post
                loading = false
                result.onSuccess { (configResult, browseResult, api) ->
                    if (configResult.isFailure && browseResult.isFailure) {
                        renderError(browseResult.exceptionOrNull() ?: configResult.exceptionOrNull()!!)
                        return@onSuccess
                    }
                    val loaded = configResult.getOrNull()
                        ?: OnboardingConfig(guildId, emptyList(), emptySet(), emptySet())
                    config = loaded
                    val valid = loaded.prompts.flatMap { prompt -> prompt.options.map { it.id } }.toSet()
                    saved = loaded.responses.filterTo(linkedSetOf()) { it in valid }
                    selected.clear()
                    selected.addAll(saved)
                    channels = browseResult.getOrDefault(emptyList())
                    browseError = browseResult.exceptionOrNull()?.message
                    channels.forEach { channel -> flags[channel.id] = api.getChannelFlags(guildId, channel.id) }
                    if (loaded.prompts.isEmpty()) tab = Tab.BROWSE
                    if (initial && loaded.prompts.none { it.inOnboarding }) initial = false
                    render()
                }.onFailure { error ->
                    renderError(error)
                }
            }
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        footer.removeAllViews()
        tabs.removeAllViews()
        footer.visibility = if (initial || tab == Tab.CUSTOMIZE) View.VISIBLE else View.GONE
        val data = config
        if (loading) {
            title.text = "Channels & Roles"
            tabs.visibility = View.GONE
            content.addView(ProgressBar(activity), LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(32)
            })
            return
        }
        if (data == null) return
        if (initial) {
            renderInitial(data)
            return
        }
        title.text = "Channels & Roles"
        tabs.visibility = View.VISIBLE
        tabButton("Customize", Tab.CUSTOMIZE)
        tabButton("Browse Channels", Tab.BROWSE)
        when (tab) {
            Tab.CUSTOMIZE -> renderCustomize(data)
            Tab.BROWSE -> renderBrowse(data)
        }
    }

    private fun renderInitial(data: OnboardingConfig) {
        tabs.visibility = View.GONE
        val prompts = data.prompts.filter { it.inOnboarding }
        if (prompts.isEmpty()) {
            initial = false
            render()
            return
        }
        promptIndex = promptIndex.coerceIn(0, prompts.lastIndex)
        title.text = "Welcome to $guildName"
        label("QUESTION ${promptIndex + 1} OF ${prompts.size}", 12f, brand)
        renderPrompt(prompts[promptIndex])
        if (promptIndex > 0) {
            footer.addView(actionButton("Back", false) {
                promptIndex--
                render()
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        footer.addView(actionButton(if (promptIndex == prompts.lastIndex) "Finish" else "Continue", true) {
            val prompt = prompts[promptIndex]
            if (prompt.required && prompt.options.none { it.id in selected }) {
                Utils.showToast("Choose an answer to continue")
                return@actionButton
            }
            if (promptIndex < prompts.lastIndex) {
                promptIndex++
                render()
                scroll.scrollTo(0, 0)
            } else save(true)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (promptIndex > 0) marginStart = dp(8) })
    }

    private fun renderCustomize(data: OnboardingConfig) {
        label("Choose your channels and roles in $guildName", 16f, normal)
        if (data.prompts.isEmpty()) {
            label("This server has no customization questions.", 14f, muted)
            return
        }
        data.prompts.forEach { renderPrompt(it) }
        footer.addView(actionButton(if (saving) "Saving…" else "Save Changes", true) { save(false) },
            LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun renderPrompt(prompt: OnboardingPrompt) {
        label(prompt.title.takeIf(String::hasVisibleText) ?: "Choose an answer", 18f, normal, top = 22)
        if (prompt.required) label("Required", 12f, brand)
        if (prompt.options.isEmpty()) label("No choices available", 14f, muted)
        if (prompt.type == 1 && prompt.options.isNotEmpty()) {
            renderDropdown(prompt)
            return
        }
        prompt.options.forEach { option ->
            val setting = Utils.createCheckedSetting(
                activity,
                if (prompt.singleSelect) CheckedSetting.ViewType.RADIO else CheckedSetting.ViewType.CHECK,
                option.title,
                option.description,
            )
            setting.isChecked = option.id in selected
            setting.setOnCheckedListener { checked ->
                if (checked && prompt.singleSelect) {
                    prompt.options.forEach { selected.remove(it.id) }
                }
                if (checked) selected += option.id else selected -= option.id
                if (prompt.singleSelect) {
                    val y = scroll.scrollY
                    scroll.post {
                        render()
                        scroll.scrollTo(0, y)
                    }
                }
            }
            val emoji = createEmojiView(option, 28)
            if (emoji == null) {
                content.addView(setting, LinearLayout.LayoutParams(-1, -2))
            } else {
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(emoji, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(4)
                })
                emoji.setOnClickListener { setting.isChecked = !setting.isChecked }
                row.addView(setting, LinearLayout.LayoutParams(0, -2, 1f))
                content.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun createEmojiView(option: PromptOption, size: Int): View? {
        val id = option.emojiId
        if (id != null) {
            val uri = runCatching { ModelEmojiCustom.getImageUri(id, option.emojiAnimated, 64) }
                .getOrElse {
                    val extension = if (option.emojiAnimated) "gif" else "png"
                    "https://cdn.discordapp.com/emojis/$id.$extension?size=64&quality=lossless"
                }
            return SimpleDraweeView(activity).apply {
                setImageURI(uri)
                contentDescription = option.emojiName
            }
        }
        if (!option.emojiName.hasVisibleText()) return null
        return TextView(activity).apply {
            text = option.emojiName
            textSize = size.toFloat()
            gravity = Gravity.CENTER
            setTextColor(normal)
            contentDescription = option.emojiName
        }
    }

    private fun renderDropdown(prompt: OnboardingPrompt) {
        val chosen = prompt.options.filter { it.id in selected }
        val summary = chosen.joinToString(", ") { it.title.takeIf(String::hasVisibleText) ?: "Answer" }
            .takeIf(String::hasVisibleText)
            ?: if (prompt.singleSelect) "Select an answer" else "Select answers"
        content.addView(actionButton(summary, false) {
            showDropdownChoices(prompt)
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun showDropdownChoices(prompt: OnboardingPrompt) {
        val choices = BooleanArray(prompt.options.size) { prompt.options[it].id in selected }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        var picker: AlertDialog? = null
        prompt.options.forEachIndexed { index, option ->
            val setting = Utils.createCheckedSetting(
                activity,
                if (prompt.singleSelect) CheckedSetting.ViewType.RADIO else CheckedSetting.ViewType.CHECK,
                option.title,
                option.description,
            )
            setting.isChecked = choices[index]
            setting.setOnCheckedListener { checked ->
                if (prompt.singleSelect) {
                    if (!checked) return@setOnCheckedListener
                    prompt.options.forEach { selected.remove(it.id) }
                    selected += option.id
                    picker?.dismiss()
                    render()
                } else choices[index] = checked
            }
            val emoji = createEmojiView(option, 28)
            if (emoji == null) {
                list.addView(setting, LinearLayout.LayoutParams(-1, -2))
            } else {
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(emoji, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(4)
                })
                emoji.setOnClickListener { setting.isChecked = !setting.isChecked }
                row.addView(setting, LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setView(ScrollView(activity).apply { addView(list) })
            .setNegativeButton("Cancel", null)
        if (!prompt.singleSelect) {
            builder.setPositiveButton("Done") { _, _ ->
                prompt.options.forEach { selected.remove(it.id) }
                prompt.options.forEachIndexed { index, option ->
                    if (choices[index]) selected += option.id
                }
                render()
            }
        }
        picker = builder.create()
        picker.show()
    }

    private fun renderBrowse(data: OnboardingConfig) {
        label("Browse Channels", 18f, normal)
        label("Add channels to your list. Default channels stay visible for everyone.", 13f, muted)
        content.addView(Utils.createCheckedSetting(
            activity,
            CheckedSetting.ViewType.SWITCH,
            "Show all channels",
            "Turn off to show only channels you chose, plus the server's default channels.",
        ).apply {
            isChecked = !personalized
            setOnCheckedListener { showAll ->
                personalized = !showAll
                onPersonalizedChanged(personalized)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        browseError?.let { error ->
            label(error, 13f, muted)
            content.addView(actionButton("Retry loading channels", false) { loadChannels() },
                LinearLayout.LayoutParams(-1, dp(44)))
            return
        }
        val categories = channels.filter { it.type == 4 }.associateBy(BrowseChannel::id)
        val groups = channels.filter { it.type != 4 }.groupBy(BrowseChannel::parentId)
        if (groups.isEmpty() && categories.isEmpty()) {
            label("No channels available to browse.", 14f, muted, top = 24)
            return
        }
        val groupIds = (groups.keys + categories.keys)
            .sortedWith(compareBy({ categories[it]?.position ?: Int.MIN_VALUE }, { categories[it]?.name.orEmpty() }))
        groupIds.forEach { categoryId ->
            val members = groups[categoryId].orEmpty().sortedWith(compareBy(BrowseChannel::position, BrowseChannel::name))
            val heading = categories[categoryId]?.name ?: "Other Channels"
            val expanded = expandedCategoryId == categoryId
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(activity).apply {
                text = (if (expanded) "▾  " else "▸  ") + heading + "  (${members.size})"
                textSize = 15f
                setTextColor(normal)
                setPadding(dp(8), dp(15), dp(8), dp(15))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    expandedCategoryId = if (expanded) null else categoryId
                    render()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val allSelected = if (members.isEmpty()) {
                categoryId != 0L && flags[categoryId]?.and(OnboardingApi.OPTED_IN_FLAG) != 0
            } else members.all { isFixed(data, it.id) || flags[it.id]?.and(OnboardingApi.OPTED_IN_FLAG) != 0 }
            row.addView(CheckBox(activity).apply {
                isChecked = allSelected
                isEnabled = categoryId != 0L || members.any { !isFixed(data, it.id) }
                buttonTintList = ColorStateList.valueOf(brand)
                contentDescription = "Show $heading"
                setOnCheckedChangeListener { _, enabled ->
                    updateCategory(data, categoryId, members, enabled, this)
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (expanded) members.forEach { channel -> renderChannel(data, channel) }
        }
    }

    private fun isFixed(data: OnboardingConfig, channelId: Long): Boolean =
        channelId in data.defaultChannelIds || data.prompts.any { prompt ->
            prompt.options.any { it.id in selected && channelId in it.channelIds }
        }

    private fun updateCategory(
        data: OnboardingConfig,
        categoryId: Long,
        members: List<BrowseChannel>,
        enabled: Boolean,
        checkBox: CheckBox,
    ) {
        checkBox.isEnabled = false
        val ids = members.filterNot { isFixed(data, it.id) }.map(BrowseChannel::id).toMutableList()
        if (categoryId != 0L) ids += categoryId
        val oldFlags = ids.associateWith { flags[it] ?: 0 }
        val task = generation
        worker.execute {
            val result = runCatching {
                OnboardingApi(OnboardingApi.currentToken()).setChannelOptIns(guildId, oldFlags, enabled)
            }
            main.post {
                if (closed || generation != task) return@post
                result.onSuccess { updated ->
                    flags.putAll(updated)
                    personalized = true
                    onPersonalizedChanged(true)
                    onChannelsChanged(updated.keys.associateWith { enabled })
                    val y = scroll.scrollY
                    render()
                    scroll.post { scroll.scrollTo(0, y) }
                }.onFailure { error ->
                    checkBox.setOnCheckedChangeListener(null)
                    checkBox.isChecked = !enabled
                    checkBox.isEnabled = true
                    Utils.showToast(error.message ?: "Could not update category")
                }
            }
        }
    }

    private fun renderChannel(data: OnboardingConfig, channel: BrowseChannel) {
        val isDefault = channel.id in data.defaultChannelIds
        val assignedByPrompt = data.prompts.any { prompt ->
            prompt.options.any { it.id in selected && channel.id in it.channelIds }
        }
        val optedIn = flags[channel.id]?.and(OnboardingApi.OPTED_IN_FLAG) != 0
        val setting = Utils.createCheckedSetting(
            activity,
            CheckedSetting.ViewType.CHECK,
            (if (channel.type == 2 || channel.type == 13) "Voice: " else "# ") + channel.name,
            when {
                isDefault -> "Default channel"
                assignedByPrompt -> "Added by a customization answer"
                else -> ""
            },
        )
        setting.isChecked = isDefault || assignedByPrompt || optedIn
        setting.isEnabled = !isDefault && !assignedByPrompt
        var updating = false
        if (setting.isEnabled) setting.setOnCheckedListener { enabled ->
            if (updating) return@setOnCheckedListener
            updating = true
            setting.isEnabled = false
            val oldFlags = flags[channel.id] ?: 0
            val task = generation
            worker.execute {
                val result = runCatching {
                    OnboardingApi(OnboardingApi.currentToken()).setChannelOptIn(guildId, channel.id, oldFlags, enabled)
                }
                main.post {
                    if (closed || generation != task) return@post
                    result.onSuccess { newFlags ->
                        flags[channel.id] = newFlags
                        updating = false
                        personalized = true
                        onPersonalizedChanged(true)
                        onChannelsChanged(mapOf(channel.id to enabled))
                        val y = scroll.scrollY
                        render()
                        scroll.post { scroll.scrollTo(0, y) }
                    }.onFailure { error ->
                        setting.isChecked = !enabled
                        setting.isEnabled = true
                        updating = false
                        Utils.showToast(error.message ?: "Could not update channel")
                    }
                }
            }
        }
        content.addView(setting, LinearLayout.LayoutParams(-1, -2))
    }

    private fun tabButton(text: String, value: Tab) {
        tabs.addView(TextView(activity).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(if (tab == value) normal else muted)
            setBackgroundColor(if (tab == value) primary else secondary)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                tab = value
                render()
                scroll.scrollTo(0, 0)
            }
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun save(isInitial: Boolean) {
        if (saving) return
        val data = config ?: return
        val missing = data.prompts.firstOrNull { prompt ->
            prompt.required && (!isInitial || prompt.inOnboarding) && prompt.options.none { it.id in selected }
        }
        if (missing != null) {
            Utils.showToast("Answer required question: ${missing.title}")
            return
        }
        if (data.prompts.any { prompt -> prompt.singleSelect && prompt.options.count { it.id in selected } > 1 }) {
            Utils.showToast("Choose one answer per single-choice question")
            return
        }
        if (!isInitial && selected == saved) {
            Utils.showToast("No changes to save")
            return
        }
        saving = true
        render()
        val choices = selected.toSet()
        val task = generation
        worker.execute {
            val result = runCatching {
                val api = OnboardingApi(OnboardingApi.currentToken())
                api.saveResponses(data, choices, isInitial)
                runCatching { api.getConfig(guildId) }.getOrNull() ?: data.copy(responses = choices)
            }
            main.post {
                if (closed || generation != task) return@post
                saving = false
                result.onSuccess { updated ->
                    val current = updated.copy(responses = choices)
                    config = current
                    saved = choices
                    onConfigChanged(current)
                    if (isInitial) {
                        onInitialComplete()
                        Utils.showToast("You're all set in $guildName")
                        dismiss()
                    } else {
                        Utils.showToast("Channels & Roles updated")
                        render()
                    }
                }.onFailure { error ->
                    render()
                    showSaveError(error, data)
                }
            }
        }
    }

    private fun showSaveError(error: Throwable, data: OnboardingConfig) {
        val attempts = error.suppressed.filterIsInstance<OnboardingHttpError>() +
            listOfNotNull(error as? OnboardingHttpError)
        val details = if (attempts.isEmpty()) {
            error.message ?: "Discord did not save your choices."
        } else attempts.joinToString("\n\n") { attempt ->
            val code = if (attempt.code != 0) ", code ${attempt.code}" else ""
            val reason = attempt.discordMessage.takeIf(String::hasVisibleText) ?: "No reason returned"
            "${attempt.method}: HTTP ${attempt.status}$code — $reason"
        }
        val state = "Discord reports onboarding enabled: ${data.enabled}" +
            if (data.belowRequirements) "\nServer is below onboarding requirements." else ""
        AlertDialog.Builder(activity)
            .setTitle("Could not save onboarding choices")
            .setMessage("$details\n\n$state")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun renderError(error: Throwable) {
        content.removeAllViews()
        footer.visibility = View.VISIBLE
        label(error.message ?: "Could not load Channels & Roles", 15f, normal)
        footer.removeAllViews()
        footer.addView(actionButton("Retry", true) {
            loading = true
            render()
            load()
        }, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun loadChannels() {
        browseError = null
        val task = generation
        worker.execute {
            val result = runCatching {
                val api = OnboardingApi(OnboardingApi.currentToken())
                api.getChannels(guildId) to api
            }
            main.post {
                if (closed || generation != task) return@post
                result.onSuccess { (loaded, api) ->
                    channels = loaded
                    channels.forEach { flags[it.id] = api.getChannelFlags(guildId, it.id) }
                    render()
                }.onFailure {
                    browseError = it.message ?: "Could not load channels"
                    render()
                }
            }
        }
    }

    private fun closeWithConfirmation() {
        if (!initial && selected != saved && !saving) {
            AlertDialog.Builder(activity)
                .setTitle("Discard your changes?")
                .setNegativeButton("Keep editing", null)
                .setPositiveButton("Discard") { _, _ -> dismiss() }
                .show()
        } else dismiss()
    }

    private fun label(text: String, size: Float, color: Int, top: Int = 8) {
        content.addView(TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(top), 0, dp(8))
        })
    }

    private fun actionButton(text: String, primaryAction: Boolean, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(normal)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(if (primaryAction) brand else primary)
            }
            isClickable = true
            isFocusable = true
            isEnabled = !saving
            setOnClickListener { onClick() }
        }

    private fun color(name: String, fallback: Int): Int {
        val id = Utils.getResId(name, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(activity, id)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
