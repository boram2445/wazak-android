package ai.goyo.wazak

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class SupabaseMalangiClient {
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && key.isNotBlank()

    fun googleSignInUrl(pkce: Pkce): String {
        val redirectTo = "wazak://auth/callback"
        return "$baseUrl/auth/v1/authorize?provider=google" +
            "&redirect_to=${redirectTo.urlEncoded()}" +
            "&code_challenge=${pkce.challenge.urlEncoded()}" +
            "&code_challenge_method=s256"
    }

    fun exchangeCodeForSession(code: String, verifier: String): AuthSession {
        val body = JSONObject()
            .put("auth_code", code)
            .put("code_verifier", verifier)
            .toString()
        val json = request("POST", "/auth/v1/token", query = "grant_type=pkce", body = body)
        return json.toAuthSession()
    }

    fun refreshUser(session: AuthSession): AuthSession {
        val user = request("GET", "/auth/v1/user", accessToken = session.accessToken)
        val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
        return session.copy(
            userId = user.optString("id", session.userId),
            email = user.optNullableString("email") ?: session.email,
            displayName = metadata.optNullableString("nickname")
                ?: metadata.optNullableString("full_name")
                ?: metadata.optNullableString("name")
                ?: session.displayName,
            avatarUrl = metadata.optNullableString("avatar_url")
                ?: metadata.optNullableString("picture")
                ?: session.avatarUrl
        )
    }

    fun updateNickname(session: AuthSession, nickname: String): AuthSession {
        val body = JSONObject().put("data", JSONObject().put("nickname", nickname)).toString()
        request("PUT", "/auth/v1/user", accessToken = session.accessToken, body = body)
        return refreshUser(session)
    }

    fun fetchCatalog(): List<StoredMalangi> {
        val rows = requestArray(
            "GET",
            "/rest/v1/malangis",
            query = "select=id,name,image_path,image_file_name,sound_paths,sound_file_names&order=created_at.asc"
        )
        return (0 until rows.length()).map { rows.getJSONObject(it).toStoredMalangi(isDefault = true) }
    }

    fun fetchMarketplace(): List<MarketplaceMalangi> {
        val rows = requestArray(
            "GET",
            "/rest/v1/marketplace_malangis",
            query = "select=id,owner_id,owner_email,owner_name,name,image_url,image_file_name,sound_urls,sound_file_names,downloads_count&is_public=eq.true&order=created_at.desc"
        )
        return (0 until rows.length()).map { rows.getJSONObject(it).toMarketplace(isBuiltIn = false) }
    }

    fun fetchMyPoints(session: AuthSession): Int? {
        val rows = requestArray("GET", "/rest/v1/profiles", query = "select=points&id=eq.${session.userId}", accessToken = session.accessToken)
        return rows.optJSONObject(0)?.optInt("points")
    }

    fun fetchMyBackups(session: AuthSession): List<StoredMalangi> {
        val rows = requestArray(
            "GET",
            "/rest/v1/user_malangis",
            query = "select=id,owner_id,client_key,name,image_url,image_file_name,sound_urls,sound_file_names&owner_id=eq.${session.userId}&order=created_at.asc",
            accessToken = session.accessToken
        )
        return (0 until rows.length()).map {
            val row = rows.getJSONObject(it)
            StoredMalangi(
                name = row.optString("name", "말랑이"),
                imagePath = row.optString("image_url"),
                imageFileName = row.optNullableString("image_file_name"),
                soundPaths = row.optStringList("sound_urls"),
                soundFileNames = row.optStringList("sound_file_names"),
                backupClientKey = row.optString("client_key").ifBlank { UUID.randomUUID().toString() }
            )
        }
    }

    fun backupMalangi(malangi: StoredMalangi, session: AuthSession): StoredMalangi {
        val folder = "backup/${session.userId}/${malangi.backupClientKey}"
        val imageUrl = assetUrl(malangi.imagePath, "$folder/image.png", "image/png", session.accessToken)
        val soundUrls = malangi.soundPaths.mapIndexed { index, path ->
            assetUrl(path, "$folder/sounds/sound-${index + 1}.${soundExtension(path)}", contentType(path), session.accessToken)
        }
        val payload = JSONObject()
            .put("owner_id", session.userId)
            .put("client_key", malangi.backupClientKey)
            .put("name", malangi.name)
            .put("image_url", imageUrl)
            .put("image_file_name", malangi.imageFileName)
            .put("sound_urls", JSONArray(soundUrls))
            .put("sound_file_names", JSONArray(malangi.soundFileNames))
            .put("updated_at", java.time.Instant.now().toString())

        requestArray(
            "POST",
            "/rest/v1/user_malangis",
            query = "select=id,owner_id,client_key,name,image_url,image_file_name,sound_urls,sound_file_names",
            accessToken = session.accessToken,
            body = payload.toString(),
            prefer = "return=representation, resolution=merge-duplicates"
        )
        return malangi
    }

    fun publish(malangi: StoredMalangi, session: AuthSession): MarketplaceMalangi {
        val folder = "marketplace/${session.userId}/${UUID.randomUUID()}"
        val imageUrl = assetUrl(malangi.imagePath, "$folder/image.png", "image/png", session.accessToken)
        val soundUrls = malangi.soundPaths.mapIndexed { index, path ->
            assetUrl(path, "$folder/sounds/sound-${index + 1}-${UUID.randomUUID()}.${soundExtension(path)}", contentType(path), session.accessToken)
        }
        val payload = JSONObject()
            .put("owner_id", session.userId)
            .put("owner_email", session.email)
            .put("owner_name", session.displayName)
            .put("name", malangi.name)
            .put("image_url", imageUrl)
            .put("image_file_name", malangi.imageFileName)
            .put("sound_urls", JSONArray(soundUrls))
            .put("sound_file_names", JSONArray(malangi.soundFileNames))
            .put("is_public", true)

        val existingId = malangi.marketplaceId ?: findMarketplaceId(session, malangi.name)
        val path = "/rest/v1/marketplace_malangis"
        val select = "select=id,owner_id,owner_email,owner_name,name,image_url,image_file_name,sound_urls,sound_file_names,downloads_count"
        val rows = if (existingId == null) {
            requestArray("POST", path, query = select, accessToken = session.accessToken, body = payload.toString(), prefer = "return=representation")
        } else {
            requestArray("PATCH", path, query = "id=eq.$existingId&$select", accessToken = session.accessToken, body = payload.toString(), prefer = "return=representation")
        }
        return rows.getJSONObject(0).toMarketplace(isBuiltIn = false)
    }

    fun unpublish(id: String, session: AuthSession) {
        requestRaw("DELETE", "/rest/v1/marketplace_malangis", query = "id=eq.$id", accessToken = session.accessToken)
    }

    fun downloadMarketplace(id: String, session: AuthSession): Int {
        val body = JSONObject().put("p_malangi_id", id).toString()
        val raw = requestRaw("POST", "/rest/v1/rpc/download_marketplace_malangi", accessToken = session.accessToken, body = body)
        return raw.trim().toIntOrNull() ?: 0
    }

    private fun findMarketplaceId(session: AuthSession, name: String): String? {
        val rows = requestArray(
            "GET",
            "/rest/v1/marketplace_malangis",
            query = "owner_id=eq.${session.userId}&name=eq.${name.urlEncoded()}&select=id&limit=1",
            accessToken = session.accessToken
        )
        return rows.optJSONObject(0)?.optNullableString("id")
    }

    private fun assetUrl(reference: String, objectPath: String, contentType: String, accessToken: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference
        val file = File(reference)
        val path = "/storage/v1/object/malangi-assets/$objectPath"
        requestRaw("POST", path, accessToken = accessToken, bodyBytes = file.readBytes(), contentType = contentType, extraHeaders = mapOf("x-upsert" to "true"))
        return "$baseUrl/storage/v1/object/public/malangi-assets/$objectPath"
    }

    private fun request(
        method: String,
        path: String,
        query: String? = null,
        accessToken: String? = null,
        body: String? = null
    ): JSONObject = JSONObject(requestRaw(method, path, query, accessToken, body))

    private fun requestArray(
        method: String,
        path: String,
        query: String? = null,
        accessToken: String? = null,
        body: String? = null,
        prefer: String? = null
    ): JSONArray = JSONArray(requestRaw(method, path, query, accessToken, body, prefer = prefer))

    private fun requestRaw(
        method: String,
        path: String,
        query: String? = null,
        accessToken: String? = null,
        body: String? = null,
        prefer: String? = null,
        bodyBytes: ByteArray? = null,
        contentType: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val url = URL(baseUrl + path + (query?.let { "?$it" } ?: ""))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("apikey", key)
            setRequestProperty("Authorization", "Bearer ${accessToken ?: key}")
            setRequestProperty("Content-Type", contentType)
            if (prefer != null) setRequestProperty("Prefer", prefer)
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null || bodyBytes != null) {
                doOutput = true
                outputStream.use { it.write(bodyBytes ?: body!!.toByteArray()) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error(text.ifBlank { "Supabase request failed: HTTP $code" })
        return text.ifBlank { "[]" }
    }

    private fun JSONObject.toAuthSession(): AuthSession {
        val user = getJSONObject("user")
        val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
        return AuthSession(
            accessToken = getString("access_token"),
            refreshToken = optNullableString("refresh_token"),
            userId = user.getString("id"),
            email = user.optNullableString("email"),
            displayName = metadata.optNullableString("nickname") ?: metadata.optNullableString("full_name") ?: metadata.optNullableString("name"),
            avatarUrl = metadata.optNullableString("avatar_url") ?: metadata.optNullableString("picture")
        )
    }

    private fun JSONObject.toStoredMalangi(isDefault: Boolean): StoredMalangi = StoredMalangi(
        remoteId = optString("id"),
        name = optString("name", "말랑이"),
        imagePath = optString("image_path"),
        imageFileName = optNullableString("image_file_name"),
        soundPaths = optStringList("sound_paths"),
        soundFileNames = optStringList("sound_file_names"),
        isDefault = isDefault
    )

    private fun JSONObject.toMarketplace(isBuiltIn: Boolean): MarketplaceMalangi = MarketplaceMalangi(
        id = optString("id"),
        ownerId = optNullableString("owner_id"),
        ownerEmail = optNullableString("owner_email"),
        ownerName = optNullableString("owner_name"),
        name = optString("name", "말랑이"),
        imageURL = optString(if (isBuiltIn) "image_path" else "image_url"),
        imageFileName = optNullableString("image_file_name"),
        soundURLs = optStringList(if (isBuiltIn) "sound_paths" else "sound_urls"),
        soundFileNames = optStringList("sound_file_names"),
        downloadsCount = optInt("downloads_count", 0),
        isBuiltIn = isBuiltIn
    )

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "aif", "aiff" -> "audio/aiff"
        else -> "audio/mpeg"
    }

    private fun soundExtension(path: String): String =
        path.substringAfterLast('.', "mp3").filter(Char::isLetterOrDigit).ifBlank { "mp3" }
}

data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        fun create(): Pkce {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            return Pkce(verifier, challenge)
        }
    }
}

private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")
