package com.evelorion.contacts.ui.tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.evelorion.contacts.R
import com.evelorion.contacts.data.DuplicateFinder
import com.evelorion.contacts.data.VCard
import com.evelorion.contacts.databinding.ActivityVcardBinding
import com.evelorion.contacts.ui.BaseActivity
import com.evelorion.contacts.ui.theme.themeColor
import com.google.android.material.R as MaterialR
import org.fossify.commons.helpers.ContactsHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import com.evelorion.contacts.ui.Bg
import java.util.concurrent.Executors

/**
 * vCard 导入 / 导出。
 *
 * 走系统的文件选择器（SAF）而不是自己申请存储权限 ——
 * Android 10 起分区存储，直接写路径要么拿不到权限，要么只能写
 * App 私有目录（用户在文件管理器里找不到）。SAF 让用户自己挑位置，
 * 一个权限都不用申请。
 */
class VCardActivity : BaseActivity() {

    private lateinit var binding: ActivityVcardBinding
    private val io = Bg.single("vcard")

    /** 导出：让用户选保存位置 */
    private val createFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/vcard")
    ) { uri -> uri?.let { exportTo(it) } }

    /** 导入：让用户选文件。类型给宽一点 —— 有些文件管理器把 .vcf 报成
     *  application/octet-stream，只写 text/vcard 会导致文件灰掉选不了。 */
    private val openFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFrom(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVcardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        binding.back.setOnClickListener { finish() }
        buildRows()
    }

    private fun buildRows() {
        binding.content.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_card)
        }
        card.addRow(R.drawable.ic_content_copy, R.string.vcard_export, R.string.vcard_export_desc) {
            val name = "contacts-${System.currentTimeMillis()}.vcf"
            createFile.launch(name)
        }
        card.addRow(R.drawable.ic_add, R.string.vcard_import, R.string.vcard_import_desc) {
            openFile.launch(arrayOf("text/vcard", "text/x-vcard", "text/plain", "application/octet-stream", "*/*"))
        }
        binding.content.addView(card)
    }

    private fun LinearLayout.addRow(icon: Int, label: Int, desc: Int, onClick: () -> Unit) {
        val v = LayoutInflater.from(context).inflate(R.layout.item_settings_link, this, false)
        v.findViewById<ImageView>(R.id.link_icon).apply {
            setImageResource(icon)
            setColorFilter(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        }
        v.findViewById<TextView>(R.id.link_label).setText(label)
        v.findViewById<TextView>(R.id.link_desc).apply { visibility = View.VISIBLE; setText(desc) }
        v.setOnClickListener { onClick() }
        addView(v)
    }

    // ------------------------------------------------------------------ 导出

    private fun exportTo(uri: Uri) {
        toast(getString(R.string.vcard_exporting))
        ContactsHelper(this).getContacts { all ->
            io.execute {
                val result = runCatching {
                    if (all.isEmpty()) throw IllegalStateException(getString(R.string.vcard_empty))
                    val text = VCard.export(all)
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException("无法写入所选位置")
                    all.size
                }
                runOnUiThread {
                    result.onSuccess { toast(getString(R.string.vcard_export_done, it)) }
                        .onFailure { toast(getString(R.string.vcard_failed, it.message.orEmpty())) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 导入

    private fun importFrom(uri: Uri) {
        toast(getString(R.string.vcard_importing))
        ContactsHelper(this).getContacts { existing ->
            io.execute {
                val result = runCatching {
                    val parsed = contentResolver.openInputStream(uri)?.use { stream ->
                        VCard.parse(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
                    } ?: throw IllegalStateException("读不了所选文件")

                    // 去重：号码归一化后已存在的就跳过，避免每导一次多一份
                    val known = existing.flatMap { c ->
                        c.phoneNumbers.map { DuplicateFinder.normalizeNumber(it.value) }
                    }.filter { it.length >= 6 }.toMutableSet()
                    val knownNames = existing.map { it.getNameToDisplay().trim() }.toMutableSet()

                    val helper = ContactsHelper(this)
                    var added = 0
                    var skipped = 0
                    parsed.forEach { p ->
                        val numbers = p.phones.map { DuplicateFinder.normalizeNumber(it.first) }
                            .filter { it.length >= 6 }
                        val dupByNumber = numbers.any { it in known }
                        val dupByName = numbers.isEmpty() && p.displayName().trim() in knownNames
                        if (dupByNumber || dupByName) {
                            skipped++
                            return@forEach
                        }
                        if (helper.insertContact(p.toContact())) {
                            added++
                            known.addAll(numbers)
                            knownNames.add(p.displayName().trim())
                        }
                    }
                    added to skipped
                }
                runOnUiThread {
                    result.onSuccess { (added, skipped) ->
                        toast(getString(R.string.vcard_import_done, added, skipped))
                    }.onFailure { toast(getString(R.string.vcard_failed, it.message.orEmpty())) }
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
