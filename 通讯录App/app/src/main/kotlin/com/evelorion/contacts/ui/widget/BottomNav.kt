package com.evelorion.contacts.ui.widget

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.evelorion.contacts.R
import com.evelorion.contacts.ui.theme.themeColor
import com.google.android.material.R as MaterialR

/**
 * 底部导航。
 *
 * 不用 BottomNavigationView：设计稿的胶囊是 64×32，而它的 item 内部布局
 * 是私有的、改不到那个尺寸。手写一共几十行，还省掉一个 menu xml。
 */
class BottomNav(private val container: LinearLayout) {

    data class Item(
        @DrawableRes val icon: Int,
        @DrawableRes val iconSelected: Int,
        @StringRes val label: Int,
    )

    private val views = mutableListOf<View>()
    private var items = listOf<Item>()
    private var selected = -1

    fun setup(items: List<Item>, onSelect: (Int) -> Unit) {
        this.items = items
        container.removeAllViews()
        views.clear()

        val inflater = LayoutInflater.from(container.context)
        items.forEachIndexed { index, item ->
            val view = inflater.inflate(R.layout.item_nav, container, false)
            // 图标在这里就设好，不要只在 select() 里设 —— select 万一没被调到，
            // 用户看到的就是三个只有文字没有图标的按钮
            view.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(item.icon)
                // 先给个未选中色。select() 随后会按选中状态改，
                // 但万一 select 没被调到，至少不是一片空白
                setColorFilter(context.themeColor(MaterialR.attr.colorOnSurfaceVariant))
            }
            view.findViewById<TextView>(R.id.label).setText(item.label)
            view.contentDescription = container.context.getString(item.label)
            view.setOnClickListener {
                // 重复点当前项不回调，否则会重复触发切换
                if (index != selected) {
                    select(index)
                    onSelect(index)
                }
            }
            container.addView(view)
            views.add(view)
        }
    }

    /**
     * 只改外观，不触发回调。用于同步外部造成的页面变化。
     *
     * 注意图标的着色走 setColorFilter，**不能**指望矢量图里写
     * android:tint="?attr/..." —— 那个主题属性在 setImageResource() 这条
     * 路径上解析不出来，会变成全透明，图标直接消失。
     */
    fun select(index: Int) {
        selected = index
        views.forEachIndexed { i, view ->
            val on = i == index
            val ctx = view.context
            view.findViewById<View>(R.id.pill).isSelected = on

            // 选中换实心图标。M3 用「空心=未选 / 实心=已选」表达状态 ——
            // 只靠颜色区分对色觉障碍用户不友好
            view.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(if (on) items[i].iconSelected else items[i].icon)
                setColorFilter(
                    ctx.themeColor(
                        if (on) MaterialR.attr.colorOnSecondaryContainer
                        else MaterialR.attr.colorOnSurfaceVariant
                    )
                )
            }
            view.findViewById<TextView>(R.id.label).setTextColor(
                ctx.themeColor(
                    if (on) MaterialR.attr.colorOnSurface else MaterialR.attr.colorOnSurfaceVariant
                )
            )
        }
    }

    companion object {
        fun defaultItems() = listOf(
            Item(R.drawable.ic_group, R.drawable.ic_group, R.string.tab_contacts),
            Item(R.drawable.ic_star, R.drawable.ic_star_filled, R.string.tab_favorites),
            Item(R.drawable.ic_settings, R.drawable.ic_settings, R.string.tab_settings),
        )
    }
}
