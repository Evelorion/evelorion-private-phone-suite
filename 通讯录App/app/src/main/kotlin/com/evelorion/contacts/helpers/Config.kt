package com.evelorion.contacts.helpers

import android.content.Context

/**
 * App 配置。
 *
 * 用 SharedPreferences 而不是 DataStore —— 这里存的都是几个布尔和字符串，
 * DataStore 的协程接口会让每个读取点都变成 suspend，得不偿失。
 */
class Config(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    /**
     * 隐私保护开关。
     *
     * 打开后：私密联系人只有通过 PrivacyGuard 校验的自家 App 能读，
     * 其它调用方拿到空 Cursor。关掉的话 Provider 对所有人开放 ——
     * 这是给「我就想让第三方短信 App 也能看到」的用户留的口子。
     */
    var privacyProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY, true)
        set(v) = prefs.edit().putBoolean(KEY_PRIVACY, v).apply()

    /** 上次同步成功的时间戳。0 表示从没同步过。 */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_SYNC, v).apply()

    /**
     * 用户手动放行的额外包名。
     *
     * 默认空。加进来的包**仍然要通过证书指纹校验** —— 这个列表只放宽
     * 包名那一道，不放宽签名那一道。否则等于把开关直接关了。
     */
    var privacyAllowedPackages: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet(KEY_ALLOWED, v).apply()

    private companion object {
        const val KEY_ALLOWED = "privacy_allowed_packages"
        const val KEY_PRIVACY = "privacy_protection"
        const val KEY_LAST_SYNC = "last_sync_at"
    }
}

/** 各处都这么拿配置，避免每次 new 一个。 */
val Context.config: Config get() = Config(this)
