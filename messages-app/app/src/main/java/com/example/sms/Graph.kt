package com.example.sms

import android.content.Context
import android.os.Process
import com.example.sms.data.db.AppDatabase
import com.example.sms.data.prefs.SettingsStore
import com.example.sms.data.repo.MessageRepository
import com.example.sms.sms.SmsSender
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/** 极简依赖容器，避免为了 DI 引入额外框架 */
object Graph {

    lateinit var appContext: Context
        private set

    /**
     * 短信接收、分段回执和通知回复可能在游戏前台时到达。串行、低优先级执行，
     * 避免长短信的多个广播同时占用 Default dispatcher 的多个 CPU 核心。
     */
    private val backgroundDispatcher by lazy {
        Executors.newSingleThreadExecutor { command ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    command.run()
                },
                "sms-background",
            )
        }.asCoroutineDispatcher()
    }

    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + backgroundDispatcher)
    }

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val repository: MessageRepository by lazy { MessageRepository(appContext, database, settings = settings) }
    val sender: SmsSender by lazy { SmsSender(appContext, repository) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
