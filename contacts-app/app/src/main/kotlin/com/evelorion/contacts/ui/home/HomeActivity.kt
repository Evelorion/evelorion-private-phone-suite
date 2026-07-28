package com.evelorion.contacts.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository
import com.evelorion.contacts.data.ContactRepository.UiContact
import com.evelorion.contacts.databinding.ActivityHomeBinding
import com.evelorion.contacts.sync.VaultManager
import com.evelorion.contacts.sync.work.SyncEvents
import com.evelorion.contacts.sync.work.SyncScheduler
import com.evelorion.contacts.ui.BaseActivity
import com.evelorion.contacts.ui.CrashReport
import com.evelorion.contacts.ui.detail.DetailActivity
import com.evelorion.contacts.ui.edit.EditActivity
import com.evelorion.contacts.ui.favorites.FavoritesAdapter
import com.evelorion.contacts.ui.group.GroupContactsActivity
import com.evelorion.contacts.ui.search.SearchActivity
import com.evelorion.contacts.ui.settings.SettingsActivity
import com.evelorion.contacts.ui.widget.BottomNav
import java.text.NumberFormat
import com.evelorion.contacts.ui.Bg
import java.util.concurrent.Executors

/**
 * 主页。
 *
 * 联系人页和收藏页共用同一个 RecyclerView，只换 adapter 和 LayoutManager ——
 * 设计稿里这两页的顶部区域不同（收藏页没有搜索栏），但列表区是同一块，
 * 用 ViewPager 的话横滑手势会和列表滚动打架。
 */
