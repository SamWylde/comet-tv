package com.tdarby.comet.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatAutoCompleteTextView

/**
 * Address field that exposes BACK before the TV input method consumes it. Android routes BACK
 * through onKeyPreIme while a text editor owns focus, bypassing Activity.dispatchKeyEvent on some
 * TV builds.
 */
class TvUrlBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.autoCompleteTextViewStyle
) : AppCompatAutoCompleteTextView(context, attrs, defStyleAttr) {

    var onRemoteBack: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onRemoteBack?.invoke()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
