package com.evelorion.contacts.ui.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository
import com.evelorion.contacts.databinding.ActivityImportSystemBinding
import com.evelorion.contacts.sync.work.SyncScheduler
import com.evelorion.contacts.ui.BaseActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fossify.commons.models.contacts.Contact
import com.evelorion.contacts.ui.Bg
import java.util.concurrent.Executors

/**
 * 从系统通讯录导入。
 *
 * ── 为什么分成两步 ──────────────────────────────────────────
 *
 * 1. 导入 —— 复制进加密库，**不动系统里的原件**
 * 2. 确认结果之后，可选地删掉原件
 *
 * 合成一步的话，用户点一下按钮就同时发生了「复制」和「删除」，
 * 而删除不可逆、一次可能上千条。分开之后他能先看到
 * 「导入了 20 位，跳过重复 3 位」，再决定删不删。
 *
 * ── 只删导入成功的那些 ──────────────────────────────────────
 *
 * 第二步删的是第一步**明确报告导入成功**的那批联系人对象，
 * 不是「清空系统通讯录」。因号码重复被跳过的那些不会被碰 ——
 * 它们没进加密库，删了就真没了。
 */
class ImportSystemActivity : BaseActivity() {

    private lateinit var binding: ActivityImportSystemBinding
    private lateinit var repo: ContactRepository
    private val io = Bg.single("import")

    /** 上一步导入成功的联系人。第二步删的就是这些。 */
    private var importedOriginals: List<Contact> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshAvailable() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportSystemBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        repo = ContactRepository(this)
        binding.back.setOnClickListener { finish() }
        binding.importButton.setOnClickListener { confirmImport() }
        binding.cleanupButton.setOnClickListener { confirmCleanup() }

        ensurePermissions()
        refreshAvailable()
    }

    // ------------------------------------------------------------------ 权限

    /**
     * 删除系统联系人需要写权限。读权限主页已经要过了，
     * 但写权限之前没要 —— 不在这里补的话，第二步会静默失败。
     */
    private fun ensurePermissions() {
        val needed = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasWritePermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.WRITE_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ 第一步：导入

    private fun refreshAvailable() {
        repo.loadSystemContacts { list ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.available.text = getString(R.string.import_available, list.size)
                binding.importButton.isEnabled = list.isNotEmpty()
                binding.importButton.alpha = if (list.isNotEmpty()) 1f else 0.4f
            }
        }
    }

    private fun confirmImport() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_title)
            .setMessage(R.string.import_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> doImport() }
            .show()
    }

    private fun doImport() {
        binding.importButton.setText(R.string.import_working)
        binding.importButton.isEnabled = false

        repo.importFromSystem { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                importedOriginals = result.imported
                binding.importButton.setText(R.string.import_action)
                binding.importButton.isEnabled = true

                binding.importResult.visibility = View.VISIBLE
                binding.importResult.text =
                    getString(R.string.import_result, result.imported.size, result.skipped)

                // 有东西导入成功才显示第二步。一条都没导入的话，
                // 摆一个「删除原件」按钮只会让人手滑
                if (result.imported.isNotEmpty()) {
                    binding.cleanupCard.visibility = View.VISIBLE
                    binding.cleanupDesc.text =
                        getString(R.string.import_step2_count, result.imported.size)
                    // 导入完立刻同步，让新联系人尽快上云
                    runCatching { SyncScheduler.syncNow(this, "import") }
                } else {
                    binding.cleanupCard.visibility = View.GONE
                }
                refreshAvailable()
            }
        }
    }

    // -------------------------------------------------------- 第二步：删原件

    private fun confirmCleanup() {
        if (!hasWritePermission()) {
            toast(getString(R.string.import_need_write_permission))
            ensurePermissions()
            return
        }

        // 删除不可逆，问两次。第二次把条数再报一遍 ——
        // 第一次点的时候用户可能没细看
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_cleanup_action)
            .setMessage(getString(R.string.import_cleanup_confirm, importedOriginals.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_cleanup_next) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.import_cleanup_final_title)
                    .setMessage(getString(R.string.import_cleanup_final, importedOriginals.size))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.import_cleanup_do) { _, _ -> doCleanup() }
                    .show()
            }
            .show()
    }

    private fun doCleanup() {
        binding.cleanupButton.setText(R.string.import_cleaning)
        binding.cleanupButton.isEnabled = false

        val targets = importedOriginals
        io.execute {
            repo.deleteFromSystem(targets) { deleted ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.cleanupButton.setText(R.string.import_cleanup_action)
                    binding.cleanupButton.isEnabled = true
                    toast(getString(R.string.import_cleaned, deleted))
                    // 删完这批就不能再删一次了，清空避免重复操作
                    importedOriginals = emptyList()
                    binding.cleanupCard.visibility = View.GONE
                    refreshAvailable()
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
