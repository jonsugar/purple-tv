package com.purpletv.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import com.purpletv.R
import com.purpletv.net.DebugMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** An overlay independent of screen content and player controls. */
internal class DebugIndicator(app: Application, private val debugMode: DebugMode) :
    Application.ActivityLifecycleCallbacks {
    private val indicators = mutableMapOf<Activity, View>()

    init {
        app.registerActivityLifecycleCallbacks(this)
        MainScope().launch {
            debugMode.state.collect { state -> indicators.values.forEach { render(it, state) } }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val indicator = activity.layoutInflater.inflate(R.layout.debug_indicator, content, false)
        render(indicator, debugMode.state.value)
        content.addView(indicator)
        indicators[activity] = indicator
    }

    override fun onActivityStopped(activity: Activity) {
        indicators.remove(activity)?.let { indicator ->
            activity.findViewById<FrameLayout>(android.R.id.content).removeView(indicator)
        }
    }

    private fun render(indicator: View, state: DebugMode.State) {
        indicator.visibility = if (state.enabled) View.VISIBLE else View.GONE
        indicator.findViewById<View>(R.id.debug_flag_networking).visibility =
            if (state.networkingDisabled) View.VISIBLE else View.GONE
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
