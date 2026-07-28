package com.evelorion.contacts.ui.search

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository
import com.evelorion.contacts.data.ContactRepository.UiContact
import com.evelorion.contacts.databinding.ActivitySearchBinding
import com.evelorion.contacts.ui.BaseActivity
import com.evelorion.contacts.ui.detail.DetailActivity
import com.evelorion.contacts.ui.edit.EditActivity
import com.evelorion.contacts.ui.widget.AvatarDrawable

/**
 * 全屏搜索页。
 *
 * M3 的 docked search bar 模式：主页那条搜索栏不接受输入，点了进这一页。
 * 好处是主页完全不用处理输入法弹起导致的布局变化 —— 那是 Android 上
 * 最容易出现「FAB 被顶飞」「列表跳一下」的地方。
 */
class SearchActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var repo: ContactRepository
    private var all = listOf<UiContact>()
    private val adapter = ResultsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer)

        repo = ContactRepository(this)
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.results.adapter = adapter

        binding.back.setOnClickListener { finish() }
        binding.clear.setOnClickListener { binding.input.setText("") }
        binding.emptyCreate.setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java))
        }

        binding.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onQuery(s?.toString().orEmpty())
        })

        repo.loadAll { list -> runOnUiThread { all = list; onQuery(binding.input.text.toString()) } }

        showRecent()

        // 进来就聚焦弹键盘 —— 用户点搜索栏的意图就是要打字。
        // post 是必要的：View 还没 attach 到窗口时 showSoftInput 会被静默忽略。
        binding.input.requestFocus()
        binding.input.post {
            getSystemService<InputMethodManager>()
                ?.showSoftInput(binding.input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun onQuery(raw: String) {
        val q = raw.trim()
        binding.clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE

        if (q.isEmpty()) {
            state(recent = true, results = false, empty = false)
            return
        }
        val hits = filter(q)
        if (hits.isEmpty()) {
            binding.emptyTitle.text = getString(R.string.search_empty_title, q)
            state(recent = false, results = false, empty = true)
        } else {
            binding.resultCount.text = getString(R.string.search_result_count, hits.size)
            adapter.submit(hits)
            state(recent = false, results = true, empty = false)
        }
    }

    /**
     * 姓名、副标题（含公司）、号码都匹配。
     *
     * 号码比对前先去掉所有非数字 —— 用户存的是「138 0013 8000」，
     * 搜的时候打「13800」，不归一化一条都搜不到。
     */
    private fun filter(q: String): List<UiContact> {
        val lower = q.lowercase()
        val digits = q.filter(Char::isDigit)
        return all.filter { c ->
            c.name.lowercase().contains(lower) ||
                c.subtitle.lowercase().contains(lower) ||
                (digits.isNotEmpty() && c.primaryNumber.filter(Char::isDigit).contains(digits))
        }
    }

    private fun state(recent: Boolean, results: Boolean, empty: Boolean) {
        binding.recentHolder.visibility = if (recent) View.VISIBLE else View.GONE
        binding.resultCount.visibility = if (results) View.VISIBLE else View.GONE
        binding.results.visibility = if (results) View.VISIBLE else View.GONE
        binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ 最近搜索

    /**
     * **只存搜索词，不存搜到了谁** —— 后者等于在明文里留一份
     * 「我最近关注谁」的记录，和这个 App 加密通讯录的目的相反。
     */
    private fun showRecent() {
        val terms = prefs().getString(KEY_RECENT, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }
        binding.recentHolder.visibility = if (terms.isEmpty()) View.GONE else View.VISIBLE
        binding.recentChips.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val gap = resources.getDimensionPixelSize(R.dimen.chip_gap)
        terms.forEach { term ->
            val chip = inflater.inflate(R.layout.item_chip, binding.recentChips, false) as TextView
            chip.text = term
            (chip.layoutParams as LinearLayout.LayoutParams).marginEnd = gap
            chip.setOnClickListener {
                binding.input.setText(term)
                binding.input.setSelection(term.length)
            }
            binding.recentChips.addView(chip)
        }
    }

    private fun remember(term: String) {
        if (term.isBlank()) return
        val old = prefs().getString(KEY_RECENT, "").orEmpty()
            .split('\n').filter { it.isNotBlank() && it != term }
        prefs().edit().putString(KEY_RECENT, (listOf(term) + old).take(6).joinToString("\n")).apply()
    }

    private fun prefs() = getSharedPreferences("search", MODE_PRIVATE)

    override fun onPause() {
        super.onPause()
        remember(binding.input.text.toString().trim())
    }

    // ------------------------------------------------------------- Adapter

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.Holder>() {
        private var items = listOf<UiContact>()

        fun submit(list: List<UiContact>) {
            items = list
            // 搜索结果整批换掉，没有增量更新的意义
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val avatar = view.findViewById<ImageView>(R.id.avatar)
            private val name = view.findViewById<TextView>(R.id.name)
            private val subtitle = view.findViewById<TextView>(R.id.subtitle)

            fun bind(c: UiContact) {
                name.text = c.name
                subtitle.text = c.subtitle
                subtitle.visibility = if (c.subtitle.isBlank()) View.GONE else View.VISIBLE
                avatar.setImageDrawable(AvatarDrawable(itemView.context, c.initial, c.id))
                itemView.setOnClickListener {
                    remember(binding.input.text.toString().trim())
                    startActivity(
                        Intent(this@SearchActivity, DetailActivity::class.java)
                            .putExtra(DetailActivity.EXTRA_ID, c.id)
                    )
                }
            }
        }
    }

    private companion object {
        const val KEY_RECENT = "recent_terms"
    }
}
