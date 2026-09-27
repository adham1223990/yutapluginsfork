package com.aliucord.coreplugins.componentsv2

import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.PreHook
import com.aliucord.patcher.after
import com.discord.api.botuikit.Component
import com.discord.api.message.embed.MessageEmbed
import com.discord.models.domain.Model
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import b.a.b.a as TypeAdapterRegistrar
import b.i.d.c as FieldNamingPolicy
import b.i.d.e as GsonBuilder

/** Identity keys keep an updated preview separate from an otherwise equal old embed. */
object ComponentEmbeds {
    private class Key(embed: MessageEmbed, queue: ReferenceQueue<MessageEmbed>? = null) :
        WeakReference<MessageEmbed>(embed, queue) {
        private val hash = System.identityHashCode(embed)
        override fun hashCode() = hash
        override fun equals(other: Any?): Boolean =
            this === other || (other is Key && get() != null && get() === other.get())
    }

    private val queue = ReferenceQueue<MessageEmbed>()
    private val components = HashMap<Key, List<Component>>()

    private fun cleanup() {
        while (true) components.remove(queue.poll() ?: return)
    }

    @Synchronized
    fun get(embed: MessageEmbed): List<Component>? {
        cleanup()
        return components[Key(embed)]
    }

    @Synchronized
    fun set(embed: MessageEmbed, value: List<Component>?) {
        cleanup()
        if (value.isNullOrEmpty()) components.remove(Key(embed))
        else components[Key(embed, queue)] = value
    }
}

fun patchComponentEmbeds(patcher: PatcherAPI) {
    val gson = GsonBuilder().run {
        c = FieldNamingPolicy.m
        TypeAdapterRegistrar.a(this)
        e.add(Model.TypeAdapterFactory())
        a()
    }
    val adapter = gson.h(object : TypeToken<List<Component>>() {})
    val field = object : ReflectiveTypeAdapterFactory.a("components", true, true) {
        override fun a(reader: JsonReader, target: Any) {
            ComponentEmbeds.set(target as MessageEmbed, adapter.read(reader))
        }

        override fun b(writer: JsonWriter, target: Any) {
            adapter.write(writer, ComponentEmbeds.get(target as MessageEmbed))
        }

        override fun c(target: Any) = ComponentEmbeds.get(target as MessageEmbed) != null
    }
    // Extend existing adapters as well as newly created ones. Writing the extra
    // field preserves previews in Discord's disk cache, not just live messages.
    val extend = PreHook { frame ->
        val target = frame.thisObject as ReflectiveTypeAdapterFactory.Adapter<*>
        if ("components" !in target.b && isMessageEmbedAdapter(target))
            target.b["components"] = field
    }
    patcher.patch(ReflectiveTypeAdapterFactory.Adapter::class.java, "read", arrayOf(JsonReader::class.java), extend)
    patcher.patch(ReflectiveTypeAdapterFactory.Adapter::class.java, "write", arrayOf(JsonWriter::class.java, Any::class.java), extend)

    // Preview-only updates must participate in message/store diffing too.
    patcher.after<MessageEmbed>("equals", Any::class.java) { param ->
        val other = param.args[0]
        if (param.result == true && other is MessageEmbed)
            param.result = ComponentEmbeds.get(this) == ComponentEmbeds.get(other)
    }
    patcher.after<MessageEmbed>("hashCode") { param ->
        ComponentEmbeds.get(this)?.let { param.result = 31 * (param.result as Int) + it.hashCode() }
    }
}
