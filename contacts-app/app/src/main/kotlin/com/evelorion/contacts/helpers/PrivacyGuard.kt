package com.evelorion.contacts.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Binder
import android.os.Build
import android.os.Process
import android.util.Log
// config 在同一个包里（helpers），不用 import
import java.security.MessageDigest

/**
 * 判断某个调用方能不能读取私密联系人。
 *
 * 相比之前的版本，这一版收紧了三处：
 *
 *   1. 用签名证书的 SHA-256 指纹钉扎，而不是只调用 checkSignatures。
 *      checkSignatures 比较的是「和我自己的签名是否相同」，这在正常情况下够用，
 *      但如果 App 自身被重打包（比如被人改了包名重签后发出去），
 *      重打包后的两个 App 之间照样能互相通过 checkSignatures。
 *      钉扎硬编码的指纹能让「只有我签的那一套 App」这个约束真正成立。
 *
 *   2. 逐个校验调用方 uid 下的每一个包，而不是「有一个匹配就放行」。
 *      一个 uid 可以对应多个共享 uid 的包，只要有一个不可信就应当整体拒绝。
 *
 *   3. 明确处理 uid 复用：先用 Binder.getCallingUid() 拿到真实调用者，
 *      callingPackage 是调用方自报的，不能单独作为依据。
 *
 * 需要说清楚的边界：signature 级权限由系统 PackageManager 执行。
 * 设备被 root、装了 Xposed / LSPosed、或者刷了改过 framework 的系统时，
 * 这层保护可以被绕过。它挡的是「普通第三方 App 想读你的通讯录」，
 * 不是「拿到 root 的攻击者」。
 */
object PrivacyGuard {

    private const val TAG = "PrivacyGuard"

    /**
     * 自家 App 的包名。debug 构建会带 .debug 后缀，所以按前缀匹配。
     * 注意：只在包名前缀匹配还不够，必须同时通过下面的证书指纹校验。
     */
    private val trustedPackagePrefixes = setOf(
        "com.evelorion.contacts",
        "com.evelorion.phone",
    )

    /**
     * 允许访问的签名证书 SHA-256 指纹，小写十六进制无分隔符。
     *
     * 怎么填：用你的正式签名证书打包后执行
     *     keytool -list -v -keystore your.jks -alias your-alias
     * 取 "SHA256:" 那一行，去掉冒号并转成小写。
     * 或者对已装好的 APK：
     *     apksigner verify --print-certs app-release.apk
     *
     * 留空集合表示「不做指纹钉扎，退回到 checkSignatures」——
     * 开发阶段可以先这样，正式发版前一定要填上，否则第 1 条加固等于没做。
     */
    private val pinnedCertSha256 = setOf<String>(
        // "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    )

    fun isCallerAllowed(context: Context, callingPackage: String?, privacyProtectionEnabled: Boolean): Boolean {
        if (!privacyProtectionEnabled) return true

        val callingUid = Binder.getCallingUid()
        // 自己调自己，直接放行
        if (callingUid == Process.myUid()) return true

        val packageManager = context.packageManager
        val uidPackages = packageManager.getPackagesForUid(callingUid)?.toList().orEmpty()

        if (uidPackages.isEmpty()) {
            Log.w(TAG, "拒绝：无法解析 uid $callingUid 对应的包名")
            return false
        }

        // 调用方自报的包名必须确实属于这个 uid，否则说明它在撒谎
        if (!callingPackage.isNullOrBlank() && callingPackage !in uidPackages) {
            Log.w(TAG, "拒绝：$callingPackage 不属于 uid $callingUid")
            return false
        }

        // 共享 uid 时只要有一个包不可信，整个 uid 都不能放行
        val allTrusted = uidPackages.all { pkg -> isPackageTrusted(context, pkg) }
        if (!allTrusted) {
            Log.w(TAG, "拒绝：uid $callingUid 下有不可信的包 $uidPackages")
        }
        return allTrusted
    }

    private fun isPackageTrusted(context: Context, packageName: String): Boolean {
        val allowedByName = trustedPackagePrefixes.any { prefix ->
            packageName == prefix || packageName.startsWith("$prefix.")
        } || packageName in context.config.privacyAllowedPackages

        if (!allowedByName) return false

        return if (pinnedCertSha256.isEmpty()) {
            // 还没配置指纹，退回到「和本 App 同签名」
            @Suppress("DEPRECATION")
            context.packageManager.checkSignatures(context.packageName, packageName) ==
                PackageManager.SIGNATURE_MATCH
        } else {
            signingCertificates(context, packageName).any { cert ->
                sha256Hex(cert.toByteArray()) in pinnedCertSha256
            }
        }
    }

    private fun signingCertificates(context: Context, packageName: String): List<Signature> = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo
            when {
                signing == null -> emptyList()
                // 轮换过签名的 App 会有多个历史证书，任意一个匹配都算数
                signing.hasMultipleSigners() -> signing.apkContentsSigners.toList()
                else -> signing.signingCertificateHistory.toList()
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * 给设置页显示用：算出本机这套 App 的证书指纹，方便用户把它填进 pinnedCertSha256。
     * 显示时按每两个字符加冒号，和 keytool 的输出格式对齐。
     */
    fun ownCertificateFingerprint(context: Context): String =
        signingCertificates(context, context.packageName)
            .firstOrNull()
            ?.let { sha256Hex(it.toByteArray()) }
            ?.chunked(2)
            ?.joinToString(":")
            ?.uppercase()
            .orEmpty()
}
