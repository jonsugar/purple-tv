package com.purpletv.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** True when the next picture has a different size from the one the decoder was set up for. */
fun needsFreshDecoder(old: Format, new: Format): Boolean = old.width != new.width || old.height != new.height

/**
 * Restarts the video decoder on every resolution change instead of reusing it.
 *
 * A reused decoder keeps its 1920x1088 buffers and writes a 720p picture into their corner. The
 * TV's video plane mishandles that crop and shows the picture zoomed past the screen edges,
 * while screenshots (composed by the GPU) look right. A fresh decoder sizes its buffers to the
 * picture. The cost is a brief black frame at each switch.
 */
@OptIn(UnstableApi::class)
class FreshDecoderRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(object : MediaCodecVideoRenderer(
            context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
            enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
        ) {
            override fun canReuseCodec(codecInfo: MediaCodecInfo, oldFormat: Format, newFormat: Format): DecoderReuseEvaluation =
                if (needsFreshDecoder(oldFormat, newFormat)) {
                    DecoderReuseEvaluation(codecInfo.name, oldFormat, newFormat,
                        DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED)
                } else {
                    super.canReuseCodec(codecInfo, oldFormat, newFormat)
                }
        })
    }
}
