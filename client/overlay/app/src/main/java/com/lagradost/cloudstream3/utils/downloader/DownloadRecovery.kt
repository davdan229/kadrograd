package com.lagradost.cloudstream3.utils.downloader

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

/** Keeps an interrupted queue available without replaying a process crash at startup. */
internal object DownloadRecovery {
    private var checked = false
    private var hold = false

    internal fun isCrash(reason: Int, status: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR -> true
        ApplicationExitInfo.REASON_EXIT_SELF -> status != 0
        ApplicationExitInfo.REASON_SIGNALED -> status in setOf(6, 7, 8, 11)
        else -> false
    }

    @Synchronized fun holdRestoredQueue(context: Context): Boolean {
        if (checked) return hold
        val settings = context.getSharedPreferences("download_recovery", Context.MODE_PRIVATE)
        hold = settings.getBoolean("hold", false)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val previous = context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 8)
                    .filter { it.processName == context.packageName }.maxByOrNull { it.timestamp }
                if (previous != null && previous.timestamp > settings.getLong("handled_exit", 0)) {
                    if (isCrash(previous.reason, previous.status)) {
                        hold = true
                        Log.w("DownloadRecovery", "Restored queue held after exit reason=${previous.reason}, status=${previous.status}")
                    }
                    check(settings.edit().putBoolean("hold", hold)
                        .putLong("handled_exit", previous.timestamp).commit())
                }
            } catch (error: Exception) {
                Log.w("DownloadRecovery", "Could not inspect previous process exit", error)
            }
        }
        checked = true
        return hold
    }

    @Synchronized fun allowUserRequest(context: Context) {
        holdRestoredQueue(context)
        check(context.getSharedPreferences("download_recovery", Context.MODE_PRIVATE)
            .edit().putBoolean("hold", false).commit())
        hold = false
    }
}
