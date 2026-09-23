package com.example

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AppDatabaseManager {
    private const val PREFS_NAME = "itacumbi_agro_database"
    private const val KEY_USERS = "db_users"
    private const val KEY_FARMS = "db_farms"
    private const val KEY_LOGS = "db_logs"

    val initialFarms = emptyList<Farm>()

    fun loadUsers(context: Context): List<UserAccount> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_USERS, null)
        if (jsonString.isNullOrBlank()) {
            saveUsers(context, defaultUsers)
            return defaultUsers
        }
        return try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<UserAccount>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val assigned = if (obj.has("assignedFarmName") && !obj.isNull("assignedFarmName")) {
                    val s = obj.getString("assignedFarmName")
                    if (s.isBlank()) null else s
                } else null

                list.add(
                    UserAccount(
                        username = obj.getString("username"),
                        password = obj.getString("password"),
                        displayName = obj.getString("displayName"),
                        role = try {
                            UserRole.valueOf(obj.getString("role"))
                        } catch (_: Exception) {
                            UserRole.FAZENDA
                        },
                        assignedFarmName = assigned
                    )
                )
            }
            // Ensure essential default accounts exist so existing installs don't get locked out
            val mergedList = list.toMutableList()
            var modified = false
            for (defUser in defaultUsers) {
                val existing = mergedList.find { it.username.equals(defUser.username, ignoreCase = true) }
                if (existing == null) {
                    mergedList.add(defUser)
                    modified = true
                }
            }
            if (modified || mergedList.isEmpty()) {
                saveUsers(context, if (mergedList.isEmpty()) defaultUsers else mergedList)
            }
            if (mergedList.isEmpty()) defaultUsers else mergedList
        } catch (_: Exception) {
            saveUsers(context, defaultUsers)
            defaultUsers
        }
    }

    fun saveUsers(context: Context, users: List<UserAccount>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (u in users) {
            val obj = JSONObject().apply {
                put("username", u.username)
                put("password", u.password)
                put("displayName", u.displayName)
                put("role", u.role.name)
                put("assignedFarmName", u.assignedFarmName ?: JSONObject.NULL)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_USERS, array.toString()).commit()
    }

    fun loadFarms(context: Context): List<Farm> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_FARMS, null)
        if (jsonString.isNullOrBlank()) {
            saveFarms(context, initialFarms)
            return initialFarms
        }
        return try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<Farm>()
            var hadDiagnostic = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val farm = Farm(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    region = obj.getString("region")
                )
                if (farm.isDiagnostic()) {
                    hadDiagnostic = true
                } else {
                    list.add(farm)
                }
            }
            val mergedList = list.toMutableList()
            var modified = hadDiagnostic
            for (defFarm in initialFarms) {
                val existing = mergedList.find { it.name.equals(defFarm.name, ignoreCase = true) }
                if (existing == null) {
                    mergedList.add(defFarm)
                    modified = true
                }
            }
            if (modified || mergedList.isEmpty()) {
                saveFarms(context, if (mergedList.isEmpty()) initialFarms else mergedList)
            }
            if (mergedList.isEmpty()) initialFarms else mergedList
        } catch (_: Exception) {
            saveFarms(context, initialFarms)
            initialFarms
        }
    }

    fun saveFarms(context: Context, farms: List<Farm>) {
        val cleanFarms = farms.filterNot { it.isDiagnostic() }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (f in cleanFarms) {
            val obj = JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("region", f.region)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_FARMS, array.toString()).commit()
    }

    fun loadLogs(context: Context, defaultLogs: List<RainfallLog>): List<RainfallLog> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LOGS)) {
            saveLogs(context, defaultLogs)
            return defaultLogs
        }
        val jsonString = prefs.getString(KEY_LOGS, null)
        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<RainfallLog>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    RainfallLog(
                        id = obj.getString("id"),
                        date = obj.getString("date"),
                        farmName = obj.getString("farmName"),
                        volumeMm = obj.getDouble("volumeMm"),
                        notes = obj.optString("notes", ""),
                        isEdited = obj.optBoolean("isEdited", false),
                        createdAt = run {
                            val raw = if (obj.has("createdAt") && !obj.isNull("createdAt")) obj.getString("createdAt") else null
                            if (raw == "null" || raw.isNullOrBlank()) null else raw
                        },
                        hasPendingSync = obj.optBoolean("hasPendingSync", false)
                    )
                )
            }
            list
        } catch (_: Exception) {
            defaultLogs
        }
    }

    fun saveLogs(context: Context, logs: List<RainfallLog>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (l in logs) {
            val obj = JSONObject().apply {
                put("id", l.id)
                put("date", l.date)
                put("farmName", l.farmName)
                put("volumeMm", l.volumeMm)
                put("notes", l.notes)
                put("isEdited", l.isEdited)
                put("hasPendingSync", l.hasPendingSync)
                if (!l.createdAt.isNullOrBlank() && l.createdAt != "null") put("createdAt", l.createdAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_LOGS, array.toString()).commit()
    }

    fun generateSpreadsheetCsv(context: Context, logs: List<RainfallLog>, farmNameFilter: String? = null): java.io.File {
        val rawLogs = if (farmNameFilter != null) logs.filter { it.farmName == farmNameFilter } else logs
        val filteredLogs = rawLogs.sortedWith(
            compareByDescending<RainfallLog> {
                val parts = it.date.trim().split("/")
                if (parts.size >= 3) {
                    val d = parts[0].toIntOrNull() ?: 0
                    val m = parts[1].toIntOrNull() ?: 0
                    val y = parts[2].toIntOrNull() ?: 0
                    y * 10000L + m * 100L + d
                } else 0L
            }.thenByDescending { it.createdAt ?: "" }
        )
        val sb = StringBuilder()
        // UTF-8 BOM so Excel on Android/Windows opens accents properly
        sb.append('\uFEFF')
        sb.append("ITACUMBI AGRO - RELATÓRIO PLUVIOMÉTRICO CONSOLIDADO\n")
        sb.append("Filtro;${farmNameFilter ?: "Todas as Fazendas"}\n")
        sb.append("Gerado em;${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n")
        sb.append("Total de Registros;${filteredLogs.size}\n")
        val totalVolume = filteredLogs.sumOf { it.volumeMm }
        val daysWithRain = filteredLogs.count { it.volumeMm > 0.0 }
        sb.append("Precipitação Total Acumulada (mm);${String.format(java.util.Locale("pt", "BR"), "%.1f", totalVolume)}\n")
        sb.append("Dias com Chuva Registrada;${daysWithRain}\n\n")

        // Columns header
        sb.append("ID;Data;Fazenda / Unidade;Volume de Chuva (mm);Classificação;Observações\n")
        filteredLogs.forEach { log ->
            val status = when {
                log.volumeMm == 0.0 -> "Estiagem / Sem Chuva"
                log.volumeMm <= 5.0 -> "Chuva Fraca / Garoa"
                log.volumeMm <= 20.0 -> "Chuva Moderada"
                log.volumeMm <= 50.0 -> "Chuva Forte"
                else -> "Temporal Severo"
            }
            val safeNotes = log.notes.replace(";", ",").replace("\n", " ").trim()
            val mmStr = String.format(java.util.Locale("pt", "BR"), "%.1f", log.volumeMm)
            sb.append("${log.id};${log.date};${log.farmName};${mmStr};${status};${safeNotes}\n")
        }

        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val fileName = "relatorio_chuvas_${timeStamp}.csv"
        val file = java.io.File(context.cacheDir, fileName)
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    fun exportFullDatabaseJson(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = JSONObject()
        root.put("database_name", "Itacumbi Agro Database")
        root.put("export_date", java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
        root.put("users", JSONArray(prefs.getString(KEY_USERS, "[]") ?: "[]"))
        root.put("farms", JSONArray(prefs.getString(KEY_FARMS, "[]") ?: "[]"))
        root.put("logs", JSONArray(prefs.getString(KEY_LOGS, "[]") ?: "[]"))
        return root.toString(2)
    }

    fun getDatabaseSizeBytes(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val u = prefs.getString(KEY_USERS, "")?.length ?: 0
        val f = prefs.getString(KEY_FARMS, "")?.length ?: 0
        val l = prefs.getString(KEY_LOGS, "")?.length ?: 0
        return (u + f + l).toLong()
    }

    // --- MÉTODOS ASSÍNCRONOS (COROUTINES) PARA NÃO BLOQUEAR A MAIN THREAD ---
    suspend fun loadUsersAsync(context: Context): List<UserAccount> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadUsers(context) }
    suspend fun saveUsersAsync(context: Context, users: List<UserAccount>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveUsers(context, users) }
    
    suspend fun loadFarmsAsync(context: Context): List<Farm> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadFarms(context) }
    suspend fun saveFarmsAsync(context: Context, farms: List<Farm>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveFarms(context, farms) }
    
    suspend fun loadLogsAsync(context: Context, defaultLogs: List<RainfallLog>): List<RainfallLog> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadLogs(context, defaultLogs) }
    suspend fun saveLogsAsync(context: Context, logs: List<RainfallLog>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveLogs(context, logs) }
}
