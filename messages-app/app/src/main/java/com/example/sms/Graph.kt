package com.example.sms

import android.content.Context
import com.example.sms.data.db.AppDatabase
import com.example.sms.data.prefs.SettingsStore
import com.example.sms.data.repo.MessageRepository
import com.example.sms.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** 极简依赖容器，避免为了 DI 引入额外框架 */
object Graph {

    lateinit var appContext: Context
        private set

    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val repository: MessageRepository by lazy { MessageRepository(appContext, database, settings = settings) }
    val sender: SmsSender by lazy { SmsSender(appContext, repository) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
