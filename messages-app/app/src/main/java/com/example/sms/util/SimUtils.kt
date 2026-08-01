package com.example.sms.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** 一张可用的 SIM 卡 */
data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String,
) {
    /** 「卡1 · 中国移动」 */
    val label: String
        get() {
            val name = displayName.ifBlank { carrierName }.ifBlank { "SIM" }
            return "卡" + (slotIndex + 1) + " · " + name
        }

    val shortLabel: String get() = "卡" + (slotIndex + 1)
}

object SimUtils {

    /** 用系统默认卡发送 */
    const val SUB_DEFAULT = -1

    /** 读取当前可用的 SIM 卡；无权限或读取失败返回空列表 */
    fun activeSims(context: Context): List<SimInfo> {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()

        val sm = ContextCompat.getSystemService(context, SubscriptionManager::class.java)
            ?: return emptyList()

        return runCatching {
            sm.activeSubscriptionInfoList.orEmpty()
                .map { it.toSimInfo() }
                .sortedBy { it.slotIndex }
        }.getOrDefault(emptyList())
    }

    /** subId 对应的卡；找不到返回 null */
    fun findSim(context: Context, subId: Int): SimInfo? =
        if (subId < 0) null else activeSims(context).firstOrNull { it.subscriptionId == subId }

    private fun SubscriptionInfo.toSimInfo() = SimInfo(
        subscriptionId = subscriptionId,
        slotIndex = simSlotIndex,
        displayName = displayName?.toString().orEmpty(),
        carrierName = carrierName?.toString().orEmpty(),
    )
}
