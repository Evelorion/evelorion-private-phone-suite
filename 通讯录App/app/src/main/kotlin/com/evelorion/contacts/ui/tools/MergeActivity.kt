package com.evelorion.contacts.ui.tools

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository
import com.evelorion.contacts.data.DuplicateFinder
import com.evelorion.contacts.databinding.ActivityMergeBinding
import com.evelorion.contacts.ui.BaseActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.evelorion.contacts.ui.Bg
import java.util.concurrent.Executors

/**
 * 合并重复联系人。
 *
 * 判重规则和保留策略见 [DuplicateFinder] 的注释 —— 那里说明了为什么
 * 不做姓名模糊匹配（合并不可逆，宁可漏判不可误判）。
 */
class MergeActivity : BaseActivity() {

    private lateinit var binding: ActivityMergeBinding
    private val io = Bg.single("merge")
    private val adapter = GroupAdapter()
    private var groups = listOf<DuplicateFinder.Group>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMergeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        binding.back.setOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.mergeAll.setOnClickListener { confirmMerge(null) }

        scan()
    }

    private val scanIo = Bg.single("merge2")

    private fun scan() {
        binding.summary.setText(R.string.merge_scanning)
        // 查重的对象必须和合并的对象是同一份数据 —— 都是加密库里的联系人。
        // 扫系统通讯录、却往加密库里合并，只会合出一堆本来不存在的东西。
        scanIo.execute {
            val all = ContactRepository(this).loadRawForMerge()
            val found = DuplicateFinder.find(all)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                groups = found
                adapter.submit(found)
                val empty = found.isEmpty()
                binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
                binding.list.visibility = if (empty) View.GONE else View.VISIBLE
                binding.mergeAll.visibility = if (empty) View.GONE else View.VISIBLE
                binding.summary.text =
                    if (empty) getString(R.string.merge_none) else getString(R.string.merge_found, found.size)
            }
        }
    }

    /** @param group 传 null 表示全部合并 */
    private fun confirmMerge(group: DuplicateFinder.Group?) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.merge_title)
            .setMessage(R.string.merge_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                doMerge(if (group == null) groups else listOf(group))
            }
            .show()
    }

    private fun doMerge(targets: List<DuplicateFinder.Group>) {
        io.execute {
            // 合并的对象是**加密库里**的联系人，所以读写都必须走 repo。
            // 之前这里用的是 ContactsHelper —— 那是读写系统通讯录的，
            // 结果是主记录写不进加密库，副本却按系统 id 去删，两边都不对。
            val repo = ContactRepository(this)
            var merged = 0
            targets.forEach { g ->
                val (primary, others) = DuplicateFinder.merge(g)
                // 先写主记录再删其余的。反过来的话，万一更新失败，
                // 那些号码就跟着被删的联系人一起没了
                if (repo.save(primary)) {
                    others.forEach { victim ->
                        repo.deleteContact(victim) {}
                    }
                    merged++
                }
            }
            runOnUiThread {
                Toast.makeText(this, getString(R.string.merge_done, merged), Toast.LENGTH_SHORT).show()
                scan()
            }
        }
    }

    // ------------------------------------------------------------- Adapter

    private inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.Holder>() {
        private var items = listOf<DuplicateFinder.Group>()

        fun submit(list: List<DuplicateFinder.Group>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_merge_group, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val reason = view.findViewById<TextView>(R.id.reason)
            private val members = view.findViewById<TextView>(R.id.members)
            private val action = view.findViewById<TextView>(R.id.merge_one)

            fun bind(g: DuplicateFinder.Group) {
                reason.setText(
                    when (g.reason) {
                        DuplicateFinder.Reason.SAME_NUMBER -> R.string.merge_group_by_number
                        DuplicateFinder.Reason.SAME_NAME -> R.string.merge_group_by_name
                    }
                )
                // 把每条的姓名和第一个号码列出来，让用户能判断是不是真重复
                members.text = g.contacts.joinToString("\n") { c ->
                    val n = c.phoneNumbers.firstOrNull()?.value.orEmpty()
                    if (n.isBlank()) c.getNameToDisplay() else "${c.getNameToDisplay()} · $n"
                }
                action.setOnClickListener { confirmMerge(g) }
            }
        }
    }
}
