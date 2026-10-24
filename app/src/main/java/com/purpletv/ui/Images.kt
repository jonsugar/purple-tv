package com.purpletv.ui

import android.widget.ImageView
import coil.dispose

/** Empties a recycled image view, cancelling a load still in flight so it cannot land later. */
fun ImageView.clear() {
    dispose()
    setImageDrawable(null)
}
