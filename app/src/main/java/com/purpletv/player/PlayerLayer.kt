package com.purpletv.player

/** What covers the video: the tune-in, nothing, the reconnecting message or the offline page. */
enum class PlayerLayer { TUNING, VIDEO, RECONNECTING, OFFLINE }

sealed interface PlayerEvent {
    object Started : PlayerEvent
    object FirstFrame : PlayerEvent
    object Offline : PlayerEvent
    object ConnectionLost : PlayerEvent
}

/** An offline channel stays offline until playback starts again; frames and drops don't change that. */
fun nextLayer(current: PlayerLayer, event: PlayerEvent): PlayerLayer = when {
    event == PlayerEvent.Started -> PlayerLayer.TUNING
    event == PlayerEvent.Offline -> PlayerLayer.OFFLINE
    current == PlayerLayer.OFFLINE -> PlayerLayer.OFFLINE
    event == PlayerEvent.FirstFrame -> PlayerLayer.VIDEO
    else -> PlayerLayer.RECONNECTING
}
