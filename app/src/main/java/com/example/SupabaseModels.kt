package com.example

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.UUID

@JsonClass(generateAdapter = true)
data class AuditLogEntry(
    @Json(name = "id") val id: String = UUID.randomUUID().toString(),
    @Json(name = "action") val action: String, // 'CRIACAO', 'EDICAO', 'EXCLUSAO'
    @Json(name = "table_name") val tableName: String = "rainfall_logs",
    @Json(name = "record_id") val recordId: String? = null,
    @Json(name = "farm_name") val farmName: String? = null,
    @Json(name = "performed_by") val performedBy: String,
    @Json(name = "details") val details: String,
    @Json(name = "old_data") val oldData: String? = null,
    @Json(name = "new_data") val newData: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

data class SupabaseHealthStatus(
    val isOnline: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val rlsBlocked: Boolean,
    val farmCount: Int,
    val userCount: Int,
    val logCount: Int,
    val message: String
)
