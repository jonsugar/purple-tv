package com.purpletv.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.purpletv.R
import com.purpletv.twitch.FollowApi
import com.purpletv.twitch.FollowKind
import com.purpletv.twitch.NotLoggedInException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException

/** One request at a time. Existing follows are shown without offering an accidental unfollow. */
class FollowButton(
    private val view: View,
    private val scope: CoroutineScope,
    private val api: FollowApi,
    private val account: () -> String?,
    private val onAuthRequired: () -> Unit,
    private val onLabelChanged: () -> Unit = {},
) {
    private var job: Job? = null
    private var generation = 0
    private var target: Pair<FollowKind, String>? = null
    private var following = false
    private var busy = false
    private var known = false
    private var boundAccount: String? = null

    init {
        view.setOnClickListener { if (!busy && !following) { if (known) follow() else load() } }
        render()
    }

    fun bind(kind: FollowKind, id: String) {
        if (target == (kind to id) && boundAccount == account() && job?.isActive == true) return
        generation++
        job?.cancel()
        target = kind to id
        boundAccount = account()
        following = false
        known = false
        load()
    }

    fun clear() {
        generation++
        job?.cancel()
        target = null
        following = false
        known = false
        busy = false
        render()
    }

    private fun load() {
        val (kind, id) = target ?: return
        val request = generation
        busy = true
        render()
        job = scope.launch {
            try {
                val result = api.isFollowing(kind, id)
                ensureActive()
                if (request != generation || boundAccount != account()) return@launch
                following = result
                known = true
            } catch (e: IOException) {
                if (request != generation || boundAccount != account()) return@launch
                if (e is NotLoggedInException) onAuthRequired()
            } finally {
                if (request == generation) { busy = false; render() }
            }
        }
    }

    private fun follow() {
        val (kind, id) = target ?: return
        if (boundAccount == null || boundAccount != account()) { onAuthRequired(); return }
        val request = generation
        busy = true
        render()
        job = scope.launch {
            try {
                api.follow(kind, id)
                ensureActive()
                if (request != generation || boundAccount != account()) return@launch
                following = true
            } catch (e: IOException) {
                if (request != generation || boundAccount != account()) return@launch
                if (e is NotLoggedInException) onAuthRequired()
                else Toast.makeText(view.context, R.string.follow_error, Toast.LENGTH_LONG).show()
            } finally {
                if (request == generation) { busy = false; render() }
            }
        }
    }

    private fun render() {
        val label = view.context.getString(when {
            busy -> R.string.follow_loading
            !known -> R.string.follow_retry
            following -> R.string.following_state
            else -> R.string.follow
        })
        view.contentDescription = label
        view.isClickable = target != null && !busy && !following
        view.isSelected = following
        view.alpha = if (busy || target == null) 0.5f else 1f
        (view as? TextView)?.text = label
        (view as? ImageView)?.setImageResource(if (following) R.drawable.ic_followed else R.drawable.ic_following)
        onLabelChanged()
    }
}
