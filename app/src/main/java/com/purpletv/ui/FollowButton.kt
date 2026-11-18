package com.purpletv.ui

import android.app.AlertDialog
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** One request at a time. Removing a follow requires confirmation. */
class FollowButton(
    private val view: View,
    private val scope: CoroutineScope,
    private val api: FollowApi,
    private val account: () -> String?,
    private val onAuthRequired: () -> Unit,
    private val onLabelChanged: () -> Unit = {},
    private val pinButton: PinButton? = null,
    private val onConfirmationChanged: (Boolean) -> Unit = {},
) {
    private var job: Job? = null
    private var generation = 0
    private var target: Pair<FollowKind, String>? = null
    private var targetName = ""
    private var confirmation: AlertDialog? = null
    private var following = false
    private var busy = false
    private var known = false
    private var boundAccount: String? = null

    init {
        view.setOnClickListener {
            if (busy || confirmation != null) return@setOnClickListener
            when {
                !known -> load()
                following -> confirmUnfollow()
                else -> writeFollow(follow = true)
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { confirmation?.dismiss() }
        })
        render()
    }

    fun bind(kind: FollowKind, id: String, name: String) {
        targetName = name
        if (target == (kind to id) && boundAccount == account() && (busy || confirmation != null)) return
        generation++
        confirmation?.dismiss()
        job?.cancel()
        target = kind to id
        boundAccount = account()
        following = false
        known = false
        load()
    }

    fun clear() {
        generation++
        confirmation?.dismiss()
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

    private fun confirmUnfollow() {
        if (boundAccount == null || boundAccount != account()) { onAuthRequired(); return }
        val request = generation
        val dialog = AlertDialog.Builder(view.context, R.style.PurpleDialog)
            .setTitle(view.context.getString(R.string.unfollow_title, targetName))
            .setNegativeButton(R.string.logout_cancel, null)
            .setPositiveButton(R.string.unfollow) { _, _ ->
                if (request == generation && !busy && following) writeFollow(follow = false)
            }
            .create()
        confirmation = dialog
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
        dialog.setOnDismissListener {
            if (confirmation === dialog) confirmation = null
            onConfirmationChanged(false)
            if (request == generation && view.isShown) view.requestFocus()
        }
        onConfirmationChanged(true)
        dialog.show()
    }

    private fun writeFollow(follow: Boolean) {
        val (kind, id) = target ?: return
        val owner = boundAccount
        if (owner == null || owner != account()) { onAuthRequired(); return }
        val request = generation
        busy = true
        render()
        job = scope.launch {
            try {
                if (follow) api.follow(kind, id)
                // The write may already have reached Twitch when the viewer leaves this screen.
                else withContext(NonCancellable) { api.unfollow(kind, id, owner) }
                ensureActive()
                if (request != generation || boundAccount != account()) return@launch
                following = follow
            } catch (e: IOException) {
                if (request != generation || boundAccount != account()) return@launch
                if (e is NotLoggedInException) onAuthRequired()
                else Toast.makeText(view.context, if (follow) R.string.follow_error else R.string.unfollow_error, Toast.LENGTH_LONG).show()
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
        view.isClickable = target != null && !busy
        view.isSelected = following
        view.alpha = if (busy || target == null) 0.5f else 1f
        (view as? TextView)?.text = label
        (view as? ImageView)?.setImageResource(if (following) R.drawable.ic_followed else R.drawable.ic_following)
        pinButton?.bind(target, targetName, known && following && boundAccount == account(), busy)
        onLabelChanged()
    }
}
