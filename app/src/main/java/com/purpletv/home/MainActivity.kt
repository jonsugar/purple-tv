package com.purpletv.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.auth.LoginActivity
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.NavRail
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/** The launcher entry: navigation rail on the left, the selected page on the right. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private val app get() = PurpleApp.instance
    private lateinit var content: FrameLayout
    private lateinit var rail: NavRail
    private lateinit var home: HomePage
    private lateinit var browse: BrowsePage
    private var loaded = false
    private var awaitingLogin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        val avatars = AvatarCache(app.helix)
        home = HomePage(this, app.helix, avatars)
        browse = BrowsePage(this, scope, app.helix, avatars)
        rail = NavRail(findViewById(R.id.nav_rail), ::showScreen)
        showScreen(Screen.HOME)
    }

    override fun onStart() {
        super.onStart()
        home.start()
        when {
            awaitingLogin -> Unit
            app.tokens.accessToken == null -> login()
            !loaded -> load()
        }
    }

    override fun onStop() {
        home.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Activity result API of the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LOGIN) return
        awaitingLogin = false
        if (resultCode == RESULT_OK) { if (!loaded) load() } else finish()
    }

    fun showScreen(screen: Screen) {
        rail.select(screen)
        content.removeAllViews()
        content.addView(when (screen) {
            Screen.HOME -> home.view
            Screen.BROWSE -> browse.view.also { browse.show() }
            else -> comingSoon()
        })
    }

    private fun login() {
        awaitingLogin = true
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
    }

    private fun load() {
        loaded = true
        scope.launch {
            try {
                val me = app.helix.me()
                app.tokens.userId = me.id
                rail.setAvatar(me.profileImageUrl)
                home.load(me.id)
            } catch (e: NotLoggedInException) {
                loaded = false
                login()
            } catch (e: IOException) {
                loaded = false
                home.showError()
            }
        }
    }

    private fun comingSoon(): View = TextView(this).apply {
        setText(R.string.coming_soon)
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.text_secondary))
    }

    private companion object {
        const val REQ_LOGIN = 1
    }
}
