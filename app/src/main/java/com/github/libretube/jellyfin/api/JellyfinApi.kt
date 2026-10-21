package com.github.libretube.jellyfin.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The Jellyfin endpoints that LibreTube uses.
 *
 * The paths have no slash at the start, thus a server URL with a path
 * (for example `https://example.org/jellyfin`) also works.
 */
interface JellyfinApi {
    @POST("Users/AuthenticateByName")
    suspend fun authenticate(@Body body: AuthenticateRequest): AuthenticationResult

    /**
     * Revoke the token in [authorization] on the server.
     */
    @POST("Sessions/Logout")
    suspend fun logout(@Header("Authorization") authorization: String): Response<Unit>

    @GET("UserItems/Resume")
    suspend fun getResumeItems(
        @Query("userId") userId: String,
        @Query("mediaTypes") mediaTypes: String = "Video",
        @Query("fields") fields: String,
        @Query("limit") limit: Int
    ): ItemsResult

    @GET("Shows/NextUp")
    suspend fun getNextUp(
        @Query("userId") userId: String,
        @Query("fields") fields: String,
        @Query("limit") limit: Int,
        @Query("seriesId") seriesId: String? = null,
        @Query("enableResumable") enableResumable: Boolean = true
    ): ItemsResult

    @GET("Items/Latest")
    suspend fun getLatest(
        @Query("userId") userId: String,
        @Query("includeItemTypes") includeItemTypes: String,
        @Query("fields") fields: String,
        @Query("limit") limit: Int
    ): List<BaseItem>

    @GET("Items")
    suspend fun getItems(
        @Query("userId") userId: String,
        @Query("includeItemTypes") includeItemTypes: String? = null,
        @Query("parentId") parentId: String? = null,
        @Query("recursive") recursive: Boolean? = null,
        @Query("sortBy") sortBy: String? = null,
        @Query("sortOrder") sortOrder: String? = null,
        @Query("filters") filters: String? = null,
        @Query("searchTerm") searchTerm: String? = null,
        @Query("fields") fields: String,
        @Query("startIndex") startIndex: Int? = null,
        @Query("limit") limit: Int? = null
    ): ItemsResult

    @GET("Items/{itemId}")
    suspend fun getItem(
        @Path("itemId") itemId: String,
        @Query("userId") userId: String
    ): BaseItem

    @GET("Shows/{seriesId}/Seasons")
    suspend fun getSeasons(
        @Path("seriesId") seriesId: String,
        @Query("userId") userId: String,
        @Query("fields") fields: String
    ): ItemsResult

    @GET("Shows/{seriesId}/Episodes")
    suspend fun getEpisodes(
        @Path("seriesId") seriesId: String,
        @Query("userId") userId: String,
        @Query("fields") fields: String,
        @Query("seasonId") seasonId: String? = null,
        @Query("startItemId") startItemId: String? = null,
        @Query("limit") limit: Int? = null
    ): ItemsResult

    @POST("Items/{itemId}/PlaybackInfo")
    suspend fun getPlaybackInfo(
        @Path("itemId") itemId: String,
        @Body body: PlaybackInfoRequest
    ): PlaybackInfoResponse

    @GET("MediaSegments/{itemId}")
    suspend fun getMediaSegments(@Path("itemId") itemId: String): MediaSegmentsResult

    @POST("Sessions/Playing")
    suspend fun reportPlaybackStart(@Body body: PlaybackReport): Response<Unit>

    @POST("Sessions/Playing/Progress")
    suspend fun reportPlaybackProgress(@Body body: PlaybackReport): Response<Unit>

    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(@Body body: PlaybackReport): Response<Unit>

    @DELETE("Videos/ActiveEncodings")
    suspend fun stopActiveEncodings(
        @Query("deviceId") deviceId: String,
        @Query("playSessionId") playSessionId: String
    ): Response<Unit>

    @POST("UserPlayedItems/{itemId}")
    suspend fun markPlayed(
        @Path("itemId") itemId: String,
        @Query("userId") userId: String
    ): Response<Unit>

    /**
     * Set the position of an item, for progress that LibreTube saved offline.
     */
    @POST("UserItems/{itemId}/UserData")
    suspend fun updateUserData(
        @Path("itemId") itemId: String,
        @Query("userId") userId: String,
        @Body body: UpdateUserItemDataRequest
    ): Response<Unit>

    /**
     * Mark the item as not watched. Jellyfin also removes the position.
     */
    @DELETE("UserPlayedItems/{itemId}")
    suspend fun markUnplayed(
        @Path("itemId") itemId: String,
        @Query("userId") userId: String
    ): Response<Unit>
}
