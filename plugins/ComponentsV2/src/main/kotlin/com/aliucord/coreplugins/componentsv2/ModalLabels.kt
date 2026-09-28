package com.aliucord.coreplugins.componentsv2

import android.view.View
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.api.botuikit.ActionRowComponent
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.LabelComponent
import com.discord.api.botuikit.TextComponent
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.botuikit.ModalComponent
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import com.google.gson.stream.JsonWriter
import b.i.d.q.x.c as ReflectiveBoundField

private val labelModals = HashSet<Long>()

// Discord 126 only builds modal fields from Action Rows. Adapt Labels for its
// UI, then restore the Label response shape when the form is submitted.
fun patchModalLabels(patcher: PatcherAPI) {
    patcher.patch(ModalComponent::class.java, "onViewBound", arrayOf(View::class.java), PreHook { frame ->
        val modal = (frame.thisObject as ModalComponent).args.modal
        val original = modal.b()
        if (original.none { it is LabelComponent }) return@PreHook
        val adapted = original.map { component ->
            if (component !is LabelComponent || component.component !is TextComponent) return@map component
            val input = component.component as TextComponent
            ReflectUtils.setField(input, "label", component.label)
            ReflectUtils.allocateInstance(ActionRowComponent::class.java).also { row ->
                ReflectUtils.setField(row, "type", ComponentType.ACTION_ROW)
                ReflectUtils.setField(row, "components", listOf(input))
            }
        }
        ReflectUtils.setField(modal, "components", adapted)
        synchronized(labelModals) { labelModals.add(modal.id) }
    })

    patcher.patch(RestAPI::class.java, "sendModalInteraction",
        arrayOf(RestAPIParams.ModalInteraction::class.java), PreHook { frame ->
            val request = frame.args[0] as RestAPIParams.ModalInteraction
            val data = request.data
            if (!synchronized(labelModals) { labelModals.contains(data.id) }) return@PreHook
            val labels = data.components.map { row ->
                val input = row.components?.singleOrNull()
                if (input == null) row else RestAPIParams.ModalInteractionDataComponent(
                    ComponentV2Type.LABEL, listOf(input), null, null)
            }
            val replacement = data.copy(data.id, data.customId, labels)
            frame.args[0] = request.copy(request.type, request.applicationId,
                request.channelId, request.nonce, request.guildId, request.sessionId,
                request.messageId, replacement)
        })

    // The old request model has only a `components` array. A Label response
    // has one `component` object instead, so write that shape for type 18.
    patcher.patch(ReflectiveTypeAdapterFactory.Adapter::class.java, "write",
        arrayOf(JsonWriter::class.java, Any::class.java), PreHook { frame ->
            val value = frame.args[1] as? RestAPIParams.ModalInteractionDataComponent ?: return@PreHook
            if (value.type != ComponentV2Type.LABEL) return@PreHook
            val writer = frame.args[0] as JsonWriter
            val child = value.components?.singleOrNull() ?: return@PreHook
            val adapter = frame.thisObject as ReflectiveTypeAdapterFactory.Adapter<*>
            val typeField = adapter.b["type"] as? ReflectiveBoundField ?: return@PreHook
            if (typeField.d.declaringClass != RestAPIParams.ModalInteractionDataComponent::class.java)
                return@PreHook
            writer.c()
            writer.n("type").A(18L)
            writer.n("component")
            typeField.g.i(RestAPIParams.ModalInteractionDataComponent::class.java).write(writer, child)
            writer.f()
            frame.result = null
        })
}
