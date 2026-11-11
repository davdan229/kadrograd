package com.lagradost.cloudstream3.utils.downloader

import android.os.Build
import android.view.WindowInsets
import android.widget.ListView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.R

internal object DownloadChoiceDialog {
    /** Android 15 can extend the sheet below the system navigation buttons. */
    fun fitSystemBars(dialog: BottomSheetDialog) {
        if (Build.VERSION.SDK_INT < 35) return
        val list = dialog.findViewById<ListView>(R.id.listview1) ?: return
        val initialBottom = list.paddingBottom
        fun updatePadding() {
            val window = dialog.window ?: return
            val metrics = window.windowManager.currentWindowMetrics
            val bars = metrics.windowInsets.getInsets(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
            )
            val position = IntArray(2)
            list.getLocationOnScreen(position)
            val overlap = (position[1] + list.height - (metrics.bounds.bottom - bars.bottom)).coerceAtLeast(0)
            val bottom = initialBottom + overlap
            if (list.paddingBottom != bottom) {
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottom)
            }
        }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePadding() }
        list.post { updatePadding() }
    }
}
