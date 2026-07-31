package com.evelorion.phone.telecom

import android.telecom.Call
import android.telecom.CallScreeningService
import com.evelorion.phone.data.BlockedNumberStore

/**
 * Android 系统来电筛选入口。
 *
 * 只有用户在系统弹窗中授予“来电拦截”角色后，系统才会调用这里。被拦截的来电
 * 会被拒接，但仍保留在通话记录中，避免用户完全不知道发生过什么。
 */
class PhoneCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val blocked = runCatching {
            BlockedNumberStore.isEnabled(this) &&
                number.isNotBlank() &&
                BlockedNumberStore.isBlocked(this, number)
        }.getOrDefault(false)

        val response = CallResponse.Builder()
            .setDisallowCall(blocked)
            .setRejectCall(blocked)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
    }
}
