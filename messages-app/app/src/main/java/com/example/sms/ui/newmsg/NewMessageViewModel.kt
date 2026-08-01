package com.example.sms.ui.newmsg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sms.Graph
import com.example.sms.data.repo.MessageRepository
import com.example.sms.data.system.SystemContact
import com.example.sms.ui.common.colorForName
import com.example.sms.util.PhoneUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Recipient(val id: String, val name: String, val phone: String)

data class NewMessageUiState(
    val query: String = "",
    val recipients: List<Recipient> = emptyList(),
    val suggestions: List<SystemContact> = emptyList(),
    val loadingContacts: Boolean = true,
) {
    val canStart: Boolean get() = recipients.isNotEmpty()
    /** 输入的是个像号码的字符串，但不在联系人里 —— 提供「发送到 xxx」 */
    val showRawNumberOption: Boolean
        get() = query.isNotBlank() && PhoneUtils.isLikelyPhone(query) &&
            suggestions.none { PhoneUtils.sameNumber(it.phone, query) }
}

class NewMessageViewModel(
    private val repository: MessageRepository = Graph.repository,
) : ViewModel() {

    private val _state = MutableStateFlow(NewMessageUiState())
    val state: StateFlow<NewMessageUiState> = _state.asStateFlow()

    private var allContacts: List<SystemContact> = emptyList()

    init { loadContacts() }

    fun loadContacts() = viewModelScope.launch {
        allContacts = repository.contacts.loadAll()
        _state.update { it.copy(loadingContacts = false, suggestions = filtered(it.query)) }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q, suggestions = filtered(q)) }
    }

    fun addContact(c: SystemContact) {
        _state.update { s ->
            if (s.recipients.any { PhoneUtils.sameNumber(it.phone, c.phone) }) s
            else s.copy(
                recipients = s.recipients + Recipient(c.id, c.name, c.phone),
                query = "",
                suggestions = filtered(""),
            )
        }
    }

    fun addRawNumber(number: String) {
        val n = number.trim()
        if (n.isEmpty()) return
        _state.update { s ->
            if (s.recipients.any { PhoneUtils.sameNumber(it.phone, n) }) s
            else s.copy(
                recipients = s.recipients + Recipient("raw:" + n, PhoneUtils.format(n), n),
                query = "",
                suggestions = filtered(""),
            )
        }
    }

    fun removeRecipient(r: Recipient) {
        _state.update { s -> s.copy(recipients = s.recipients.filterNot { it.id == r.id }) }
    }

    fun colorFor(name: String) = colorForName(name)

    /** 建会话并返回 threadId */
    fun startConversation(onReady: (Long) -> Unit) {
        val recipients = _state.value.recipients
        if (recipients.isEmpty()) return
        viewModelScope.launch {
            val joined = PhoneUtils.joinAddresses(recipients.map { it.phone })
            val nameHint = if (recipients.size == 1) recipients[0].name
                           else "群发（" + recipients.size + "）"
            val threadId = repository.ensureThread(joined, nameHint)
            onReady(threadId)
        }
    }

    private fun filtered(q: String): List<SystemContact> {
        if (q.isBlank()) return allContacts.take(30)
        val lower = q.lowercase()
        return allContacts.filter {
            it.name.lowercase().contains(lower) ||
                PhoneUtils.normalize(it.phone).contains(PhoneUtils.normalize(q))
        }.take(30)
    }
}
