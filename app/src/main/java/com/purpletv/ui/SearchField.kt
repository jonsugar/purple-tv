package com.purpletv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * A text field that behaves on a TV remote. The keyboard opens on OK only. While it is closed,
 * the arrows leave the field: the TV keyboard otherwise takes them before the view sees them,
 * even hidden, so Down never reached the results.
 */
class SearchField(context: Context, attrs: AttributeSet?) : EditText(context, attrs) {
    /** Where Down from the field goes; null lets the focus search decide. */
    var onDownFromField: (() -> Unit)? = null
    private var keyboardOpen = false
    private val imm get() = context.getSystemService(InputMethodManager::class.java)

    init {
        showSoftInputOnFocus = false
        setOnClickListener { openKeyboard() }
    }

    fun openKeyboard() {
        keyboardOpen = true
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun closeKeyboard() {
        keyboardOpen = false
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyboardOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) keyboardOpen = false
            return super.onKeyPreIme(keyCode, event)
        }
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT.takeIf { selectionStart == 0 }
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT.takeIf { selectionEnd == text.length }
            else -> null
        } ?: return super.onKeyPreIme(keyCode, event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val down = onDownFromField
            if (direction == View.FOCUS_DOWN && down != null) down() else focusSearch(direction)?.requestFocus(direction)
        }
        return true
    }
}
