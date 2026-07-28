package com.evelorion.phone.ui

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
 * 崩溃记录。和通讯录里那套是同一个东西，原因也一样：
 * 真机上拿不到 logcat，没有它，「闪退了」就只剩这三个字，
 * 崩在哪只能靠猜，猜错就浪费一个来回。
 *
 * 写进 filesDir（App 私有目录）。堆栈里会出现类名、字段名，
 * 有时还带数据片段，不该让别的 App 读到。
 */
object CrashReport {

    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            // 记完照样崩。吞掉异常让 App 假装活着更糟：状态已经不一致，
            // 接着跑只会产生更难查的问题。
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        File(context.filesDir, FILE).writeText(
            buildString {
                appendLine("时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
                appendLine("线程：" + thread.name)
                appendLine("机型：" + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE)
                appendLine()
                append(sw.toString())
            }
        )
    }

    fun pending(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /** @return true 表示已跳去崩溃页，调用方应立刻 finish()，不要继续初始化。 */
    fun showIfPending(activity: Activity): Boolean {
        if (pending(activity) == null) return false
        activity.startActivity(Intent(activity, CrashActivity::class.java))
        return true
    }
}

/**
 * 崩溃详情页。**故意不用 Compose、不用主题、不碰数据库。**
 * 它存在的意义就是别的东西都坏掉时还能显示出来，
 * 每多依赖一样东西就多一分自己也崩掉的可能。
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
        root.addView(
            ScrollView(this).apply {
                addView(TextView(this@CrashActivity).apply {
                    text = report
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.BLACK)
                    setTextIsSelectable(true)
                    typeface = Typeface.MONOSPACE
                })
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, pad / 2, 0, 0)
            addView(Button(this@CrashActivity).apply {
                text = "复制"
                setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("crash", report))
                    Toast.makeText(this@CrashActivity, "已复制", Toast.LENGTH_SHORT).show()
                }
            })
            addView(Button(this@CrashActivity).apply {
                text = "清除记录"
                setOnClickListener { CrashReport.clear(this@CrashActivity); finishAffinity() }
            })
        })
        setContentView(root)
    }
}
