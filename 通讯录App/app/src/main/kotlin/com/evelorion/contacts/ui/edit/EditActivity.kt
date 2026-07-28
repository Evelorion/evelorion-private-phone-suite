package com.evelorion.contacts.ui.edit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository
import com.evelorion.contacts.databinding.ActivityEditBinding
import com.evelorion.contacts.sync.work.SyncScheduler
import com.evelorion.contacts.ui.BaseActivity
import com.evelorion.contacts.ui.theme.themeColor
import com.evelorion.contacts.ui.widget.AvatarDrawable
import com.google.android.material.R as MaterialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.models.contacts.Address
import org.fossify.commons.models.contacts.Email
import org.fossify.commons.models.contacts.Event
import org.fossify.commons.models.contacts.Organization
// PhoneNumber 在 models 包下，不在 models.contacts —— 这一条和 Email/Organization 不一样
import org.fossify.commons.models.PhoneNumber
import com.evelorion.contacts.ui.Bg
import java.util.concurrent.Executors

/**
 * 新建 / 编辑联系人。
 *
 * 这一版是**基础字段**：姓名、电话、邮箱、公司。
 * 地址、纪念日、即时通讯、多个号码这些在下一版补 —— 设计稿里
 * 「添加更多字段」那个入口就是给它们留的。
 */
class EditActivity : BaseActivity() {

    private lateinit var binding: ActivityEditBinding
    private lateinit var repo: ContactRepository
    private val io = Bg.single("edit")

    private var existing: Contact? = null
    private val inputs = mutableMapOf<Field, EditText>()

    private enum class Field(
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
        val inputType: Int,
        /** true = 默认不显示，由「添加更多字段」加出来 */
        val optional: Boolean = false,
    ) {
        NAME(R.string.field_name, R.drawable.ic_person, android.text.InputType.TYPE_CLASS_TEXT),
        PHONE(R.string.field_phone, R.drawable.ic_call, android.text.InputType.TYPE_CLASS_PHONE),
        EMAIL(R.string.field_email, R.drawable.ic_mail,
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or android.text.InputType.TYPE_CLASS_TEXT),
        COMPANY(R.string.field_company, R.drawable.ic_apartment, android.text.InputType.TYPE_CLASS_TEXT),

        // 下面这些默认不显示，由「添加更多字段」按需加出来。
        // 一上来就摆九个空框会让新建联系人显得很有负担 —— 多数人只填姓名和号码。
        JOB(R.string.field_job, R.drawable.ic_apartment, android.text.InputType.TYPE_CLASS_TEXT, true),
        ADDRESS(R.string.field_address, R.drawable.ic_person,
            android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_CLASS_TEXT, true),
        BIRTHDAY(R.string.field_birthday, R.drawable.ic_cake, android.text.InputType.TYPE_CLASS_TEXT, true),
        WEBSITE(R.string.field_website, R.drawable.ic_cloud_sync,
            android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT, true),
        NOTE(R.string.field_note, R.drawable.ic_edit,
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_CLASS_TEXT, true),
    }

    /** 已经加出来的可选字段。用来算「添加更多字段」还剩哪些能加。 */
    private val shownOptional = mutableSetOf<Field>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        repo = ContactRepository(this)
        binding.close.setOnClickListener { finish() }
        binding.save.setOnClickListener { save() }
        binding.addField.setOnClickListener { showAddFieldPicker() }

        buildFields()

