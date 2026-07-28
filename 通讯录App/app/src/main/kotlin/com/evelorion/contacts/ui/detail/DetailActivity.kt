package com.evelorion.contacts.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository
import com.evelorion.contacts.databinding.ActivityDetailBinding
import com.evelorion.contacts.ui.BaseActivity
import com.evelorion.contacts.ui.edit.EditActivity
import com.evelorion.contacts.ui.theme.themeColor
import com.evelorion.contacts.ui.widget.AvatarDrawable
import com.google.android.material.R as MaterialR
import org.fossify.commons.models.contacts.Contact
import com.evelorion.contacts.ui.Bg
import java.util.concurrent.Executors

/**
 * 联系人详情。
 *
 * 信息卡片是代码生成的而不是写死在 XML 里：字段数量随联系人变化，
 * 写死的话每种组合都要写一遍，而且空 section 会留下空白卡片。
 */
class DetailActivity : BaseActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var repo: ContactRepository
    private val io = Bg.single("detail")
    private var contact: Contact? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        repo = ContactRepository(this)
        binding.back.setOnClickListener { finish() }
        binding.editButton.setOnClickListener {
            startActivity(
                Intent(this, EditActivity::class.java)
                    .putExtra(EditActivity.EXTRA_ID, intent.getIntExtra(EXTRA_ID, 0))
            )
        }
        binding.star.setOnClickListener { toggleFavorite() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        io.execute {
            val c = repo.contactById(id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (c == null) { finish(); return@runOnUiThread }
                contact = c
                render(c)
            }
        }
    }

    private fun render(c: Contact) {
        val display = c.getNameToDisplay()
        binding.name.text = display
        binding.avatar.setImageDrawable(
            AvatarDrawable(this, AvatarDrawable.initialOf(display), c.id, AvatarDrawable.Shape.SQUIRCLE)
        )

        val role = listOf(c.organization.jobPosition, c.organization.company)
            .filter { it.isNotBlank() }.joinToString(" · ")
        binding.role.text = role
        binding.role.visibility = if (role.isBlank()) View.GONE else View.VISIBLE

        binding.star.setImageResource(if (c.starred == 1) R.drawable.ic_star_filled else R.drawable.ic_star)
        binding.star.setColorFilter(
            themeColor(if (c.starred == 1) androidx.appcompat.R.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant)
        )

        buildActions(c)
        buildCards(c)
    }

    // ------------------------------------------------------------ 快捷动作

    /**
     * 设计稿是四格（拨号/短信/视频/邮件）。这里按**当前联系人真正能做的**
     * 来出：没有号码就不出拨号，没有邮箱就不出邮件。
     * 摆一个点了没反应的按钮比少一格更糟。
     */
    private fun buildActions(c: Contact) {
        binding.actions.removeAllViews()
        val number = c.phoneNumbers.firstOrNull()?.value
        val email = c.emails.firstOrNull()?.value

        if (!number.isNullOrBlank()) {
            addAction(R.drawable.ic_call, R.string.action_call) { open("tel:$number", Intent.ACTION_DIAL) }
            addAction(R.drawable.ic_chat_bubble, R.string.action_sms) { open("smsto:$number", Intent.ACTION_SENDTO) }
        }
        if (!email.isNullOrBlank()) {
            addAction(R.drawable.ic_mail, R.string.action_mail) { open("mailto:$email", Intent.ACTION_SENDTO) }
        }
    }

    private fun addAction(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_detail_action, binding.actions, false)
        view.findViewById<ImageView>(R.id.action_icon).apply {
            setImageResource(icon)
            setColorFilter(themeColor(MaterialR.attr.colorOnSecondaryContainer))
        }
        view.findViewById<TextView>(R.id.action_label).setText(label)
        view.findViewById<View>(R.id.action_tile).setOnClickListener { onClick() }
        if (binding.actions.childCount > 0) {
            (view.layoutParams as LinearLayout.LayoutParams).marginStart =
                resources.getDimensionPixelSize(R.dimen.detail_action_gap)
        }
        binding.actions.addView(view)
    }

    // ------------------------------------------------------------- 信息卡片

    private fun buildCards(c: Contact) {
        binding.cardsHolder.removeAllViews()

        val phones = c.phoneNumbers.map { Row(R.drawable.ic_call, it.value, labelOf(it.label, R.string.label_mobile), copy = true) }
        val emails = c.emails.map { Row(R.drawable.ic_mail, it.value, labelOf(it.label, R.string.label_email), copy = true) }
        val org = listOfNotNull(
            c.organization.company.takeIf { it.isNotBlank() }
                ?.let { Row(R.drawable.ic_apartment, it, getString(R.string.label_company)) }
        )
        val events = c.events.map { Row(R.drawable.ic_cake, it.value, getString(R.string.label_birthday)) }
        val note = listOfNotNull(
            c.notes.takeIf { it.isNotBlank() }?.let { Row(R.drawable.ic_edit, it, getString(R.string.label_note)) }
        )

        listOf(phones, emails, org, events, note).filter { it.isNotEmpty() }.forEach { addCard(it) }
    }

    private data class Row(
        @DrawableRes val icon: Int,
        val value: String,
        val label: String,
        val copy: Boolean = false,
    )

    private fun labelOf(raw: String, @StringRes fallback: Int) =
        raw.ifBlank { getString(fallback) }

    private fun addCard(rows: List<Row>) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.fav_grid_gap) }
        }
        val inflater = LayoutInflater.from(this)
        rows.forEach { row ->
            val v = inflater.inflate(R.layout.item_info_row, card, false)
            v.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(row.icon)
                setColorFilter(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            }
            v.findViewById<TextView>(R.id.value).text = row.value
            v.findViewById<TextView>(R.id.label).text = row.label
            v.findViewById<ImageView>(R.id.copy).apply {
                visibility = if (row.copy) View.VISIBLE else View.GONE
                setOnClickListener { copyToClipboard(row.value) }
            }
            card.addView(v)
        }
        binding.cardsHolder.addView(card)
    }

    // ------------------------------------------------------------------ 动作

    private fun toggleFavorite() {
        val c = contact ?: return
        val next = c.starred != 1
        io.execute {
            repo.toggleFavorite(c, next)
            runOnUiThread { load() }
        }
    }

    private fun copyToClipboard(text: String) {
        getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText(text, text))
        android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun open(uri: String, action: String) {
        runCatching { startActivity(Intent(action, Uri.parse(uri))) }
    }

    companion object {
        const val EXTRA_ID = "contact_id"
    }
}
