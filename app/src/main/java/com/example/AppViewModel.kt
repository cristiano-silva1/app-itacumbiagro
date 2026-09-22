package com.example

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    // Estado reativo da UI
    val farms = mutableStateListOf<Farm>()
    val users = mutableStateListOf<UserAccount>()
    val logs = mutableStateListOf<RainfallLog>()

    var isSyncingData by mutableStateOf(false)
        private set
    var syncStatusMessage by mutableStateOf<String?>("Inicializando...")
        private set

    init {
        // Carregamento Assíncrono para evitar travamento da Main Thread
        viewModelScope.launch {
            val initialFarms = AppDatabaseManager.loadFarmsAsync(context).filterNot { it.isDiagnostic() }
            farms.clear()
            farms.addAll(initialFarms)

            val initialUsers = AppDatabaseManager.loadUsersAsync(context)
            users.addAll(initialUsers)

            val sampleLogs = listOf(
                RainfallLog("1", "15/09/2026", "Fazenda Primavera", 45.0, "Chuva forte de fim de tarde"),
                RainfallLog("2", "22/09/2026", "Fazenda Bela Vista", 12.5, "Garoa leve pela manhã"),
                RainfallLog("3", "01/10/2026", "Fazenda Primavera", 30.0, "Pancada rápida"),
                RainfallLog("4", "10/10/2026", "Sítio São João", 80.0, "Temporal severo, algumas estradas danificadas"),
                RainfallLog("5", "05/11/2026", "Fazenda Lambari", 25.0, "Chuva constante durante a noite"),
                RainfallLog("6", "15/12/2026", "Fazenda Santa Maria", 60.0, "Chuva forte acompanhada de ventania"),
                RainfallLog("7", "20/01/2027", "Fazenda Primavera", 15.0, "Garoa persistente"),
                RainfallLog("8", "25/02/2027", "Fazenda Bela Vista", 40.0, "Chuva moderada a forte"),
                RainfallLog("9", "10/03/2027", "Sítio São João", 10.0, "Chuva fraca"),
                RainfallLog("10", "05/04/2027", "Fazenda Lambari", 55.0, "Pancada forte de verão"),
                RainfallLog("11", "20/08/2026", "Fazenda Primavera", 20.0, "Chuva isolada"),
                RainfallLog("12", "01/09/2026", "Fazenda Bela Vista", 5.0, "Apenas neblina espessa e chuvisco"),
                RainfallLog("13", "12/10/2026", "Sítio São João", 35.0, "Chuva boa para o plantio"),
                RainfallLog("14", "28/11/2026", "Fazenda Lambari", 42.0, "Chuva constante e volumosa"),
                RainfallLog("15", "05/01/2027", "Fazenda Santa Maria", 18.0, "Chuva moderada de fim de tarde"),
                RainfallLog("16", "14/02/2027", "Fazenda Primavera", 75.0, "Temporal com granizo relatado na sede"),
                RainfallLog("17", "22/03/2027", "Fazenda Bela Vista", 22.0, "Chuva regular"),
                RainfallLog("18", "14/04/2026", "Fazenda Primavera", 28.0, "Chuva constante"),
                RainfallLog("19", "02/04/2026", "Fazenda Primavera", 15.0, "Garoa matinal"),
                RainfallLog("20", "22/09/2025", "Fazenda Primavera", 35.0, "Início das chuvas da primavera"),
                RainfallLog("21", "15/09/2025", "Fazenda Primavera", 45.0, "Chuva boa de abertura"),
                RainfallLog("22", "18/08/2025", "Fazenda Primavera", 10.0, "Chuva fria de inverno"),
                RainfallLog("23", "20/07/2025", "Fazenda Primavera", 15.0, "Frente fria isolada"),
                RainfallLog("24", "16/06/2025", "Fazenda Primavera", 32.0, "Chuva moderada no inverno"),
                RainfallLog("25", "05/06/2025", "Fazenda Primavera", 28.0, "Garoa persistente"),
                RainfallLog("26", "24/05/2025", "Fazenda Primavera", 50.0, "Pancada forte com trovões"),
                RainfallLog("27", "11/05/2025", "Fazenda Primavera", 45.0, "Chuva de fim de tarde"),
                RainfallLog("28", "29/04/2025", "Fazenda Primavera", 60.0, "Chuva intensa"),
                RainfallLog("29", "10/04/2025", "Fazenda Primavera", 50.0, "Precipitação generalizada")
            )
            val initialLogs = AppDatabaseManager.loadLogsAsync(context, sampleLogs)
            val sorted = initialLogs.sortedWith(
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
            logs.addAll(sorted)
            syncStatusMessage = "Dados locais carregados"
        }
    }

    // Sincronização Extraída do Compose! (Desacoplamento)
    fun performCloudSync(activeUser: UserAccount, showToast: Boolean) {
        if (isSyncingData) return
        isSyncingData = true

        viewModelScope.launch {
            try {
                var hasRlsWarning = false

                // 1. Sincronizar Fazendas
                val farmsResult = NetworkModule.supabaseRepository.fetchFarms()
                if (farmsResult.isSuccess) {
                    val remoteFarms = farmsResult.getOrNull().orEmpty().filterNot { it.isDiagnostic() }
                    
                    // Limpar qualquer fazenda de teste/diagnóstico que tenha ficado na memória
                    farms.removeAll { it.isDiagnostic() }

                    if (remoteFarms.isNotEmpty()) {
                        remoteFarms.forEach { rf ->
                            if (farms.none { it.name.equals(rf.name, ignoreCase = true) }) {
                                farms.add(rf)
                            }
                        }
                    }
                    AppDatabaseManager.saveFarmsAsync(context, farms)

                    val pendingFarms = farms.filter { lf ->
                        !lf.isDiagnostic() && remoteFarms.none { it.name.equals(lf.name, ignoreCase = true) }
                    }
                    for (f in pendingFarms) {
                        val res = NetworkModule.supabaseRepository.upsertFarm(f)
                        if (res.isFailure && res.exceptionOrNull()?.message?.contains("RLS") == true) {
                            hasRlsWarning = true
                        }
                    }

                    // Tenta garantir que qualquer registro residual de diagnóstico no Supabase seja excluído
                    try {
                        NetworkModule.supabaseRepository.deleteFarm("__diagnostico__")
                    } catch (_: Exception) {}
                }

                // 2. Sincronizar Usuários
                val usersResult = NetworkModule.supabaseRepository.fetchUsers()
                if (usersResult.isSuccess) {
                    val remoteUsers = usersResult.getOrNull().orEmpty()
                    if (remoteUsers.isNotEmpty()) {
                        remoteUsers.forEach { ru ->
                            val idx = users.indexOfFirst { it.username.equals(ru.username, ignoreCase = true) }
                            if (idx != -1) {
                                users[idx] = ru
                            } else {
                                users.add(ru)
                            }
                        }
                        AppDatabaseManager.saveUsersAsync(context, users)
                    }
                    val pendingUsers = users.filter { lu ->
                        remoteUsers.none { it.username.equals(lu.username, ignoreCase = true) }
                    }
                    for (u in pendingUsers) {
                        val res = NetworkModule.supabaseRepository.upsertUser(u)
                        if (res.isFailure && res.exceptionOrNull()?.message?.contains("RLS") == true) {
                            hasRlsWarning = true
                        }
                    }
                }

                // 3. Sincronizar Logs
                val logsResult = NetworkModule.supabaseRepository.fetchLogs()
                if (logsResult.isSuccess) {
                    val remoteLogs = logsResult.getOrNull().orEmpty()
                    val remoteIds = remoteLogs.map { it.id }.toSet()

                    if (remoteLogs.isNotEmpty()) {
                        var hasChanges = false
                        remoteLogs.forEach { rl ->
                            val existingIndex = logs.indexOfFirst { it.id == rl.id }
                            if (existingIndex != -1) {
                                val currentLocal = logs[existingIndex]
                                if (!currentLocal.hasPendingSync) {
                                    val merged = rl.copy(
                                        isEdited = rl.isEdited || currentLocal.isEdited
                                    )
                                    if (currentLocal != merged) {
                                        logs[existingIndex] = merged
                                        hasChanges = true
                                    }
                                }
                            } else {
                                logs.add(rl)
                                hasChanges = true
                            }
                        }
                        if (hasChanges) {
                            val sorted = logs.toList().sortedWith(
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
                            logs.clear()
                            logs.addAll(sorted)
                            AppDatabaseManager.saveLogsAsync(context, logs)
                        }
                    }

                    val pendingNewLogs = logs.filter { it.id !in remoteIds || (it.hasPendingSync && !it.isEdited) }
                    for (l in pendingNewLogs) {
                        val res = NetworkModule.supabaseRepository.upsertLog(l, currentUser = activeUser.username)
                        if (res.isSuccess) {
                            val i = logs.indexOfFirst { it.id == l.id }
                            if (i != -1 && logs[i].hasPendingSync) {
                                logs[i] = logs[i].copy(hasPendingSync = false)
                                AppDatabaseManager.saveLogsAsync(context, logs)
                            }
                        } else if (res.exceptionOrNull()?.message?.contains("RLS") == true) {
                            hasRlsWarning = true
                        }
                    }

                    val pendingEditedLogs = logs.filter { it.hasPendingSync && it.isEdited }
                    for (l in pendingEditedLogs) {
                        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.getDefault()).format(java.util.Date())
                        val res = NetworkModule.supabaseRepository.upsertLog(l, currentUser = activeUser.username, updatedAt = nowIso)
                        if (res.isSuccess) {
                            val i = logs.indexOfFirst { it.id == l.id }
                            if (i != -1) {
                                logs[i] = l.copy(hasPendingSync = false, isEdited = true)
                                AppDatabaseManager.saveLogsAsync(context, logs)
                            }
                            NetworkModule.supabaseRepository.recordAuditLog(
                                AuditLogEntry(
                                    action = "EDICAO",
                                    tableName = "rainfall_logs",
                                    recordId = l.id,
                                    farmName = l.farmName,
                                    performedBy = activeUser.username,
                                    details = "Sincronização de alteração offline na ${l.farmName}: Data ${l.date}, Volume ${l.volumeMm} mm",
                                    createdAt = nowIso
                                )
                            )
                        } else if (res.exceptionOrNull()?.message?.contains("RLS") == true) {
                            hasRlsWarning = true
                        }
                    }
                    if (pendingEditedLogs.isNotEmpty()) {
                        AppDatabaseManager.saveLogsAsync(context, logs)
                    }
                    if (hasRlsWarning) {
                        syncStatusMessage = "Supabase: Leitura OK (RLS pendente na escrita)"
                        if (showToast) {
                            Toast.makeText(context, "Leitura Supabase OK! Para salvar na nuvem, libere o RLS no SQL Editor.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        syncStatusMessage = "Sincronizado com o Supabase"
                        if (showToast) {
                            Toast.makeText(context, "Sincronização com Supabase concluída com sucesso!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    syncStatusMessage = "Offline (Local Ativo)"
                    if (showToast) {
                        val err = logsResult.exceptionOrNull()?.message ?: "Verifique sua conexão"
                        Toast.makeText(context, "Aviso de conexão Supabase: $err", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                syncStatusMessage = "Erro ao sincronizar"
                if (showToast) {
                    Toast.makeText(context, "Erro na sincronização: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSyncingData = false
            }
        }
    }
}