        val id = intent.getIntExtra(EXTRA_ID, 0)
        binding.title.setText(if (id == 0) R.string.edit_new_title else R.string.edit_title)
        if (id != 0) loadExisting(id) else updateAvatar("")
    }

    private fun buildFields() {
        Field.entries.filter { !it.optional }.forEach { addField(it) }

        // 姓名变了就更新头像的首字母，让用户立刻看到效果
        inputs[Field.NAME]?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateAvatar(s?.toString().orEmpty())
        })
    }

    /** 把一个字段的输入框加到表单末尾。 */
    private fun addField(field: Field) {
        if (inputs.containsKey(field)) return
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_edit_field, binding.fieldsHolder, false)
        view.findViewById<TextView>(R.id.field_label).setText(field.label)
        // setImageResource 加载的矢量图不带主题，必须自己着色
        view.findViewById<ImageView>(R.id.field_icon).apply {
            setImageResource(field.icon)
            setColorFilter(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        }
        view.findViewById<EditText>(R.id.field_input).also {
            it.inputType = field.inputType
            inputs[field] = it
        }
        binding.fieldsHolder.addView(view)
        if (field.optional) shownOptional.add(field)
    }

    /**
     * 「添加更多字段」。
     *
     * 只列还没加出来的。全加完了就把按钮藏起来 ——
     * 点了弹一个空列表比按钮消失更让人困惑。
     */
    private fun showAddFieldPicker() {
        val available = Field.entries.filter { it.optional && it !in shownOptional }
        if (available.isEmpty()) {
            binding.addField.visibility = View.GONE
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_add_field)
            .setItems(available.map { getString(it.label) }.toTypedArray()) { _, which ->
                addField(available[which])
                if (available.size == 1) binding.addField.visibility = View.GONE
            }
            .show()
    }

    private fun updateAvatar(name: String) {
        binding.avatar.setImageDrawable(
            AvatarDrawable(
                this, AvatarDrawable.initialOf(name),
                existing?.id ?: name.ifBlank { "new" },
                AvatarDrawable.Shape.SQUIRCLE
            )
        )
    }

    private fun loadExisting(id: Int) {
        io.execute {
            val c = repo.contactById(id)
            runOnUiThread {
                if (isFinishing || isDestroyed || c == null) return@runOnUiThread
                existing = c
                inputs[Field.NAME]?.setText(c.getNameToDisplay())
                inputs[Field.PHONE]?.setText(c.phoneNumbers.firstOrNull()?.value.orEmpty())
                inputs[Field.EMAIL]?.setText(c.emails.firstOrNull()?.value.orEmpty())
                inputs[Field.COMPANY]?.setText(c.organization.company)

                // 已经有值的可选字段要自动加出来。不加的话用户看不到自己填过的内容，
                // 保存时那个字段还会被当成「没填」清掉。
                fun prefill(field: Field, value: String) {
                    if (value.isBlank()) return
                    addField(field)
                    inputs[field]?.setText(value)
                }
                prefill(Field.JOB, c.organization.jobPosition)
                prefill(Field.ADDRESS, c.addresses.firstOrNull()?.value.orEmpty())
                prefill(Field.BIRTHDAY, c.events.firstOrNull()?.value.orEmpty())
                prefill(Field.WEBSITE, c.websites.firstOrNull().orEmpty())
                prefill(Field.NOTE, c.notes)

                updateAvatar(c.getNameToDisplay())
            }
        }
    }

    private fun value(field: Field) = inputs[field]?.text?.toString()?.trim().orEmpty()

    private fun save() {
        val name = value(Field.NAME)
        if (name.isEmpty()) {
            inputs[Field.NAME]?.error = getString(R.string.field_name)
            return
        }

        io.execute {
            // commons 没有 getEmptyContact，直接构造一个空的。
            // 参数很多但大部分有默认值，只有 id / 各个 ArrayList / organization 必须给。
            val target = existing ?: Contact(
                id = 0,
                prefix = "", firstName = "", middleName = "", surname = "", suffix = "",
                nickname = "", photoUri = "",
                phoneNumbers = ArrayList(), emails = ArrayList(),
                addresses = ArrayList(), events = ArrayList(),
                source = "", starred = 0, contactId = 0, thumbnailUri = "",
                photo = null, notes = "", groups = ArrayList(),
                organization = Organization("", ""),
                websites = ArrayList(), IMs = ArrayList(),
                mimetype = "", ringtone = null,
            )

            // 姓名整串塞进 firstName。真正的姓/名拆分需要按语言判断
            // （中文是姓在前，英文是名在前），那是单独一件事。
            target.firstName = name
            target.surname = ""
            target.middleName = ""

            target.phoneNumbers = arrayListOf<PhoneNumber>().apply {
                val p = value(Field.PHONE)
                if (p.isNotEmpty()) add(PhoneNumber(p, 2, "", p, true))
            }
            target.emails = arrayListOf<Email>().apply {
                val e = value(Field.EMAIL)
                if (e.isNotEmpty()) add(Email(e, 1, ""))
            }
            target.organization = Organization(value(Field.COMPANY), value(Field.JOB))
            if (Field.NOTE in inputs) target.notes = value(Field.NOTE)

            // 可选字段只在用户加出来过的情况下才覆盖 ——
            // 无条件覆盖的话，没加出来的字段会被空串清掉
            if (Field.ADDRESS in inputs) {
                target.addresses = arrayListOf<Address>().apply {
                    value(Field.ADDRESS).takeIf { it.isNotEmpty() }?.let { add(Address(it, 1, "")) }
                }
            }
            if (Field.BIRTHDAY in inputs) {
                target.events = arrayListOf<Event>().apply {
                    value(Field.BIRTHDAY).takeIf { it.isNotEmpty() }?.let { add(Event(it, 3)) }
                }
            }
            if (Field.WEBSITE in inputs) {
                target.websites = arrayListOf<String>().apply {
                    value(Field.WEBSITE).takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }

            // 一律存进加密库 —— 这个 App 只有这一个数据源
            val ok = repo.save(target)
            // 改完立刻推上去。不做的话用户得等下一个周期任务（最长一小时），
            // 期间在别的设备上看不到刚加的联系人
            if (ok) runCatching { SyncScheduler.syncNow(this, "edit") }

            runOnUiThread {
                Toast.makeText(this, if (ok) R.string.saved else R.string.save_failed, Toast.LENGTH_SHORT).show()
                if (ok) finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "contact_id"
    }
}
