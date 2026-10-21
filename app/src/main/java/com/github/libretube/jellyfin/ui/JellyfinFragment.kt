package com.github.libretube.jellyfin.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.libretube.R
import com.github.libretube.databinding.FragmentJellyfinBinding
import com.github.libretube.jellyfin.JellyfinClient
import com.github.libretube.jellyfin.JellyfinSession
import com.github.libretube.jellyfin.TranscodingMode
import com.github.libretube.jellyfin.api.AuthenticateRequest
import com.github.libretube.jellyfin.api.BaseItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException

/**
 * The tab Jellyfin: the login form without a login, else the pages overview, series and movies.
 *
 * The login is in the tab itself and not in the settings of LibreTube,
 * thus no upstream settings file changes.
 */
class JellyfinFragment : Fragment(R.layout.fragment_jellyfin) {
    private var _binding: FragmentJellyfinBinding? = null
    private val binding get() = _binding!!

    private var tabLayoutMediator: TabLayoutMediator? = null

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            if (JellyfinSession.isLoggedIn) menuInflater.inflate(R.menu.jellyfin_menu, menu)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.jellyfin_transcoding -> showTranscodingDialog()
                R.id.jellyfin_switch_server -> logout(forgetServer = true)
                R.id.jellyfin_logout -> logout(forgetServer = false)
                else -> return false
            }
            return true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentJellyfinBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.loginButton.setOnClickListener { login() }
        binding.password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                login()
                true
            } else {
                false
            }
        }

        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)

        showState()
    }

    override fun onDestroyView() {
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        super.onDestroyView()
        _binding = null
    }

    /**
     * Show the login form or the pages, depending on the login state.
     */
    fun showState() {
        val binding = _binding ?: return
        val loggedIn = JellyfinSession.isLoggedIn

        binding.loginContainer.isVisible = !loggedIn
        binding.content.isVisible = loggedIn

        if (loggedIn && binding.pager.adapter == null) {
            // the overview has horizontal rows, thus the pages only change with the tabs
            binding.pager.isUserInputEnabled = false
            binding.pager.adapter = JellyfinPagesAdapter(this)
            tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
                tab.text = getString(PAGE_TITLES[position])
            }.also { it.attach() }
        } else if (!loggedIn) {
            tabLayoutMediator?.detach()
            tabLayoutMediator = null
            binding.pager.adapter = null
            binding.tabLayout.removeAllTabs()

            if (binding.serverUrl.text.isNullOrEmpty()) binding.serverUrl.setText(JellyfinSession.serverUrl)
            if (binding.username.text.isNullOrEmpty()) binding.username.setText(JellyfinSession.username)
        }

        activity?.invalidateMenu()
    }

    private fun login() {
        val serverUrl = JellyfinSession.normalizeServerUrl(binding.serverUrl.text.toString())
        val username = binding.username.text.toString().trim()
        val password = binding.password.text.toString()

        if (serverUrl.toHttpUrlOrNull() == null) {
            binding.serverUrlLayout.error = getString(R.string.jellyfin_invalid_url)
            return
        }
        binding.serverUrlLayout.error = null
        setLoginInProgress(true)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    JellyfinClient.apiFor(serverUrl).authenticate(AuthenticateRequest(username, password))
                }
            } catch (e: Exception) {
                val message = if (e is HttpException && e.code() == HTTP_UNAUTHORIZED) {
                    R.string.jellyfin_login_failed
                } else {
                    R.string.jellyfin_server_unreachable
                }
                binding.serverUrlLayout.error = getString(message)
                setLoginInProgress(false)
                return@launch
            }
            setLoginInProgress(false)

            val user = result.user
            val token = result.accessToken
            if (user == null || token.isNullOrEmpty()) {
                binding.serverUrlLayout.error = getString(R.string.jellyfin_server_unreachable)
                return@launch
            }

            JellyfinSession.login(serverUrl, user.id, user.name ?: username, token)
            binding.password.text = null
            showState()
        }
    }

    private fun setLoginInProgress(inProgress: Boolean) {
        binding.loginButton.isEnabled = !inProgress
        binding.loginProgress.isVisible = inProgress
    }

    private fun logout(forgetServer: Boolean) {
        // revoke the token on the server; the call must use the token before LibreTube removes it
        val api = JellyfinClient.api
        val authorization = JellyfinClient.authorizationHeader()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { api.logout(authorization) }
        }

        if (forgetServer) JellyfinSession.forgetServer() else JellyfinSession.logout()
        _binding?.serverUrl?.setText(JellyfinSession.serverUrl)
        _binding?.username?.setText(JellyfinSession.username)
        showState()
    }

    private fun showTranscodingDialog() {
        val modes = TranscodingMode.entries
        val labels = modes.map {
            getString(
                when (it) {
                    TranscodingMode.NEVER -> R.string.jellyfin_transcoding_never
                    TranscodingMode.AUDIO_ONLY -> R.string.jellyfin_transcoding_audio_only
                    TranscodingMode.ALWAYS -> R.string.jellyfin_transcoding_always
                }
            )
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.jellyfin_transcoding)
            .setSingleChoiceItems(labels, JellyfinSession.transcodingMode.ordinal) { dialog, which ->
                JellyfinSession.transcodingMode = modes[which]
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class JellyfinPagesAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = PAGE_TITLES.size

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> JellyfinHomePage()
            1 -> JellyfinLibraryPage.newInstance(BaseItem.TYPE_SERIES)
            else -> JellyfinLibraryPage.newInstance(BaseItem.TYPE_MOVIE)
        }
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401

        private val PAGE_TITLES = listOf(
            R.string.jellyfin_overview,
            R.string.jellyfin_series,
            R.string.jellyfin_movies_tab
        )
    }
}
