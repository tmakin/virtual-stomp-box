package uk.neatlines.stompbox.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Android 15 draws apps edge to edge (targetSdk 35), under the status and navigation bars.
 * Pads this view by the bars' size plus [extraPx] so nothing ends up behind them.
 */
fun View.padForSystemBars(extraPx: Int) {
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left + extraPx, b.top + extraPx, b.right + extraPx, b.bottom + extraPx)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft + extraPx, insets.systemWindowInsetTop + extraPx,
                insets.systemWindowInsetRight + extraPx, insets.systemWindowInsetBottom + extraPx)
        }
        insets
    }
}
