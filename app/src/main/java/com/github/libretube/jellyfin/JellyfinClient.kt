package com.github.libretube.jellyfin

import android.os.Build
import com.github.libretube.BuildConfig
import com.github.libretube.jellyfin.api.JellyfinApi
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.net.URLEncoder

/**
 * HTTP access to the Jellyfin server.
 *
 * Each call sends the `Authorization` header. A URL that cannot send a header
 * (for example an image that Coil loads, or a download) gets the token as `ApiKey`.
 * LibreTube never uses the legacy `api_key` parameter.
 */
object JellyfinClient {
    private const val CLIENT_NAME = "LibreTube"

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                // a call can set its own header, for example the logout with the old token
                if (request.header("Authorization") != null) return@addInterceptor chain.proceed(request)

                chain.proceed(
                    request.newBuilder().header("Authorization", authorizationHeader()).build()
                )
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    // BASIC does not log the headers, thus the token stays out of the log
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
    }

    @Volatile
    private var cachedApi: Pair<String, JellyfinApi>? = null

    val api: JellyfinApi
        get() = apiFor(JellyfinSession.serverUrl)

    fun apiFor(serverUrl: String): JellyfinApi {
        cachedApi?.let { (url, api) -> if (url == serverUrl) return api }

        return Retrofit.Builder()
            .baseUrl("$serverUrl/")
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create<JellyfinApi>()
            .also { cachedApi = serverUrl to it }
    }

    fun reset() {
        cachedApi = null
    }

    /**
     * The value of the `Authorization` header, with the token if the user is logged in.
     */
    fun authorizationHeader(token: String = JellyfinSession.token): String {
        val parts = mutableListOf(
            "Client" to CLIENT_NAME,
            "Device" to Build.MODEL,
            "DeviceId" to JellyfinSession.deviceId,
            "Version" to BuildConfig.VERSION_NAME
        )
        if (token.isNotEmpty()) parts.add("Token" to token)

        return "MediaBrowser " + parts.joinToString(", ") { (key, value) ->
            "$key=\"${URLEncoder.encode(value, "UTF-8")}\""
        }
    }

    fun authHeaders() = mapOf("Authorization" to authorizationHeader())

    /**
     * An absolute URL on the server for [path] with the given query parameters.
     * Parameters with the value `null` are not added.
     */
    fun url(path: String, vararg query: Pair<String, Any?>): String {
        return "${JellyfinSession.serverUrl}/${path.trimStart('/')}".toHttpUrl()
            .newBuilder()
            .apply {
                for ((key, value) in query) {
                    if (value != null) addQueryParameter(key, value.toString())
                }
            }
            .build()
            .toString()
    }

    /**
     * The URL of an image of an item. Jellyfin gives images without a login.
     */
    fun imageUrl(itemId: String, type: String = "Primary", tag: String? = null, maxWidth: Int? = null) =
        url("Items/$itemId/Images/$type", "tag" to tag, "maxWidth" to maxWidth, "quality" to 90)
}
