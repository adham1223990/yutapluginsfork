package com.github.yutaplug.reportform

import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.api.report.MenuAPIResponse
import com.discord.api.report.ReportNodeBottomButton
import com.discord.api.report.ReportNodeChild
import com.discord.api.report.ReportSubmissionBody
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.mobile_reports.MobileReportsViewModel
import com.discord.widgets.mobile_reports.ReportsFeatureFlag
import com.discord.widgets.mobile_reports.ReportsMenuNode
import com.discord.widgets.mobile_reports.WidgetMobileReports
import com.discord.widgets.tos.WidgetTosReportViolation
import rx.Emitter
import rx.Observable

@AliucordPlugin
class ReportForm : Plugin() {
    private val menus = ReportMenus()
    private val inputTag = 0x7f000000 or android.view.View.generateViewId()
    private val views = java.util.WeakHashMap<ReportsMenuNode, Boolean>()
    private val submitting = ThreadLocal<Map<String, List<String>>>()

    @Volatile private var active = false
    private val getState = MobileReportsViewModel::class.java.getDeclaredMethod(
        "access\$getViewState\$p",
        MobileReportsViewModel::class.java,
    )

    override fun start(context: Context) {
        active = true
        patcher.patch(ReportsFeatureFlag.Companion::class.java, "isEnabled", emptyArray(), PreHook { it.result = true })
        patcher.patch(
            WidgetTosReportViolation.Companion::class.java,
            "show",
            arrayOf(Context::class.java, String::class.java, Long::class.javaObjectType, Long::class.javaObjectType),
            PreHook { call ->
                val channelId = call.args[2] as? Long ?: return@PreHook
                val messageId = call.args[3] as? Long ?: return@PreHook
                WidgetMobileReports.Companion::class.java
                    .getDeclaredMethod(
                        "launchMessageReport",
                        Context::class.java,
                        Long::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                    ).invoke(
                        WidgetMobileReports::class.java.getField("Companion").get(null),
                        call.args[0],
                        messageId,
                        channelId,
                    )
                call.result = null
            },
        )
        patcher.patch(
            RestAPI::class.java,
            "getReportMenu",
            arrayOf(String::class.java),
            PreHook { call ->
                if (call.args[0] != "message") return@PreHook
                call.result = Observable.o({ emitter: Emitter<MenuAPIResponse> ->
                    Utils.threadPool.execute {
                        try {
                            val menu = menus.fetch()
                            if (active) {
                                emitter.onNext(menu)
                                emitter.onCompleted()
                            }
                        } catch (error: Exception) {
                            if (active) emitter.onError(error)
                        }
                    }
                }, Emitter.BackpressureMode.l)
            },
        )
        patcher.patch(
            ReportsMenuNode::class.java,
            "setup",
            arrayOf(MobileReportsViewModel.NodeState::class.java),
            Hook { call ->
                val view = call.thisObject as ReportsMenuNode
                val state = call.args[0] as MobileReportsViewModel.NodeState
                val extras = menus.get(state.node) ?: return@Hook
                var inputs = view.getTag(inputTag) as? NativeInputs
                if (inputs?.node !== state.node) {
                    inputs?.remove()
                    inputs = NativeInputs(view, state.node, extras)
                    view.setTag(inputTag, inputs)
                    views[view] = true
                }
                inputs.setLoading(state.submitState is MobileReportsViewModel.SubmitState.Loading)
            },
        )
        patcher.patch(
            ReportsMenuNode::class.java,
            "childClickListener",
            arrayOf(ReportNodeChild::class.java),
            PreHook { call -> if (!capture(call.thisObject as ReportsMenuNode)) call.result = null },
        )
        patcher.patch(
            ReportsMenuNode::class.java,
            "bottomButtonClickListener",
            arrayOf(ReportNodeBottomButton::class.java),
            PreHook { call ->
                val action = call.args[0]
                if (action is ReportNodeBottomButton.Next || action is ReportNodeBottomButton.Submit) {
                    if (!capture(call.thisObject as ReportsMenuNode)) call.result = null
                }
            },
        )
        patcher.patch(
            MobileReportsViewModel::class.java,
            "handleSubmit",
            emptyArray(),
            PreHook { call ->
                val state =
                    getState.invoke(null, call.thisObject) as? MobileReportsViewModel.ViewState.Menu
                        ?: return@PreHook
                val current = state.nodeNavigationType.node
                val extras = menus.get(current) ?: return@PreHook
                val values = mutableMapOf<String, List<String>>()
                for (result in state.history) extras.session.answers[result.c().e()]?.let { values.putAll(it) }
                extras.session.answers[current.e()]?.let { values.putAll(it) }
                submitting.set(values)
            },
        )
        patcher.patch(MobileReportsViewModel::class.java, "handleSubmit", emptyArray(), Hook { submitting.remove() })
        patcher.patch(
            RestAPI::class.java,
            "submitReport",
            arrayOf(String::class.java, ReportSubmissionBody::class.java),
            PreHook { call ->
                if (call.args[0] != "message") return@PreHook
                val extra = submitting.get() ?: return@PreHook
                val body = call.args[1] as ReportSubmissionBody

                @Suppress("UNCHECKED_CAST")
                val original = ReflectUtils.getField(body, "elements") as? Map<String, List<String>>
                val values = original?.toMutableMap() ?: mutableMapOf()
                values.putAll(extra)
                ReflectUtils.setField(body, "elements", values)
            },
        )
    }

    private fun capture(view: ReportsMenuNode): Boolean {
        val inputs = view.getTag(inputTag) as? NativeInputs ?: return true
        val state = view.viewState ?: return true
        if (state.submitState is MobileReportsViewModel.SubmitState.Loading) return false
        if (!inputs.save()) return false
        state.checkboxElement?.let { checkbox ->
            if (inputs.extras.raw.optBoolean("is_multi_select_required") && checkbox.selections.isEmpty()) {
                inputs.error("Select at least one option before continuing.")
                return false
            }
            inputs.extras.session.answers.getOrPut(state.node.e()) { mutableMapOf() }[checkbox.name] =
                checkbox.selections.map { it.a() }
        }
        return true
    }

    override fun stop(context: Context) {
        active = false
        patcher.unpatchAll()
        for (view in views.keys.toList()) {
            (view.getTag(inputTag) as? NativeInputs)?.remove()
            view.setTag(inputTag, null)
        }
        views.clear()
        menus.clear()
        submitting.remove()
    }
}
