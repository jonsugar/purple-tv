package com.purpletv.auth

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.purpletv.PurpleApp
import com.purpletv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Shows a Device Code Flow code until the user confirms it at twitch.tv/activate, then
 * finishes with RESULT_OK. An expired code is replaced by a fresh one.
 */
class LoginActivity : Activity() {
    private val scope = MainScope()
    private var countdown: Job? = null
    private lateinit var codeBoxes: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        codeBoxes = findViewById(R.id.login_code)
        status = findViewById(R.id.login_status)
        findViewById<TextView>(R.id.login_step_open).text = stepOpen()
        signIn()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun signIn() = scope.launch {
        val auth = PurpleApp.instance.auth
        while (true) {
            try {
                val code = auth.start()
                show(code)
                auth.poll(code)
                setResult(RESULT_OK)
                finish()
                return@launch
            } catch (e: DeviceCodeExpiredException) {
                // Next round asks for a fresh code.
            } catch (e: IOException) {
                // The message carries only the HTTP status, never a token.
                Log.w(TAG, "device login (${auth.clientId.take(4)}…): ${e.message}")
                countdown?.cancel()
                status.setText(R.string.login_network_error)
                delay(RETRY_MS)
            }
        }
    }

    private fun show(code: DeviceCode) {
        codeBoxes.removeAllViews()
        codeBoxes.contentDescription = getString(R.string.login_code_description, code.userCode)
        code.userCode.forEachIndexed { i, ch -> codeBoxes.addView(box(ch, gapAfter = i == code.userCode.length / 2 - 1)) }
        countdown?.cancel()
        countdown = scope.launch {
            for (left in code.expiresInSec downTo 0) {
                status.text = getString(R.string.login_waiting, "%d:%02d".format(left / 60, left % 60))
                delay(1_000)
            }
        }
    }

    private fun box(ch: Char, gapAfter: Boolean) = TextView(this).apply {
        text = ch.toString()
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        setTypeface(typeface, Typeface.BOLD)
        setBackgroundResource(R.drawable.code_box)
        importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(62)).apply { marginEnd = dp(if (gapAfter) 21 else 7) }
    }

    private fun stepOpen(): CharSequence {
        val host = getString(R.string.login_activate_host)
        val text = getString(R.string.login_step_open, host)
        val start = text.indexOf(host)
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(getColor(R.color.purple_light)), start, start + host.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, start + host.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val RETRY_MS = 5_000L
        const val TAG = "PurpleTV"
    }
}
