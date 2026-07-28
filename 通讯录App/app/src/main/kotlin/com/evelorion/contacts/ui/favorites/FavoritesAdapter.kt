package com.evelorion.contacts.ui.favorites

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository.UiContact
import com.evelorion.contacts.data.ContactRepository.UiGroup
import com.evelorion.contacts.ui.widget.AvatarDrawable

/**
 * 收藏页：两列卡片 + 下面一段群组列表。
 *
 * 一个 adapter 装三种条目（卡片 / 分区标题 / 群组行），
 * 靠 [spanSizeAt] 告诉 GridLayoutManager 谁占一列谁占两列。
 * 拆成两个 RecyclerView 的话滚动会分段，体验很差。
 */
class FavoritesAdapter(
    private val onClick: (UiContact) -> Unit,
    private val onCall: (UiContact) -> Unit,
    private val onSms: (UiContact) -> Unit,
    private val onGroupClick: (UiGroup) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Fav(val contact: UiContact) : Row()
        data class Header(val titleRes: Int) : Row()
        data class GroupRow(val group: UiGroup) : Row()
    }

    private var rows = listOf<Row>()

    fun submit(favorites: List<UiContact>, groups: List<UiGroup>) {
        val list = mutableListOf<Row>()
        favorites.forEach { list.add(Row.Fav(it)) }
        if (groups.isNotEmpty()) {
            list.add(Row.Header(R.string.section_groups))
            groups.forEach { list.add(Row.GroupRow(it)) }
        }
        rows = list
        notifyDataSetChanged()
    }

    /** 卡片占一列，标题和群组行占满两列。 */
    fun spanSizeAt(position: Int) = if (rows.getOrNull(position) is Row.Fav) 1 else 2

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Fav -> TYPE_FAV
        is Row.Header -> TYPE_HEADER
        is Row.GroupRow -> TYPE_GROUP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_FAV -> FavHolder(inflater.inflate(R.layout.item_fav_card, parent, false))
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            else -> GroupHolder(inflater.inflate(R.layout.item_group, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Fav -> (holder as FavHolder).bind(row.contact)
            is Row.Header -> (holder as HeaderHolder).bind(row.titleRes)
            is Row.GroupRow -> (holder as GroupHolder).bind(row.group)
        }
    }

    inner class FavHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar = view.findViewById<ImageView>(R.id.avatar)
        private val name = view.findViewById<TextView>(R.id.name)
        private val number = view.findViewById<TextView>(R.id.number)
        private val call = view.findViewById<View>(R.id.action_call)
        private val sms = view.findViewById<View>(R.id.action_sms)

        fun bind(c: UiContact) {
            name.text = c.name
            number.text = c.primaryNumber
            number.visibility = if (c.primaryNumber.isBlank()) View.GONE else View.VISIBLE
            avatar.setImageDrawable(AvatarDrawable(itemView.context, c.initial, c.id))
            itemView.setOnClickListener { onClick(c) }
            call.setOnClickListener { onCall(c) }
            sms.setOnClickListener { onSms(c) }
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.section_title)
        fun bind(res: Int) { title.setText(res) }
    }

    inner class GroupHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.group_name)
        private val count = view.findViewById<TextView>(R.id.group_count)

        fun bind(g: UiGroup) {
            name.text = g.name
            count.text = g.count.toString()
            itemView.setOnClickListener { onGroupClick(g) }
        }
    }

    private companion object {
        const val TYPE_FAV = 0
        const val TYPE_HEADER = 1
        const val TYPE_GROUP = 2
    }
}
