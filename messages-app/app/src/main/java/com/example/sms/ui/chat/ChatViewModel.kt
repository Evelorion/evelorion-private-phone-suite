package com.example.sms.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sms.Graph
import com.example.sms.data.db.MessageEntity
import com.example.sms.data.db.MsgStatus
import com.example.sms.data.db.MsgType
import com.example.sms.data.repo.MessageRepository
import com.example.sms.util.PhoneUtils
import com.example.sms.util.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 会话里的一行：气泡或日期分隔 */
sealed interface ChatItem {
    data class DayDivider(val label: String) : ChatItem
    data class Message(
        val id: Long,
        val body: String,
        val outgoing: Boolean,
        val time: String,
        val status: MsgStatus,
        val type: MsgType,
        val attachmentUri: String?,
        val durationMs: Long,
        val reaction: String?,
        val statusLabel: String?,
        val errorMessage: String?,
    ) : ChatItem
    data object Typing : ChatItem
}

data class ChatUiState(
    val title: String = "",
    val subtitle: String = "",
    val address: String = "",
    val items: List<ChatItem> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
)

class ChatViewModel(
    private val threadId: Long,
    private val repository: MessageRepository = Graph.repository,
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val typing = MutableStateFlow(false)
    private val sending = MutableStateFlow(false)

    val state: StateFlow<ChatUiState> = combine(
        repository.observeConversation(threadId),
        repository.observeThread(threadId),
        draft,
        typing,
        sending,
    ) { conv, messages, d, isTyping, isSending ->
        ChatUiState(
            title = conv?.displayName.orEmpty(),
            subtitle = subtitleFor(conv?.address.orEmpty()),
            address = conv?.address.orEmpty(),
            items = buildItems(messages, isTyping),
            draft = d,
            sending = isSending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    val isTyping: StateFlow<Boolean> = typing.asStateFlow()

    init {
        viewModelScope.launch {
            repository.markRead(threadId)
            repository.getConversation(threadId)?.let { draft.value = it.draft }
        }
    }

    fun onDraftChange(text: String) {
        draft.value = text
    }

    fun persistDraft() = viewModelScope.launch { repository.saveDraft(threadId, draft.value) }

    fun send() {
        val body = draft.value.trim()
        if (body.isEmpty() || sending.value) return
        val address = state.value.address
        if (address.isBlank()) return
        draft.value = ""
        sending.value = true
        viewModelScope.launch {
            try {
                val subId = Graph.settings.settings.first().sendSubId
                Graph.sender.send(threadId, address, body, subId)
                repository.saveDraft(threadId, "")
            } finally {
                sending.value = false
            }
        }
    }

    fun sendImage(uri: String, caption: String = "") = viewModelScope.launch {
        // 图片作为本地附件消息记录；真正的 MMS 发送需要运营商彩信通道
        repository.createOutgoing(
            threadId = threadId,
            address = state.value.address,
            body = caption.ifBlank { "图片" },
            type = MsgType.IMAGE,
            attachmentUri = uri,
        ).also { repository.setStatus(it, MsgStatus.SENT) }
    }

    fun react(messageId: Long, emoji: String?) =
        viewModelScope.launch { repository.setReaction(messageId, emoji) }

    fun retry(messageId: Long, body: String) = viewModelScope.launch {
        val subId = Graph.settings.settings.first().sendSubId
        Graph.sender.retry(messageId, state.value.address, body, subId)
    }

    fun deleteMessage(messageId: Long) = viewModelScope.launch { repository.deleteMessage(messageId) }

    fun setPeerTyping(v: Boolean) { typing.value = v }

    /* ---------------- 内部 ---------------- */

    private fun subtitleFor(address: String): String {
        val list = PhoneUtils.splitAddresses(address)
        return when {
            list.isEmpty() -> ""
            list.size > 1 -> list.size.toString() + " 位收件人"
            else -> PhoneUtils.format(list[0])
        }
    }

    private fun buildItems(messages: List<MessageEntity>, isTyping: Boolean): List<ChatItem> {
        val out = mutableListOf<ChatItem>()
        var lastTime = 0L
        messages.forEach { m ->
            if (lastTime == 0L || TimeFormat.needsDivider(lastTime, m.time)) {
                out += ChatItem.DayDivider(TimeFormat.dayDivider(m.time))
            }
            lastTime = m.time
            out += ChatItem.Message(
                id = m.id,
                body = m.body,
                outgoing = m.outgoing,
                time = TimeFormat.clock(m.time),
                status = m.status,
                type = m.type,
                attachmentUri = m.attachmentUri,
                durationMs = m.durationMs,
                reaction = m.reaction,
                statusLabel = statusLabel(m),
                errorMessage = m.errorMessage,
            )
        }
        if (isTyping) out += ChatItem.Typing
        return out
    }

    private fun statusLabel(m: MessageEntity): String? {
        if (!m.outgoing) return null
        return when (m.status) {
            MsgStatus.PENDING -> "发送中…"
            MsgStatus.SENT -> "已发送 " + TimeFormat.clock(m.time)
            MsgStatus.DELIVERED -> "已送达 " + TimeFormat.clock(m.time)
            MsgStatus.FAILED -> m.errorMessage ?: "发送失败，点按重试"
            MsgStatus.RECEIVED -> null
        }
    }

}
