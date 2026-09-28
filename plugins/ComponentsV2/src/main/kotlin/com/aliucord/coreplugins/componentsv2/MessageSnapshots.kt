package com.aliucord.coreplugins.componentsv2

import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.PreHook
import com.aliucord.patcher.Hook
import com.aliucord.utils.ReflectUtils
import com.discord.api.botuikit.Component
import com.discord.api.message.Message
import com.discord.models.domain.Model
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import b.a.b.a as TypeAdapterRegistrar
import b.i.d.c as FieldNamingPolicy
import b.i.d.e as GsonBuilder
import b.i.d.q.x.c as ReflectiveBoundField
import java.io.StringReader
import java.util.IdentityHashMap
import android.util.Log

private class ForwardedSnapshot(val message: SnapshotMessage?)
private class SnapshotMessage(
    val components: List<Component>?,
    val content: String?,
    val flags: Long?,
)

fun patchMessageSnapshots(patcher: PatcherAPI) {
    val gson = GsonBuilder().run {
        c = FieldNamingPolicy.m
        TypeAdapterRegistrar.a(this)
        e.add(Model.TypeAdapterFactory())
        a()
    }
    val snapshots = gson.h(object : TypeToken<List<ForwardedSnapshot>>() {})
    val pending = IdentityHashMap<Any, SnapshotMessage>()
    class SnapshotField(private val previous: ReflectiveTypeAdapterFactory.a?) :
        ReflectiveTypeAdapterFactory.a("message_snapshots", true, true) {
        override fun a(reader: JsonReader, target: Any) {
            try {
                val json = gson.i(JsonElement::class.java).read(reader)
                previous?.a(JsonReader(StringReader(json.toString())), target)
                val snapshot = snapshots.fromJsonTree(json)?.firstOrNull()?.message ?: return
                synchronized(pending) {
                    if (pending.size >= 512) pending.clear()
                    pending[target] = snapshot
                }
            } catch (error: Throwable) {
                Log.e("ComponentsV2", "Failed to read forwarded message snapshot", error)
            }
        }

        override fun b(writer: JsonWriter, target: Any) {
            if (previous != null) previous.b(writer, target) else writer.s()
        }

        override fun c(target: Any) = previous?.c(target) ?: false
    }
    val extend = PreHook { frame ->
        val adapter = frame.thisObject as ReflectiveTypeAdapterFactory.Adapter<*>
        val content = adapter.b["content"] as? ReflectiveBoundField
        if (content?.d?.declaringClass == Message::class.java ||
            content?.d?.declaringClass == com.discord.models.message.Message::class.java) {
            val current = adapter.b["message_snapshots"]
            if (current !is SnapshotField)
                adapter.b["message_snapshots"] = SnapshotField(current)
        }
    }
    patcher.patch(ReflectiveTypeAdapterFactory.Adapter::class.java, "read", arrayOf(JsonReader::class.java), extend)
    patcher.patch(ReflectiveTypeAdapterFactory.Adapter::class.java, "read",
        arrayOf(JsonReader::class.java), Hook { frame ->
        val model = frame.result as? com.discord.models.message.Message ?: return@Hook
        val snapshot = synchronized(pending) { pending.remove(model) } ?: return@Hook
        runCatching { applySnapshot(model, snapshot) }
            .onFailure { Log.e("ComponentsV2", "Failed to apply cached message snapshot", it) }
    })

    // The snapshot may precede `components` or `flags` on the wire. Wait until
    // the API message is complete before copying its display data into the model.
    patcher.patch(com.discord.models.message.Message::class.java.getDeclaredConstructor(Message::class.java),
        PreHook { frame ->
            val message = frame.args[0] as Message
            val snapshot = synchronized(pending) { pending.remove(message) } ?: return@PreHook
            runCatching { applySnapshot(message, snapshot) }
                .onFailure { Log.e("ComponentsV2", "Failed to apply message snapshot", it) }
        })
}

private fun applySnapshot(message: Message, snapshot: SnapshotMessage) {
    if (message.h().isNullOrEmpty() && !snapshot.components.isNullOrEmpty()) {
        ReflectUtils.setField(message, "components", snapshot.components)
        ReflectUtils.setField(message, "flags", (message.l() ?: 0L) or 32768L)
    }
    if (message.i().isNullOrEmpty() && !snapshot.content.isNullOrEmpty())
        ReflectUtils.setField(message, "content", snapshot.content)
}

private fun applySnapshot(message: com.discord.models.message.Message, snapshot: SnapshotMessage) {
    if (message.components.isNullOrEmpty() && !snapshot.components.isNullOrEmpty()) {
        ReflectUtils.setField(message, "components", snapshot.components)
        ReflectUtils.setField(message, "flags", (message.flags ?: 0L) or 32768L)
    }
    if (message.content.isNullOrEmpty() && !snapshot.content.isNullOrEmpty())
        ReflectUtils.setField(message, "content", snapshot.content)
}
