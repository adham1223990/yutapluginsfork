package com.aliucord.coreplugins.componentsv2

import com.aliucord.api.PatcherAPI
import com.aliucord.coreplugins.isComponentV2
import com.aliucord.patcher.*
import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageReplies.MessageState
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreThreadMessages
import com.discord.widgets.chat.list.entries.BotUiComponentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.botuikit.ComponentExperiments
import com.discord.widgets.botuikit.ComponentChatListState.ComponentStoreState
import com.discord.api.botuikit.ComponentType
import com.discord.widgets.botuikit.ComponentStateMapper
import com.discord.widgets.chat.list.model.WidgetChatListModelMessages

fun patchMessageItems(patcher: PatcherAPI) {
    @Suppress("UNUSED_DESTRUCTURED_PARAMETER_ENTRY", "LocalVariableName", "UnusedVariable")
    patcher.patch(WidgetChatListModelMessages.Companion::class.java.declaredMethods.find { it.name == "getMessageItems" }!!)
    {(
         param,
         channel: Channel,
         guildMembers: Map<Long, GuildMember>,
         guildRoles: Map<Long, GuildRole>,
         _blockedRelationships: Map<Long, Int>?,
         _referencedChannel: Channel?,
         _threadStoreState: StoreThreadMessages.ThreadState?,
         _message: Message,
         state: StoreMessageState.State?,
         _repliedMessages: Map<Long, MessageState>?,
         _isBlockedExpanded: Boolean,
         _isMinimal: Boolean,
     ) ->
        @Suppress("UNCHECKED_CAST")
        val result = (param.result as MutableList<ChatListEntry>)
        val meId = param.args[15] as Long
        result.forEachIndexed { index, entry ->
            if (entry is BotUiComponentEntry && entry.message.isComponentV2) {
                val fields = BotUiComponentV2Entry.V2Fields(state, meId, channel, guildMembers, guildRoles)
                result[index] = BotUiComponentV2Entry.fromV1(entry, fields)
            } else if (entry is EmbedEntry) {
                val components = ComponentEmbeds.get(entry.embed)
                if (!components.isNullOrEmpty()) {
                    val mapped = ComponentStateMapper.INSTANCE.processComponentsToMessageComponents(
                        components, null,
                        object : ComponentExperiments {
                            override fun isEnabled(type: ComponentType) = true
                        },
                        entry.allowAnimatedEmojis,
                    )
                    if (mapped.isNotEmpty()) {
                        val fields = BotUiComponentV2Entry.V2Fields(state, meId, channel, guildMembers, guildRoles)
                        result[index] = BotUiComponentV2Entry(
                            entry.message, 0L, entry.guildId, mapped.toMutableList(), fields, entry.embedIndex,
                        )
                    }
                }
            }
        }
        // Search, pins and other secondary lists disable the client's bot-row
        // switch. CV2 messages can contain all of their visible text in that row.
        if (param.args[16] == false && _message.isComponentV2 &&
            result.none { it is BotUiComponentEntry } && !_message.components.isNullOrEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val componentStates = param.args[17] as Map<Long, ComponentStoreState>
            val mapped = ComponentStateMapper.INSTANCE.processComponentsToMessageComponents(
                _message.components,
                componentStates[_message.id],
                object : ComponentExperiments {
                    override fun isEnabled(type: ComponentType) = true
                },
                param.args[12] as Boolean,
            )
            if (mapped.isNotEmpty()) {
                val fields = BotUiComponentV2Entry.V2Fields(state, meId, channel, guildMembers, guildRoles)
                result.add(BotUiComponentV2Entry(
                    _message,
                    _message.applicationId ?: _message.author?.id ?: 0L,
                    channel.i(),
                    mapped.toMutableList(),
                    fields,
                ))
            }
        }
    }
}
