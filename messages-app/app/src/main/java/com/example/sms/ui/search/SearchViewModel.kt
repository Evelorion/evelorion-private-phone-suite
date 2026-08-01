package com.example.sms.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sms.Graph
import com.example.sms.data.repo.MessageRepository
import com.example.sms.ui.common.Avatar
import com.example.sms.ui.common.toUi
import com.example.sms.util.CodeExtractor
import com.example.sms.util.TimeFormat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchHit(
    val messageId: Long,
    val threadId: Long,
    val sender: String,
    val time: String,
    val snippet: String,
    val avatar: Avatar,
)

data class ExtractedCard(
    val code: String,
    val label: String,
    val context: String,
    val sender: String,
)

data class SearchUiState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val extracted: List<ExtractedCard> = emptyList(),
    val searching: Boolean = false,
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val noResults: Boolean get() = hasQuery && !searching && hits.isEmpty()
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repository: MessageRepository = Graph.repository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow.debounce(200).collect { runSearch(it) }
        }
        loadExtracted()
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q, searching = q.isNotBlank()) }
        queryFlow.value = q
    }

    fun clear() = onQueryChange("")

    private suspend fun runSearch(q: String) {
        if (q.isBlank()) {
            _state.update { it.copy(hits = emptyList(), searching = false) }
            return
        }
        val convs = repository.observeConversations().first().associateBy { it.threadId }
        val hits = repository.searchMessages(q).mapNotNull { m ->
            val conv = convs[m.threadId] ?: return@mapNotNull null
            val ui = conv.toUi()
            SearchHit(
                messageId = m.id,
                threadId = m.threadId,
                sender = ui.name,
                time = TimeFormat.listStamp(m.time),
                snippet = m.body,
                avatar = ui.avatar,
            )
        }
        _state.update { it.copy(hits = hits, searching = false) }
    }

    /** 「从信息中提取」：扫最近 30 天收到的短信，抽出验证码/取件码 */
    fun loadExtracted() = viewModelScope.launch {
        val convs = repository.observeConversations().first().associateBy { it.threadId }
        val cards = repository.recentIncoming().mapNotNull { m ->
            CodeExtractor.extract(m.body)?.let { e ->
                ExtractedCard(
                    code = e.code,
                    label = e.label,
                    context = e.context,
                    sender = convs[m.threadId]?.displayName.orEmpty(),
                )
            }
        }.distinctBy { it.code }.take(5)
        _state.update { it.copy(extracted = cards) }
    }
}
