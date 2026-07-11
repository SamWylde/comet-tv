package com.tdarby.comet.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.AppCompatSpinner

/**
 * A TV-friendly collapsed Spinner. Android's default Spinner consumes every D-pad arrow, trapping
 * focus on the control. Here arrows continue spatial settings navigation; Enter still opens the
 * choice list, whose own Up/Down/Enter/Back behavior remains native.
 */
class TvSettingsSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.spinnerStyle
) : AppCompatSpinner(context, attrs, defStyleAttr) {

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_DOWN
            else -> return super.onKeyDown(keyCode, event)
        }
        val next = focusSearch(direction)
        return if (next != null && next !== this) {
            next.requestFocus()
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }
}
