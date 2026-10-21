package com.github.libretube.jellyfin

import android.content.Context
import androidx.core.net.toUri
import androidx.navigation.NavDeepLinkRequest
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.ui.activities.MainActivity

object JellyfinNav {
    const val ARG_ITEM_ID = "itemId"

    /**
     * The series page is in the nested graph `jellyfin_nav.xml`. A deep link finds it
     * from every place in the app, also from the player. The link is not exported.
     */
    private const val PARENT_DEEP_LINK = "libretube-jellyfin://parent/"

    /**
     * Open the page of a series or a collection, and minimize the player (like a channel link).
     */
    fun openParent(context: Context, itemId: String) {
        val activity = ContextHelper.tryUnwrapActivity<MainActivity>(context) ?: return
        val request = NavDeepLinkRequest.Builder.fromUri("$PARENT_DEEP_LINK$itemId".toUri()).build()
        activity.navController.navigate(request)

        runCatching {
            activity.runOnPlayerFragment {
                binding.playerMotionLayout.transitionToEnd()
                true
            }
            activity.minimizePlayerContainerLayout()
        }
    }
}
