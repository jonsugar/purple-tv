package com.purpletv.ui

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import coil.dispose
import coil.imageLoader
import coil.load
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.ViewSizeResolver
import coil.transform.CircleCropTransformation
import coil.transition.TransitionTarget
import com.purpletv.R

/** Empties a recycled image view, cancelling a load still in flight so it cannot land later. */
fun ImageView.clear() {
    (getTag(R.id.card_load) as? Disposable)?.dispose()
    setTag(R.id.card_load, null)
    dispose()
    setImageDrawable(null)
}

fun ImageView.loadAccountAvatar(url: String?) {
    if (url.isNullOrEmpty()) clear()
    else load(url) { transformations(CircleCropTransformation()) }
}

/**
 * Loads a card's picture. Coil ties a plain load to the view's window: a card scrolled off screen
 * cancels it and, back on screen, runs it again from the start, which took over half of the main
 * thread while Home scrolled. A card keeps its picture instead, until it loads another or is cleared.
 */
fun ImageView.loadCard(url: String, circle: Boolean = false) {
    (getTag(R.id.card_load) as? Disposable)?.dispose()
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(ViewSizeResolver(this))
        .target(CardTarget(this))
        .apply { if (circle) transformations(CircleCropTransformation()) }
        .build()
    setTag(R.id.card_load, context.imageLoader.enqueue(request))
}

/** Not a ViewTarget, so the load does not follow the window; still fades in like the others. */
private class CardTarget(override val view: ImageView) : TransitionTarget {
    override val drawable: Drawable? get() = view.drawable

    override fun onStart(placeholder: Drawable?) = view.setImageDrawable(placeholder)

    override fun onError(error: Drawable?) = view.setImageDrawable(error)

    override fun onSuccess(result: Drawable) {
        view.setImageDrawable(result)
        // The crossfade waits for a start that a ViewTarget would give it.
        (result as? Animatable)?.start()
    }
}
