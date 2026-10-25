package com.purpletv.home

enum class Screen { HOME, BROWSE, SEARCH, PROFILE }

/** The screen an intent asks for by name; Home for none or an unknown one. */
fun screenFrom(name: String?): Screen = Screen.entries.firstOrNull { it.name == name } ?: Screen.HOME
