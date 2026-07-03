package ai.goyo.wazak

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.io.File
import java.util.UUID

class MalangiRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("wazak", Context.MODE_PRIVATE)

    private val rootDir: File
        get() = File(context.filesDir, "malangis").apply { mkdirs() }

    var selectedIndex: Int
        get() = prefs.getInt(KEY_SELECTED_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_SELECTED_INDEX, value).apply()

    var overlayX: Int
        get() = prefs.getInt(KEY_OVERLAY_X, 80)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_X, value).apply()

    var overlayY: Int
        get() = prefs.getInt(KEY_OVERLAY_Y, 220)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_Y, value).apply()

    var authSession: AuthSession?
        get() = prefs.getString(KEY_AUTH_SESSION, null)?.let { AuthSession.fromJson(org.json.JSONObject(it)) }
        set(value) {
            if (value == null) prefs.edit().remove(KEY_AUTH_SESSION).apply()
            else prefs.edit().putString(KEY_AUTH_SESSION, value.toJson().toString()).apply()
        }

    fun all(): List<StoredMalangi> = localMalangis() + remoteCatalog().filter { remote ->
        localMalangis().none { it.remoteId == remote.remoteId || it.imagePath == remote.imagePath }
    }

    fun selected(): StoredMalangi? = all().let { list ->
        if (list.isEmpty()) null else list[selectedIndex.coerceIn(0, list.lastIndex)]
    }

    fun saveRemoteCatalog(malangis: List<StoredMalangi>) {
        prefs.edit().putString(KEY_REMOTE_CATALOG, JSONArray(malangis.map { it.copy(isDefault = true).toJson() }).toString()).apply()
    }

    fun saveLocal(malangi: StoredMalangi) {
        val list = localMalangis().toMutableList()
        val index = list.indexOfFirst { it.localId == malangi.localId || it.imagePath == malangi.imagePath }
        if (index >= 0) list[index] = malangi.copy(isDefault = false) else list.add(malangi.copy(isDefault = false))
        saveLocalList(list)
        selectedIndex = all().indexOfFirst { it.localId == malangi.localId }.coerceAtLeast(0)
    }

    fun deleteCurrent() {
        val current = selected() ?: return
        if (current.isDefault) return
        val list = localMalangis().filterNot { it.localId == current.localId || it.imagePath == current.imagePath }
        current.imagePath.takeIf(::isLocalReference)?.let { File(it).parentFile?.deleteRecursively() }
        saveLocalList(list)
        selectedIndex = selectedIndex.coerceAtMost(all().lastIndex.coerceAtLeast(0))
    }

    fun addFromMarketplace(item: MarketplaceMalangi) {
        val downloaded = item.toStored()
        if (localMalangis().none { it.imagePath == downloaded.imagePath || it.name == downloaded.name }) {
            saveLocal(downloaded.copy(isDefault = false))
        }
    }

    fun createOrUpdate(
        existing: StoredMalangi?,
        name: String,
        imageUri: Uri?,
        soundUris: List<Pair<Uri, String>>?
    ): StoredMalangi {
        val id = existing?.localId ?: UUID.randomUUID().toString()
        val dir = File(rootDir, id).apply { mkdirs() }
        val imagePath = if (imageUri != null) {
            val image = File(dir, "malangi-image")
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                image.outputStream().use { output -> input.copyTo(output) }
            }
            image.absolutePath
        } else {
            existing?.imagePath ?: ""
        }

        val savedSounds = if (soundUris != null) {
            val soundsDir = File(dir, "sounds").apply {
                deleteRecursively()
                mkdirs()
            }
            soundUris.mapIndexed { index, pair ->
                val extension = pair.second.substringAfterLast('.', "m4a").filter(Char::isLetterOrDigit).ifBlank { "m4a" }
                val file = File(soundsDir, "sound-${index + 1}-${UUID.randomUUID()}.$extension")
                context.contentResolver.openInputStream(pair.first)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.absolutePath to pair.second
            }
        } else {
            existing?.soundPaths.orEmpty().zip(existing?.soundFileNames.orEmpty())
        }

        val normalizedName = name.trim().ifBlank { "말랑이 ${localMalangis().size + 1}" }
        val malangi = StoredMalangi(
            localId = id,
            remoteId = existing?.remoteId,
            name = normalizedName,
            imagePath = imagePath,
            imageFileName = imageUri?.let { displayName(it.toString()) } ?: existing?.imageFileName,
            soundPaths = savedSounds.map { it.first },
            soundFileNames = savedSounds.map { it.second },
            marketplaceId = existing?.marketplaceId,
            backupClientKey = existing?.backupClientKey ?: UUID.randomUUID().toString()
        )
        saveLocal(malangi)
        return malangi
    }

    fun addRecordedSound(target: StoredMalangi, recordedFile: File) {
        val dir = File(rootDir, target.localId).apply { mkdirs() }
        val soundsDir = File(dir, "sounds").apply { mkdirs() }
        val destination = File(soundsDir, "recording-${UUID.randomUUID()}.m4a")
        recordedFile.copyTo(destination, overwrite = true)
        saveLocal(target.copy(
            soundPaths = target.soundPaths + destination.absolutePath,
            soundFileNames = target.soundFileNames + "recording.m4a"
        ))
    }

    fun setMarketplaceId(localId: String, marketplaceId: String?) {
        saveLocalList(localMalangis().map {
            if (it.localId == localId) it.copy(marketplaceId = marketplaceId) else it
        })
    }

    private fun localMalangis(): List<StoredMalangi> =
        prefs.getString(KEY_LOCAL_MALANGIS, null)?.let(::decodeList).orEmpty()

    private fun remoteCatalog(): List<StoredMalangi> =
        prefs.getString(KEY_REMOTE_CATALOG, null)?.let(::decodeList).orEmpty().map { it.copy(isDefault = true) }

    private fun saveLocalList(list: List<StoredMalangi>) {
        prefs.edit().putString(KEY_LOCAL_MALANGIS, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    private fun decodeList(raw: String): List<StoredMalangi> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { StoredMalangi.fromJson(array.getJSONObject(it)) }
    }

    companion object {
        private const val KEY_LOCAL_MALANGIS = "localMalangis"
        private const val KEY_REMOTE_CATALOG = "remoteCatalog"
        private const val KEY_SELECTED_INDEX = "selectedIndex"
        private const val KEY_OVERLAY_X = "overlayX"
        private const val KEY_OVERLAY_Y = "overlayY"
        private const val KEY_AUTH_SESSION = "authSession"

        fun isLocalReference(path: String): Boolean = !path.startsWith("http://") && !path.startsWith("https://")
    }
}
