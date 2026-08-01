package com.example.sms.util

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager

/**
 * 「默认短信应用」资格自检。
 *
 * 系统要求一个 App 同时具备下面四个组件，才会把它列进短信应用候选。
 * 少任何一个都不会出现在选择列表里，而且系统不会给出任何提示 —— 所以这里自己查一遍。
 */
object SmsRoleCheck {

    /** Android 10+ 以 RoleManager 为准；旧系统再使用 Telephony 的默认包名。 */
    fun isDefaultSmsApp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val held = runCatching {
                context.getSystemService(RoleManager::class.java)
                    ?.isRoleHeld(RoleManager.ROLE_SMS) == true
            }.getOrDefault(false)
            if (held) return true
        }
        return runCatching {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }.getOrDefault(false)
    }

    data class Result(
        /** SENDTO Activity（smsto:） */
        val sendToActivity: Boolean,
        /** RESPOND_VIA_MESSAGE Service（来电快捷回复） */
        val respondService: Boolean,
        /** SMS_DELIVER 接收器 */
        val smsDeliver: Boolean,
        /** WAP_PUSH_DELIVER 接收器（彩信） */
        val wapPushDeliver: Boolean,
        /** 系统是否开放 SMS 角色（无电话功能的设备为 false） */
        val roleAvailable: Boolean,
        /** 设备是否有电话功能 */
        val hasTelephony: Boolean,
        /** 当前的默认短信应用包名 */
        val currentDefault: String?,
        val isSelf: Boolean,
    ) {
        /** 四个必需组件是否齐全 */
        val componentsOk: Boolean
            get() = sendToActivity && respondService && smsDeliver && wapPushDeliver

        val missing: List<String>
            get() = buildList {
                if (!sendToActivity) add("SENDTO Activity")
                if (!respondService) add("RESPOND_VIA_MESSAGE Service")
                if (!smsDeliver) add("SMS_DELIVER 接收器")
                if (!wapPushDeliver) add("WAP_PUSH_DELIVER 接收器")
            }

        /** 给用户看的一句话结论 */
        val summary: String
            get() = when {
                isSelf -> "已经是默认短信应用"
                !hasTelephony -> "本机没有电话功能，系统不提供短信应用选项"
                !componentsOk -> "组件缺失：" + missing.joinToString("、")
                !roleAvailable -> "系统未开放短信应用角色（可能被厂商系统限制）"
                else -> "组件齐全，可以设为默认；若选择列表里仍看不到本应用，" +
                    "多半是厂商系统限制了第三方短信应用"
            }
    }

    fun check(context: Context): Result {
        val pm = context.packageManager
        val self = context.packageName

        val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        val roleAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.getSystemService(RoleManager::class.java)
                    ?.isRoleAvailable(RoleManager.ROLE_SMS) == true
            }.getOrDefault(false)
        } else true

        val default = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

        return Result(
            sendToActivity = pm.queryIntentActivities(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), 0,
            ).any { it.activityInfo?.packageName == self },

            respondService = pm.queryIntentServices(
                Intent(TelephonyManager.ACTION_RESPOND_VIA_MESSAGE, Uri.parse("smsto:")), 0,
            ).any { it.serviceInfo?.packageName == self },

            smsDeliver = pm.queryBroadcastReceivers(
                Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION), 0,
            ).any { it.activityInfo?.packageName == self },

            wapPushDeliver = pm.queryBroadcastReceivers(
                Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                    setDataAndType(Uri.parse("mms:"), "application/vnd.wap.mms-message")
                },
                0,
            ).any { it.activityInfo?.packageName == self },

            roleAvailable = roleAvailable,
            hasTelephony = hasTelephony,
            currentDefault = default,
            isSelf = isDefaultSmsApp(context),
        )
    }
}
