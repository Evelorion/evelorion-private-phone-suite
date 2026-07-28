package com.evelorion.contacts.ui.theme

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.evelorion.contacts.R

/**
 * 配色与深浅模式。
 *
 * ── 和上一版最大的不同 ──────────────────────────────────────
 *
 * 这个工程的主题直接继承 Theme.Material3，**完全不经过 commons**。
 * 上一版继承 commons 的 AppTheme.Base，结果 commons 的
 * BaseSimpleActivity.onCreate() 里那句 setTheme(getThemeId(...))
 * 把主题整个换掉，M3 的 token 一个都活不下来。
 *
 * 这里没有那个问题，[apply] 在 setContentView 之前调用即可。
 *
 * ── 为什么配色要用 ThemeOverlay ─────────────────────────────
 *
 * Android 没法在运行时改一个 color 资源的值。想让用户切配色，只能让布局
 * 引用 ?attr/colorPrimary 这类**属性**，再用不同的 overlay 决定属性指向
 * 哪个颜色。布局里写死 @color/m3_primary 就锁死了。
 */
object M3Theme {

    private const val PREFS = "theme"
    private const val KEY_PALETTE = "palette"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_VERSION = "theme_version"

    enum class Palette(val id: String) {
        DEFAULT("default"),
        TEAL("teal"),
        WARM("warm"),

        /**
         * 动态取色（Material You）。从用户的壁纸生成整套配色。
         * 需要 Android 12+，低版本上会自动退回默认紫 —— 见 [apply]。
         */
        DYNAMIC("dynamic");

        companion object {
            fun from(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    /** 动态取色只有 Android 12+ 才有。低版本上不要把这个选项显示给用户。 */
    val dynamicColorAvailable: Boolean
        get() = DynamicColors.isDynamicColorAvailable()

    /** FOLLOW_SYSTEM 是默认值 —— 用户没表态时不要替他决定。 */
    enum class DarkMode(val id: String, val nightMode: Int) {
        FOLLOW_SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

        companion object {
            fun from(id: String?) = entries.firstOrNull { it.id == id } ?: FOLLOW_SYSTEM
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun palette(context: Context) = Palette.from(prefs(context).getString(KEY_PALETTE, null))

    fun darkMode(context: Context) = DarkMode.from(prefs(context).getString(KEY_DARK, null))

    /** 必须在 setContentView 之前调用。晚了的话已 inflate 的 View 会留着旧颜色。 */
    fun apply(activity: Activity) {
        when (palette(activity)) {
            Palette.DEFAULT -> Unit
            Palette.TEAL -> activity.theme.applyStyle(R.style.ThemeOverlay_Contacts_Teal, true)
            Palette.WARM -> activity.theme.applyStyle(R.style.ThemeOverlay_Contacts_Warm, true)
            Palette.DYNAMIC ->
                // 这个方法自己会判断系统版本：不支持时什么都不做，
                // 于是保持基础主题的默认紫，不会崩也不会变成一片灰
                DynamicColors.applyToActivityIfAvailable(activity)
        }
    }

    /**
     * 主题版本号。
     *
     * ── 为什么需要它 ──────────────────────────────────────────
     *
     * 在设置页切了配色只 recreate 设置页自己，返回栈里的主页、详情页
     * 都是**用旧主题创建的**，返回过去还是旧颜色 —— 这就是「只有设置页
     * 变了色」的原因。
     *
     * 解法是每次切换把这个计数器 +1，各个 Activity 在 onResume 里比对
     * 自己创建时记下的版本号，不一致就 recreate 自己。
     * 见 BaseActivity。
     */
    fun version(context: Context) = prefs(context).getInt(KEY_VERSION, 0)

    private fun bumpVersion(context: Context) {
        prefs(context).edit().putInt(KEY_VERSION, version(context) + 1).apply()
    }

    /** 在 Application.onCreate 里调一次，必须早于任何 Activity 创建。 */
    fun applyDarkModeGlobally(context: Context) {
        AppCompatDelegate.setDefaultNightMode(darkMode(context).nightMode)
    }

    fun setPalette(activity: Activity, palette: Palette) {
        if (palette == palette(activity)) return
        prefs(activity).edit().putString(KEY_PALETTE, palette.id).apply()
        // 先加版本号再 recreate：返回栈里的其它页面会在各自 onResume 时
        // 发现版本对不上，自己重建
        bumpVersion(activity)
        activity.recreate()
    }

    fun setDarkMode(activity: Activity, mode: DarkMode) {
        if (mode == darkMode(activity)) return
        prefs(activity).edit().putString(KEY_DARK, mode.id).apply()
        // setDefaultNightMode 自己会重建所有 Activity，这里不要再 recreate ——
        // 重复调会重建两次，肉眼可见地闪一下
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }
}
