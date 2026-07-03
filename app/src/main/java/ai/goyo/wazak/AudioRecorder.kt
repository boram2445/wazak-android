package ai.goyo.wazak

import android.content.Context
import android.media.MediaRecorder
import java.io.File

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAtMillis = 0L

    val isRecording: Boolean
        get() = recorder != null

    fun start(outputFile: File) {
        stopOrDiscard()
        outputFile.parentFile?.mkdirs()

        @Suppress("DEPRECATION")
        val nextRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }

        recorder = nextRecorder
        startedAtMillis = System.currentTimeMillis()
    }

    fun elapsedSeconds(): Long {
        if (recorder == null) return 0
        return (System.currentTimeMillis() - startedAtMillis) / 1000
    }

    fun stop(): Boolean {
        val active = recorder ?: return false
        return try {
            active.stop()
            true
        } finally {
            active.release()
            recorder = null
        }
    }

    fun stopOrDiscard() {
        val active = recorder ?: return
        try {
            active.stop()
        } catch (_: RuntimeException) {
        } finally {
            active.release()
            recorder = null
        }
    }
}
