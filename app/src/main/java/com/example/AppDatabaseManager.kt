package com.example

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ParsedCsvResult(
    val detectedFarmName: String?,
    val validLogs: List<RainfallLog>,
    val totalVolumeMm: Double,
    val ignoredEmptyLines: Int,
    val warnings: List<String>
)

object AppDatabaseManager {
    private const val PREFS_NAME = "itacumbi_agro_database"
    private const val KEY_USERS = "db_users"
    private const val KEY_FARMS = "db_farms"
    private const val KEY_LOGS = "db_logs"
    private const val KEY_LOGGED_IN_USER = "db_logged_in_user"

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

    fun saveLoggedInUser(context: Context, user: UserAccount?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (user == null) {
            prefs.edit().remove(KEY_LOGGED_IN_USER).commit()
        } else {
            val obj = JSONObject().apply {
                put("username", user.username)
                put("password", user.password)
                put("displayName", user.displayName)
                put("role", user.role.name)
                put("assignedFarmName", user.assignedFarmName ?: JSONObject.NULL)
            }
            prefs.edit().putString(KEY_LOGGED_IN_USER, obj.toString()).commit()
        }
    }

    fun getLoggedInUser(context: Context): UserAccount? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_LOGGED_IN_USER, null)
        if (jsonString.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(jsonString)
            val assigned = if (obj.has("assignedFarmName") && !obj.isNull("assignedFarmName")) {
                val s = obj.getString("assignedFarmName")
                if (s.isBlank()) null else s
            } else null

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
        } catch (_: Exception) {
            null
        }
    }

    fun clearLoggedInUser(context: Context) {
        saveLoggedInUser(context, null)
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

    fun generateTemplateCsv(context: Context, farmName: String): File {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append("# ITACUMBI AGRO - MODELO OFICIAL DE IMPORTAÇÃO DE HISTÓRICO\n")
        sb.append("# FAZENDA;").append(farmName).append("\n")
        sb.append("# INSTRUÇÕES;Preencha abaixo apenas os dias em que houve chuva (volume maior que 0).\n# FORMATO DA DATA;DD/MM/AAAA (ex: 15/01/2023)\n# FORMATO DO VOLUME;Aceita vírgula ou ponto (ex: 12,5 ou 12.5)\n#\nData;Volume_Chuva_mm;Observacoes\n10/01/2024;25,5;Chuva forte à tarde\n12/01/2024;8,0;Garoa rápida\n")
        val safeName = farmName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(context.cacheDir, "modelo_importacao_${safeName}.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    private fun cleanVolume(raw: String): Double? {
        val s = raw.trim()
            .removeSurrounding("\"")
            .replace("mm", "", ignoreCase = true)
            .replace("MM", "")
            .trim()
        if (s.isBlank()) return null

        val normalized = if (s.contains(".") && s.contains(",")) {
            if (s.indexOf(".") < s.indexOf(",")) {
                // Formato brasileiro: 1.250,50 -> 1250.50
                s.replace(".", "").replace(",", ".")
            } else {
                // Formato internacional: 1,250.50 -> 1250.50
                s.replace(",", "")
            }
        } else if (s.contains(",")) {
            // Formato decimal com vírgula: 25,5 -> 25.5
            s.replace(",", ".")
        } else {
            s
        }
        return normalized.toDoubleOrNull()
    }

    private fun columnLetterToIndex(letters: String): Int {
        var col = 0
        for (ch in letters.uppercase()) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
            }
        }
        return if (col > 0) col - 1 else 0
    }

    private fun unescapeXml(text: String): String {
        return text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun parseXlsxRows(inputStream: java.io.InputStream): List<List<String>> {
        val zip = java.util.zip.ZipInputStream(inputStream)
        var entry = zip.nextEntry
        val filesMap = mutableMapOf<String, ByteArray>()
        while (entry != null) {
            val name = entry.name.lowercase()
            if (name == "xl/sharedstrings.xml" || name.startsWith("xl/worksheets/sheet")) {
                val baos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var len: Int
                while (zip.read(buf).also { len = it } > 0) {
                    baos.write(buf, 0, len)
                }
                filesMap[name] = baos.toByteArray()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }

        val sharedStrings = mutableListOf<String>()
        val sharedStringsBytes = filesMap["xl/sharedstrings.xml"]
        if (sharedStringsBytes != null) {
            val xml = String(sharedStringsBytes, Charsets.UTF_8)
            val siRegex = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
            val tRegex = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
            for (siMatch in siRegex.findAll(xml)) {
                val siContent = siMatch.groupValues[1]
                val tMatches = tRegex.findAll(siContent)
                val combined = tMatches.joinToString("") { unescapeXml(it.groupValues[1]) }
                sharedStrings.add(combined)
            }
        }

        val sheetEntryKey = filesMap.keys.firstOrNull { it == "xl/worksheets/sheet1.xml" }
            ?: filesMap.keys.firstOrNull { it.startsWith("xl/worksheets/sheet") }
            ?: return emptyList()

        val sheetBytes = filesMap[sheetEntryKey] ?: return emptyList()
        val sheetXml = String(sheetBytes, Charsets.UTF_8)
        val rows = mutableListOf<List<String>>()

        val rowRegex = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cellRegex = Regex("<c\\s+([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
        val valRegex = Regex("<v>(.*?)</v>")
        val inlineStrRegex = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        val typeRegex = Regex("t=\"([^\"]*)\"")
        val colRefRegex = Regex("r=\"([A-Z]+)[0-9]+\"")

        for (rowMatch in rowRegex.findAll(sheetXml)) {
            val rowContent = rowMatch.groupValues[1]
            val cellMap = mutableMapOf<Int, String>()
            var fallbackCol = 0
            for (cellMatch in cellRegex.findAll(rowContent)) {
                val attrs = cellMatch.groupValues[1]
                val cellInner = cellMatch.groupValues[2]
                val type = typeRegex.find(attrs)?.groupValues?.get(1) ?: ""
                val colLetters = colRefRegex.find(attrs)?.groupValues?.get(1)
                val colIdx = if (colLetters != null) columnLetterToIndex(colLetters) else fallbackCol
                fallbackCol = colIdx + 1

                val cellValue = when (type) {
                    "s" -> {
                        val sIdx = valRegex.find(cellInner)?.groupValues?.get(1)?.toIntOrNull()
                        if (sIdx != null && sIdx in sharedStrings.indices) sharedStrings[sIdx] else ""
                    }
                    "inlineStr" -> {
                        val tMatches = inlineStrRegex.findAll(cellInner)
                        tMatches.joinToString("") { unescapeXml(it.groupValues[1]) }
                    }
                    else -> {
                        val v = valRegex.find(cellInner)?.groupValues?.get(1) ?: ""
                        unescapeXml(v)
                    }
                }
                cellMap[colIdx] = cellValue.trim()
            }

            if (cellMap.isNotEmpty()) {
                val maxCol = cellMap.keys.maxOrNull() ?: 0
                val rowList = (0..maxCol).map { cellMap[it] ?: "" }
                rows.add(rowList)
            }
        }
        return rows
    }

    fun splitCsvLine(line: String, delimiter: Char = ';'): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c == delimiter && !inQuotes) {
                result.add(sb.toString().trim().removeSurrounding("\"").trim())
                sb.clear()
            } else {
                sb.append(c)
            }
        }
        result.add(sb.toString().trim().removeSurrounding("\"").trim())
        return result
    }

    fun normalizeDate(raw: String): String? {
        val s = raw.trim().removeSurrounding("\"").trim()
        if (s.isBlank()) return null
        
        // Handle Excel numeric serial dates (e.g. 45312)
        val numDate = s.toDoubleOrNull()
        if (numDate != null && numDate in 20000.0..70000.0) {
            val cal = java.util.Calendar.getInstance()
            cal.set(1899, java.util.Calendar.DECEMBER, 30, 0, 0, 0)
            cal.add(java.util.Calendar.DAY_OF_YEAR, numDate.toInt())
            val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val m = cal.get(java.util.Calendar.MONTH) + 1
            val y = cal.get(java.util.Calendar.YEAR)
            return String.format(java.util.Locale.US, "%02d/%02d/%04d", d, m, y)
        }

        val clean = s.replace("-", "/")
        val parts = clean.split("/")
        if (parts.size == 3) {
            val p0 = parts[0].trim()
            val p1 = parts[1].trim()
            val p2 = parts[2].trim()

            // If YYYY/MM/DD
            if (p0.length == 4) {
                val y = p0.toIntOrNull() ?: return null
                val m = p1.toIntOrNull() ?: return null
                val d = p2.toIntOrNull() ?: return null
                if (d in 1..31 && m in 1..12 && y in 1900..2100) {
                    return String.format(java.util.Locale.US, "%02d/%02d/%04d", d, m, y)
                }
            } else if (p2.length == 4) {
                // DD/MM/YYYY
                val d = p0.toIntOrNull() ?: return null
                val m = p1.toIntOrNull() ?: return null
                val y = p2.toIntOrNull() ?: return null
                if (d in 1..31 && m in 1..12 && y in 1900..2100) {
                    return String.format(java.util.Locale.US, "%02d/%02d/%04d", d, m, y)
                }
            }
        }
        return null
    }

    fun parseImportCsv(context: Context, uri: Uri, fallbackFarmName: String): ParsedCsvResult {
        val stream = context.contentResolver.openInputStream(uri) 
            ?: throw java.io.IOException("Não foi possível acessar o arquivo selecionado.")
        
        val bytes = stream.use { it.readBytes() }
        if (bytes.isEmpty()) {
            return ParsedCsvResult(null, emptyList(), 0.0, 0, listOf("O arquivo selecionado está vazio."))
        }

        val isZipXlsx = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

        val rawRows: List<List<String>> = if (isZipXlsx) {
            try {
                parseXlsxRows(java.io.ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                return ParsedCsvResult(null, emptyList(), 0.0, 0, listOf("Erro ao processar planilha Excel: ${e.localizedMessage}"))
            }
        } else {
            // Decodificação inteligente para arquivos de texto CSV com fallback para Windows-1252 / ISO-8859-1
            val textContent = try {
                val decoder = Charsets.UTF_8.newDecoder()
                decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                try {
                    String(bytes, java.nio.charset.Charset.forName("Windows-1252"))
                } catch (_: Exception) {
                    String(bytes, Charsets.ISO_8859_1)
                }
            }

            val lines = textContent.lines()
            var dominantDelimiter = ';'
            for (l in lines.take(20)) {
                val trimL = l.trim().removePrefix("\uFEFF").trim()
                if (trimL.isNotBlank() && !trimL.startsWith("#")) {
                    dominantDelimiter = if (trimL.contains(";")) ';' else if (trimL.contains(",")) ',' else '\t'
                    break
                }
            }

            lines.map { l ->
                val clean = l.trim().removePrefix("\uFEFF").trim()
                if (clean.isBlank()) emptyList() else splitCsvLine(clean, dominantDelimiter)
            }
        }

        var detectedFarm: String? = null
        val warnings = mutableListOf<String>()
        val validLogs = mutableListOf<RainfallLog>()
        var ignoredEmptyLines = 0
        var totalVolumeMm = 0.0

        var dateColIdx = -1
        var volColIdx = -1
        var obsColIdx = -1
        var farmColIdx = -1
        var headerRowFound = false

        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.getDefault()).format(java.util.Date())

        for ((idx, cols) in rawRows.withIndex()) {
            val lineNum = idx + 1
            if (cols.isEmpty() || cols.all { it.isBlank() }) {
                ignoredEmptyLines++
                continue
            }

            // Linha de metadados / comentário (# FAZENDA: ...)
            val firstCell = cols.first().trim()
            if (firstCell.startsWith("#")) {
                val upper = firstCell.uppercase()
                if (upper.startsWith("# FAZENDA;") || upper.startsWith("# FAZENDA:")) {
                    val parts = firstCell.split(Regex("[:;]"), limit = 2)
                    if (parts.size >= 2 && parts[1].isNotBlank()) {
                        detectedFarm = parts[1].trim()
                    }
                }
                continue
            }

            if (!headerRowFound) {
                val upperCols = cols.map { it.uppercase().trim() }
                val hasDate = upperCols.any { it.contains("DATA") || it.contains("DATE") || it.contains("DIA") }
                val hasVol = upperCols.any { it.contains("CHUVA") || it.contains("VOLUME") || it.contains("VOL") || it.contains("MM") || it.contains("PRECIP") }

                if (hasDate || hasVol) {
                    headerRowFound = true
                    dateColIdx = upperCols.indexOfFirst { it.contains("DATA") || it.contains("DATE") || it.contains("DIA") }
                    volColIdx = upperCols.indexOfFirst { it.contains("CHUVA") || it.contains("VOLUME") || it.contains("VOL") || it.contains("MM") || it.contains("PRECIP") }
                    obsColIdx = upperCols.indexOfFirst { it.contains("OBS") || it.contains("NOTA") || it.contains("HIST") || it.contains("DESC") }
                    farmColIdx = upperCols.indexOfFirst { it.contains("FAZ") || it.contains("LOCAL") || it.contains("PROP") }
                    continue
                } else if (normalizeDate(cols[0]) != null) {
                    headerRowFound = true
                    dateColIdx = 0
                    volColIdx = if (cols.size > 1) 1 else -1
                    obsColIdx = if (cols.size > 2) 2 else -1
                    farmColIdx = if (cols.size > 3) 3 else -1
                    // Não dá continue pois esta linha já contém um registro válido de dados
                } else {
                    val titleJoined = cols.joinToString(" ").uppercase()
                    if (titleJoined.contains("FAZENDA")) {
                        val cleanTitle = cols.joinToString(" ").replace(Regex("(?i)fazenda[:\\s]*"), "").trim()
                        if (cleanTitle.isNotBlank() && detectedFarm == null) {
                            detectedFarm = cleanTitle
                        }
                    }
                    continue
                }
            }

            // Pula linhas de totais / médias
            val firstColUpper = cols.firstOrNull()?.uppercase() ?: ""
            if (firstColUpper.contains("TOTAL") || firstColUpper.contains("SOMA") || firstColUpper.contains("MEDIA") || firstColUpper.contains("MÉDIA")) {
                continue
            }

            val dIdx = if (dateColIdx >= 0 && dateColIdx < cols.size) dateColIdx else 0
            val vIdx = if (volColIdx >= 0 && volColIdx < cols.size) volColIdx else 1
            val oIdx = if (obsColIdx >= 0 && obsColIdx < cols.size) obsColIdx else -1
            val fIdx = if (farmColIdx >= 0 && farmColIdx < cols.size) farmColIdx else -1

            val rawDate = cols.getOrNull(dIdx) ?: ""
            val rawVol = cols.getOrNull(vIdx) ?: ""
            val rawObs = if (oIdx >= 0) cols.getOrNull(oIdx) ?: "" else ""
            val rowFarm = if (fIdx >= 0) cols.getOrNull(fIdx)?.takeIf { it.isNotBlank() } else null

            if (rowFarm != null && detectedFarm == null) {
                detectedFarm = rowFarm
            }

            val normDate = normalizeDate(rawDate)
            if (normDate == null) {
                if (rawDate.isNotBlank() || rawVol.isNotBlank()) {
                    if (!rawDate.contains("exemplo", ignoreCase = true) && !rawDate.contains("data", ignoreCase = true)) {
                        warnings.add("Linha $lineNum: Data não reconhecida ('$rawDate')")
                    }
                } else {
                    ignoredEmptyLines++
                }
                continue
            }

            val vol = cleanVolume(rawVol)
            if (vol == null) {
                if (rawVol.isNotBlank()) {
                    warnings.add("Linha $lineNum ($normDate): Volume não reconhecido ('$rawVol')")
                } else {
                    ignoredEmptyLines++
                }
                continue
            }

            if (vol < 0.0) {
                warnings.add("Linha $lineNum ($normDate): Volume de chuva não pode ser negativo ($vol mm)")
                continue
            }

            if (vol == 0.0) {
                // Dias com 0.0 mm (sem chuva) são contabilizados e ignorados para não poluir o banco
                ignoredEmptyLines++
                continue
            }

            val targetFarm = rowFarm ?: detectedFarm ?: fallbackFarmName
            val log = RainfallLog(
                id = UUID.randomUUID().toString(),
                date = normDate,
                farmName = targetFarm,
                volumeMm = vol,
                notes = rawObs.ifBlank { "Importado via planilha" },
                isEdited = false,
                createdAt = nowIso,
                hasPendingSync = true
            )
            validLogs.add(log)
            totalVolumeMm += vol
        }

        return ParsedCsvResult(
            detectedFarmName = detectedFarm,
            validLogs = validLogs,
            totalVolumeMm = totalVolumeMm,
            ignoredEmptyLines = ignoredEmptyLines,
            warnings = warnings
        )
    }

    // Alias para compatibilidade
    fun parseSpreadsheetBytes(context: Context, uri: Uri, fallbackFarmName: String): ParsedCsvResult =
        parseImportCsv(context, uri, fallbackFarmName)

    // --- MÉTODOS ASSÍNCRONOS (COROUTINES) PARA NÃO BLOQUEAR A MAIN THREAD ---
    suspend fun loadUsersAsync(context: Context): List<UserAccount> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadUsers(context) }
    suspend fun saveUsersAsync(context: Context, users: List<UserAccount>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveUsers(context, users) }
    
    suspend fun loadFarmsAsync(context: Context): List<Farm> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadFarms(context) }
    suspend fun saveFarmsAsync(context: Context, farms: List<Farm>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveFarms(context, farms) }
    
    suspend fun loadLogsAsync(context: Context, defaultLogs: List<RainfallLog>): List<RainfallLog> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadLogs(context, defaultLogs) }
    suspend fun saveLogsAsync(context: Context, logs: List<RainfallLog>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveLogs(context, logs) }
}

