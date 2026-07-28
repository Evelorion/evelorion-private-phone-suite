package com.evelorion.contacts.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository
import com.evelorion.contacts.databinding.ActivitySyncSetupBinding
import com.evelorion.contacts.sync.VaultManager
import com.evelorion.contacts.sync.db.SyncDatabase
import com.evelorion.contacts.sync.net.SessionStore
import com.evelorion.contacts.sync.net.SyncApi
import com.evelorion.contacts.sync.engine.SyncEngine
import com.evelorion.contacts.sync.work.SyncEvents
import com.evelorion.contacts.sync.work.SyncScheduler
import com.evelorion.contacts.ui.BaseActivity
import com.evelorion.contacts.ui.theme.themeColor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as MaterialR
import com.evelorion.contacts.ui.Bg
import java.util.concurrent.Executors

/**
 * 加密同步的配置与状态页。
 *
 * ── 这一页做的事 ────────────────────────────────────────────
 *
 * 未配置：填服务器地址 / 用户名 / 主口令（注册时还要邀请码）→ 注册或登录。
 * 已配置：显示账号、上次同步、待上传条数 → 立即同步 / 退出登录。
 *
 * ── 主口令为什么不存 ────────────────────────────────────────
 *
 * 主口令**从不离开这台设备**，也不写进任何存储。它只用来现场派生出主密钥，
 * 主密钥再派生出 KEK（解开 DEK 的钥匙）和 authSecret（发给服务器认证的）。
 * 服务器拿到的只有 authSecret 的哈希，反推不出口令。
 *
 * 所以「忘记口令」是真的打不开 —— 那就是恢复码存在的意义。
 */
class SyncSetupActivity : BaseActivity() {

    private lateinit var binding: ActivitySyncSetupBinding
    private lateinit var vault: VaultManager
    private val io = Bg.single("sync")

    private val fields = mutableMapOf<Field, EditText>()
    /** 三种模式：注册新账号 / 用口令登录 / 用恢复码恢复。 */
    private enum class Mode { REGISTER, LOGIN, RECOVER }

    private var mode = Mode.LOGIN

    private enum class Field(@StringRes val label: Int, @DrawableRes val icon: Int, val inputType: Int) {
        SERVER(R.string.sync_field_server, R.drawable.ic_cloud_sync,
            android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT),
        USERNAME(R.string.sync_field_username, R.drawable.ic_person, android.text.InputType.TYPE_CLASS_TEXT),
        PASSPHRASE(R.string.sync_field_passphrase, R.drawable.ic_lock,
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or android.text.InputType.TYPE_CLASS_TEXT),
        INVITE(R.string.sync_field_invite, R.drawable.ic_add, android.text.InputType.TYPE_CLASS_TEXT),

        /** 恢复码。不是密码类型 —— 用户要能看着抄，遮起来只会抄错。 */
        RECOVERY(R.string.sync_field_recovery, R.drawable.ic_lock, android.text.InputType.TYPE_CLASS_TEXT),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        vault = VaultManager.get(this)
        binding.back.setOnClickListener { finish() }
        binding.submit.setOnClickListener { submit() }
        binding.syncNow.setOnClickListener { runSync() }
        binding.signOut.setOnClickListener { signOut() }
        binding.recoveryCopy.setOnClickListener { copyRecovery() }
        binding.testConnection.setOnClickListener { testConnection() }

        buildFields()
        buildModeChips()
        render()
    }

    // ------------------------------------------------------------------ 表单

