package com.almus.studio

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

object CrashReporter {
    fun install(context: Context) {
        val file = File(context.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { file.writeText("Thread: ${thread.name}\n" + Log.getStackTraceString(error)) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns true if a crash report was shown. */
    fun showIfCrashed(activity: Activity): Boolean {
        val file = File(activity.filesDir, "last_crash.txt")
        var found: String? = null
        if (file.exists()) {
            found = file.readText()
            file.delete()
        } else if (Build.VERSION.SDK_INT >= 30) {
            val prefs = activity.getSharedPreferences("crash", Context.MODE_PRIVATE)
            val info = activity.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(activity.packageName, 0, 1).firstOrNull()
            if (info != null && info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE &&
                info.timestamp > prefs.getLong("seen", 0L)) {
                prefs.edit().putLong("seen", info.timestamp).apply()
                found = "NATIVE CRASH: ${info.description}"
            }
        }
        val message = found ?: return false

        val body = TextView(activity).apply {
            text = message
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        val copyBtn = Button(activity).apply {
            text = "Copy crash report"
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", message))
            }
        }
        val openBtn = Button(activity).apply {
            text = "Try opening app again"
            setOnClickListener { activity.recreate() }
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(copyBtn)
            addView(openBtn)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        activity.setContentView(root)
        return true
    }
}
