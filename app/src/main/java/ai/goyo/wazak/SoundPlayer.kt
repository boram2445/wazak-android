package ai.goyo.wazak

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.URL
import java.security.MessageDigest

class SoundPlayer(private val cacheDir: File) {
    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun play(path: String) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            playRemote(path)
        } else {
            startLocal(path)
        }
    }

    fun play(file: File) = play(file.absolutePath)

    private fun playRemote(url: String) {
        val cached = cacheFileFor(url)
        if (cached.exists()) {
            startLocal(cached.absolutePath)
            return
        }
        Thread {
            val downloaded = runCatching {
                val tmp = File(cached.parentFile, "${cached.name}.tmp")
                URL(url).openStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.renameTo(cached)
                cached
            }.onFailure {
                Log.w("WazakSound", "download failed url=$url", it)
            }.getOrNull()
            if (downloaded != null) {
                mainHandler.post { startLocal(downloaded.absolutePath) }
            }
        }.start()
    }

    private fun startLocal(absPath: String) {
        val file = File(absPath)
        if (!file.exists()) return
        releaseCurrent()
        val nextPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(file.absolutePath)
            setVolume(1f, 1f)
            setOnCompletionListener { mp ->
                mp.release()
                if (player === mp) clearCurrentReferences()
            }
            setOnPreparedListener { it.start() }
            setOnErrorListener { mp, what, extra ->
                Log.w("WazakSound", "MediaPlayer error what=$what extra=$extra path=$absPath")
                mp.release()
                if (player === mp) clearCurrentReferences()
                true
            }
            prepareAsync()
        }
        player = nextPlayer
        enhancer = runCatching {
            LoudnessEnhancer(nextPlayer.audioSessionId).apply {
                setTargetGain(TARGET_GAIN_MB)
                enabled = true
            }
        }.onFailure {
            Log.w("WazakSound", "LoudnessEnhancer unavailable", it)
        }.getOrNull()
    }

    /** Releases the current player + enhancer (used when replacing/stopping playback). */
    private fun releaseCurrent() {
        player?.release()
        clearCurrentReferences()
    }

    /** Drops references to an already-released player + its enhancer. */
    private fun clearCurrentReferences() {
        enhancer?.release()
        enhancer = null
        player = null
    }

    private fun cacheFileFor(url: String): File {
        val dir = File(cacheDir, "sound-cache").apply { mkdirs() }
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }
        val extension = url.substringAfterLast('.', "mp3").substringBefore('?').filter(Char::isLetterOrDigit).ifBlank { "mp3" }
        return File(dir, "$hash.$extension")
    }

    fun release() {
        releaseCurrent()
    }

    companion object {
        // +12dB boost on top of the source's native loudness.
        private const val TARGET_GAIN_MB = 1200
    }
}
