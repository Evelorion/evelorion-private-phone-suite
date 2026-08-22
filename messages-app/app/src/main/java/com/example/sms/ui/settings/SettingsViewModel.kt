package com.example.sms.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sms.Graph
import com.example.sms.data.prefs.AppSettings
import com.example.sms.data.prefs.ListStyle
import com.example.sms.data.prefs.SettingsStore
import com.example.sms.data.prefs.ThemeMode
import com.example.sms.data.repo.MessageRepository
import com.example.sms.data.system.SystemSmsStore
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
    val clearingSystemSms: Boolean = false,
    val systemCleanupMessage: String = "",
)

private data class CleanupState(
    val running: Boolean = false,
    val message: String = "",
)

class SettingsViewModel(
    private val store: SettingsStore = Graph.settings,
    private val repository: MessageRepository = Graph.repository,
) : ViewModel() {

    private val roleRefresh = MutableStateFlow(0)
    private val cleanupState = MutableStateFlow(CleanupState())

    val state: StateFlow<SettingsUiState> = combine(
        store.settings,
        repository.observeBlockedCountThisMonth(),
        roleRefresh,
        cleanupState,
    ) { s, blocked, _, cleanup ->
        SettingsUiState(
            settings = s,
            blockedThisMonth = blocked,
            isDefaultSmsApp = repository.systemSms.isDefaultSmsApp(),
            roleCheck = runCatching { SmsRoleCheck.check(AppGraph.appContext) }.getOrNull(),
            clearingSystemSms = cleanup.running,
            systemCleanupMessage = cleanup.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setNotifications(v: Boolean) = viewModelScope.launch { store.setNotifications(v) }
    fun setBlockSpam(v: Boolean) = viewModelScope.launch { store.setBlockSpam(v) }
    fun setRcs(v: Boolean) = viewModelScope.launch { store.setRcs(v) }
    fun setListStyle(v: ListStyle) = viewModelScope.launch { store.setListStyle(v) }
    fun setThemeMode(v: ThemeMode) = viewModelScope.launch { store.setThemeMode(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { store.setDynamicColor(v) }
    fun setSeedColor(v: Int) = viewModelScope.launch { store.setSeedColor(v) }
    fun setDeleteSystemSmsAfterImport(v: Boolean) = viewModelScope.launch {
        store.setDeleteSystemSmsAfterImport(v)
    }
    fun reimport() = viewModelScope.launch { repository.importSystemSms(force = true) }
    fun clearSystemSms() = viewModelScope.launch {
        if (cleanupState.value.running) return@launch
        cleanupState.value = CleanupState(running = true, message = "正在清理系统短信…")
        cleanupState.value = when (val result = repository.systemSms.clearAll()) {
            is SystemSmsStore.ClearResult.Success -> CleanupState(
                message = if (result.deleted > 0) {
                    "已清除 ${result.deleted} 条系统短信；本应用内短信保持不变"
                } else {
                    "系统短信库已经是空的；本应用内短信保持不变"
                },
            )
            SystemSmsStore.ClearResult.NotDefaultSmsApp -> CleanupState(
                message = "请先把本应用设为默认短信应用",
            )
            SystemSmsStore.ClearResult.MissingReadPermission -> CleanupState(
                message = "请先授予短信权限后重试",
            )
            is SystemSmsStore.ClearResult.Failed -> CleanupState(
                message = "系统短信清理失败：${result.reason}",
            )
        }
    }
    fun refreshRole() { roleRefresh.value += 1 }
}
