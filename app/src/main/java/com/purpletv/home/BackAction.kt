package com.purpletv.home

/** What Back does: other pages return to Home, Home returns to its start, and only then the app closes. */
enum class BackAction { GO_HOME, HOME_TOP, EXIT }

fun backAction(screen: Screen, homeAtTop: Boolean): BackAction = when {
    screen != Screen.HOME -> BackAction.GO_HOME
    !homeAtTop -> BackAction.HOME_TOP
    else -> BackAction.EXIT
}