class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var repo: ContactRepository
    private lateinit var nav: BottomNav

    private val io = Bg.single("home")

    private var contacts = listOf<UiContact>()
    private var groups = listOf<ContactRepository.UiGroup>()
    private var tab = TAB_CONTACTS

    private val contactsAdapter by lazy { ContactsAdapter { openDetail(it) } }
    private val favoritesAdapter by lazy {
        FavoritesAdapter(
            onClick = { openDetail(it) },
            onCall = { dial(it.primaryNumber) },
            onSms = { sms(it.primaryNumber) },
            onGroupClick = { g ->
                startActivity(
                    Intent(this, GroupContactsActivity::class.java)
                        .putExtra(GroupContactsActivity.EXTRA_ID, g.id)
                        .putExtra(GroupContactsActivity.EXTRA_NAME, g.name)
                )
            },
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 上次是崩着退出的，先把堆栈摆给用户看，别急着重新初始化 ——
        // 崩溃的原因多半还在，继续走下去只会再崩一次，用户又是一片空白。
        if (CrashReport.showIfPending(this)) {
            finish()
            return
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.statusBarSpacer, binding.bottomNav)

        repo = ContactRepository(this)

        binding.searchBar.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        binding.profileAvatar.setOnClickListener { openSettings() }
        binding.fab.setOnClickListener { startActivity(Intent(this, EditActivity::class.java)) }

        nav = BottomNav(binding.bottomNav)
        nav.setup(BottomNav.defaultItems()) { index ->
            when (index) {
                NAV_CONTACTS -> showTab(TAB_CONTACTS)
                NAV_FAVORITES -> showTab(TAB_FAVORITES)
                NAV_SETTINGS -> {
                    openSettings()
                    // 设置是另一个 Activity 不是页签 —— 高亮要退回当前页，
                    // 否则用户返回后会看到「设置」还是选中态
                    nav.select(if (tab == TAB_CONTACTS) NAV_CONTACTS else NAV_FAVORITES)
                }
            }
        }
        nav.select(NAV_CONTACTS)
        showTab(TAB_CONTACTS)

        ensurePermission()
    }

    /**
     * 同步完成后刷新列表。
     *
     * 保持成一个字段而不是每次新建 lambda —— 注册和注销必须是同一个
     * 对象，否则移不掉，Activity 就泄漏了。
     */
    private val syncListener = SyncEvents.Listener { pulled, _ ->
        if (pulled > 0 && !isFinishing && !isDestroyed) load()
    }

    override fun onStart() {
        super.onStart()
        SyncEvents.addListener(syncListener)
    }

    override fun onStop() {
        super.onStop()
        // 不注销的话这个列表会一直攥着 Activity，整棵 View 树回收不掉
        SyncEvents.removeListener(syncListener)
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) load()

        // 回到前台顺手同步一次。
        //
        // 走 WorkManager 而不是直接开线程 —— 它自带网络约束和退避重试，
        // 而且 REPLACE 策略保证用户来回切 App 不会堆出一串任务。
        //
        // 同步完成后 SyncWorker 会发广播，下面的 receiver 收到就刷新列表，
        // 用户不用手动下拉。
        runCatching {
            if (VaultManager.get(this).isConfigured) SyncScheduler.syncNow(this, "resume")
        }
    }

    // ------------------------------------------------------------------ 权限

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermission() {
        if (!hasPermission()) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            )
        }
    }

    // ------------------------------------------------------------------ 数据

    private fun load() {
        io.execute {
            repo.loadAll { list ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    contacts = list
                    updateSubtitle()
                    render()
                }
            }
            // 这个是同步的，已经在后台线程里了
            val g = repo.loadGroupsSync()
            runOnUiThread { groups = g; if (tab == TAB_FAVORITES) render() }
        }
    }

    private fun updateSubtitle() {
        val count = NumberFormat.getInstance().format(contacts.size)
        // 同步状态在同步模块接入后才有意义，现在只显示条数 ——
        // 挂一个「未同步」会让人以为出了问题
        binding.subtitle.text = getString(R.string.subtitle_count, count)
    }

    // ------------------------------------------------------------------ 渲染

    private fun showTab(which: Int) {
        tab = which
        binding.title.setText(if (which == TAB_CONTACTS) R.string.tab_contacts else R.string.tab_favorites)
        // 收藏页没有搜索栏（设计稿如此），标题直接接列表
        binding.searchBar.visibility = if (which == TAB_CONTACTS) View.VISIBLE else View.GONE
        binding.subtitle.visibility = if (which == TAB_CONTACTS) View.VISIBLE else View.GONE
        render()
    }

    private fun render() {
        if (tab == TAB_CONTACTS) {
            binding.list.layoutManager = LinearLayoutManager(this)
            binding.list.adapter = contactsAdapter
            binding.list.setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.list_bottom_inset))
            contactsAdapter.submitList(ContactsAdapter.buildRows(contacts))
            toggleEmpty(contacts.isEmpty(), R.string.empty_no_contacts, R.string.empty_no_contacts_desc)
        } else {
            // 收藏页是两列网格；群组那一段跨两列
            val lm = GridLayoutManager(this, 2)
            lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = favoritesAdapter.spanSizeAt(position)
            }
            binding.list.layoutManager = lm
            binding.list.adapter = favoritesAdapter
            val side = resources.getDimensionPixelSize(R.dimen.card_margin_horizontal)
            binding.list.setPadding(side, 0, side, resources.getDimensionPixelSize(R.dimen.list_bottom_inset))
            val favorites = contacts.filter { it.isFavorite }
            favoritesAdapter.submit(favorites, groups)
            toggleEmpty(favorites.isEmpty() && groups.isEmpty(), R.string.empty_no_favorites, 0)
        }
    }

    private fun toggleEmpty(empty: Boolean, titleRes: Int, descRes: Int) {
        binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.list.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            binding.emptyTitle.setText(titleRes)
            binding.emptyDesc.visibility = if (descRes == 0) View.GONE else View.VISIBLE
            if (descRes != 0) binding.emptyDesc.setText(descRes)
        }
    }

    // ------------------------------------------------------------------ 跳转

    private fun openDetail(c: UiContact) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, c.id))
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun dial(number: String) {
        if (number.isBlank()) return
        startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")))
    }

    private fun sms(number: String) {
        if (number.isBlank()) return
        startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$number")))
    }

    companion object {
        private const val TAB_CONTACTS = 0
        private const val TAB_FAVORITES = 1
        private const val NAV_CONTACTS = 0
        private const val NAV_FAVORITES = 1
        private const val NAV_SETTINGS = 2
    }
}
