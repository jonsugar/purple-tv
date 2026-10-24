package com.purpletv.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import com.purpletv.R
import com.purpletv.player.PlayerPrefs
import com.purpletv.twitch.User

/** Profilo: who is signed in, the default quality, the chat preference, and sign-out. */
class ProfilePage(
    private val context: Context,
    private val playerPrefs: PlayerPrefs,
    private val onLogout: () -> Unit,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_profile, null)

    private val avatar: ImageView = view.findViewById(R.id.profile_avatar)
    private val name: TextView = view.findViewById(R.id.profile_name)
    private val quality: TextView = view.findViewById(R.id.profile_quality_value)
    private val chat: TextView = view.findViewById(R.id.profile_chat_value)

    init {
        avatar.clipToOutline = true
        view.findViewById<View>(R.id.profile_logout).setOnClickListener { onLogout() }
        view.findViewById<View>(R.id.profile_quality).setOnClickListener { nextQuality() }
        view.findViewById<View>(R.id.profile_chat).setOnClickListener {
            playerPrefs.chatVisible = !playerPrefs.chatVisible
            chat.text = chatLabel()
        }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        view.findViewById<TextView>(R.id.profile_version).text = context.getString(R.string.app_version, version)
    }

    fun bind(user: User?) {
        name.text = user?.displayName.orEmpty()
        user?.profileImageUrl?.takeIf { it.isNotEmpty() }?.let {
            avatar.load(it) { crossfade(false); transformations(CircleCropTransformation()) }
        }
        quality.text = qualityLabel()
        chat.text = chatLabel()
    }

    private fun chatLabel() = context.getString(if (playerPrefs.chatVisible) R.string.profile_chat_on else R.string.profile_chat_off)

    /** OK walks through the presets: 1080p60, 720p60, 480p, Automatica. */
    private fun nextQuality() {
        val current = PRESETS.indexOfFirst { it.first == playerPrefs.maxHeight && it.second == playerPrefs.preferHighFrameRate }
        val (height, highFps) = PRESETS[(current + 1) % PRESETS.size]
        playerPrefs.maxHeight = height
        playerPrefs.preferHighFrameRate = highFps
        quality.text = qualityLabel()
    }

    private fun qualityLabel(): String = when {
        playerPrefs.isAuto -> context.getString(R.string.quality_auto)
        playerPrefs.preferHighFrameRate && playerPrefs.maxHeight >= HD -> "${playerPrefs.maxHeight}p60"
        else -> "${playerPrefs.maxHeight}p"
    }

    private companion object {
        val PRESETS = listOf(1080 to true, 720 to true, 480 to false, PlayerPrefs.AUTO to true)
        const val HD = 720
    }
}
