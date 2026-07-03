package ai.goyo.wazak

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StoredMalangi(
    val localId: String = UUID.randomUUID().toString(),
    val remoteId: String? = null,
    val name: String,
    val imagePath: String,
    val imageFileName: String? = null,
    val soundPaths: List<String> = emptyList(),
    val soundFileNames: List<String> = emptyList(),
    val isDefault: Boolean = false,
    val marketplaceId: String? = null,
    val backupClientKey: String = UUID.randomUUID().toString()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("localId", localId)
        put("remoteId", remoteId)
        put("name", name)
        put("imagePath", imagePath)
        put("imageFileName", imageFileName)
        put("soundPaths", JSONArray(soundPaths))
        put("soundFileNames", JSONArray(soundFileNames))
        put("isDefault", isDefault)
        put("marketplaceId", marketplaceId)
        put("backupClientKey", backupClientKey)
    }

    companion object {
        fun fromJson(json: JSONObject): StoredMalangi = StoredMalangi(
            localId = json.optString("localId").ifBlank { UUID.randomUUID().toString() },
            remoteId = json.optNullableString("remoteId"),
            name = json.optString("name", "말랑이"),
            imagePath = json.optString("imagePath"),
            imageFileName = json.optNullableString("imageFileName"),
            soundPaths = json.optStringList("soundPaths"),
            soundFileNames = json.optStringList("soundFileNames").ifEmpty { json.optStringList("soundPaths").map(::displayName) },
            isDefault = json.optBoolean("isDefault", false),
            marketplaceId = json.optNullableString("marketplaceId"),
            backupClientKey = json.optString("backupClientKey").ifBlank { UUID.randomUUID().toString() }
        )
    }
}

data class AuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val userId: String,
    val email: String?,
    val displayName: String?,
    val avatarUrl: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("accessToken", accessToken)
        put("refreshToken", refreshToken)
        put("userId", userId)
        put("email", email)
        put("displayName", displayName)
        put("avatarUrl", avatarUrl)
    }

    companion object {
        fun fromJson(json: JSONObject): AuthSession = AuthSession(
            accessToken = json.optString("accessToken"),
            refreshToken = json.optNullableString("refreshToken"),
            userId = json.optString("userId"),
            email = json.optNullableString("email"),
            displayName = json.optNullableString("displayName"),
            avatarUrl = json.optNullableString("avatarUrl")
        )
    }
}

data class MarketplaceMalangi(
    val id: String,
    val ownerId: String?,
    val ownerEmail: String?,
    val ownerName: String?,
    val name: String,
    val imageURL: String,
    val imageFileName: String?,
    val soundURLs: List<String>,
    val soundFileNames: List<String>,
    val downloadsCount: Int,
    val isBuiltIn: Boolean = false
) {
    fun toStored(): StoredMalangi = StoredMalangi(
        remoteId = if (isBuiltIn) id else null,
        name = name,
        imagePath = imageURL,
        imageFileName = imageFileName,
        soundPaths = soundURLs,
        soundFileNames = soundFileNames,
        isDefault = isBuiltIn,
        marketplaceId = if (isBuiltIn) null else id
    )
}

fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
}

fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

fun displayName(path: String): String = path.substringAfterLast('/').substringBefore('?').ifBlank { "sound" }
