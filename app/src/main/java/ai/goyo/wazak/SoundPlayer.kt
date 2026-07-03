package ai.goyo.wazak

import android.media.MediaPlayer
import android.util.Log
import java.io.File

class SoundPlayer {
    private var player: MediaPlayer? = null

    fun play(path: String) {
        player?.release()
        val nextPlayer = MediaPlayer().apply {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                setDataSource(path)
            } else {
                val file = File(path)
                if (!file.exists()) return
                setDataSource(file.absolutePath)
            }
            setVolume(1f, 1f)
            setOnCompletionListener {
                it.release()
                if (player == it) player = null
            }
            setOnPreparedListener { it.start() }
            setOnErrorListener { mp, what, extra ->
                Log.w("WazakSound", "MediaPlayer error what=$what extra=$extra path=$path")
                mp.release()
                if (player == mp) player = null
                true
            }
            prepareAsync()
        }
        player = nextPlayer
    }

    fun play(file: File) = play(file.absolutePath)

    fun release() {
        player?.release()
        player = null
    }
}
