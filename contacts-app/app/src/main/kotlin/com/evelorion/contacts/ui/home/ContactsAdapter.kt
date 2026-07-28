package com.evelorion.contacts.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.evelorion.contacts.R
import com.evelorion.contacts.data.ContactRepository.UiContact
import com.evelorion.contacts.ui.widget.AvatarDrawable

/**
 * 联系人列表。
 *
 * 列表里混着两种条目：字母分组头和联系人行。用一个 sealed class 表示，
 * 而不是两个 RecyclerView 或者 SectionIndexer —— 前者滚动不连贯，
 * 后者在 Android 上早就没人维护了。
 */
class ContactsAdapter(
    private val onClick: (UiContact) -> Unit,
) : ListAdapter<ContactsAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed class Row {
        data class Letter(val letter: String) : Row()
        data class Person(val contact: UiContact) : Row()
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position) is Row.Letter) TYPE_LETTER else TYPE_PERSON

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LETTER) {
            LetterHolder(inflater.inflate(R.layout.item_index_letter, parent, false))
        } else {
            PersonHolder(inflater.inflate(R.layout.item_contact, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Letter -> (holder as LetterHolder).bind(row)
            is Row.Person -> (holder as PersonHolder).bind(row.contact)
        }
    }

    class LetterHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val letter = view.findViewById<TextView>(R.id.letter)
        fun bind(row: Row.Letter) { letter.text = row.letter }
    }

    inner class PersonHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar = view.findViewById<ImageView>(R.id.avatar)
        private val name = view.findViewById<TextView>(R.id.name)
        private val subtitle = view.findViewById<TextView>(R.id.subtitle)
        private val star = view.findViewById<ImageView>(R.id.star)

        fun bind(c: UiContact) {
            name.text = c.name
            subtitle.text = c.subtitle
            subtitle.visibility = if (c.subtitle.isBlank()) View.GONE else View.VISIBLE
            star.visibility = if (c.isFavorite) View.VISIBLE else View.GONE
            avatar.setImageDrawable(AvatarDrawable(itemView.context, c.initial, c.id))
            itemView.setOnClickListener { onClick(c) }
        }
    }

    companion object {
        private const val TYPE_LETTER = 0
        private const val TYPE_PERSON = 1

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Letter && b is Row.Letter -> a.letter == b.letter
                a is Row.Person && b is Row.Person -> a.contact.id == b.contact.id
                else -> false
            }

            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }

        /** 把排好序的联系人切成「字母头 + 若干行」。 */
        fun buildRows(contacts: List<UiContact>): List<Row> {
            val rows = mutableListOf<Row>()
            var current: String? = null
            contacts.forEach { c ->
                if (c.sortLetter != current) {
                    current = c.sortLetter
                    rows.add(Row.Letter(c.sortLetter))
                }
                rows.add(Row.Person(c))
            }
            return rows
        }
    }
}
