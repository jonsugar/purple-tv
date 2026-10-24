package com.purpletv.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import com.purpletv.R
import com.purpletv.auth.TokenStore
import com.purpletv.twitch.User

/** Profilo: who is signed in, whether the ad-free twitch.tv session is set, and sign-out. */
class ProfilePage(
    private val context: Context,
    private val tokens: TokenStore,
    private val onLogout: () -> Unit,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_profile, null)

    private val avatar: ImageView = view.findViewById(R.id.profile_avatar)
    private val name: TextView = view.findViewById(R.id.profile_name)
    private val session: TextView = view.findViewById(R.id.profile_session)

    init {
        avatar.clipToOutline = true
        view.findViewById<View>(R.id.profile_logout).setOnClickListener { onLogout() }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        view.findViewById<TextView>(R.id.profile_version).text = context.getString(R.string.app_version, version)
    }

    fun bind(user: User?) {
        name.text = user?.displayName.orEmpty()
        user?.profileImageUrl?.takeIf { it.isNotEmpty() }?.let {
            avatar.load(it) { crossfade(false); transformations(CircleCropTransformation()) }
        }
        // Only whether the token exists is shown, never the token.
        val active = tokens.sessionAuthToken != null
        session.setText(if (active) R.string.session_active else R.string.session_missing)
        session.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(if (active) ACTIVE_GREEN else INACTIVE_GREY), null, null, null)
    }

    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        val size = (DOT_DP * context.resources.displayMetrics.density).toInt()
        setSize(size, size)
    }

    private companion object {
        const val DOT_DP = 6
        const val ACTIVE_GREEN = 0xFF00C853.toInt()
        const val INACTIVE_GREY = 0xFF6B6B73.toInt()
    }
}
