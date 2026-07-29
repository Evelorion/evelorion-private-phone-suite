package com.evelorion.phone.data

import android.content.Context

object FavoriteOrderStore {
    private const val PREFS = "favorite_order"
    private const val KEY_IDS = "ids"
    private const val SEPARATOR = "\u001F"

    fun resolve(context: Context, currentIds: List<String>): List<String> {
        val current = currentIds.toSet()
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_IDS, "")
            .orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotEmpty() && it in current }
        return stored + currentIds.filterNot { it in stored }
    }

    fun save(context: Context, ids: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IDS, ids.joinToString(SEPARATOR))
            .apply()
    }
}
