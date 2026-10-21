package com.github.libretube.jellyfin.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.DialogDownloadBinding
import com.github.libretube.databinding.JellyfinWarningCardBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.jellyfin.DownloadCheck
import com.github.libretube.jellyfin.JellyfinDeviceProfile
import com.github.libretube.jellyfin.JellyfinRepository
import com.github.libretube.jellyfin.api.MediaStream
import com.github.libretube.parcelable.DownloadData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Download dialog for a Jellyfin item.
 *
 * The YouTube dialog asks for video, audio and subtitles separately. A Jellyfin download is
 * one file that holds all of them, thus this dialog shows one file with its size,
 * and the audio and subtitles as "in the file". It uses the same layout.
 */
class JellyfinDownloadDialog : DialogFragment() {
    private lateinit var videoId: String
    private lateinit var binding: DialogDownloadBinding
    private lateinit var warning: JellyfinWarningCardBinding
    private var file: PipedStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId = requireArguments().getString(IntentData.videoId)!!
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogDownloadBinding.inflate(layoutInflater)
        warning = JellyfinWarningCardBinding.inflate(layoutInflater, binding.root, false)
        binding.root.addView(warning.root, 2)

        binding.videoSpinner.items = listOf(getString(R.string.jellyfin_loading))
        listOf(binding.videoSpinner, binding.audioSpinner, binding.subtitleSpinner).forEach {
            it.isEnabled = false
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.download)
            .setView(binding.root)
            .setPositiveButton(R.string.download, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        val downloadButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        downloadButton.isEnabled = false
        downloadButton.setOnClickListener { startDownload() }

        loadFile(dialog)
        return dialog
    }

    private fun loadFile(dialog: AlertDialog) = lifecycleScope.launch {
        val streams = try {
            withContext(Dispatchers.IO) { MediaServiceRepository.instance.getStreams(videoId) }
        } catch (e: Exception) {
            Log.e(TAG(), e.stackTraceToString())
            showWarning(e.localizedMessage.orEmpty())
            return@launch
        }
        val file = streams.videoStreams.firstOrNull()
        if (file?.url == null) {
            showWarning(getString(R.string.jellyfin_download_not_possible))
            return@launch
        }
        this@JellyfinDownloadDialog.file = file

        binding.videoTitle.text = streams.title
        val fileInfo = listOfNotNull(
            file.quality,
            file.format,
            file.height?.let { "${it}p" },
            file.contentLength.takeIf { it > 0 }?.formatAsFileSize()
        )
        binding.videoSpinner.items = listOf(fileInfo.joinToString(" · "))

        val mediaStreams = JellyfinRepository.cachedItem(videoId)?.mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
        val audioStreams = mediaStreams.filter { it.type == MediaStream.TYPE_AUDIO }
        val subtitleStreams = mediaStreams.filter { it.type == MediaStream.TYPE_SUBTITLE && !it.isExternal }
        binding.audioSpinner.items = listOf(inFileText(audioStreams.mapNotNull { languageName(it) }, audioStreams.size))
        binding.subtitleSpinner.items = listOf(inFileText(emptyList(), subtitleStreams.size))
        binding.subtitleSpinner.isVisible = subtitleStreams.isNotEmpty()

        binding.alreadyDownloadedCard.isVisible = withContext(Dispatchers.IO) {
            DatabaseHolder.Database.downloadDao().exists(videoId)
        }

        // the downloader asks for the file in parts; without "206 Partial Content" the file breaks
        val check = withContext(Dispatchers.IO) { JellyfinRepository.checkDownload(file.url!!) }
        if (check != DownloadCheck.OK) {
            showWarning(getString(downloadCheckMessage(check)))
            return@launch
        }

        // without FFmpeg, the phone cannot play some audio codecs (for example DTS) itself
        val playableAudio = audioStreams.any { stream ->
            stream.codec?.lowercase()?.let { it in JellyfinDeviceProfile.audioCodecs } == true
        }
        if (audioStreams.isNotEmpty() && !playableAudio) {
            val codecs = audioStreams.mapNotNull { it.codec?.uppercase(Locale.ROOT) }.distinct().joinToString()
            showWarning(getString(R.string.jellyfin_download_audio_warning, codecs))
        }

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = true
    }

    private fun languageName(stream: MediaStream): String? {
        val code = stream.language ?: return null
        return Locale.forLanguageTag(code).displayLanguage.takeIf { it.isNotBlank() } ?: code
    }

    private fun inFileText(languages: List<String>, trackCount: Int): String {
        if (trackCount == 0) return getString(R.string.jellyfin_none)
        val detail = languages.distinct().joinToString().ifEmpty {
            resources.getQuantityString(R.plurals.jellyfin_tracks, trackCount, trackCount)
        }
        return getString(R.string.jellyfin_in_file, detail)
    }

    private fun showWarning(text: String) {
        warning.warningText.text = text
        warning.root.isVisible = true
    }

    private fun startDownload() {
        val file = file ?: return
        DownloadHelper.startDownloadService(
            requireContext(),
            DownloadData(
                videoId = videoId,
                videoFormat = file.format,
                videoQuality = file.quality,
                audioFormat = null,
                audioQuality = null,
                audioLanguage = null,
                subtitleCode = null
            )
        )
        dismiss()
    }

    companion object {
        fun downloadCheckMessage(check: DownloadCheck) = when (check) {
            DownloadCheck.NOT_ALLOWED -> R.string.jellyfin_download_not_allowed
            DownloadCheck.NO_RESUME -> R.string.jellyfin_download_no_resume
            else -> R.string.jellyfin_download_not_possible
        }

        fun show(fragmentManager: FragmentManager, videoId: String) {
            JellyfinDownloadDialog().apply {
                arguments = bundleOf(IntentData.videoId to videoId)
            }.show(fragmentManager, JellyfinDownloadDialog::class.java.name)
        }
    }
}
