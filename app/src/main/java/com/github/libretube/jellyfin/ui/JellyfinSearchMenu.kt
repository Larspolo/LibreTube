package com.github.libretube.jellyfin.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import com.github.libretube.R
import com.github.libretube.jellyfin.JellyfinSession

/**
 * On the Jellyfin pages, the search of the toolbar searches in Jellyfin instead of YouTube.
 *
 * @param showSearchAction false on the search page itself
 */
class JellyfinSearchMenu(
    private val fragment: Fragment,
    private val showSearchAction: Boolean = true
) : MenuProvider {
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        if (showSearchAction && JellyfinSession.isLoggedIn) {
            menuInflater.inflate(R.menu.jellyfin_search_menu, menu)
        }
    }

    override fun onPrepareMenu(menu: Menu) {
        // the YouTube search of the main activity
        if (JellyfinSession.isLoggedIn) menu.findItem(R.id.action_search)?.isVisible = false
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.jellyfin_search) return false
        fragment.findNavController().navigate(R.id.jellyfinSearchFragment)
        return true
    }

    companion object {
        /**
         * Add the menu while the view of [fragment] is resumed.
         */
        fun addTo(fragment: Fragment, showSearchAction: Boolean = true) {
            fragment.requireActivity().addMenuProvider(
                JellyfinSearchMenu(fragment, showSearchAction),
                fragment.viewLifecycleOwner,
                Lifecycle.State.RESUMED
            )
        }
    }
}
