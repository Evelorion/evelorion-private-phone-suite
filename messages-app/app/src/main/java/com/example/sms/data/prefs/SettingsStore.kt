package com.example.sms.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 三套列表方案，对应设计稿 1a / 1b / 1c */
enum class ListStyle { LIST_1A, LIST_1B, LIST_1C }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val blockSpam: Boolean = false,
    val rcsEnabled: Boolean = true,
    val smartReplyEnabled: Boolean = true,
    val listStyle: ListStyle = ListStyle.LIST_1A,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** 手动主题色种子（ARGB）；0 表示未选择 */
    val seedColor: Int = 0,
    /** 发送短信使用的 SIM 卡 subscriptionId；-1 表示系统默认卡 */
    val sendSubId: Int = -1,
    val importedSystemSms: Boolean = false,
)

/** 设置写在 DataStore 的真实文件里（files/datastore/settings.preferences_pb），重启保留 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val notifications = booleanPreferencesKey("notifications_enabled")
        val blockSpam = booleanPreferencesKey("block_spam")
        val rcs = booleanPreferencesKey("rcs_enabled")
        val smartReply = booleanPreferencesKey("smart_reply_enabled")
        val listStyle = stringPreferencesKey("list_style")
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val seedColor = intPreferencesKey("seed_color")
        val sendSubId = intPreferencesKey("send_sub_id")
        val imported = booleanPreferencesKey("imported_system_sms")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            notificationsEnabled = p[Keys.notifications] ?: true,
            blockSpam = p[Keys.blockSpam] ?: false,
            rcsEnabled = p[Keys.rcs] ?: true,
            smartReplyEnabled = p[Keys.smartReply] ?: true,
            listStyle = runCatching { ListStyle.valueOf(p[Keys.listStyle] ?: "") }
                .getOrDefault(ListStyle.LIST_1A),
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.themeMode] ?: "") }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = p[Keys.dynamicColor] ?: true,
            seedColor = p[Keys.seedColor] ?: 0,
            sendSubId = p[Keys.sendSubId] ?: -1,
            importedSystemSms = p[Keys.imported] ?: false,
        )
    }

    suspend fun setNotifications(v: Boolean) = context.dataStore.edit { it[Keys.notifications] = v }
    suspend fun setBlockSpam(v: Boolean) = context.dataStore.edit { it[Keys.blockSpam] = v }
    suspend fun setRcs(v: Boolean) = context.dataStore.edit { it[Keys.rcs] = v }
    suspend fun setSmartReply(v: Boolean) = context.dataStore.edit { it[Keys.smartReply] = v }
    suspend fun setListStyle(v: ListStyle) = context.dataStore.edit { it[Keys.listStyle] = v.name }
    suspend fun setThemeMode(v: ThemeMode) = context.dataStore.edit { it[Keys.themeMode] = v.name }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = v }
    suspend fun setSeedColor(v: Int) = context.dataStore.edit { it[Keys.seedColor] = v }
    suspend fun setSendSubId(v: Int) = context.dataStore.edit { it[Keys.sendSubId] = v }
    suspend fun setImported(v: Boolean) = context.dataStore.edit { it[Keys.imported] = v }
}
