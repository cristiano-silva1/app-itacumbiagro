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

            val sampleLogs = emptyList<RainfallLog>()
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

                // 1. Sincronizar Fazendas (espelha a lista remota do Supabase e sobe criadas offline)
                val farmsResult = NetworkModule.supabaseRepository.fetchFarms()
                if (farmsResult.isSuccess) {
                    val remoteFarms = farmsResult.getOrNull().orEmpty().filterNot { it.isDiagnostic() }
                    val remoteIds = remoteFarms.map { it.id }.toSet()
                    val remoteNames = remoteFarms.map { it.name.trim().lowercase() }.toSet()

                    // Fazendas criadas localmente offline que ainda não existem no Supabase
                    val pendingLocalFarms = farms.filter { it.id !in remoteIds && it.name.trim().lowercase() !in remoteNames && !it.isDiagnostic() }
                    for (pf in pendingLocalFarms) {
                        try {
                            NetworkModule.supabaseRepository.upsertFarm(pf)
                        } catch (_: Exception) {}
                    }

                    val mergedFarms = (remoteFarms + pendingLocalFarms).distinctBy { it.name.trim().lowercase() }
                    if (farms.toList() != mergedFarms) {
                        farms.clear()
                        farms.addAll(mergedFarms)
                        AppDatabaseManager.saveFarmsAsync(context, farms)
                    }

                    // Tenta garantir que qualquer registro residual de diagnóstico no Supabase seja excluído
                    try {
                        NetworkModule.supabaseRepository.deleteFarm("__diagnostico__")
                    } catch (_: Exception) {}
                }

                // 2. Sincronizar Usuários (espelha a lista remota do Supabase e sobe criados offline)
                val usersResult = NetworkModule.supabaseRepository.fetchUsers()
                if (usersResult.isSuccess) {
                    val remoteUsers = usersResult.getOrNull().orEmpty()
                    val remoteUsernames = remoteUsers.map { it.username.trim().lowercase() }.toSet()

                    val pendingLocalUsers = users.filter { it.username.trim().lowercase() !in remoteUsernames && it.role == UserRole.FAZENDA }
                    for (pu in pendingLocalUsers) {
                        try {
                            NetworkModule.supabaseRepository.upsertUser(pu)
                        } catch (_: Exception) {}
                    }

                    val mergedUsers = (remoteUsers + pendingLocalUsers).distinctBy { it.username.trim().lowercase() }
                    if (mergedUsers.isNotEmpty() && users.toList() != mergedUsers) {
                        users.clear()
                        users.addAll(mergedUsers)
                        AppDatabaseManager.saveUsersAsync(context, users)
                    }
                }

                // 3. Sincronizar Logs
                val logsResult = NetworkModule.supabaseRepository.fetchLogs()
                if (logsResult.isSuccess) {
                    val remoteLogs = logsResult.getOrNull().orEmpty()
                    val remoteIds = remoteLogs.map { it.id }.toSet()

                    // Remove do aparelho local lançamentos que foram excluídos no Supabase
                    // (mantendo apenas registros locais que ainda estão pendentes de envio offline)
                    logs.removeAll { !it.hasPendingSync && it.id !in remoteIds }

                    // Atualiza ou insere registros vindos do Supabase
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
                                }
                            }
                        } else {
                            logs.add(rl)
                        }
                    }

                    val sortedLogs = logs.toList().sortedWith(
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
                    logs.addAll(sortedLogs)
                    AppDatabaseManager.saveLogsAsync(context, logs)

                    // Apenas registros criados offline neste aparelho devem ser enviados
                    val pendingNewLogs = logs.filter { it.hasPendingSync && !it.isEdited }
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
