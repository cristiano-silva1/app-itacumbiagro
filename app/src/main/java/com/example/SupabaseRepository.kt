package com.example

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SupabaseRepository(private val api: SupabaseApi) {
    private val TAG = "SupabaseRepository"
    
    val SUPABASE_URL = BuildConfig.SUPABASE_URL

    val SQL_UNLOCK_SCRIPT = """-- ==============================================================================
-- SCRIPT DE AUDITORIA E CORREÇÃO DE HORÁRIOS - SUPABASE (CFL AGRO / ITACUMBI)
-- Execute no SQL Editor do seu projeto Supabase:
-- ==============================================================================

-- 1. Configurar fuso horário padrão do banco para o horário do Brasil (MS / Campo Grande)
ALTER DATABASE postgres SET timezone TO 'America/Campo_Grande';

-- 2. Tabela de Auditoria (histórico de exclusões, alterações e cadastros)
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id TEXT PRIMARY KEY,
    action TEXT NOT NULL,
    table_name TEXT NOT NULL,
    record_id TEXT,
    farm_name TEXT,
    performed_by TEXT NOT NULL,
    details TEXT NOT NULL,
    old_data JSONB,
    new_data JSONB,
    created_at TIMESTAMPTZ DEFAULT now() NOT NULL
);

-- 3. Adicionar campos de autoria e atualização em rainfall_logs
ALTER TABLE public.rainfall_logs 
    ADD COLUMN IF NOT EXISTS created_by TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_by TEXT;

-- 4. Liberar leitura e gravação no app
ALTER TABLE public.farms DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.users DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.rainfall_logs DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_logs DISABLE ROW LEVEL SECURITY;

-- 5. Gatilho automático para registrar qualquer alteração ou exclusão
CREATE OR REPLACE FUNCTION log_rainfall_changes()
RETURNS TRIGGER AS ${'$'}${'$'}
BEGIN
    IF (TG_OP = 'DELETE') THEN
        INSERT INTO public.audit_logs (id, action, table_name, record_id, farm_name, performed_by, details, old_data, created_at)
        VALUES (
            gen_random_uuid()::text,
            'EXCLUSAO',
            'rainfall_logs',
            OLD.id,
            OLD.farm_name,
            COALESCE(OLD.updated_by, 'sistema/supabase'),
            'Exclusão de medição de ' || OLD.volume_mm || ' mm em ' || OLD.date || ' (' || OLD.farm_name || ')',
            to_jsonb(OLD),
            now()
        );
        RETURN OLD;
    ELSIF (TG_OP = 'UPDATE') THEN
        INSERT INTO public.audit_logs (id, action, table_name, record_id, farm_name, performed_by, details, old_data, new_data, created_at)
        VALUES (
            gen_random_uuid()::text,
            'EDICAO',
            'rainfall_logs',
            NEW.id,
            NEW.farm_name,
            COALESCE(NEW.updated_by, 'sistema/supabase'),
            'Edição de medição: volume alterado de ' || OLD.volume_mm || ' mm para ' || NEW.volume_mm || ' mm em ' || NEW.date,
            to_jsonb(OLD),
            to_jsonb(NEW),
            now()
        );
        RETURN NEW;
    END IF;
    RETURN NULL;
END;
${'$'}${'$'} LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_rainfall_logs ON public.rainfall_logs;
CREATE TRIGGER trg_audit_rainfall_logs
AFTER UPDATE OR DELETE ON public.rainfall_logs
FOR EACH ROW EXECUTE FUNCTION log_rainfall_changes();"""

    suspend fun fetchFarms(): Result<List<Farm>> = withContext(Dispatchers.IO) {
        try {
            val response = api.fetchFarms()
            if (response.isSuccessful) {
                val cleanList = (response.body() ?: emptyList()).filterNot { it.isDiagnostic() }
                Result.success(cleanList)
            } else {
                Result.failure(Exception("Supabase HTTP ${response.code()}: ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching farms", e)
            Result.failure(e)
        }
    }

    suspend fun upsertFarm(farm: Farm): Result<Unit> = withContext(Dispatchers.IO) {
        if (farm.isDiagnostic()) {
            return@withContext Result.success(Unit) // Nunca sincroniza fazenda de diagnóstico para o Supabase
        }
        try {
            val response = api.upsertFarm(farm = farm)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                val msg = if (errorBody.contains("42501") || errorBody.contains("row-level security")) {
                    "Aviso RLS (42501): Proteção ativa no Supabase. É necessário desativar o RLS ou criar política de inserção na tabela 'farms'."
                } else {
                    "Upsert farm error ${response.code()}: $errorBody"
                }
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error upserting farm", e)
            Result.failure(e)
        }
    }

    suspend fun deleteFarm(farmName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.deleteFarm(nameFilter = "eq.$farmName")
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Delete farm error ${response.code()}: ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting farm", e)
            Result.failure(e)
        }
    }

    suspend fun fetchUsers(): Result<List<UserAccount>> = withContext(Dispatchers.IO) {
        try {
            val response = api.fetchUsers()
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Supabase HTTP ${response.code()}: ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching users", e)
            Result.failure(e)
        }
    }

    suspend fun upsertUser(user: UserAccount): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.upsertUser(user = user)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                val msg = if (errorBody.contains("42501") || errorBody.contains("row-level security")) {
                    "Aviso RLS (42501): Proteção ativa no Supabase. É necessário desativar o RLS ou criar política de inserção na tabela 'users'."
                } else {
                    "Upsert user error ${response.code()}: $errorBody"
                }
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error upserting user", e)
            Result.failure(e)
        }
    }

    suspend fun deleteUser(username: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.deleteUser(usernameFilter = "eq.$username")
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Delete user error ${response.code()}: ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting user", e)
            Result.failure(e)
        }
    }

    suspend fun authenticateUser(usernameOrFarm: String, password: String): Result<UserAccount> = withContext(Dispatchers.IO) {
        try {
            val cleanInput = usernameOrFarm.trim().lowercase()
            val cleanPass = password.trim()

            val isGerencialKeyword = cleanInput in listOf("diretoria", "gerencial", "gerente", "admin")
            val filter = "username.eq.$cleanInput,assigned_farm_name.eq.$cleanInput" + if (isGerencialKeyword) ",role.eq.GERENCIAL" else ""

            val response = api.authenticateUser(orFilter = filter)
            if (response.isSuccessful) {
                val remoteUsers = response.body() ?: emptyList()
                val found = remoteUsers.find { u ->
                    val matchesUsername = u.username.trim().lowercase() == cleanInput
                    val matchesFarm = u.assignedFarmName?.trim()?.lowercase() == cleanInput
                    val matchesRole = (isGerencialKeyword && u.role == UserRole.GERENCIAL)
                    (matchesUsername || matchesFarm || matchesRole) && u.password.trim() == cleanPass
                }
                if (found != null) {
                    Result.success(found)
                } else {
                    Result.failure(Exception("INVALID_CREDENTIALS"))
                }
            } else {
                Result.failure(Exception("Supabase HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error authenticating", e)
            Result.failure(e)
        }
    }

    // Helper: converts DD/MM/YYYY to YYYY-MM-DD
    private fun formatDateToDb(dateStr: String): String {
        return try {
            val parts = dateStr.trim().split("/")
            if (parts.size == 3) {
                val day = parts[0].padStart(2, '0')
                val month = parts[1].padStart(2, '0')
                val year = parts[2]
                "$year-$month-$day"
            } else {
                dateStr
            }
        } catch (_: Exception) {
            dateStr
        }
    }

    // Helper: converts YYYY-MM-DD to DD/MM/YYYY
    private fun formatDateFromDb(dateStr: String): String {
        return try {
            val parts = dateStr.trim().split("-")
            if (parts.size == 3) {
                val year = parts[0]
                val month = parts[1].padStart(2, '0')
                val day = parts[2].padStart(2, '0')
                "$day/$month/$year"
            } else {
                dateStr
            }
        } catch (_: Exception) {
            dateStr
        }
    }

    suspend fun fetchLogs(): Result<List<RainfallLog>> = withContext(Dispatchers.IO) {
        try {
            val response = api.fetchLogs()
            if (response.isSuccessful) {
                val rawLogs = response.body() ?: emptyList()
                val mappedLogs = rawLogs.map { log ->
                    log.copy(date = formatDateFromDb(log.date))
                }
                Result.success(mappedLogs)
            } else {
                Result.failure(Exception("Supabase HTTP ${response.code()}: ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching logs", e)
            Result.failure(e)
        }
    }

    suspend fun upsertLog(
        log: RainfallLog,
        currentUser: String? = null,
        updatedAt: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any>()
            map["id"] = log.id
            map["farm_name"] = log.farmName
            map["date"] = formatDateToDb(log.date)
            map["volume_mm"] = log.volumeMm
            map["notes"] = log.notes
            if (!log.createdAt.isNullOrBlank() && log.createdAt != "null") {
                map["created_at"] = log.createdAt
            }
            if (currentUser != null) {
                map["created_by"] = currentUser
            }
            if (updatedAt != null) {
                map["updated_at"] = updatedAt
                if (currentUser != null) {
                    map["updated_by"] = currentUser
                }
            }

            val response = api.upsertLog(payload = map)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                val msg = if (errorBody.contains("42501") || errorBody.contains("row-level security")) {
                    "Aviso RLS (42501): Proteção ativa no Supabase. É necessário desativar o RLS ou criar política de inserção na tabela 'rainfall_logs'."
                } else {
                    "Upsert log error ${response.code()}: $errorBody"
                }
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error upserting log", e)
            Result.failure(e)
        }
    }

    suspend fun deleteLog(logId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.deleteLog(idFilter = "eq.$logId")
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Delete log error ${response.code()}: ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting log", e)
            Result.failure(e)
        }
    }

    suspend fun recordAuditLog(entry: AuditLogEntry): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.recordAuditLog(entry = entry)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                Log.w(TAG, "Audit log recording returned ${response.code()}: $errorBody")
                Result.failure(Exception("Audit log error ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error recording audit log", e)
            Result.failure(e)
        }
    }

    suspend fun fetchAuditLogs(limit: Int = 100): Result<List<AuditLogEntry>> = withContext(Dispatchers.IO) {
        try {
            val response = api.fetchAuditLogs(limit = limit)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Fetch audit error ${response.code()}: ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching audit logs", e)
            Result.failure(e)
        }
    }

    suspend fun runDiagnostics(): SupabaseHealthStatus = withContext(Dispatchers.IO) {
        try {
            val fResp = api.fetchFarms()
            val canRead = fResp.isSuccessful
            val farmCount = fResp.body()?.size ?: 0
            
            val uResp = api.fetchUsers()
            val userCount = uResp.body()?.size ?: 0
            
            val lResp = api.fetchLogs()
            val logCount = lResp.body()?.size ?: 0
            
            val probePayload = mapOf("id" to "probe-diag", "name" to "__diagnostico__", "region" to "diag")
            val probeResp = api.probeWrite(farm = probePayload)
            var canWrite = false
            var rlsBlocked = false
            
            if (probeResp.isSuccessful) {
                canWrite = true
                try { api.deleteFarm("eq.__diagnostico__") } catch (_: Exception) {}
            } else {
                val err = probeResp.errorBody()?.string() ?: ""
                if (err.contains("42501") || err.contains("row-level security")) {
                    rlsBlocked = true
                }
            }
            
            val msg = if (rlsBlocked) {
                "Conectado ao Supabase! Porém o PostgreSQL bloqueia gravação porque o RLS (Row Level Security) está ativo sem política de inserção para o app. Execute o script SQL para desbloquear."
            } else if (canWrite) {
                "Conexão com Supabase 100% operacional! Leitura, inserção, alteração e exclusão liberadas."
            } else {
                "Conexão com Supabase ativa em modo leitura."
            }

            SupabaseHealthStatus(true, canRead, canWrite, rlsBlocked, farmCount, userCount, logCount, msg)
        } catch (e: Exception) {
            SupabaseHealthStatus(false, false, false, false, 0, 0, 0, "Falha ao conectar com o Supabase: ${e.localizedMessage}")
        }
    }
}