    private fun buildFields() {
        val inflater = LayoutInflater.from(this)
        Field.entries.forEach { field ->
            val view = inflater.inflate(R.layout.item_edit_field, binding.fieldsHolder, false)
            view.findViewById<TextView>(R.id.field_label).setText(field.label)
            view.findViewById<ImageView>(R.id.field_icon).apply {
                setImageResource(field.icon)
                setColorFilter(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            }
            val input = view.findViewById<EditText>(R.id.field_input)
            input.inputType = field.inputType
            if (field == Field.SERVER) input.setText("https://")
            fields[field] = input
            binding.fieldsHolder.addView(view)
            // 邀请码只在注册时用，登录时会隐藏，所以要记住这个 View
            view.tag = field
        }
    }

    /** 三种模式的 chip。 */
    private fun buildModeChips() {
        val inflater = LayoutInflater.from(this)
        val gap = resources.getDimensionPixelSize(R.dimen.chip_gap)
        MODES.forEachIndexed { i, (m, label) ->
            val chip = inflater.inflate(R.layout.item_chip, binding.modeChips, false) as TextView
            chip.setText(label)
            if (i > 0) (chip.layoutParams as LinearLayout.LayoutParams).marginStart = gap
            chip.setOnClickListener {
                mode = m
                render()
            }
            binding.modeChips.addView(chip)
        }
    }

    private fun render() {
        val configured = vault.isConfigured
        binding.setupForm.visibility = if (configured) View.GONE else View.VISIBLE
        binding.statusHolder.visibility = if (configured) View.VISIBLE else View.GONE

        if (configured) {
            loadStatus()
            return
        }

        // 每个模式要填的字段不同：
        //   注册 → 服务器 + 用户名 + 口令 + 邀请码
        //   登录 → 服务器 + 用户名 + 口令
        //   恢复 → 服务器 + 用户名 + 恢复码（不需要口令 —— 忘了才走这条路）
        binding.fieldsHolder.children().forEach { v ->
            v.visibility = when (v.tag) {
                Field.INVITE -> if (mode == Mode.REGISTER) View.VISIBLE else View.GONE
                Field.RECOVERY -> if (mode == Mode.RECOVER) View.VISIBLE else View.GONE
                Field.PASSPHRASE -> if (mode == Mode.RECOVER) View.GONE else View.VISIBLE
                else -> View.VISIBLE
            }
        }
        binding.submitLabel.setText(MODES.first { it.first == mode }.second)
        binding.modeHint.setText(
            when (mode) {
                Mode.REGISTER -> R.string.sync_hint_register
                Mode.LOGIN -> R.string.sync_hint_login
                Mode.RECOVER -> R.string.sync_hint_recover
            }
        )

        binding.modeChips.children().forEachIndexed { i, chip ->
            val on = MODES[i].first == mode
            chip.isSelected = on
            (chip as TextView).setTextColor(
                themeColor(
                    if (on) MaterialR.attr.colorOnSecondaryContainer
                    else MaterialR.attr.colorOnSurfaceVariant
                )
            )
        }
    }

    private fun value(field: Field) = fields[field]?.text?.toString()?.trim().orEmpty()

    private fun submit() {
        val server = value(Field.SERVER)
        val username = value(Field.USERNAME)
        val passphrase = value(Field.PASSPHRASE)
        val invite = value(Field.INVITE)
        val recovery = value(Field.RECOVERY)

        val missing = server.isEmpty() || username.isEmpty() || when (mode) {
            Mode.REGISTER -> passphrase.isEmpty() || invite.isEmpty()
            Mode.LOGIN -> passphrase.isEmpty()
            Mode.RECOVER -> recovery.isEmpty()
        }
        if (missing) {
            toast(getString(R.string.sync_need_all_fields))
            return
        }

        binding.submitLabel.setText(R.string.sync_working)
        val deviceName = android.os.Build.MODEL.orEmpty().ifBlank { "Android" }

        io.execute {
            runCatching {
                when (mode) {
                    Mode.REGISTER -> vault.register(
                        baseUrl = server,
                        username = username,
                        passphrase = passphrase,
                        registrationToken = invite,
                        deviceName = deviceName,
                        // 缓存到 Keystore：下次打开 App 不用再输口令。
                        // 不要求屏幕锁 —— 那是给更敏感场景的开关，
                        // 打开后锁屏状态下来电显示会查不到名字。
                        cacheOnDevice = true,
                        requireScreenLock = false,
                    )

                    Mode.LOGIN -> {
                        vault.login(
                            baseUrl = server,
                            username = username,
                            passphrase = passphrase,
                            deviceName = deviceName,
                            cacheOnDevice = true,
                            requireScreenLock = false,
                        )
                        null
                    }

                    Mode.RECOVER -> {
                        val mustReset = vault.loginWithRecoveryCode(
                            baseUrl = server,
                            username = username,
                            recoveryCode = recovery,
                            deviceName = deviceName,
                            cacheOnDevice = true,
                            requireScreenLock = false,
                        )
                        runOnUiThread {
                            if (mustReset) toast(getString(R.string.sync_recovered_set_new_pass))
                        }
                        null
                    }
                }
            }.onSuccess { result ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // 注册返回恢复码，必须让用户当场抄下来 —— 服务器上没有第二份
                    result?.recoveryCode?.let { showRecovery(it) }
                    render()
                    // 配好账号之后才排周期任务 —— 没登录时排是白排
                    SyncScheduler.schedulePeriodic(this)
                    runSync()
                }
            }.onFailure { e ->
                runOnUiThread {
                    binding.submitLabel.setText(MODES.first { it.first == mode }.second)
                    toast(describe(e))
                }
            }
        }
    }

