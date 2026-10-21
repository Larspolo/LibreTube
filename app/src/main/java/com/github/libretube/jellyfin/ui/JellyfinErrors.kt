package com.github.libretube.jellyfin.ui

import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.libretube.R
import com.github.libretube.jellyfin.JellyfinSession
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException

private const val HTTP_UNAUTHORIZED = 401

/**
 * Show the error of a Jellyfin call.
 *
 * The answer 401 means that the token is not valid anymore (for example, the user removed
 * the device on the server). LibreTube then removes the login and shows the login form again.
 */
fun Fragment.showJellyfinError(e: Exception) {
    if (e is CancellationException) return
    Log.e("Jellyfin", e.stackTraceToString())
    val context = context ?: return

    if (e is HttpException && e.code() == HTTP_UNAUTHORIZED) {
        JellyfinSession.logout()
        Toast.makeText(context, R.string.jellyfin_session_expired, Toast.LENGTH_LONG).show()
        generateSequence(this) { it.parentFragment }
            .filterIsInstance<JellyfinFragment>()
            .firstOrNull()
            ?.showState()
        return
    }

    val message = e.localizedMessage?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_error)
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
