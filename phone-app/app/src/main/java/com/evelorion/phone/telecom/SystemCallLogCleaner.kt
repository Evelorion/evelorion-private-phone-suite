package com.evelorion.phone.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一键清除 Android 系统通话记录；本应用自己的 CallDatabase 完全不受影响。 */
object SystemCallLogCleaner {

    sealed interface Result {
        data class Success(val deleted: Int) : Result
        data object NotDefaultDialer : Result
        data object MissingPermission : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun clearAll(context: Context): Result = withContext(Dispatchers.IO) {
        if (!DialerRole.isDefault(context)) return@withContext Result.NotDefaultDialer
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.MissingPermission
        }

        runCatching {
            // Android 16 不允许逐条 item URI 删除；必须使用基 URI。
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        }.fold(
            onSuccess = { Result.Success(it) },
            onFailure = { Result.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }
}