    // ------------------------------------------------------------------ 状态

    private fun loadStatus() {
        binding.account.text = "${vault.session.username} @ ${vault.session.baseUrl}"
        io.execute {
            // 同步库打不开时这里会抛。以前异常直接冲出后台线程，进程被杀 ——
            // 用户看到的是「点开同步页就闪退」，而真正的原因（数据库文件的
            // 加密状态不对）藏在十几层框架栈后面。
            val loaded = runCatching {
                val dao = SyncDatabase.get(this).syncDao()
                Triple(dao.getState(), dao.countPending(), ContactRepository(this).privateCount())
            }
            val failure = loaded.exceptionOrNull()
            if (failure != null) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.lastSync.text = getString(R.string.sync_status_unavailable)
                    binding.pending.text = failure.message ?: failure::class.java.simpleName
                }
                return@execute
            }
            val (state, pending, privateCount) = loaded.getOrThrow()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.lastSync.text = when {
                    state == null || state.lastSyncAt == 0L -> getString(R.string.sync_never_run)
                    else -> getString(
                        R.string.sync_last_run,
                        DateUtils.getRelativeTimeSpanString(state.lastSyncAt).toString()
                    )
                }
                binding.pending.text = getString(R.string.sync_pending, pending) +
                    "  ·  " + getString(R.string.sync_private_count, privateCount)
                val err = state?.lastError.orEmpty()
                // 明确说这是「上一次」的结果。同一段红字，读成「现在坏着」
                // 还是「上次跑失败了」，用户要做的事完全不同。
                binding.warning.text = if (err.isEmpty()) "" else getString(R.string.sync_last_error, err)
                binding.warning.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    /**
     * 同步。
     *
     * ── 为什么要先解锁 ──────────────────────────────────────
     *
     * DEK（真正解密数据的密钥）只存在于内存里。App 进程被系统回收后
     * 它就没了，重新打开时必须从 Keystore 把它取回来。
     *
     * 之前这里没做这一步，所以每次重启 App 后手动点「立即同步」都会报
     * 「保险库已锁定」—— 而后台的 SyncWorker 是会解锁的，于是出现
     * 「自动同步好像有用、手点就报错」这种非常费解的现象。
     *
     * 取不回来只有两种情况：从没登录过（isConfigured 已经挡住了），
     * 或者用户选了「主口令派生」模式（Keystore 里本来就什么都不存）。
     * 后者需要当场输口令。
     */
    private fun runSync() {
        if (!vault.isConfigured) return
        toast(getString(R.string.sync_running))
        io.execute {
            if (!vault.isUnlocked && !runCatching { vault.unlockFromCache() }.getOrDefault(false)) {
                runOnUiThread { askPassphraseThenSync() }
                return@execute
            }
            val report = runCatching { SyncEngine(this).sync() }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    report == null -> toast(getString(R.string.sync_failed, ""))
                    !report.ok -> toast(getString(R.string.sync_failed, report.error))
                    // 清单校验没过意味着服务器给的数据不完整或被回退过。
                    // 这不是「同步慢了点」，必须和普通成功区分开报出来。
                    !report.trustworthy ->
                        toast(getString(R.string.sync_integrity_warning, report.integrityIssues.size))
                    else -> {
                        toast(getString(R.string.sync_done, report.pulled, report.pushed))
                        // 手动同步走的是 SyncEngine 而不是 Worker，
                        // 所以这条路要自己发通知，否则主页不会刷新
                        SyncEvents.notifyFinished(report.pulled, report.pushed)
                    }
                }
                loadStatus()
            }
        }
    }

    /**
     * Keystore 里取不到 DEK 时，让用户当场输主口令。
     *
     * 走 unlockWithPassphrase：它去服务器取回被包裹的 DEK，
     * 用口令派生的 KEK 解开。口令本身不落盘。
     */
    private fun askPassphraseThenSync() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.sync_field_passphrase)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_locked_title)
            .setMessage(R.string.sync_locked_desc)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pass = input.text?.toString().orEmpty()
                if (pass.isBlank()) return@setPositiveButton
                io.execute {
                    val result = runCatching {
                        vault.unlockWithPassphrase(pass, cacheOnDevice = true, requireScreenLock = false)
                    }
                    runOnUiThread {
                        if (result.isSuccess) runSync()
                        else toast(describe(result.exceptionOrNull() ?: Exception()))
                    }
                }
            }
            .show()
    }

    private fun signOut() {
        io.execute {
            runCatching { vault.signOut() }
            // 退出后取消周期任务，否则它会一直跑、一直失败、一直耗电
            runCatching { SyncScheduler.cancelAll(this) }
            runOnUiThread {
                toast(getString(R.string.sync_signed_out))
                binding.recoveryHolder.visibility = View.GONE
                render()
            }
        }
    }

    // -------------------------------------------------------------- 恢复码

    private fun showRecovery(code: String) {
        binding.recoveryHolder.visibility = View.VISIBLE
        binding.recoveryCode.text = code
    }

    private fun copyRecovery() {
        val code = binding.recoveryCode.text?.toString().orEmpty()
        if (code.isEmpty()) return
        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("recovery", code))
        toast(getString(R.string.copied))
    }

    // ---------------------------------------------------------------- 工具

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /**
     * 把异常翻译成用户能照着做的话。
     *
     * 直接把 e.message 丢出来的话，用户看到的是
     * 「javax.net.ssl.SSLHandshakeException」这种东西 —— 知道出错了，
     * 但不知道该改什么。
     */
    private fun describe(e: Throwable): String = when (e) {
        is SyncApi.HttpFailure -> when (e.code) {
            "bad_registration_token" -> getString(R.string.err_bad_invite)
            "username_taken" -> getString(R.string.err_username_taken)
            "invalid_credentials" -> getString(R.string.err_bad_credentials)
            "too_many_attempts" -> getString(R.string.err_rate_limited)
            else -> getString(R.string.err_server, e.status, e.message)
        }
        is SyncApi.AuthExpired -> getString(R.string.err_auth_expired)
        is java.net.UnknownHostException -> getString(R.string.err_unknown_host)
        is java.net.SocketTimeoutException -> getString(R.string.err_timeout)
        is java.net.ConnectException -> getString(R.string.err_connect)
        is javax.net.ssl.SSLException -> getString(R.string.err_ssl)
        is IllegalArgumentException -> e.message ?: getString(R.string.err_generic, e.javaClass.simpleName)
        else -> getString(R.string.err_generic, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
    }

    /**
     * 连通性自检。
     *
     * 只打 /v1/health，不需要账号 —— 用来把「服务器不通」和
     * 「账号或口令不对」这两类问题分开。用户报「出错了」的时候，
     * 第一步就该按这个。
     */
    private fun testConnection() {
        val server = value(Field.SERVER)
        SessionStore(this).validateUrl(server)?.let { toast(it); return }

        binding.testConnection.setText(R.string.sync_testing)
        io.execute {
            val result = runCatching {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder()
                    .url(server.trimEnd('/') + "/v1/health")
                    .get().build()
                client.newCall(req).execute().use { r ->
                    "${r.code} ${r.body?.string()?.take(120).orEmpty()}"
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.testConnection.setText(R.string.sync_test_connection)
                result.onSuccess { toast(getString(R.string.sync_test_ok, it)) }
                    .onFailure { toast(getString(R.string.sync_test_failed, describe(it))) }
            }
        }
    }

    private fun LinearLayout.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    private companion object {
        val MODES = listOf(
            Mode.REGISTER to R.string.sync_mode_register,
            Mode.LOGIN to R.string.sync_mode_login,
            Mode.RECOVER to R.string.sync_mode_recover,
        )
    }
}
