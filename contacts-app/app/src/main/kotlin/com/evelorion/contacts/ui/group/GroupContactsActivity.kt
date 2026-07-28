package com.evelorion.contacts.ui.group

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository
import com.evelorion.contacts.databinding.ActivityGroupContactsBinding
import com.evelorion.contacts.ui.BaseActivity
import com.evelorion.contacts.ui.detail.DetailActivity
import com.evelorion.contacts.ui.home.ContactsAdapter
import com.evelorion.contacts.ui.Bg
import java.util.concurrent.Executors

/**
 * 群组内的联系人。
 *
 * 复用主页的 [ContactsAdapter] —— 同样是「字母分组头 + 联系人行」，
 * 没必要为了少几行再写一个适配器。
 */
class GroupContactsActivity : BaseActivity() {

    private lateinit var binding: ActivityGroupContactsBinding
    private val io = Bg.single("group")
    private val adapter by lazy {
        ContactsAdapter { c ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, c.id))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        binding.back.setOnClickListener { finish() }
        binding.title.text = intent.getStringExtra(EXTRA_NAME).orEmpty()
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val groupId = intent.getLongExtra(EXTRA_ID, 0L)
        io.execute {
            ContactRepository(this).loadAll { all ->
                // commons 的 Contact 带着 groups 列表，直接按 id 过滤
                val members = all.filter { c -> c.raw.groups.any { it.id == groupId } }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    adapter.submitList(ContactsAdapter.buildRows(members))
                    binding.list.visibility = if (members.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    companion object {
        const val EXTRA_ID = "group_id"
        const val EXTRA_NAME = "group_name"
    }
}
