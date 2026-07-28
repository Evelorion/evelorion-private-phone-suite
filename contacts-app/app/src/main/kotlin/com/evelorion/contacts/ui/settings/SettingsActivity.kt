package com.evelorion.contacts.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.evelorion.contacts.R
import com.evelorion.contacts.databinding.ActivitySettingsBinding
import com.evelorion.contacts.ui.BaseActivity
import com.evelorion.contacts.ui.theme.M3Theme
import com.evelorion.contacts.ui.tools.ImportSystemActivity
import com.evelorion.contacts.ui.tools.MergeActivity
import com.evelorion.contacts.ui.tools.VCardActivity
import com.evelorion.contacts.ui.theme.themeColor
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as MaterialR

/**
 * 设置页。
 *
 * 分区和条目是代码生成的，不是写死在 XML 里 —— 卡片 + 分区标题 + 开关行
 * 是同一套模板，写在 XML 里要重复八九遍，加一项就要复制粘贴一遍。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        binding.back.setOnClickListener { finish() }
        buildScreen()
    }

    private fun buildScreen() {
        binding.content.removeAllViews()

        // ── 外观 ──
        section(R.string.settings_appearance_section)
        card {
            paletteRow()
            // this 在 card{} 里指向 LinearLayout，要 M3Theme 的话得写全 —— 
            // 不写的话编译器会把 LinearLayout 当成 Context 传进去
            switchRow(
                R.string.settings_dark, R.string.settings_dark_desc,
                checked = M3Theme.darkMode(this@SettingsActivity) == M3Theme.DarkMode.DARK,
            ) { on ->
                M3Theme.setDarkMode(
                    this@SettingsActivity,
                    if (on) M3Theme.DarkMode.DARK else M3Theme.DarkMode.LIGHT
                )
            }
        }

        // ── 显示与排序 ──
        section(R.string.settings_display_section)
        card {
            switchRow(R.string.settings_pinyin, R.string.settings_pinyin_desc, prefs.getBoolean(KEY_PINYIN, true)) {
                prefs.edit().putBoolean(KEY_PINYIN, it).apply()
            }
            switchRow(R.string.settings_compact, R.string.settings_compact_desc, prefs.getBoolean(KEY_COMPACT, false)) {
                prefs.edit().putBoolean(KEY_COMPACT, it).apply()
            }
        }

        // ── 隐私与同步 ──
        section(R.string.settings_privacy_section)
        card {
            linkRow(R.drawable.ic_cloud_sync, R.string.settings_sync, R.string.settings_sync_desc) {
                startActivity(Intent(this@SettingsActivity, SyncSetupActivity::class.java))
            }
            linkRow(R.drawable.ic_lock, R.string.settings_local_encryption, 0) {
                startActivity(Intent(this@SettingsActivity, LocalEncryptionActivity::class.java))
            }
        }

        // ── 数据与权限 ──
        section(R.string.settings_data_section)
        card {
            linkRow(R.drawable.ic_lock, R.string.settings_permission, R.string.settings_permission_desc) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            }
            linkRow(R.drawable.ic_person_add, R.string.import_title, R.string.settings_import_system_desc) {
                startActivity(Intent(this@SettingsActivity, ImportSystemActivity::class.java))
            }
            linkRow(R.drawable.ic_group, R.string.settings_merge, 0) {
                startActivity(Intent(this@SettingsActivity, MergeActivity::class.java))
            }
            linkRow(R.drawable.ic_content_copy, R.string.settings_import_export, 0) {
                startActivity(Intent(this@SettingsActivity, VCardActivity::class.java))
            }
        }
    }

    // ------------------------------------------------------------ 构建工具

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private fun section(@StringRes title: Int) {
        val tv = TextView(this).apply {
            setText(title)
            setTextAppearance(R.style.Text_SectionHeader)
            val h = resources.getDimensionPixelSize(R.dimen.chip_gap)
            setPadding(h - 2, resources.getDimensionPixelSize(R.dimen.field_gap), h - 2, 10)
        }
        binding.content.addView(tv)
    }

    /** 一张卡片。里面的行由 [block] 往里加。 */
    private fun card(block: LinearLayout.() -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.block()
        binding.content.addView(card)
    }

    private fun LinearLayout.switchRow(
        @StringRes label: Int,
        @StringRes desc: Int,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val v = LayoutInflater.from(context).inflate(R.layout.item_settings_switch, this, false)
        v.findViewById<TextView>(R.id.switch_label).setText(label)
        v.findViewById<TextView>(R.id.switch_desc).apply {
            if (desc == 0) visibility = View.GONE else setText(desc)
        }
        val toggle = v.findViewById<MaterialSwitch>(R.id.switch_toggle)
        toggle.isChecked = checked
        // 整行可点。开关本身 clickable=false（在布局里设的），
        // 否则点开关和点行会各触发一次
        v.setOnClickListener {
            toggle.isChecked = !toggle.isChecked
            onChange(toggle.isChecked)
        }
        addView(v)
    }

    private fun LinearLayout.linkRow(
        @DrawableRes icon: Int,
        @StringRes label: Int,
        @StringRes desc: Int,
        onClick: () -> Unit,
    ) {
        val v = LayoutInflater.from(context).inflate(R.layout.item_settings_link, this, false)
        v.findViewById<ImageView>(R.id.link_icon).apply {
            setImageResource(icon)
            setColorFilter(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        }
        v.findViewById<TextView>(R.id.link_label).setText(label)
        v.findViewById<TextView>(R.id.link_desc).apply {
            if (desc == 0) visibility = View.GONE else { visibility = View.VISIBLE; setText(desc) }
        }
        v.setOnClickListener { onClick() }
        addView(v)
    }

    /** 配色三选一，chip 横排。 */
    private fun LinearLayout.paletteRow() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 14.dp(), 18.dp(), 14.dp())
        }
        row.addView(TextView(context).apply {
            setText(R.string.settings_palette)
            setTextAppearance(R.style.Text_BodyLarge)
        })

        // 四个配色 chip 横着放会超出卡片宽度，最后一个被裁掉一半。
        // 套一层横向滚动 —— 以后再加配色也不会挤爆。
        val scroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            // clipToPadding=false 让 chip 能滚到 padding 区域里，
            // 不加的话最后一个 chip 右边会贴死在边缘
            clipToPadding = false
            setPadding(0, 10.dp(), 0, 0)
        }
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val current = M3Theme.palette(this@SettingsActivity)
        val options = buildList {
            add(M3Theme.Palette.DEFAULT to R.string.palette_default)
            add(M3Theme.Palette.TEAL to R.string.palette_teal)
            add(M3Theme.Palette.WARM to R.string.palette_warm)
            // 动态取色只有 Android 12+ 有。低版本上直接不显示这个选项，
            // 而不是显示一个点了没反应的按钮
            if (M3Theme.dynamicColorAvailable) {
                add(M3Theme.Palette.DYNAMIC to R.string.palette_dynamic)
            }
        }
        options.forEachIndexed { i, (palette, label) ->
            val chip = LayoutInflater.from(context)
                .inflate(R.layout.item_chip, chips, false) as TextView
            chip.setText(label)
            // 明确不省略：chip 的文案本来就短，宁可横向滚也不要显示成「动态…」
            chip.ellipsize = null
            chip.isSelected = palette == current
            chip.setTextColor(
                themeColor(
                    if (palette == current) MaterialR.attr.colorOnSecondaryContainer
                    else MaterialR.attr.colorOnSurfaceVariant
                )
            )
            if (i > 0) (chip.layoutParams as LinearLayout.LayoutParams).marginStart =
                resources.getDimensionPixelSize(R.dimen.chip_gap)
            chip.setOnClickListener { M3Theme.setPalette(this@SettingsActivity, palette) }
            chips.addView(chip)
        }
        scroll.addView(chips)
        row.addView(scroll)
        addView(row)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_PINYIN = "pinyin_index"
        const val KEY_COMPACT = "compact_density"
    }
}
