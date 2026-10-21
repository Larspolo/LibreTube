package com.github.libretube.jellyfin.ui

import android.view.View
import android.widget.ImageView
import androidx.core.view.updateLayoutParams
import com.github.libretube.extensions.dpToPx
import com.github.libretube.jellyfin.api.BaseItem
import com.github.libretube.jellyfin.durationSeconds
import com.github.libretube.jellyfin.resumePositionMs
import com.github.libretube.ui.extensions.setWatchProgressLength
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel

/** Height of a poster divided by its width (posters have the format 2:3). */
private const val POSTER_HEIGHT_RATIO = 1.5f
const val POSTER_CORNER_DP = 4f

/**
 * Give an image view the format of a poster. LibreTube made the view for a square channel avatar,
 * and a poster in a square view has empty space at the sides.
 */
fun ImageView.setPosterSize(widthDp: Float) {
    updateLayoutParams {
        width = widthDp.dpToPx()
        height = (widthDp * POSTER_HEIGHT_RATIO).dpToPx()
    }
    scaleType = ImageView.ScaleType.CENTER_CROP
}

/**
 * Show a poster with rounded corners instead of the round channel avatar.
 */
fun ShapeableImageView.showAsPoster(widthDp: Float) {
    setPosterSize(widthDp)
    shapeAppearanceModel = ShapeAppearanceModel.builder()
        .setAllCornerSizes(POSTER_CORNER_DP.dpToPx().toFloat())
        .build()
}

/**
 * The progress bar under a thumbnail. A watched item gets a full bar, like a watched YouTube video.
 * Jellyfin sets the position back to 0 when an item is watched, thus the position alone is not enough.
 */
fun View.setJellyfinWatchProgress(item: BaseItem) {
    if (item.userData?.played == true) {
        // a position of 1 s in a duration of 1 s gives a full bar
        setWatchProgressLength(1000L, 1L)
    } else {
        setWatchProgressLength(item.resumePositionMs, item.durationSeconds)
    }
}
