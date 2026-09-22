package com.example

import retrofit2.Response
import retrofit2.http.*

interface SupabaseApi {

    @GET("rest/v1/farms")
    suspend fun fetchFarms(@Query("select") select: String = "*"): Response<List<Farm>>

    @POST("rest/v1/farms")
    suspend fun upsertFarm(
        @Header("Prefer") prefer: String = "resolution=merge-duplicates",
        @Query("on_conflict") conflict: String = "name",
        @Body farm: Farm
    ): Response<Unit>

    @DELETE("rest/v1/farms")
    suspend fun deleteFarm(@Query("name") nameFilter: String): Response<Unit>

    @GET("rest/v1/users")
    suspend fun fetchUsers(@Query("select") select: String = "*"): Response<List<UserAccount>>

    @GET("rest/v1/users")
    suspend fun authenticateUser(
        @Query("select") select: String = "*",
        @Query("or") orFilter: String
    ): Response<List<UserAccount>>

    @POST("rest/v1/users")
    suspend fun upsertUser(
        @Header("Prefer") prefer: String = "resolution=merge-duplicates",
        @Query("on_conflict") conflict: String = "username",
        @Body user: UserAccount
    ): Response<Unit>

    @DELETE("rest/v1/users")
    suspend fun deleteUser(@Query("username") usernameFilter: String): Response<Unit>

    @GET("rest/v1/rainfall_logs")
    suspend fun fetchLogs(@Query("select") select: String = "*"): Response<List<RainfallLog>>

    @POST("rest/v1/rainfall_logs")
    suspend fun upsertLog(
        @Header("Prefer") prefer: String = "resolution=merge-duplicates",
        @Query("on_conflict") conflict: String = "id",
        @Body payload: Any
    ): Response<Unit>

    @DELETE("rest/v1/rainfall_logs")
    suspend fun deleteLog(@Query("id") idFilter: String): Response<Unit>

    @POST("rest/v1/audit_logs")
    suspend fun recordAuditLog(
        @Header("Prefer") prefer: String = "resolution=merge-duplicates",
        @Body entry: AuditLogEntry
    ): Response<Unit>

    @GET("rest/v1/audit_logs")
    suspend fun fetchAuditLogs(
        @Query("select") select: String = "*",
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 100
    ): Response<List<AuditLogEntry>>
    
    // Probe para diagnóstico
    @POST("rest/v1/farms")
    suspend fun probeWrite(
        @Header("Prefer") prefer: String = "resolution=merge-duplicates",
        @Query("on_conflict") conflict: String = "name",
        @Body farm: Map<String, String>
    ): Response<Unit>
}
