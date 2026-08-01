package com.example.sms.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sms.Graph
import com.example.sms.data.db.MsgCategory
import com.example.sms.data.prefs.AppSettings
import com.example.sms.data.prefs.ListStyle
import com.example.sms.data.repo.MessageRepository
import com.example.sms.ui.common.ConversationUi
import com.example.sms.ui.common.toUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MsgFilter(val label: String) {
    ALL("全部"), UNREAD("未读"), PERSONAL("个人"), TRANSACTION("交易"), PROMO("推广");

    fun matches(c: ConversationUi): Boolean = when (this) {
        ALL -> true
        UNREAD -> c.unreadCount > 0
        PERSONAL -> c.category == MsgCategory.PERSONAL
        TRANSACTION -> c.category == MsgCategory.TRANSACTION
        PROMO -> c.category == MsgCategory.PROMO
    }
}

data class ListUiState(
    val all: List<ConversationUi> = emptyList(),
    val filter: MsgFilter = MsgFilter.ALL,
    val loading: Boolean = true,
    val blockedThisMonth: Int = 0,
) {
    val shown: List<ConversationUi> get() = all.filter { filter.matches(it) }
    val unread: List<ConversationUi> get() = shown.filter { it.unreadCount > 0 }
    val earlier: List<ConversationUi> get() = shown.filter { it.unreadCount == 0 }
    val unreadTotal: Int get() = all.count { it.unreadCount > 0 }
    val isEmpty: Boolean get() = !loading && all.isEmpty()
}

class ConversationListViewModel(
    private val repository: MessageRepository = Graph.repository,
) : ViewModel() {

    private val filter = MutableStateFlow(MsgFilter.ALL)

    val settings: StateFlow<AppSettings> = Graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** 相对时间（「3 分钟前」）需要定时重算，否则会一直停在最初的值 */
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    val state: StateFlow<ListUiState> = combine(
        repository.observeConversations(),
        filter,
        repository.observeBlockedCountThisMonth(),
        ticker,
    ) { convs, f, blocked, now ->
        ListUiState(
            all = convs.map { it.toUi(now) },
            filter = f,
            loading = false,
            blockedThisMonth = blocked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    fun setFilter(f: MsgFilter) { filter.value = f }

    fun setListStyle(style: ListStyle) = viewModelScope.launch { Graph.settings.setListStyle(style) }

    fun togglePin(threadId: Long, pinned: Boolean) =
        viewModelScope.launch { repository.setPinned(threadId, pinned) }

    fun toggleMute(threadId: Long, muted: Boolean) =
        viewModelScope.launch { repository.setMuted(threadId, muted) }

    fun block(threadId: Long) = viewModelScope.launch { repository.setBlocked(threadId, true) }

    fun markRead(threadId: Long) = viewModelScope.launch { repository.markRead(threadId) }

    /** 一键已读 */
    fun markAllRead() = viewModelScope.launch { repository.markAllRead() }

    fun delete(threadId: Long) = viewModelScope.launch { repository.deleteConversation(threadId) }

    /** 首次授予 READ_SMS 后把系统里已有短信导进来 */
    fun importSystemSms(force: Boolean = false) =
        viewModelScope.launch { repository.importSystemSms(force) }
}
