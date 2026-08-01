package com.example.sms.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFormat {

    private val hm = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val ymd = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
    private val fullDay = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

    /**
     * 列表里的时间：直接写「过了多久」，不用「昨天 / 周日」这类模糊说法。
     * 刚刚 / 3 分钟前 / 5 小时前 / 12 天前 / 3 个月前 / 2 年前
     */
    fun listStamp(time: Long, now: Long = System.currentTimeMillis()): String {
        if (time <= 0) return ""
        return elapsed(time, now)
    }

    /** 经过时长的中文描述 */
    fun elapsed(time: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - time
        if (diff < 0) return "刚刚"

        val minutes = diff / 60_000L
        val hours = diff / 3_600_000L
        val days = diff / 86_400_000L

        return when {
            diff < 60_000L -> "刚刚"
            minutes < 60 -> minutes.toString() + " 分钟前"
            hours < 24 -> hours.toString() + " 小时前"
            days < 30 -> days.toString() + " 天前"
            days < 365 -> (days / 30).toString() + " 个月前"
            else -> (days / 365).toString() + " 年前"
        }
    }

    /**
     * 会话里的日期分隔条：给出确切时刻 + 过了多久，
     * 例如「今天 10:12 · 3 小时前」「7月24日 18:03 · 8 天前」
     */
    fun dayDivider(time: Long, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val sameYear = c.get(Calendar.YEAR) == n.get(Calendar.YEAR)
        val absolute = when {
            daysBetween(c, n) == 0 -> "今天 " + hm.format(Date(time))
            sameYear -> fullDay.format(Date(time))
            else -> ymd.format(Date(time)) + " " + hm.format(Date(time))
        }
        return absolute + " · " + elapsed(time, now)
    }

    fun clock(time: Long): String = hm.format(Date(time))

    /** 两条消息之间超过 15 分钟就插一条分隔 */
    fun needsDivider(prev: Long, cur: Long): Boolean = cur - prev > 15 * 60 * 1000L

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        val a0 = startOfDay(a)
        val b0 = startOfDay(b)
        return ((b0 - a0) / 86_400_000L).toInt()
    }

    private fun startOfDay(c: Calendar): Long {
        val x = c.clone() as Calendar
        x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0)
        x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0)
        return x.timeInMillis
    }
}
