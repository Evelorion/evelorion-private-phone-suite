package com.evelorion.phone.data

import androidx.compose.ui.graphics.Color

enum class CallKind { Incoming, Outgoing, Missed, Video }

val CallKind.label: String
    get() = when (this) {
        CallKind.Incoming -> "来电"
        CallKind.Outgoing -> "拨出"
        CallKind.Missed -> "未接来电"
        CallKind.Video -> "视频通话"
    }

data class Person(
    val id: String,
    val name: String,
    val number: String,
    val letter: String,
    val initial: String,
    val bg: Color,
    val fg: Color,
    val city: String,
    val favorite: Boolean = false,
    val family: Boolean = false
)

data class CallLog(
    val id: String,
    val personId: String?,
    val group: String,
    val kind: CallKind,
    val startedAt: Long,
    val endedAt: Long,
    val time: String,
    val duration: String? = null,
    val repeatCount: Int = 1,
    val spam: Boolean = false,
    val displayName: String? = null,
    val displayNumber: String? = null,
    val displayInitial: String? = null,
    val displayBg: Color? = null,
    val displayFg: Color? = null,
    val displayCity: String? = null
)

data class HistoryEntry(
    val kind: String,
    val date: String,
    val timeRange: String,
    val duration: String,
    val missed: Boolean = false,
    val selected: Boolean = false,
)
