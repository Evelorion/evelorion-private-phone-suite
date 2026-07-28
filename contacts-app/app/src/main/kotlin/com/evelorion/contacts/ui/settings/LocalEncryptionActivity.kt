package com.evelorion.contacts.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.evelorion.contacts.R
import com.evelorion.contacts.databinding.ActivityLocalEncryptionBinding
import com.evelorion.contacts.sync.localdb.DatabaseKey
import com.evelorion.contacts.sync.localdb.EncryptedDatabases
import com.evelorion.contacts.sync.localdb.EncryptionMode
import com.evelorion.contacts.ui.BaseActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.evelorion.contacts.ui.Bg
import java.util.concurrent.Executors

/**
 * 本地数据库加密。
 *
 * ── 加密的是什么 ────────────────────────────────────────────
 *
 * 私密联系人的 Room 库和同步库，用 SQLCipher 整库加密。
 * 加密的是**落在磁盘上的文件** —— 拿到手机文件（丢手机、adb backup、
 * 从备份里翻）的人打不开它。
 *
 * ── 三种模式的取舍 ──────────────────────────────────────────
 *
 * 区别只在**密钥放哪**：
 *
 *   Keystore              密钥在硬件密钥库里，开机即可用。挡住拿文件的人，挡不住 root。
 *   Keystore + 屏幕锁     同上，但用之前要验证一次。锁屏状态下 root 也开不了。
 *   主口令派生            Keystore 里什么都不存，唯一能挡住 root 的。代价是每次启动都要输口令。
 *
 * 每张卡底下都写清楚了挡得住什么、挡不住什么 —— 安全功能最怕用户
 * 以为自己开了个万能开关。
 */
class LocalEncryptionActivity : BaseActivity() {

    private lateinit var binding: ActivityLocalEncryptionBinding
    private val io = Bg.single("localenc")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalEncryptionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        binding.back.setOnClickListener { finish() }
        render()
    }

    private fun render() {
        val enabled = DatabaseKey.isEnabled(this)
        binding.stateText.setText(
            if (enabled) R.string.localdb_encrypted else R.string.localdb_not_encrypted
        )

        val current = EncryptionMode.current(this)
        binding.modes.removeAllViews()
        val inflater = LayoutInflater.from(this)

        EncryptionMode.entries.forEach { mode ->
            val v = inflater.inflate(R.layout.item_mode_card, binding.modes, false)
            v.findViewById<TextView>(R.id.mode_title).setText(titleOf(mode))
            v.findViewById<TextView>(R.id.mode_desc).text = EncryptionMode.describe(mode)

            val selected = enabled && mode == current
            v.isSelected = selected
            v.findViewById<ImageView>(R.id.mode_check).visibility =
                if (selected) View.VISIBLE else View.INVISIBLE

            v.setOnClickListener { onModeClicked(mode, current, enabled) }
            binding.modes.addView(v)
        }
    }

    private fun titleOf(mode: EncryptionMode) = when (mode) {
        EncryptionMode.KEYSTORE -> R.string.localdb_mode_keystore
        EncryptionMode.KEYSTORE_SCREEN_LOCK -> R.string.localdb_mode_screen_lock
        EncryptionMode.PASSPHRASE -> R.string.localdb_mode_passphrase
    }

    private fun onModeClicked(target: EncryptionMode, current: EncryptionMode, enabled: Boolean) {
        if (enabled && target == current) return

        // 切到主口令模式、或者从主口令模式切走，都需要主口令：
        // 前者要用它派生新密钥，后者要用它解开旧的
        val needsPassphrase =
            target == EncryptionMode.PASSPHRASE || (enabled && current == EncryptionMode.PASSPHRASE)

        if (needsPassphrase) {
            askPassphrase { switch(target, it) }
        } else {
            confirm(target) { switch(target, null) }
        }
    }

    private fun confirm(target: EncryptionMode, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleOf(target))
            .setMessage(R.string.localdb_switch_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .show()
    }

    private fun askPassphrase(onEntered: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.sync_field_passphrase)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.localdb_need_passphrase)
            .setMessage(R.string.localdb_need_passphrase_desc)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isBlank()) {
                    toast(getString(R.string.localdb_passphrase_empty))
                } else {
                    onEntered(text)
                }
            }
            .show()
    }

    /**
     * 执行切换。
     *
     * 这一步会阻塞几百毫秒到几秒（Argon2 派生 + 两个库 rekey），必须在
     * 后台线程。中途失败会抛异常，数据库保持切换前的状态 —— rekey
     * 内部有备份还原。
     */
    private fun switch(target: EncryptionMode, passphrase: String?) {
        toast(getString(R.string.localdb_switching))
        io.execute {
            val result = runCatching {
                EncryptionMode.switchTo(this, target, passphrase)
                // 切完要把加密层重新装回去，否则下一次访问数据库
                // 会用旧密钥去开新文件，直接崩
                EncryptedDatabases.reinstall(this)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    toast(getString(R.string.localdb_switched))
                    render()
                }.onFailure {
                    toast(getString(R.string.localdb_switch_failed, it.message.orEmpty()))
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
