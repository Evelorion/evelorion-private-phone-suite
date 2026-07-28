package com.evelorion.contacts.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录。
 *
 * ── 为什么要自己做一个 ──────────────────────────────────────
 *
 * 这个 App 跑在真机上，而真机上拿不到 logcat：没插线、没 root、
 * 厂商 ROM 也不给看系统日志。于是「闪退了」就只剩这三个字，
 * 崩在哪只能靠猜 —— 猜错就再发一个包，一来一回好几轮。
 *
 * 把堆栈写进文件，下次启动直接显示出来。一次崩溃换一份完整堆栈，
 * 比任何猜测都便宜。
 *
 * 存在 filesDir（App 私有目录）而不是外部存储：堆栈里会出现类名、字段名，
 * 有时还带数据片段，不该让别的 App 读到。
 */
object CrashReport {

    private const val FILE = "last-crash.txt"

    /**
     * 装全局兜底。必须在 attachBaseContext 里调用 ——
     * 比它更早的崩溃没有任何办法记录。
     *
     * 记完之后**照样交给原来的处理器**，也就是照样崩。
     * 吞掉异常让 App 假装活着是更糟的选择：状态已经不一致了，
     * 接着跑只会产生更难查的问题，甚至写坏数据。
     */
    fun install(context: Context) {
        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        val text = buildString {
            appendLine("时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
            appendLine("线程：" + thread.name)
            appendLine("机型：" + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE)
            appendLine()
            append(sw.toString())
        }
        File(context.filesDir, FILE).writeText(text)
    }

    fun pending(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.isFile }?.let {
            runCatching { it.readText() }.getOrNull()
        }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /**
     * 上次崩过就先把堆栈显示出来。
     *
     * @return true 表示已经跳去崩溃页，调用方应当立刻 finish()，不要继续初始化 ——
     *   继续走下去很可能再崩一次，用户又什么都看不到。
     */
    fun showIfPending(activity: Activity): Boolean {
        if (pending(activity) == null) return false
        activity.startActivity(Intent(activity, CrashActivity::class.java))
        return true
    }
}

/**
 * 崩溃详情页。
 *
 * **故意不用布局 XML、不继承 BaseActivity、不碰数据库、不读主题。**
 * 这个页面的意义就是在别的东西都坏掉时还能显示出来，
 * 它每多依赖一样东西，就多一分自己也崩掉的可能。
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReport.pending(this) ?: "没有崩溃记录"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "上次启动崩溃了"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "把下面的内容发给开发者，就能直接定位问题。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.DKGRAY)
            setPadding(0, pad / 2, 0, pad / 2)
        })

        val body = TextView(this).apply {
            text = report
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        root.addView(
            ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, pad / 2, 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "复制"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", report))
                Toast.makeText(this@CrashActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            text = "清除记录"
            setOnClickListener {
                CrashReport.clear(this@CrashActivity)
                finishAffinity()
            }
        })
        root.addView(buttons)

        setContentView(root)
    }
}
