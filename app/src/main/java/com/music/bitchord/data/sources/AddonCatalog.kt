package com.music.bitchord.data.sources

import android.content.Context
import com.music.bitchord.BuildConfig
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** One curated add-on entry received from BitFuse's versioned catalog. */
@Serializable
data class ManagedAddonEntry(
    val id: String,
    val name: String,
    @SerialName("base_url") val baseUrl: String,
)

@Serializable
private data class CatalogResponse(
    val revision: Long = 0,
    val addons: List<ManagedAddonEntry> = emptyList(),
    val accessToken: String? = null,
    val error: String? = null,
)

@Serializable
private data class ActivationRequest(val password: String)

private class CatalogUnauthorizedException : IOException("Device authorization was revoked.")

/** Enrollment and catalog sync. The Telegram bot credential never exists in this app. */
object AddonCatalog {
    private const val PREFS = "bitchord_addon_catalog"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_REVISION = "catalog_revision"
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) = EncryptedPrefs.open(
        context.applicationContext,
        PREFS,
        "${PREFS}_plain",
    )

    fun isEnrolled(context: Context): Boolean = prefs(context).getString(KEY_DEVICE_TOKEN, null) != null

    suspend fun activate(context: Context, password: String): Int = withContext(Dispatchers.IO) {
        require(password.isNotBlank()) { "Enter the access password." }
        val response = request(
            path = "/v1/activate",
            method = "POST",
            body = json.encodeToString(ActivationRequest.serializer(), ActivationRequest(password)),
        )
        val token = response.accessToken?.takeIf(String::isNotBlank)
            ?: throw IOException(response.error ?: "The catalog did not issue a device token.")
        prefs(context).edit()
            .putString(KEY_DEVICE_TOKEN, token)
            .putLong(KEY_REVISION, response.revision)
            .apply()
        SourceRegistry.syncManagedAddons(response.addons)
        AddonCatalogSync.schedule(context.applicationContext)
        response.addons.size
    }

    suspend fun sync(context: Context): Int = withContext(Dispatchers.IO) {
        val token = prefs(context).getString(KEY_DEVICE_TOKEN, null)
            ?: throw IOException("This device is not connected to the add-on catalog.")
        val response = try {
            request(path = "/v1/catalog", method = "GET", token = token)
        } catch (unauthorized: CatalogUnauthorizedException) {
            prefs(context).edit().remove(KEY_DEVICE_TOKEN).remove(KEY_REVISION).apply()
            SourceRegistry.syncManagedAddons(emptyList())
            AddonCatalogSync.cancel(context.applicationContext)
            throw IOException("Catalog access expired. Enter the password again to reconnect.")
        }
        if (response.error != null) throw IOException(response.error)
        // Apply even at the same revision, so a restored or partially migrated
        // local registry repairs itself from the authoritative snapshot.
        SourceRegistry.syncManagedAddons(response.addons)
        prefs(context).edit().putLong(KEY_REVISION, response.revision).apply()
        response.addons.size
    }

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        val store = prefs(context)
        val token = store.getString(KEY_DEVICE_TOKEN, null)
        if (token != null && server.isNotBlank()) {
            runCatching { request("/v1/device", "DELETE", token = token) }
        }
        store.edit().remove(KEY_DEVICE_TOKEN).remove(KEY_REVISION).apply()
        SourceRegistry.syncManagedAddons(emptyList())
        AddonCatalogSync.cancel(context.applicationContext)
    }

    private val server: String
        get() = BuildConfig.ADDON_CATALOG_SERVER.trim().trimEnd('/').also { value ->
            require(value.isNotBlank()) { "The add-on catalog service is not configured in this build." }
            require(value.startsWith("https://", ignoreCase = true)) {
                "The add-on catalog requires an HTTPS address."
            }
        }

    private fun request(
        path: String,
        method: String,
        body: String? = null,
        token: String? = null,
    ): CatalogResponse {
        val builder = Request.Builder().url(server + path)
            .header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post((body ?: "{}").toRequestBody(mediaType))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        Http.client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val decoded = runCatching { json.decodeFromString<CatalogResponse>(text) }
                .getOrElse { throw IOException("The add-on catalog returned an unreadable response.", it) }
            if (!response.isSuccessful) {
                if (response.code == 401 && path == "/v1/catalog") throw CatalogUnauthorizedException()
                throw IOException(decoded.error ?: "Catalog request failed (${response.code}).")
            }
            return decoded
        }
    }
}

/** Six-hour background refresh plus one catch-up when the app launches. */
object AddonCatalogSync {
    private const val PERIODIC = "managed-addon-catalog-periodic"
    private const val NOW = "managed-addon-catalog-now"

    fun init(context: Context) {
        if (!AddonCatalog.isEnrolled(context)) return
        schedule(context)
        checkNow(context)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AddonCatalogWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AddonCatalogWorker>().build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class AddonCatalogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AddonCatalog.sync(applicationContext)
        Result.success()
    } catch (_: Exception) {
        when {
            !AddonCatalog.isEnrolled(applicationContext) -> Result.failure()
            runAttemptCount < 5 -> Result.retry()
            else -> Result.failure()
        }
    }
}
