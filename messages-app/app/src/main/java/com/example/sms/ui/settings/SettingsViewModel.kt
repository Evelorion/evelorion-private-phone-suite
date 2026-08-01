package com.example.sms.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sms.Graph
import com.example.sms.data.prefs.AppSettings
import com.example.sms.data.prefs.ListStyle
import com.example.sms.data.prefs.SettingsStore
import com.example.sms.data.prefs.ThemeMode
import com.example.sms.data.repo.MessageRepository
import com.example.sms.Graph as AppGraph
import com.example.sms.util.SmsRoleCheck
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val blockedThisMonth: Int = 0,
    val isDefaultSmsApp: Boolean = false,
    val roleCheck: SmsRoleCheck.Result? = null,
)

class SettingsViewModel(
    private val store: SettingsStore = Graph.settings,
    private val repository: MessageRepository = Graph.repository,
) : ViewModel() {

    private val roleRefresh = MutableStateFlow(0)

    val state: StateFlow<SettingsUiState> = combine(
        store.settings,
        repository.observeBlockedCountThisMonth(),
        roleRefresh,
    ) { s, blocked, _ ->
        SettingsUiState(
            settings = s,
            blockedThisMonth = blocked,
            isDefaultSmsApp = repository.systemSms.isDefaultSmsApp(),
            roleCheck = runCatching { SmsRoleCheck.check(AppGraph.appContext) }.getOrNull(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setNotifications(v: Boolean) = viewModelScope.launch { store.setNotifications(v) }
    fun setBlockSpam(v: Boolean) = viewModelScope.launch { store.setBlockSpam(v) }
    fun setRcs(v: Boolean) = viewModelScope.launch { store.setRcs(v) }
    fun setSmartReply(v: Boolean) = viewModelScope.launch { store.setSmartReply(v) }
    fun setListStyle(v: ListStyle) = viewModelScope.launch { store.setListStyle(v) }
    fun setThemeMode(v: ThemeMode) = viewModelScope.launch { store.setThemeMode(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { store.setDynamicColor(v) }
    fun setSeedColor(v: Int) = viewModelScope.launch { store.setSeedColor(v) }
    fun reimport() = viewModelScope.launch { repository.importSystemSms(force = true) }
    fun refreshRole() { roleRefresh.value += 1 }
}
