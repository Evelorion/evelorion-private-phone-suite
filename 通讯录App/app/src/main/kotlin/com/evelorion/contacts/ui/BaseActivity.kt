package com.evelorion.contacts.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.evelorion.contacts.ui.theme.M3Theme

/**
 * 所有页面的基类。
 *
 * 做两件事：套配色 overlay、处理 edge-to-edge 的系统栏留白。
 *
 * ── 关于 edge-to-edge ──────────────────────────────────────
 *
 * 设计稿里状态栏区域是页面底色的一部分（顶上那行「9:30」直接压在
 * 浅紫背景上），所以内容必须延伸到系统栏底下，再用一个占位 View 把
 * 顶部内容顶下来。
 *
 * 不给根布局加 padding 是因为那样底部导航也会跟着缩进，
 * 而设计稿里导航栏的底色是一直铺到屏幕最底下的。
 */
abstract class BaseActivity : AppCompatActivity() {

    /** 创建时的主题版本。onResume 里拿它和当前值比，不一致说明配色被改过。 */
    private var themeVersion = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        M3Theme.apply(this)
        themeVersion = M3Theme.version(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    /**
     * 配色改过就重建自己。
     *
     * 在设置页切配色只会 recreate 设置页，返回栈里的其它页面是用旧主题
     * 创建的，不管的话返回过去还是旧颜色。
     */
    override fun onResume() {
        super.onResume()
        if (themeVersion != M3Theme.version(this)) {
            recreate()
        }
    }

    /**
     * @param spacer 顶部占位 View，高度设成状态栏高度
     * @param bottomPadded 需要在底部留出导航栏高度的 View（一般是底部导航本身）
     */
    protected fun applyInsets(spacer: View?, bottomPadded: View? = null) {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            spacer?.updateLayoutParams { height = bars.top }
            bottomPadded?.updatePadding(bottom = bars.bottom)
            insets
        }
    }
}
