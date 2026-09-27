package com.aliucord.coreplugins.componentsv2

import com.discord.api.message.embed.MessageEmbed
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import b.i.d.q.x.c as ReflectiveBoundField

internal fun isMessageEmbedAdapter(adapter: ReflectiveTypeAdapterFactory.Adapter<*>): Boolean =
    (adapter.b["url"] as? ReflectiveBoundField)?.d?.declaringClass == MessageEmbed::class.java
