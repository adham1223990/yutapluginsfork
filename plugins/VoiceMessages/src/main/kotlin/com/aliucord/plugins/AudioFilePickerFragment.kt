package com.aliucord.plugins

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment

class AudioFilePickerFragment : Fragment() {
    private var launched = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        launched = state?.getBoolean("launched") ?: false
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("launched", launched)
        super.onSaveInstanceState(state)
    }

    fun open() {
        if (launched) return
        launched = true
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                },
                REQUEST_CODE,
            )
        } catch (error: RuntimeException) {
            VoiceMessages.instance?.onAudioFilePicked(null)
            removeSelf()
            throw error
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        VoiceMessages.instance?.onAudioFilePicked(if (resultCode == Activity.RESULT_OK) data?.data else null)
        removeSelf()
    }

    private fun removeSelf() {
        if (!isAdded) return
        val manager = parentFragmentManager
        if (!manager.isDestroyed) manager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    companion object {
        private const val REQUEST_CODE = 4832
    }
}
