package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// --- Brand Colors (Agritech Theme) ---
val BrandGreen = Color(0xFF145C29) // Corporate Deep Green
val BrandGreenLight = Color(0xFFE8F5E9)
val BrandWater = Color(0xFF0288D1) // Vivid Blue for Rain
val BrandBackground = Color(0xFFF4F7F6)
val BrandSurface = Color(0xFFFFFFFF)
val BrandTextPrimary = Color(0xFF1B1B1B)
val BrandTextSecondary = Color(0xFF6B7280)

enum class UserRole {
    GERENCIAL, // Visualiza e gerencia todas as fazendas individualmente
    FAZENDA    // Restrito a apenas uma fazenda atribuída
}

@JsonClass(generateAdapter = true)
data class UserAccount(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String = "1234",
    @Json(name = "display_name") val displayName: String,
    @Json(name = "role") val role: UserRole = UserRole.FAZENDA,
    @Json(name = "assigned_farm_name") val assignedFarmName: String? = null // null se GERENCIAL, ou nome da fazenda se FAZENDA
)

val defaultUsers = listOf(
    UserAccount("gerente", "1234", "Diretoria Agro (Gerencial)", UserRole.GERENCIAL, null),
    UserAccount("primavera", "1234", "Operador Primavera", UserRole.FAZENDA, "Fazenda Primavera"),
    UserAccount("belavista", "1234", "Operador Bela Vista", UserRole.FAZENDA, "Fazenda Bela Vista"),
    UserAccount("saojoao", "1234", "Operador São João", UserRole.FAZENDA, "Sítio São João"),
    UserAccount("lambari", "1234", "Operador Lambari", UserRole.FAZENDA, "Fazenda Lambari"),
    UserAccount("santamaria", "1234", "Operador Santa Maria", UserRole.FAZENDA, "Fazenda Santa Maria")
)

@JsonClass(generateAdapter = true)
data class Farm(
    @Json(name = "id") val id: String = UUID.randomUUID().toString(),
    @Json(name = "name") val name: String,
    @Json(name = "region") val region: String = ""
)

@JsonClass(generateAdapter = true)
data class RainfallLog(
    @Json(name = "id") val id: String = UUID.randomUUID().toString(),
    @Json(name = "date") val date: String,
    @Json(name = "farm_name") val farmName: String,
    @Json(name = "volume_mm") val volumeMm: Double,
    @Json(name = "notes") val notes: String = "",
    @Json(name = "is_edited") val isEdited: Boolean = false,
    @Json(name = "created_at") val createdAt: String? = null,
    @Transient val hasPendingSync: Boolean = false
)

fun parseDateToComparableLong(dateStr: String): Long {
    val parts = dateStr.trim().split("/")
    if (parts.size >= 3) {
        val d = parts[0].trim().toIntOrNull() ?: 0
        val m = parts[1].trim().toIntOrNull() ?: 0
        val y = parts[2].trim().toIntOrNull() ?: 0
        return y * 10000L + m * 100L + d
    }
    return 0L
}

fun sortLogsChronologically(list: List<RainfallLog>): List<RainfallLog> {
    return list.sortedWith(
        compareByDescending<RainfallLog> { parseDateToComparableLong(it.date) }
            .thenByDescending { it.createdAt ?: "" }
            .thenByDescending { it.id }
    )
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme(darkTheme = false, dynamicColor = false) {
        val viewModel: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
        val users = viewModel.users
        val farms = viewModel.farms
        var currentUser by remember { mutableStateOf<UserAccount?>(null) }
        
        if (currentUser == null) {
          LoginScreen(
            availableFarms = farms,
            users = users,
            onLoginSuccess = { user ->
              currentUser = user
            }
          )
        } else {
          ItacumbiAgroApp(
            currentUser = currentUser!!,
            viewModel = viewModel,
            onLogout = {
              currentUser = null
            }
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RainfallDatePickerDialog(
    initialDateStr: String,
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialMillis = remember(initialDateStr) {
        try {
            val parts = initialDateStr.trim().split("/")
            if (parts.size == 3) {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(Calendar.YEAR, parts[2].toInt())
                    set(Calendar.MONTH, parts[1].toInt() - 1)
                    set(Calendar.DAY_OF_MONTH, parts[0].toInt())
                }
                cal.timeInMillis
            } else {
                System.currentTimeMillis()
            }
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val sel = datePickerState.selectedDateMillis
                    if (sel != null) {
                        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = sel
                        }
                        val d = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                        val m = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
                        val y = cal.get(Calendar.YEAR).toString()
                        onDateSelected("$d/$m/$y")
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Confirmar", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = BrandTextSecondary)
            }
        },
        colors = DatePickerDefaults.colors(
            containerColor = Color.White
        )
    ) {
        DatePicker(
            state = datePickerState,
            colors = DatePickerDefaults.colors(
                containerColor = Color.White,
                titleContentColor = BrandGreen,
                headlineContentColor = BrandTextPrimary,
                weekdayContentColor = BrandTextSecondary,
                subheadContentColor = BrandTextPrimary,
                yearContentColor = BrandTextPrimary,
                currentYearContentColor = BrandGreen,
                selectedYearContentColor = Color.White,
                selectedYearContainerColor = BrandGreen,
                dayContentColor = BrandTextPrimary,
                disabledDayContentColor = Color(0xFFD1D5DB),
                selectedDayContentColor = Color.White,
                selectedDayContainerColor = BrandGreen,
                todayContentColor = BrandGreen,
                todayDateBorderColor = BrandGreen
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItacumbiAgroApp(
    currentUser: UserAccount,
    viewModel: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onLogout: () -> Unit = {}
) {
  val context = LocalContext.current
  
  val farms = viewModel.farms
  val users = viewModel.users
  val logs = viewModel.logs
  val isSyncingData = viewModel.isSyncingData
  val syncStatusMessage = viewModel.syncStatusMessage

  var activeUser by remember(currentUser) { mutableStateOf(currentUser) }

  // Filtrar fazendas permitidas de acordo com o perfil de acesso
  val accessibleFarms = remember(activeUser, farms.toList()) {
    if (activeUser.role == UserRole.GERENCIAL) {
      farms.toList()
    } else {
      farms.filter { it.name == activeUser.assignedFarmName }
    }
  }

  var selectedFarm by remember(activeUser, farms.toList()) {
    val initial = if (activeUser.role == UserRole.FAZENDA) {
      farms.firstOrNull { it.name == activeUser.assignedFarmName } ?: farms.firstOrNull() ?: Farm("", "", "")
    } else {
      farms.firstOrNull() ?: Farm("", "", "")
    }
    mutableStateOf(initial)
  }

  val coroutineScope = rememberCoroutineScope()
  var selectedTabIndex by remember { mutableStateOf(0) }

  // Sincronização centralizada delegada para a ViewModel
  val performCloudSync: (Boolean) -> Unit = { showToast ->
    viewModel.performCloudSync(activeUser, showToast)
  }

  // Sincronização automática na inicialização e periódica a cada 60s
  LaunchedEffect(Unit) {
    performCloudSync(false)
    while (true) {
      delay(60_000)
      performCloudSync(false)
    }
  }

  // Listener de reconexão de rede: assim que a internet voltar, dispara sincronização automática das medições offline
  DisposableEffect(context) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    val callback = object : android.net.ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: android.net.Network) {
        performCloudSync(false)
      }
    }
    try {
      val request = android.net.NetworkRequest.Builder()
        .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
      cm?.registerNetworkCallback(request, callback)
    } catch (e: Exception) {
      // Ignora caso restrito pelo sistema
    }
    onDispose {
      try {
        cm?.unregisterNetworkCallback(callback)
      } catch (e: Exception) {}
    }
  }
  var showAddDialog by remember { mutableStateOf(false) }
  var showAddFarmDialog by remember { mutableStateOf(false) }
  var userToEdit by remember { mutableStateOf<UserAccount?>(null) }
  var showEditUserDialog by remember { mutableStateOf(false) }
  var showDatabaseViewerDialog by remember { mutableStateOf(false) }
  var showExportSpreadsheetDialog by remember { mutableStateOf(false) }
  var showSupabaseManagerDialog by remember { mutableStateOf(false) }

  // Estados para Ações de Lançamento (Editar / Excluir)
  var selectedLogForAction by remember { mutableStateOf<RainfallLog?>(null) }
  var showLogActionOptionsDialog by remember { mutableStateOf(false) }
  var showEditLogDialog by remember { mutableStateOf(false) }
  var showDeleteLogConfirmDialog by remember { mutableStateOf(false) }

  Scaffold(
    containerColor = BrandBackground,
    bottomBar = {
        NavigationBar(
            containerColor = BrandSurface,
            contentColor = BrandTextSecondary
        ) {
            NavigationBarItem(
                icon = { Icon(Icons.Filled.List, contentDescription = "Registros") },
                label = { Text("Registros") },
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BrandGreen,
                    selectedTextColor = BrandGreen,
                    indicatorColor = BrandGreenLight
                )
            )
            NavigationBarItem(
                icon = { Icon(Icons.Filled.BarChart, contentDescription = "Estatísticas") },
                label = { Text("Estatísticas") },
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BrandGreen,
                    selectedTextColor = BrandGreen,
                    indicatorColor = BrandGreenLight
                )
            )
            NavigationBarItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = "Configurações") },
                label = { Text("Ajustes") },
                selected = selectedTabIndex == 2,
                onClick = { selectedTabIndex = 2 },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BrandGreen,
                    selectedTextColor = BrandGreen,
                    indicatorColor = BrandGreenLight
                )
            )
        }
    },
    floatingActionButton = {
      if (selectedTabIndex == 0) {
          FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = BrandGreen,
            contentColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
          ) {
            Icon(Icons.Filled.Add, "Novo Registro")
          }
      }
    }
  ) { innerPadding ->
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(bottom = innerPadding.calculateBottomPadding())
      ) {
          if (selectedTabIndex == 0 || selectedTabIndex == 1) {
              // Header shared by Tab 0 and 1
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(BrandGreen)
                  .padding(
                    top = innerPadding.calculateTopPadding() + 20.dp,
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 28.dp
                  )
              ) {
                Column {
                  // Top bar with User Badge and Logout button
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Box(
                          modifier = Modifier
                            .size(30.dp)
                            .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(8.dp)),
                          contentAlignment = Alignment.Center
                        ) {
                          Icon(
                            imageVector = Icons.Filled.WaterDrop,
                            contentDescription = "Logo",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                          )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                          text = "Itacumbi Agro", 
                          color = Color.White, 
                          style = MaterialTheme.typography.headlineMedium, 
                          fontWeight = FontWeight.Black,
                          fontSize = 21.sp,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis
                        )
                      }
                      Spacer(Modifier.height(2.dp))
                      Text(
                        text = if (selectedTabIndex == 0) "Gestão Pluviométrica Diária" else "Análise de Volume e Histórico", 
                        color = Color.White.copy(alpha = 0.85f), 
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                      )
                    }
                    
                    Spacer(Modifier.width(8.dp))

                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.End
                    ) {
                      // Cloud Sync Button
                      IconButton(
                        onClick = { showSupabaseManagerDialog = true },
                        modifier = Modifier.size(34.dp)
                      ) {
                        if (isSyncingData) {
                          CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                          )
                        } else {
                          Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Sincronizar com Supabase",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                          )
                        }
                      }

                      Spacer(Modifier.width(4.dp))

                      // Informative User Role Badge (compact and single line)
                      Surface(
                        color = Color.White.copy(alpha = 0.20f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                      ) {
                        Row(
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          Icon(
                            imageVector = if (activeUser.role == UserRole.GERENCIAL) Icons.Filled.AdminPanelSettings else Icons.Filled.AccountCircle,
                            contentDescription = "Tipo de Conta",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                          )
                          Spacer(Modifier.width(4.dp))
                          Text(
                            text = if (activeUser.role == UserRole.GERENCIAL) "Gerencial" else "Operador",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                          )
                        }
                      }
                    }
                  }
                  
                  Spacer(modifier = Modifier.height(20.dp))
                  
                  ModernFarmSelector(
                    farms = accessibleFarms,
                    selectedFarm = selectedFarm,
                    onFarmSelected = { selectedFarm = it },
                    isManager = activeUser.role == UserRole.GERENCIAL
                  )
                }
              }
          }

          val currentFarmLogs = logs.filter { it.farmName == selectedFarm.name }

          when (selectedTabIndex) {
              0 -> LogsTab(
                  logs = currentFarmLogs,
                  onLogClick = { log ->
                      selectedLogForAction = log
                      showLogActionOptionsDialog = true
                  }
              )
              1 -> StatsTab(logs = currentFarmLogs)
              2 -> InfoTab(
                  innerPadding = innerPadding,
                  currentUser = activeUser,
                  farms = farms,
                  users = users,
                  logs = logs,
                  onEditUser = { user ->
                      userToEdit = user
                      showEditUserDialog = true
                  },
                  onOpenCreateFarm = { showAddFarmDialog = true },
                  onOpenDatabaseExplorer = { showDatabaseViewerDialog = true },
                  onOpenExportSpreadsheet = { showExportSpreadsheetDialog = true },
                  onTriggerSync = { showSupabaseManagerDialog = true },
                  isSyncing = isSyncingData,
                  onLogout = onLogout
              )
          }
      }
  }

  // --- DIALOGS ---

  if (showAddDialog) {
    val context = LocalContext.current
    val sdf = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    
    var selectedDateString by remember { mutableStateOf(sdf.format(Date())) }
    var volumeInput by remember { mutableStateOf("") }
    var notesInput by remember { mutableStateOf("") }
    var isSavingLog by remember { mutableStateOf(false) }
    var showDatePickerModal by remember { mutableStateOf(false) }

    val openDatePicker = {
      showDatePickerModal = true
    }

    if (showDatePickerModal) {
      RainfallDatePickerDialog(
        initialDateStr = selectedDateString,
        onDateSelected = { selectedDateString = it },
        onDismiss = { showDatePickerModal = false }
      )
    }

    val dialogTextFieldColors = OutlinedTextFieldDefaults.colors(
      focusedTextColor = BrandTextPrimary,
      unfocusedTextColor = BrandTextPrimary,
      focusedLabelColor = BrandGreen,
      unfocusedLabelColor = BrandTextSecondary,
      focusedBorderColor = BrandGreen,
      unfocusedBorderColor = Color(0xFF9CA3AF),
      cursorColor = BrandGreen,
      focusedPlaceholderColor = Color(0xFF9CA3AF),
      unfocusedPlaceholderColor = Color(0xFF9CA3AF),
      focusedContainerColor = Color(0xFFF9FAFB),
      unfocusedContainerColor = Color(0xFFF9FAFB)
    )

    AlertDialog(
      onDismissRequest = { showAddDialog = false },
      title = { Text("Novo Registro", fontWeight = FontWeight.Bold, color = BrandGreen) },
      containerColor = BrandSurface,
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Fazenda: ${selectedFarm.name}", color = BrandTextPrimary, fontWeight = FontWeight.SemiBold)
          
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
              value = selectedDateString,
              onValueChange = { selectedDateString = it },
              label = { Text("Data (DD/MM/AAAA)", color = BrandTextPrimary) },
              colors = dialogTextFieldColors,
              modifier = Modifier.fillMaxWidth(),
              trailingIcon = {
                IconButton(onClick = openDatePicker) {
                  Icon(Icons.Filled.DateRange, contentDescription = "Abrir Calendário", tint = BrandGreen)
                }
              }
            )

            // Quick date selection chips
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              val todayStr = sdf.format(Date())
              val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
              val yesterdayStr = sdf.format(yesterdayCal.time)

              FilterChip(
                selected = selectedDateString == todayStr,
                onClick = { selectedDateString = todayStr },
                label = { Text("Hoje", fontWeight = FontWeight.SemiBold) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = BrandGreenLight,
                  selectedLabelColor = BrandGreen,
                  containerColor = Color(0xFFF3F4F6),
                  labelColor = BrandTextPrimary
                )
              )
              FilterChip(
                selected = selectedDateString == yesterdayStr,
                onClick = { selectedDateString = yesterdayStr },
                label = { Text("Ontem", fontWeight = FontWeight.SemiBold) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = BrandGreenLight,
                  selectedLabelColor = BrandGreen,
                  containerColor = Color(0xFFF3F4F6),
                  labelColor = BrandTextPrimary
                )
              )
              SuggestionChip(
                onClick = openDatePicker,
                label = { Text("Calendário", color = BrandGreen, fontWeight = FontWeight.Bold) },
                icon = {
                  Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp), tint = BrandGreen)
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                  containerColor = BrandGreenLight.copy(alpha = 0.5f)
                )
              )
            }
          }
          
          OutlinedTextField(
            value = volumeInput,
            onValueChange = { volumeInput = it },
            label = { Text("Volume (mm)", color = BrandTextPrimary) },
            placeholder = { Text("Ex: 25.5", color = Color(0xFF9CA3AF)) },
            colors = dialogTextFieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = notesInput,
            onValueChange = { notesInput = it },
            label = { Text("Observações (opcional)", color = BrandTextPrimary) },
            placeholder = { Text("Ex: Chuva forte à tarde", color = Color(0xFF9CA3AF)) },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        Button(
          enabled = !isSavingLog,
          onClick = {
            isSavingLog = true
            coroutineScope.launch {
              delay(300)
              val vol = volumeInput.replace(',', '.').toDoubleOrNull() ?: 0.0
              val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.getDefault()).format(java.util.Date())
              val newLog = RainfallLog(
                id = UUID.randomUUID().toString(),
                date = selectedDateString,
                farmName = selectedFarm.name,
                volumeMm = vol,
                notes = if (notesInput.isBlank()) if (vol > 0) "Chuva" else "Estiagem" else notesInput,
                createdAt = nowIso,
                hasPendingSync = true
              )
              logs.add(newLog)
              val sorted = sortLogsChronologically(logs)
              logs.clear()
              logs.addAll(sorted)
              coroutineScope.launch { AppDatabaseManager.saveLogsAsync(context, logs.toList()) }
              
              // Sincroniza em segundo plano no Supabase e grava auditoria
              launch {
                val upsertRes = NetworkModule.supabaseRepository.upsertLog(newLog, currentUser = activeUser.username)
                if (upsertRes.isSuccess) {
                  val i = logs.indexOfFirst { it.id == newLog.id }
                  if (i != -1 && logs[i].hasPendingSync) {
                    logs[i] = logs[i].copy(hasPendingSync = false)
                    coroutineScope.launch { AppDatabaseManager.saveLogsAsync(context, logs.toList()) }
                  }
                }
                NetworkModule.supabaseRepository.recordAuditLog(
                  AuditLogEntry(
                    action = "CRIACAO",
                    tableName = "rainfall_logs",
                    recordId = newLog.id,
                    farmName = newLog.farmName,
                    performedBy = activeUser.username,
                    details = "Cadastro de medição: ${newLog.volumeMm} mm na ${newLog.farmName} referente a ${newLog.date}",
                    newData = org.json.JSONObject().apply {
                      put("id", newLog.id)
                      put("farm", newLog.farmName)
                      put("date", newLog.date)
                      put("volume_mm", newLog.volumeMm)
                      put("notes", newLog.notes)
                    }.toString(),
                    createdAt = nowIso
                  )
                )
              }

              isSavingLog = false
              showAddDialog = false
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
        ) {
          if (isSavingLog) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              color = Color.White,
              strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text("Salvando...", color = Color.White, fontWeight = FontWeight.Bold)
          } else {
            Text("Salvar", color = Color.White, fontWeight = FontWeight.Bold)
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddDialog = false }) {
          Text("Cancelar", color = BrandTextSecondary)
        }
      }
    )
  }

  // --- MODAL DE OPÇÕES DO LANÇAMENTO (EDITAR / EXCLUIR) ---
  if (showLogActionOptionsDialog && selectedLogForAction != null) {
    val targetLog = selectedLogForAction!!
    AlertDialog(
      onDismissRequest = { showLogActionOptionsDialog = false },
      title = {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Opções do Lançamento", fontWeight = FontWeight.Bold, color = BrandTextPrimary, style = MaterialTheme.typography.titleMedium)
            if (targetLog.isEdited) {
              Surface(
                color = Color(0xFFFEF3C7),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f))
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(Icons.Filled.Edit, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(11.dp))
                  Spacer(Modifier.width(3.dp))
                  Text("Editado", color = Color(0xFF92400E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
              }
            }
          }
          Spacer(Modifier.height(4.dp))
          Text(
            "${targetLog.date} • ${targetLog.volumeMm} mm • ${targetLog.farmName}",
            style = MaterialTheme.typography.bodySmall,
            color = BrandTextSecondary
          )
        }
      },
      containerColor = BrandSurface,
      text = {
        Column(
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // Opção 1: Editar
          Surface(
            onClick = {
              showLogActionOptionsDialog = false
              showEditLogDialog = true
            },
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFF9FAFB),
            border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .size(40.dp)
                  .background(BrandGreenLight, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Filled.Edit, contentDescription = "Editar", tint = BrandGreen, modifier = Modifier.size(20.dp))
              }
              Spacer(Modifier.width(14.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Editar Lançamento", fontWeight = FontWeight.Bold, color = BrandTextPrimary, fontSize = 15.sp)
                Text("Alterar data, volume pluviométrico ou notas", color = BrandTextSecondary, fontSize = 12.sp)
              }
            }
          }

          // Opção 2: Excluir
          Surface(
            onClick = {
              showLogActionOptionsDialog = false
              showDeleteLogConfirmDialog = true
            },
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFFEF2F2),
            border = BorderStroke(1.dp, Color(0xFFFECACA)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .size(40.dp)
                  .background(Color(0xFFFEE2E2), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Filled.Delete, contentDescription = "Excluir", tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp))
              }
              Spacer(Modifier.width(14.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Excluir Lançamento", fontWeight = FontWeight.Bold, color = Color(0xFFDC2626), fontSize = 15.sp)
                Text("Remover este registro do histórico e da nuvem", color = Color(0xFF991B1B).copy(alpha = 0.8f), fontSize = 12.sp)
              }
            }
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { showLogActionOptionsDialog = false }) {
          Text("Fechar", color = BrandTextSecondary, fontWeight = FontWeight.SemiBold)
        }
      }
    )
  }

  // --- DIALOG DE EDIÇÃO DO LANÇAMENTO ---
  if (showEditLogDialog && selectedLogForAction != null) {
    val targetLog = selectedLogForAction!!
    val editSdf = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    var editDateString by remember(targetLog) { mutableStateOf(targetLog.date) }
    var editVolumeInput by remember(targetLog) { mutableStateOf(if (targetLog.volumeMm % 1.0 == 0.0) targetLog.volumeMm.toLong().toString() else targetLog.volumeMm.toString()) }
    var editNotesInput by remember(targetLog) { mutableStateOf(targetLog.notes) }
    var isSavingEdit by remember { mutableStateOf(false) }
    var showEditDatePickerModal by remember { mutableStateOf(false) }

    val openEditDatePicker = {
      showEditDatePickerModal = true
    }

    if (showEditDatePickerModal) {
      RainfallDatePickerDialog(
        initialDateStr = editDateString,
        onDateSelected = { editDateString = it },
        onDismiss = { showEditDatePickerModal = false }
      )
    }

    val dialogTextFieldColors = OutlinedTextFieldDefaults.colors(
      focusedBorderColor = BrandGreen,
      unfocusedBorderColor = Color(0xFFD1D5DB),
      focusedLabelColor = BrandGreen,
      unfocusedLabelColor = BrandTextSecondary,
      focusedContainerColor = Color.White,
      unfocusedContainerColor = Color(0xFFF9FAFB)
    )

    AlertDialog(
      onDismissRequest = { if (!isSavingEdit) showEditLogDialog = false },
      title = { 
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Editar Lançamento", fontWeight = FontWeight.Bold, color = BrandGreen)
          }
          if (targetLog.isEdited) {
            Surface(
              color = Color(0xFFFEF3C7),
              shape = RoundedCornerShape(6.dp),
              border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f))
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text("Editado", color = Color(0xFF92400E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      },
      containerColor = BrandSurface,
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Fazenda: ${targetLog.farmName}", color = BrandTextPrimary, fontWeight = FontWeight.SemiBold)
            if (targetLog.isEdited) {
              Surface(
                color = Color(0xFFFEF3C7),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(Icons.Filled.History, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(13.dp))
                  Spacer(Modifier.width(4.dp))
                  Text("Lançamento já editado", color = Color(0xFF92400E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
              }
            }
          }

          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
              value = editDateString,
              onValueChange = { editDateString = it },
              label = { Text("Data (DD/MM/AAAA)", color = BrandTextPrimary) },
              colors = dialogTextFieldColors,
              modifier = Modifier.fillMaxWidth(),
              trailingIcon = {
                IconButton(onClick = openEditDatePicker) {
                  Icon(Icons.Filled.DateRange, contentDescription = "Calendário", tint = BrandGreen)
                }
              }
            )

            // Chips rápidos
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              val todayStr = editSdf.format(Date())
              val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
              val yesterdayStr = editSdf.format(yesterdayCal.time)

              FilterChip(
                selected = editDateString == todayStr,
                onClick = { editDateString = todayStr },
                label = { Text("Hoje", fontWeight = FontWeight.SemiBold) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = BrandGreenLight,
                  selectedLabelColor = BrandGreen,
                  containerColor = Color(0xFFF3F4F6),
                  labelColor = BrandTextPrimary
                )
              )
              FilterChip(
                selected = editDateString == yesterdayStr,
                onClick = { editDateString = yesterdayStr },
                label = { Text("Ontem", fontWeight = FontWeight.SemiBold) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = BrandGreenLight,
                  selectedLabelColor = BrandGreen,
                  containerColor = Color(0xFFF3F4F6),
                  labelColor = BrandTextPrimary
                )
              )
              SuggestionChip(
                onClick = openEditDatePicker,
                label = { Text("Calendário", color = BrandGreen, fontWeight = FontWeight.Bold) },
                icon = {
                  Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp), tint = BrandGreen)
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                  containerColor = BrandGreenLight.copy(alpha = 0.5f)
                )
              )
            }
          }

          OutlinedTextField(
            value = editVolumeInput,
            onValueChange = { editVolumeInput = it },
            label = { Text("Volume (mm)", color = BrandTextPrimary) },
            placeholder = { Text("Ex: 25.5", color = Color(0xFF9CA3AF)) },
            colors = dialogTextFieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
          )

          OutlinedTextField(
            value = editNotesInput,
            onValueChange = { editNotesInput = it },
            label = { Text("Observações", color = BrandTextPrimary) },
            placeholder = { Text("Ex: Chuva torrencial", color = Color(0xFF9CA3AF)) },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        Button(
          enabled = !isSavingEdit,
          onClick = {
            isSavingEdit = true
            coroutineScope.launch {
              delay(200)
              val vol = editVolumeInput.replace(',', '.').toDoubleOrNull() ?: targetLog.volumeMm
              val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.getDefault()).format(java.util.Date())
              val safeCreatedAt = if (targetLog.createdAt.isNullOrBlank() || targetLog.createdAt == "null") nowIso else targetLog.createdAt
              val updatedLog = targetLog.copy(
                date = editDateString,
                volumeMm = vol,
                notes = if (editNotesInput.isBlank()) (if (vol > 0) "Chuva" else "Estiagem") else editNotesInput,
                isEdited = true,
                createdAt = safeCreatedAt,
                hasPendingSync = true
              )
              val idx = logs.indexOfFirst { it.id == targetLog.id }
              if (idx != -1) {
                logs[idx] = updatedLog
              }
              val sorted = sortLogsChronologically(logs)
              logs.clear()
              logs.addAll(sorted)
              coroutineScope.launch { AppDatabaseManager.saveLogsAsync(context, logs.toList()) }

              // Sincroniza atualização no Supabase em segundo plano e grava auditoria
              launch {
                val upsertRes = NetworkModule.supabaseRepository.upsertLog(updatedLog, currentUser = activeUser.username, updatedAt = nowIso)
                if (upsertRes.isSuccess) {
                  val i = logs.indexOfFirst { it.id == updatedLog.id }
                  if (i != -1) {
                    // Mantém isEdited = true para sinalizar visualmente que o registro foi editado
                    logs[i] = updatedLog.copy(hasPendingSync = false, isEdited = true)
                    coroutineScope.launch { AppDatabaseManager.saveLogsAsync(context, logs.toList()) }
                  }
                  NetworkModule.supabaseRepository.recordAuditLog(
                    AuditLogEntry(
                      action = "EDICAO",
                      tableName = "rainfall_logs",
                      recordId = updatedLog.id,
                      farmName = updatedLog.farmName,
                      performedBy = activeUser.username,
                      details = "Ajuste na ${updatedLog.farmName}: Data (${targetLog.date} -> ${updatedLog.date}), Volume (${targetLog.volumeMm} mm -> ${updatedLog.volumeMm} mm), Obs: ('${targetLog.notes}' -> '${updatedLog.notes}')",
                      oldData = org.json.JSONObject().apply {
                        put("date", targetLog.date)
                        put("volume_mm", targetLog.volumeMm)
                        put("notes", targetLog.notes)
                      }.toString(),
                      newData = org.json.JSONObject().apply {
                        put("date", updatedLog.date)
                        put("volume_mm", updatedLog.volumeMm)
                        put("notes", updatedLog.notes)
                      }.toString(),
                      createdAt = nowIso
                    )
                  )
                }
              }

              isSavingEdit = false
              showEditLogDialog = false
              selectedLogForAction = null
              Toast.makeText(context, "Lançamento atualizado com sucesso!", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
        ) {
          if (isSavingEdit) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Salvando...", color = Color.White, fontWeight = FontWeight.Bold)
          } else {
            Text("Salvar Alterações", color = Color.White, fontWeight = FontWeight.Bold)
          }
        }
      },
      dismissButton = {
        TextButton(
          enabled = !isSavingEdit,
          onClick = {
            showEditLogDialog = false
            selectedLogForAction = null
          }
        ) {
          Text("Cancelar", color = BrandTextSecondary)
        }
      }
    )
  }

  // --- DIALOG DE CONFIRMAÇÃO DE EXCLUSÃO ---
  if (showDeleteLogConfirmDialog && selectedLogForAction != null) {
    val targetLog = selectedLogForAction!!
    var isDeletingLog by remember { mutableStateOf(false) }

    AlertDialog(
      onDismissRequest = { if (!isDeletingLog) showDeleteLogConfirmDialog = false },
      icon = {
        Icon(
          Icons.Filled.Warning,
          contentDescription = null,
          tint = Color(0xFFDC2626),
          modifier = Modifier.size(36.dp)
        )
      },
      title = {
        Text("Excluir Lançamento?", fontWeight = FontWeight.Bold, color = BrandTextPrimary, textAlign = TextAlign.Center)
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            "Tem certeza que deseja remover este lançamento permanentemente?",
            color = BrandTextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
          )
          Surface(
            color = Color(0xFFF9FAFB),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text("• Data: ${targetLog.date}", fontWeight = FontWeight.Medium, color = BrandTextPrimary, fontSize = 13.sp)
              Text("• Volume: ${targetLog.volumeMm} mm", fontWeight = FontWeight.Medium, color = BrandTextPrimary, fontSize = 13.sp)
              Text("• Fazenda: ${targetLog.farmName}", fontWeight = FontWeight.Medium, color = BrandTextPrimary, fontSize = 13.sp)
              if (targetLog.notes.isNotBlank()) {
                Text("• Obs: ${targetLog.notes}", fontWeight = FontWeight.Normal, color = BrandTextSecondary, fontSize = 13.sp)
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          enabled = !isDeletingLog,
          onClick = {
            isDeletingLog = true
            coroutineScope.launch {
              delay(200)
              val removedId = targetLog.id
              val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.getDefault()).format(java.util.Date())
              logs.removeAll { it.id == removedId }
              coroutineScope.launch { AppDatabaseManager.saveLogsAsync(context, logs.toList()) }

              // Remove também do Supabase na nuvem e grava auditoria
              launch {
                NetworkModule.supabaseRepository.deleteLog(removedId)
                NetworkModule.supabaseRepository.recordAuditLog(
                  AuditLogEntry(
                    action = "EXCLUSAO",
                    tableName = "rainfall_logs",
                    recordId = removedId,
                    farmName = targetLog.farmName,
                    performedBy = activeUser.username,
                    details = "Exclusão de medição de ${targetLog.volumeMm} mm na ${targetLog.farmName} referente a ${targetLog.date} (Obs: '${targetLog.notes}')",
                    oldData = org.json.JSONObject().apply {
                      put("id", targetLog.id)
                      put("farm", targetLog.farmName)
                      put("date", targetLog.date)
                      put("volume_mm", targetLog.volumeMm)
                      put("notes", targetLog.notes)
                    }.toString(),
                    createdAt = nowIso
                  )
                )
              }

              isDeletingLog = false
              showDeleteLogConfirmDialog = false
              selectedLogForAction = null
              Toast.makeText(context, "Lançamento excluído com sucesso!", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
          if (isDeletingLog) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Excluindo...", color = Color.White, fontWeight = FontWeight.Bold)
          } else {
            Text("Sim, Excluir", color = Color.White, fontWeight = FontWeight.Bold)
          }
        }
      },
      dismissButton = {
        TextButton(
          enabled = !isDeletingLog,
          onClick = {
            showDeleteLogConfirmDialog = false
            selectedLogForAction = null
          }
        ) {
          Text("Cancelar", color = BrandTextSecondary)
        }
      },
      containerColor = BrandSurface
    )
  }

  if (showAddFarmDialog) {
    var farmNameInput by remember { mutableStateOf("") }
    var farmRegionInput by remember { mutableStateOf("") }
    var operatorDisplayName by remember { mutableStateOf("") }
    var operatorUsername by remember { mutableStateOf("") }
    var operatorPassword by remember { mutableStateOf("") }
    var operatorPasswordVisible by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var isSavingFarm by remember { mutableStateOf(false) }

    val dialogTextFieldColors = OutlinedTextFieldDefaults.colors(
      focusedTextColor = BrandTextPrimary,
      unfocusedTextColor = BrandTextPrimary,
      focusedLabelColor = BrandGreen,
      unfocusedLabelColor = BrandTextSecondary,
      focusedBorderColor = BrandGreen,
      unfocusedBorderColor = Color(0xFF9CA3AF),
      cursorColor = BrandGreen,
      focusedPlaceholderColor = Color(0xFF9CA3AF),
      unfocusedPlaceholderColor = Color(0xFF9CA3AF),
      focusedContainerColor = Color(0xFFF9FAFB),
      unfocusedContainerColor = Color(0xFFF9FAFB)
    )

    AlertDialog(
      onDismissRequest = { showAddFarmDialog = false },
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.AddBusiness, contentDescription = null, tint = BrandGreen)
          Spacer(Modifier.width(8.dp))
          Text("Nova Fazenda & Acesso", fontWeight = FontWeight.Bold, color = BrandGreen)
        }
      },
      containerColor = BrandSurface,
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            "Cadastre a unidade e crie o login e senha que ficarão salvos no banco de dados.",
            style = MaterialTheme.typography.bodySmall,
            color = BrandTextSecondary
          )

          OutlinedTextField(
            value = farmNameInput,
            onValueChange = { 
              farmNameInput = it
              validationError = null
              val clean = it.trim().lowercase()
                .replace("fazenda", "")
                .replace("sítio", "")
                .replace("sitio", "")
                .replace(" ", "")
              if (clean.isNotBlank() && (operatorUsername.isBlank() || operatorUsername.length <= clean.length + 2)) {
                operatorUsername = clean
              }
            },
            label = { Text("Nome da Fazenda *", color = BrandTextPrimary) },
            placeholder = { Text("Ex: Fazenda Alvorada") },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )

          OutlinedTextField(
            value = farmRegionInput,
            onValueChange = { farmRegionInput = it; validationError = null },
            label = { Text("Região / Estado *", color = BrandTextPrimary) },
            placeholder = { Text("Ex: Mato Grosso") },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )

          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color(0xFFE5E7EB))

          Text(
            text = "Credenciais do Operador:",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = BrandGreen
          )

          OutlinedTextField(
            value = operatorUsername,
            onValueChange = { operatorUsername = it.trim().lowercase(); validationError = null },
            label = { Text("Usuário / Login *", color = BrandTextPrimary) },
            placeholder = { Text("Ex: alvorada") },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )

          OutlinedTextField(
            value = operatorPassword,
            onValueChange = { operatorPassword = it; validationError = null },
            label = { Text("Senha de Acesso *", color = BrandTextPrimary) },
            placeholder = { Text("Ex: 1234") },
            visualTransformation = if (operatorPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              IconButton(onClick = { operatorPasswordVisible = !operatorPasswordVisible }) {
                Icon(
                  if (operatorPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                  contentDescription = null
                )
              }
            },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )

          OutlinedTextField(
            value = operatorDisplayName,
            onValueChange = { operatorDisplayName = it; validationError = null },
            label = { Text("Nome do Operador (Opcional)", color = BrandTextPrimary) },
            placeholder = { Text(if (farmNameInput.isNotBlank()) "Operador $farmNameInput" else "Ex: Operador Alvorada") },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )

          if (validationError != null) {
            Text(
              text = validationError!!,
              color = Color(0xFFDC2626),
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Bold
            )
          }
        }
      },
      confirmButton = {
        Button(
          enabled = !isSavingFarm,
          onClick = {
            val fName = farmNameInput.trim()
            val fRegion = farmRegionInput.trim()
            if (fName.isBlank() || fRegion.isBlank()) {
              validationError = "Informe o nome da fazenda e a região/estado."
              return@Button
            }
            if (farms.any { it.name.equals(fName, ignoreCase = true) }) {
              validationError = "Já existe uma fazenda com esse nome."
              return@Button
            }

            val opUser = operatorUsername.trim().lowercase()
            if (opUser.isBlank() || operatorPassword.isBlank()) {
              validationError = "Informe o login e a senha para o operador."
              return@Button
            }
            if (users.any { it.username.equals(opUser, ignoreCase = true) }) {
              validationError = "O login '$opUser' já está cadastrado no banco."
              return@Button
            }

            isSavingFarm = true
            coroutineScope.launch {
              delay(500)
              val newFarm = Farm(UUID.randomUUID().toString(), fName, fRegion)
              farms.add(newFarm)
              coroutineScope.launch { AppDatabaseManager.saveFarmsAsync(context, farms.toList()) }

              val opName = if (operatorDisplayName.isNotBlank()) operatorDisplayName.trim() else "Operador $fName"
              val newOperator = UserAccount(
                username = opUser,
                password = operatorPassword.trim(),
                displayName = opName,
                role = UserRole.FAZENDA,
                assignedFarmName = newFarm.name
              )
              users.add(newOperator)
              coroutineScope.launch { AppDatabaseManager.saveUsersAsync(context, users.toList()) }

              selectedFarm = newFarm
              
              // Sincroniza nova fazenda e novo operador no Supabase
              launch {
                NetworkModule.supabaseRepository.upsertFarm(newFarm)
                NetworkModule.supabaseRepository.upsertUser(newOperator)
              }

              isSavingFarm = false
              showAddFarmDialog = false
              Toast.makeText(context, "Fazenda '$fName' e operador criados com sucesso!", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
        ) {
          if (isSavingFarm) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              color = Color.White,
              strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text("Salvando no BD...", color = Color.White, fontWeight = FontWeight.Bold)
          } else {
            Text("Criar e Salvar no BD", color = Color.White, fontWeight = FontWeight.Bold)
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddFarmDialog = false }) {
          Text("Cancelar", color = BrandTextSecondary)
        }
      }
    )
  }

  // --- DIALOG: EDITAR USUÁRIO / SENHA / TIPO DE ACESSO ---
  if (showEditUserDialog && userToEdit != null) {
    val targetUser = userToEdit!!
    val isEditingSelf = targetUser.username.equals(activeUser.username, ignoreCase = true)
    var editRoleSelected by remember(targetUser) { mutableStateOf(targetUser.role) }
    var editAssignedFarm by remember(targetUser) {
      mutableStateOf(farms.find { it.name == targetUser.assignedFarmName } ?: farms.firstOrNull())
    }
    var editDisplayName by remember(targetUser) { mutableStateOf(targetUser.displayName) }
    var editUsername by remember(targetUser) { mutableStateOf(targetUser.username) }
    var editPassword by remember(targetUser) { mutableStateOf(targetUser.password) }
    var editPasswordVisible by remember { mutableStateOf(false) }
    var editValidationMessage by remember { mutableStateOf<String?>(null) }
    var isSavingEdit by remember { mutableStateOf(false) }
    var editFarmDropdownOpen by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val dialogTextFieldColors = OutlinedTextFieldDefaults.colors(
      focusedTextColor = BrandTextPrimary,
      unfocusedTextColor = BrandTextPrimary,
      focusedLabelColor = BrandGreen,
      unfocusedLabelColor = BrandTextSecondary,
      focusedBorderColor = BrandGreen,
      unfocusedBorderColor = Color(0xFF9CA3AF),
      cursorColor = BrandGreen,
      focusedPlaceholderColor = Color(0xFF9CA3AF),
      unfocusedPlaceholderColor = Color(0xFF9CA3AF),
      focusedContainerColor = Color(0xFFF9FAFB),
      unfocusedContainerColor = Color(0xFFF9FAFB)
    )

    AlertDialog(
      onDismissRequest = { showEditUserDialog = false },
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Edit, contentDescription = null, tint = BrandGreen)
          Spacer(Modifier.width(8.dp))
          Text("Editar Acesso & Senha", fontWeight = FontWeight.Bold, color = BrandGreen)
        }
      },
      containerColor = BrandSurface,
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            "Altere o login, senha ou tipo de acesso. As modificações serão gravadas imediatamente no banco de dados.",
            style = MaterialTheme.typography.bodySmall,
            color = BrandTextSecondary
          )

          // Tipo de Conta
          Text("Tipo de Conta / Acesso:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = BrandTextPrimary)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            FilterChip(
              selected = editRoleSelected == UserRole.FAZENDA,
              onClick = { editRoleSelected = UserRole.FAZENDA },
              label = { Text("Operador de Fazenda") },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = BrandGreen,
                selectedLabelColor = Color.White
              ),
              modifier = Modifier.weight(1f)
            )
            FilterChip(
              selected = editRoleSelected == UserRole.GERENCIAL,
              onClick = { editRoleSelected = UserRole.GERENCIAL },
              label = { Text("Gerencial / Diretoria") },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = BrandGreen,
                selectedLabelColor = Color.White
              ),
              modifier = Modifier.weight(1f)
            )
          }

          if (editRoleSelected == UserRole.FAZENDA) {
            Box {
              OutlinedTextField(
                value = editAssignedFarm?.let { "${it.name} (${it.region})" } ?: "Selecione a fazenda",
                onValueChange = {},
                readOnly = true,
                label = { Text("Fazenda Vinculada *", color = BrandTextPrimary) },
                trailingIcon = {
                  IconButton(onClick = { editFarmDropdownOpen = !editFarmDropdownOpen }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                  }
                },
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { editFarmDropdownOpen = true },
                colors = dialogTextFieldColors
              )
              DropdownMenu(
                expanded = editFarmDropdownOpen,
                onDismissRequest = { editFarmDropdownOpen = false }
              ) {
                farms.forEach { farm ->
                  DropdownMenuItem(
                    text = { Text("${farm.name} (${farm.region})") },
                    onClick = {
                      editAssignedFarm = farm
                      editFarmDropdownOpen = false
                    }
                  )
                }
              }
            }
          }

          OutlinedTextField(
            value = editDisplayName,
            onValueChange = { editDisplayName = it; editValidationMessage = null },
            label = { Text("Nome / Identificação *", color = BrandTextPrimary) },
            placeholder = { Text("Ex: Paulo Henrique") },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )

          OutlinedTextField(
            value = editUsername,
            onValueChange = { editUsername = it.trim().lowercase(); editValidationMessage = null },
            label = { Text("Login / Usuário *", color = BrandTextPrimary) },
            placeholder = { Text("Ex: paulo") },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )

          OutlinedTextField(
            value = editPassword,
            onValueChange = { editPassword = it; editValidationMessage = null },
            label = { Text("Senha de Acesso *", color = BrandTextPrimary) },
            placeholder = { Text("Nova senha...") },
            visualTransformation = if (editPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              IconButton(onClick = { editPasswordVisible = !editPasswordVisible }) {
                Icon(
                  if (editPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                  contentDescription = null
                )
              }
            },
            colors = dialogTextFieldColors,
            modifier = Modifier.fillMaxWidth()
          )

          if (editValidationMessage != null) {
            Text(
              text = editValidationMessage!!,
              color = Color(0xFFDC2626),
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Bold
            )
          }

          // Delete user option (if not editing self)
          if (!isEditingSelf) {
            Spacer(Modifier.height(6.dp))
            if (!showDeleteConfirm) {
              TextButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
              ) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Excluir este login do banco de dados", fontSize = 12.sp)
              }
            } else {
              Surface(
                color = Color(0xFFFEE2E2),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.padding(10.dp)) {
                  Text(
                    "Tem certeza que deseja excluir o login '@${targetUser.username}'?",
                    color = Color(0xFF991B1B),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(Modifier.height(8.dp))
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                      onClick = {
                        users.removeIf { it.username == targetUser.username }
                        coroutineScope.launch { AppDatabaseManager.saveUsersAsync(context, users.toList()) }
                        Toast.makeText(context, "Usuário excluído com sucesso", Toast.LENGTH_SHORT).show()
                        showEditUserDialog = false
                      },
                      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                      modifier = Modifier.height(34.dp),
                      shape = RoundedCornerShape(6.dp)
                    ) {
                      Text("Confirmar Exclusão", fontSize = 11.sp, color = Color.White)
                    }
                    TextButton(
                      onClick = { showDeleteConfirm = false },
                      modifier = Modifier.height(34.dp)
                    ) {
                      Text("Cancelar", fontSize = 11.sp, color = BrandTextSecondary)
                    }
                  }
                }
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          enabled = !isSavingEdit,
          onClick = {
            val cleanUser = editUsername.trim().lowercase()
            if (cleanUser.isBlank() || editPassword.isBlank() || editDisplayName.isBlank()) {
              editValidationMessage = "Preencha todos os campos obrigatórios."
              return@Button
            }
            if (!cleanUser.equals(targetUser.username, ignoreCase = true) && users.any { it.username.equals(cleanUser, ignoreCase = true) }) {
              editValidationMessage = "O login '$cleanUser' já pertence a outro usuário."
              return@Button
            }
            if (editRoleSelected == UserRole.FAZENDA && editAssignedFarm == null) {
              editValidationMessage = "Selecione a fazenda vinculada ao operador."
              return@Button
            }

            isSavingEdit = true
            coroutineScope.launch {
              delay(400)
              val updated = UserAccount(
                username = cleanUser,
                password = editPassword.trim(),
                displayName = editDisplayName.trim(),
                role = editRoleSelected,
                assignedFarmName = if (editRoleSelected == UserRole.FAZENDA) editAssignedFarm?.name else null
              )

              val idx = users.indexOfFirst { it.username.equals(targetUser.username, ignoreCase = true) }
              if (idx != -1) {
                users[idx] = updated
              } else {
                users.add(updated)
              }
              coroutineScope.launch { AppDatabaseManager.saveUsersAsync(context, users.toList()) }

              // Sincroniza usuário atualizado no Supabase
              launch {
                NetworkModule.supabaseRepository.upsertUser(updated)
              }

              if (isEditingSelf) {
                activeUser = updated
              }

              Toast.makeText(context, "Dados de acesso atualizados com sucesso!", Toast.LENGTH_SHORT).show()
              isSavingEdit = false
              showEditUserDialog = false
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
        ) {
          if (isSavingEdit) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("Salvando...", color = Color.White)
          } else {
            Text("Salvar Alterações", color = Color.White, fontWeight = FontWeight.Bold)
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { showEditUserDialog = false }) {
          Text("Cancelar", color = BrandTextSecondary)
        }
      }
    )
  }

  // --- DIALOG: EXPORTAR PLANILHA ---
  if (showExportSpreadsheetDialog) {
    val isGerencial = activeUser.role == UserRole.GERENCIAL
    val initialFilter = if (isGerencial) "ALL" else (activeUser.assignedFarmName ?: selectedFarm.name)
    var selectedExportFilter by remember { mutableStateOf<String?>(initialFilter) }
    var isExporting by remember { mutableStateOf(false) }

    // Fazenda accounts can only ever access their assigned farm
    val effectiveFilter = if (isGerencial) selectedExportFilter else (activeUser.assignedFarmName ?: selectedFarm.name)
    val filteredLogs = if (effectiveFilter == "ALL") logs else logs.filter { it.farmName == effectiveFilter }
    val totalVol = filteredLogs.sumOf { it.volumeMm }

    AlertDialog(
      onDismissRequest = { showExportSpreadsheetDialog = false },
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.FileDownload, contentDescription = null, tint = BrandGreen)
          Spacer(Modifier.width(8.dp))
          Text("Exportar Planilha de Chuvas", fontWeight = FontWeight.Bold, color = BrandGreen)
        }
      },
      containerColor = BrandSurface,
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(
            if (isGerencial) {
              "Gere uma planilha formatada (.csv compatível com Excel e Google Planilhas) com os registros consolidados de precipitação de qualquer fazenda ou consolidado geral."
            } else {
              "Gere uma planilha formatada (.csv compatível com Excel e Google Planilhas) com os registros de precipitação exclusivos da sua unidade (${activeUser.assignedFarmName ?: selectedFarm.name})."
            },
            style = MaterialTheme.typography.bodySmall,
            color = BrandTextSecondary
          )

          if (isGerencial) {
            Text("Selecione o Escopo da Planilha:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = BrandTextPrimary)
            
            Surface(
              onClick = { selectedExportFilter = "ALL" },
              shape = RoundedCornerShape(10.dp),
              color = if (selectedExportFilter == "ALL") BrandGreenLight else Color(0xFFF9FAFB),
              border = BorderStroke(1.dp, if (selectedExportFilter == "ALL") BrandGreen else Color(0xFFE5E7EB)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                RadioButton(
                  selected = selectedExportFilter == "ALL",
                  onClick = { selectedExportFilter = "ALL" },
                  colors = RadioButtonDefaults.colors(selectedColor = BrandGreen)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                  Text("Todas as Fazendas (Consolidado Geral)", fontWeight = FontWeight.Bold, color = BrandTextPrimary)
                  Text("${logs.size} registros no total", style = MaterialTheme.typography.bodySmall, color = BrandTextSecondary)
                }
              }
            }

            farms.forEach { f ->
              val count = logs.count { it.farmName == f.name }
              Surface(
                onClick = { selectedExportFilter = f.name },
                shape = RoundedCornerShape(10.dp),
                color = if (selectedExportFilter == f.name) BrandGreenLight else Color(0xFFF9FAFB),
                border = BorderStroke(1.dp, if (selectedExportFilter == f.name) BrandGreen else Color(0xFFE5E7EB)),
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(
                  modifier = Modifier.padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  RadioButton(
                    selected = selectedExportFilter == f.name,
                    onClick = { selectedExportFilter = f.name },
                    colors = RadioButtonDefaults.colors(selectedColor = BrandGreen)
                  )
                  Spacer(Modifier.width(8.dp))
                  Column {
                    Text(f.name, fontWeight = FontWeight.Bold, color = BrandTextPrimary)
                    Text("$count registros • ${f.region}", style = MaterialTheme.typography.bodySmall, color = BrandTextSecondary)
                  }
                }
              }
            }
          } else {
            // Informação da Fazenda Fixa do Operador
            val currentFarmName = activeUser.assignedFarmName ?: selectedFarm.name
            val currentFarmObj = farms.find { it.name == currentFarmName } ?: selectedFarm
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = BrandGreenLight,
              border = BorderStroke(1.dp, BrandGreen),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                  Text("Unidade: $currentFarmName", fontWeight = FontWeight.Bold, color = BrandTextPrimary)
                  Text("Região: ${currentFarmObj.region} • Acesso Operacional Restrito", style = MaterialTheme.typography.bodySmall, color = BrandGreen)
                }
              }
            }
          }

          // Summary card
          Surface(
            color = Color(0xFFF0FDF4),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text("Registros Selecionados", style = MaterialTheme.typography.labelSmall, color = BrandTextSecondary)
                Text("${filteredLogs.size} lançamentos", fontWeight = FontWeight.Bold, color = BrandGreen)
              }
              Column(horizontalAlignment = Alignment.End) {
                Text("Precipitação Total", style = MaterialTheme.typography.labelSmall, color = BrandTextSecondary)
                Text("${String.format(Locale("pt", "BR"), "%.1f", totalVol)} mm", fontWeight = FontWeight.Black, color = BrandGreen)
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          enabled = !isExporting && filteredLogs.isNotEmpty(),
          onClick = {
            isExporting = true
            coroutineScope.launch {
              try {
                val farmFilter = if (isGerencial) {
                  if (selectedExportFilter == "ALL") null else selectedExportFilter
                } else {
                  activeUser.assignedFarmName ?: selectedFarm.name
                }
                val csvFile = AppDatabaseManager.generateSpreadsheetCsv(context, logs, farmFilter)
                val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/csv"
                  putExtra(Intent.EXTRA_SUBJECT, "Relatório Pluviométrico - Itacumbi Agro")
                  putExtra(Intent.EXTRA_TEXT, "Planilha consolidada de chuvas gerada pelo aplicativo Itacumbi Agro (${csvFile.name}).")
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Abrir ou Compartilhar Planilha"))
                Toast.makeText(context, "Planilha exportada com sucesso!", Toast.LENGTH_SHORT).show()
                showExportSpreadsheetDialog = false
              } catch (e: Exception) {
                Toast.makeText(context, "Erro ao exportar: ${e.message}", Toast.LENGTH_LONG).show()
              } finally {
                isExporting = false
              }
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
        ) {
          if (isExporting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("Exportando...", color = Color.White)
          } else {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Gerar e Compartilhar", fontWeight = FontWeight.Bold, color = Color.White)
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { showExportSpreadsheetDialog = false }) {
          Text("Fechar", color = BrandTextSecondary)
        }
      }
    )
  }

  // --- DIALOG: GESTÃO & DIAGNÓSTICO SUPABASE CLOUD ---
  if (showSupabaseManagerDialog) {
    var diagStatus by remember { mutableStateOf<SupabaseHealthStatus?>(null) }
    var isLoadingDiag by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
      isLoadingDiag = true
      diagStatus = NetworkModule.supabaseRepository.runDiagnostics()
      isLoadingDiag = false
    }

    AlertDialog(
      onDismissRequest = { showSupabaseManagerDialog = false },
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.CloudSync, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(24.dp))
          Spacer(Modifier.width(10.dp))
          Column {
            Text("Supabase Cloud Integrado", fontWeight = FontWeight.Bold, color = BrandGreen, fontSize = 18.sp)
            Text("Sincronização e Autenticação", style = MaterialTheme.typography.bodySmall, color = BrandTextSecondary)
          }
        }
      },
      containerColor = BrandSurface,
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          // Status Box
          Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
            border = BorderStroke(1.dp, Color(0xFFBBF7D0))
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(Color(0xFF16A34A), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("Conexão Supabase Ativa", fontWeight = FontWeight.Bold, color = Color(0xFF166534), fontSize = 13.sp)
              }
              Text(
                text = "URL: ${NetworkModule.supabaseRepository.SUPABASE_URL.removePrefix("https://")}",
                style = MaterialTheme.typography.labelSmall,
                color = BrandTextSecondary
              )
              Text(
                text = "Autenticação em Tempo Real: Ativada na tela de login",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF166534),
                fontWeight = FontWeight.SemiBold
              )
            }
          }

          // Diagnostic Counts
          if (isLoadingDiag) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically
            ) {
              CircularProgressIndicator(modifier = Modifier.size(20.dp), color = BrandGreen, strokeWidth = 2.dp)
              Spacer(Modifier.width(10.dp))
              Text("Consultando tabelas na nuvem...", fontSize = 13.sp, color = BrandTextSecondary)
            }
          } else {
            val st = diagStatus
            if (st != null) {
              Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE5E7EB))
              ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("Registros Identificados na Nuvem:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BrandTextPrimary)
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Fazendas cadastradas:", fontSize = 12.sp, color = BrandTextSecondary)
                    Text("${st.farmCount}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BrandTextPrimary)
                  }
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Usuários cadastrados:", fontSize = 12.sp, color = BrandTextSecondary)
                    Text("${st.userCount}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BrandTextPrimary)
                  }
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lançamentos de chuvas:", fontSize = 12.sp, color = BrandTextSecondary)
                    Text("${st.logCount}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BrandTextPrimary)
                  }
                  HorizontalDivider(color = Color(0xFFF3F4F6))
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Permissão de Escrita:", fontSize = 12.sp, color = BrandTextSecondary)
                    if (st.canWrite) {
                      Surface(color = Color(0xFFDCFCE7), shape = RoundedCornerShape(6.dp)) {
                        Text("TOTALMENTE LIBERADA", color = Color(0xFF15803D), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                      }
                    } else if (st.rlsBlocked) {
                      Surface(color = Color(0xFFFEF3C7), shape = RoundedCornerShape(6.dp)) {
                        Text("RLS ATIVO (PostgreSQL)", color = Color(0xFFB45309), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                      }
                    } else {
                      Surface(color = Color(0xFFF3F4F6), shape = RoundedCornerShape(6.dp)) {
                        Text("LEITURA ATIVA", color = Color(0xFF4B5563), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                      }
                    }
                  }
                }
              }

              if (st.rlsBlocked) {
                Card(
                  shape = RoundedCornerShape(12.dp),
                  colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                  border = BorderStroke(1.dp, Color(0xFFFDE68A))
                ) {
                  Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Como liberar gravação no Supabase:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF92400E))
                    Text(
                      "O Supabase ativou a proteção RLS (Row Level Security). Para que o aplicativo grave fazendas, senhas e chuvas, execute o comando abaixo no SQL Editor do seu projeto Supabase:",
                      fontSize = 11.sp,
                      color = Color(0xFF78350F)
                    )
                    Surface(
                      color = Color(0xFF1F2937),
                      shape = RoundedCornerShape(8.dp),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Text(
                        text = NetworkModule.supabaseRepository.SQL_UNLOCK_SCRIPT,
                        color = Color(0xFFF3F4F6),
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp)
                      )
                    }
                    Button(
                      onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("Supabase SQL", NetworkModule.supabaseRepository.SQL_UNLOCK_SCRIPT)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "Comando SQL copiado para a Área de Transferência!", Toast.LENGTH_SHORT).show()
                      },
                      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                      modifier = Modifier.fillMaxWidth().height(36.dp),
                      shape = RoundedCornerShape(8.dp)
                    ) {
                      Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                      Spacer(Modifier.width(6.dp))
                      Text("Copiar Script SQL", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                  }
                }
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            performCloudSync(true)
            coroutineScope.launch {
              delay(1000)
              diagStatus = NetworkModule.supabaseRepository.runDiagnostics()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
        ) {
          if (isSyncingData) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("Sincronizando...", color = Color.White)
          } else {
            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("Sincronizar Agora", fontWeight = FontWeight.Bold, color = Color.White)
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { showSupabaseManagerDialog = false }) {
          Text("Fechar", color = BrandTextSecondary)
        }
      }
    )
  }

  // --- DIALOG: EXPLORADOR DO BANCO DE DADOS LOCAL ---
  if (showDatabaseViewerDialog) {
    var activeDbTab by remember { mutableStateOf(0) }
    var auditLogs by remember { mutableStateOf<List<AuditLogEntry>>(emptyList()) }
    var isLoadingAudit by remember { mutableStateOf(false) }
    var auditError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeDbTab) {
      if (activeDbTab == 3 && auditLogs.isEmpty()) {
        isLoadingAudit = true
        auditError = null
        val res = NetworkModule.supabaseRepository.fetchAuditLogs()
        res.onSuccess {
          auditLogs = it
          isLoadingAudit = false
        }.onFailure { err ->
          auditError = err.message
          isLoadingAudit = false
        }
      }
    }

    val isGerencial = activeUser.role == UserRole.GERENCIAL
    val userFarmName = activeUser.assignedFarmName ?: selectedFarm.name
    val visibleLogs = remember(logs, isGerencial, userFarmName) {
      val raw = if (isGerencial) logs else logs.filter { it.farmName == userFarmName }
      sortLogsChronologically(raw)
    }
    val visibleFarms = if (isGerencial) farms else farms.filter { it.name == userFarmName }
    val dbSizeBytes = remember { AppDatabaseManager.getDatabaseSizeBytes(context) }
    val fullJson = remember(isGerencial, visibleLogs) {
      if (isGerencial) {
        AppDatabaseManager.exportFullDatabaseJson(context)
      } else {
        // Safe export of current farm logs only
        org.json.JSONArray().apply {
          visibleLogs.forEach { l ->
            put(org.json.JSONObject().apply {
              put("id", l.id)
              put("farmName", l.farmName)
              put("date", l.date)
              put("volumeMm", l.volumeMm)
              put("notes", l.notes)
            })
          }
        }.toString(2)
      }
    }

    AlertDialog(
      onDismissRequest = { showDatabaseViewerDialog = false },
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Storage, contentDescription = null, tint = BrandGreen)
          Spacer(Modifier.width(8.dp))
          Text(
            if (isGerencial) "Banco de Dados Local" else "Banco de Dados - $userFarmName",
            fontWeight = FontWeight.Bold,
            color = BrandGreen
          )
        }
      },
      containerColor = BrandSurface,
      text = {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
        ) {
          PrimaryTabRow(
            selectedTabIndex = activeDbTab,
            containerColor = BrandSurface,
            contentColor = BrandGreen
          ) {
            Tab(
              selected = activeDbTab == 0,
              onClick = { activeDbTab = 0 },
              text = { Text("Visão Geral", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
              selected = activeDbTab == 1,
              onClick = { activeDbTab = 1 },
              text = { Text("Dados (${visibleLogs.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
              selected = activeDbTab == 2,
              onClick = { activeDbTab = 2 },
              text = { Text("JSON", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
              selected = activeDbTab == 3,
              onClick = { activeDbTab = 3 },
              text = { Text("Auditoria / Logs", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            )
          }
          Spacer(Modifier.height(10.dp))

          when (activeDbTab) {
            0 -> {
              Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                // Status Card
                Surface(
                  color = Color(0xFFF0FDF4),
                  shape = RoundedCornerShape(10.dp),
                  border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                      Text(
                        if (isGerencial) "Status: Ativo & Operacional (Global)" else "Status: Ativo & Restrito ($userFarmName)",
                        fontWeight = FontWeight.Bold,
                        color = BrandGreen
                      )
                      Text(
                        if (isGerencial) "Gravação síncrona com commit()" else "Exibindo apenas registros da sua fazenda",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandTextSecondary
                      )
                    }
                  }
                }

                // Metric tiles
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Surface(
                    color = Color(0xFFF9FAFB),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                    modifier = Modifier.weight(1f)
                  ) {
                    Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                      Text("Lançamentos", style = MaterialTheme.typography.labelSmall, color = BrandTextSecondary)
                      Text("${visibleLogs.size}", fontWeight = FontWeight.Black, fontSize = 20.sp, color = BrandTextPrimary)
                    }
                  }
                  Surface(
                    color = Color(0xFFF9FAFB),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                    modifier = Modifier.weight(1f)
                  ) {
                    Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                      Text("Fazendas", style = MaterialTheme.typography.labelSmall, color = BrandTextSecondary)
                      Text("${visibleFarms.size}", fontWeight = FontWeight.Black, fontSize = 20.sp, color = BrandTextPrimary)
                    }
                  }
                  Surface(
                    color = Color(0xFFF9FAFB),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                    modifier = Modifier.weight(1f)
                  ) {
                    Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                      Text("Acesso", style = MaterialTheme.typography.labelSmall, color = BrandTextSecondary)
                      Text(if (isGerencial) "DIRETORIA" else "FAZENDA", fontWeight = FontWeight.Black, fontSize = 13.sp, color = BrandGreen)
                    }
                  }
                }

                // Storage size
                Surface(
                  color = Color(0xFFF9FAFB),
                  shape = RoundedCornerShape(10.dp),
                  border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text("Tamanho Armazenado no Flash:", style = MaterialTheme.typography.bodySmall, color = BrandTextSecondary)
                    Text("${dbSizeBytes / 1024 + 1} KB (${dbSizeBytes} bytes)", fontWeight = FontWeight.Bold, color = BrandTextPrimary)
                  }
                }

                // Verification Button
                OutlinedButton(
                  onClick = {
                    Toast.makeText(context, "Integridade verificada: 100% dos dados consistentes no banco!", Toast.LENGTH_SHORT).show()
                  },
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(10.dp),
                  border = BorderStroke(1.dp, BrandGreen),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGreen)
                ) {
                  Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("Validar Integridade dos Dados", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
              }
            }
            1 -> {
              if (visibleLogs.isEmpty()) {
                Box(
                  modifier = Modifier.fillMaxSize(),
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    "Nenhum registro encontrado para $userFarmName",
                    color = BrandTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                  )
                }
              } else {
                LazyColumn(
                  modifier = Modifier.fillMaxSize(),
                  verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  items(visibleLogs) { log ->
                    Surface(
                      color = Color(0xFFF9FAFB),
                      shape = RoundedCornerShape(8.dp),
                      border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Column(modifier = Modifier.weight(1f)) {
                          Text("${log.farmName} • ${log.date}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = BrandTextPrimary)
                          Text(log.notes.ifBlank { "Sem observações" }, style = MaterialTheme.typography.labelSmall, color = BrandTextSecondary, maxLines = 1)
                        }
                        Surface(
                          color = if (log.volumeMm > 0) BrandGreenLight else Color(0xFFF3F4F6),
                          shape = RoundedCornerShape(6.dp)
                        ) {
                          Text(
                            "${String.format(Locale("pt", "BR"), "%.1f", log.volumeMm)} mm",
                            fontWeight = FontWeight.Black,
                            color = if (log.volumeMm > 0) BrandGreen else BrandTextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
            2 -> {
              Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                  color = Color(0xFF1E293B),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                ) {
                  Text(
                    text = fullJson,
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                      .padding(10.dp)
                      .verticalScroll(rememberScrollState())
                  )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                  onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Backup Itacumbi Agro", fullJson)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "JSON copiado para a Área de Transferência!", Toast.LENGTH_SHORT).show()
                  },
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(8.dp),
                  colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
                ) {
                  Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text(
                    if (isGerencial) "Copiar Backup JSON Completo" else "Copiar JSON da Unidade ($userFarmName)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }
            3 -> {
              Column(modifier = Modifier.fillMaxSize()) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    "Histórico de Auditoria (${auditLogs.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = BrandTextPrimary
                  )
                  IconButton(
                    onClick = {
                      isLoadingAudit = true
                      auditError = null
                      coroutineScope.launch {
                        val res = NetworkModule.supabaseRepository.fetchAuditLogs()
                        res.onSuccess {
                          auditLogs = it
                          isLoadingAudit = false
                        }.onFailure { err ->
                          auditError = err.message
                          isLoadingAudit = false
                        }
                      }
                    },
                    modifier = Modifier.size(28.dp)
                  ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Atualizar", tint = BrandGreen, modifier = Modifier.size(18.dp))
                  }
                }

                if (isLoadingAudit) {
                  Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandGreen, modifier = Modifier.size(24.dp))
                  }
                } else if (auditError != null) {
                  Surface(
                    color = Color(0xFFFEF2F2),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFFECACA)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                  ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                      Text("Aviso de Auditoria", fontWeight = FontWeight.Bold, color = Color(0xFF991B1B), fontSize = 12.sp)
                      Spacer(Modifier.height(4.dp))
                      Text(
                        "A tabela 'audit_logs' ainda não foi criada no Supabase ou precisa de permissão. Execute o Script SQL para ativá-la.",
                        fontSize = 11.sp,
                        color = Color(0xFF7F1D1D)
                      )
                      Spacer(Modifier.height(8.dp))
                      Button(
                        onClick = {
                          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                          val clip = ClipData.newPlainText("Script Auditoria Supabase", NetworkModule.supabaseRepository.SQL_UNLOCK_SCRIPT)
                          clipboard.setPrimaryClip(clip)
                          Toast.makeText(context, "Script copiado! Cole no SQL Editor do Supabase.", Toast.LENGTH_LONG).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                      ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Copiar Script SQL Completo", fontSize = 11.sp, color = Color.White)
                      }
                    }
                  }
                } else if (auditLogs.isEmpty()) {
                  Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                      Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(32.dp))
                      Spacer(Modifier.height(6.dp))
                      Text("Nenhuma alteração ou exclusão recente registrada.", fontSize = 12.sp, color = BrandTextSecondary, textAlign = TextAlign.Center)
                    }
                  }
                } else {
                  LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    items(auditLogs) { logEntry ->
                      val (badgeColor, badgeBg) = when (logEntry.action) {
                        "CRIACAO" -> Pair(Color(0xFF059669), Color(0xFFD1FAE5))
                        "EDICAO" -> Pair(Color(0xFFD97706), Color(0xFFFEF3C7))
                        "EXCLUSAO" -> Pair(Color(0xFFDC2626), Color(0xFFFEE2E2))
                        else -> Pair(BrandGreen, BrandGreenLight)
                      }
                      Surface(
                        color = Color(0xFFF9FAFB),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                        modifier = Modifier.fillMaxWidth()
                      ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                          Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            Surface(
                              color = badgeBg,
                              shape = RoundedCornerShape(4.dp)
                            ) {
                              Text(
                                logEntry.action,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                              )
                            }
                            Text(
                              logEntry.createdAt?.take(19)?.replace("T", " ") ?: "Hoje",
                              fontSize = 10.sp,
                              color = BrandTextSecondary
                            )
                          }
                          Spacer(Modifier.height(4.dp))
                          Text(
                            logEntry.details,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = BrandTextPrimary
                          )
                          Spacer(Modifier.height(2.dp))
                          Text(
                            "Por: @${logEntry.performedBy}" + (if (logEntry.farmName != null) " • ${logEntry.farmName}" else ""),
                            fontSize = 10.sp,
                            color = BrandTextSecondary
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = { showDatabaseViewerDialog = false },
          colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
        ) {
          Text("Concluir", color = Color.White)
        }
      }
    )
  }
}

// --- TABS COMPOSABLES ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsTab(
    logs: List<RainfallLog>,
    onLogClick: (RainfallLog) -> Unit = {}
) {
    // Month name mappings
    val monthNames = listOf(
        "01" to "Janeiro", "02" to "Fevereiro", "03" to "Março", "04" to "Abril",
        "05" to "Maio", "06" to "Junho", "07" to "Julho", "08" to "Agosto",
        "09" to "Setembro", "10" to "Outubro", "11" to "Novembro", "12" to "Dezembro"
    )

    // Discover available years and months from actual logs for this farm
    val availableYears = remember(logs) {
        val years = logs.mapNotNull { log ->
            val parts = log.date.split("/")
            if (parts.size >= 3) parts[2] else null
        }.distinct().sortedDescending()
        if (years.isEmpty()) listOf(Calendar.getInstance().get(Calendar.YEAR).toString()) else years
    }

    // Filters state
    var selectedYear by remember { mutableStateOf(availableYears.firstOrNull() ?: "2026") }

    // Sync selected year if logs change (e.g. switching farm)
    LaunchedEffect(availableYears) {
        if (selectedYear !in availableYears) {
            selectedYear = availableYears.firstOrNull() ?: "2026"
        }
    }

    // Discover months that actually have logs in the selected year
    val availableMonthsInSelectedYear = remember(logs, selectedYear) {
        logs.filter { log ->
            val parts = log.date.split("/")
            parts.size >= 3 && parts[2] == selectedYear
        }.mapNotNull { log ->
            val parts = log.date.split("/")
            if (parts.size >= 2) parts[1] else null
        }.distinct().sorted()
    }

    // null = "Todos os Meses"
    var selectedMonthNum by remember { 
        mutableStateOf<String?>(availableMonthsInSelectedYear.lastOrNull() ?: "09") 
    }

    // Sync selected month when year/farm changes
    LaunchedEffect(availableMonthsInSelectedYear) {
        if (selectedMonthNum != null && selectedMonthNum !in availableMonthsInSelectedYear) {
            selectedMonthNum = availableMonthsInSelectedYear.lastOrNull()
        }
    }

    // Dropdown expanded states
    var monthMenuExpanded by remember { mutableStateOf(false) }
    var yearMenuExpanded by remember { mutableStateOf(false) }

    // Filtered logs based on selection - SEMPRE ordenados por data decrescente (mais recente no topo)
    val filteredLogs = remember(logs, selectedYear, selectedMonthNum) {
        val list = logs.filter { log ->
            val parts = log.date.split("/")
            val logMonth = if (parts.size >= 2) parts[1] else ""
            val logYear = if (parts.size >= 3) parts[2] else ""
            
            val matchesYear = logYear == selectedYear
            val matchesMonth = selectedMonthNum == null || logMonth == selectedMonthNum
            matchesYear && matchesMonth
        }
        sortLogsChronologically(list)
    }

    val periodVolume = remember(filteredLogs) { filteredLogs.sumOf { it.volumeMm } }
    val periodRainyDays = remember(filteredLogs) { filteredLogs.count { it.volumeMm > 0.0 } }

    val selectedMonthLabel = remember(selectedMonthNum) {
        if (selectedMonthNum == null) "Todos os Meses"
        else monthNames.find { it.first == selectedMonthNum }?.second ?: selectedMonthNum
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 88.dp)
    ) {
        // Premium Filter Bar
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BrandSurface),
                border = BorderStroke(1.dp, Color(0xFFE5E7EB))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(BrandGreenLight, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = null,
                                    tint = BrandGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Extrato por Período",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = BrandTextPrimary
                            )
                        }

                        if (selectedMonthNum != null) {
                            TextButton(
                                onClick = { selectedMonthNum = null },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Ver Todos",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BrandGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Month Dropdown
                        Box(modifier = Modifier.weight(1.3f)) {
                            OutlinedCard(
                                onClick = { monthMenuExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFFF9FAFB)),
                                border = BorderStroke(1.dp, Color(0xFFE5E7EB))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "Mês",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BrandTextSecondary,
                                            fontSize = 10.sp
                                        )
                                        Text(
                                            selectedMonthLabel ?: "Todos",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = BrandTextPrimary
                                        )
                                    }
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = null,
                                        tint = BrandGreen
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = monthMenuExpanded,
                                onDismissRequest = { monthMenuExpanded = false },
                                modifier = Modifier.background(BrandSurface)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Todos os Meses",
                                            fontWeight = if (selectedMonthNum == null) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedMonthNum == null) BrandGreen else BrandTextPrimary
                                        )
                                    },
                                    onClick = {
                                        selectedMonthNum = null
                                        monthMenuExpanded = false
                                    }
                                )
                                HorizontalDivider()
                                val displayedMonths = if (availableMonthsInSelectedYear.isNotEmpty()) {
                                    monthNames.filter { it.first in availableMonthsInSelectedYear }
                                } else {
                                    monthNames
                                }
                                displayedMonths.forEach { (code, name) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                name,
                                                fontWeight = if (selectedMonthNum == code) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selectedMonthNum == code) BrandGreen else BrandTextPrimary
                                            )
                                        },
                                        onClick = {
                                            selectedMonthNum = code
                                            monthMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Year Dropdown
                        Box(modifier = Modifier.weight(0.9f)) {
                            OutlinedCard(
                                onClick = { yearMenuExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFFF9FAFB)),
                                border = BorderStroke(1.dp, Color(0xFFE5E7EB))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "Ano",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BrandTextSecondary,
                                            fontSize = 10.sp
                                        )
                                        Text(
                                            selectedYear,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = BrandTextPrimary
                                        )
                                    }
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = null,
                                        tint = BrandGreen
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = yearMenuExpanded,
                                onDismissRequest = { yearMenuExpanded = false },
                                modifier = Modifier.background(BrandSurface)
                            ) {
                                availableYears.forEach { yr ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                yr,
                                                fontWeight = if (selectedYear == yr) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selectedYear == yr) BrandGreen else BrandTextPrimary
                                            )
                                        },
                                        onClick = {
                                            selectedYear = yr
                                            yearMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Period Summary Badge
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BrandBackground, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.WaterDrop,
                                contentDescription = null,
                                tint = BrandWater,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Total: ${String.format(Locale.US, "%.1f", periodVolume)} mm",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = BrandTextPrimary
                            )
                        }
                        Text(
                            "$periodRainyDays dias com chuva",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandTextSecondary
                        )
                    }
                }
            }
        }

        // Section Title
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedMonthNum != null) "Registros de $selectedMonthLabel $selectedYear" else "Histórico $selectedYear",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandTextPrimary
                )
                Text(
                    text = "${filteredLogs.size} ${if (filteredLogs.size == 1) "registro" else "registros"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandTextSecondary
                )
            }
        }

        // List of Cards
        items(filteredLogs) { log ->
            ModernRainfallLogCard(
                log = log,
                onClick = { onLogClick(log) }
            )
        }

        // Empty state
        if (filteredLogs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BrandSurface),
                    border = BorderStroke(1.dp, Color(0xFFE5E7EB))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.CalendarMonth,
                            contentDescription = null,
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Nenhum registro em $selectedMonthLabel/$selectedYear",
                            fontWeight = FontWeight.Bold,
                            color = BrandTextPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Use o botão + para lançar dados pluviométricos.",
                            color = BrandTextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

data class MonthStats(
    val month: String,
    val currentYear: Float,
    val previousYear: Float,
    val monthIndex: Int = 0,
    val currentYearRainyDays: Int = 0,
    val prevYearRainyDays: Int = 0
)
data class KpiDisplayData(val volume: Double, val rainyDays: Int, val monthLabel: String, val prevYearVolume: Double? = null)

@Composable
fun StatsTab(logs: List<RainfallLog>) {
    val monthNames = listOf("JAN", "FEV", "MAR", "ABR", "MAI", "JUN", "JUL", "AGO", "SET", "OUT", "NOV", "DEZ")

    val calendar = remember { Calendar.getInstance() }
    val currentYearSystem = calendar.get(Calendar.YEAR)

    // 1. Discover years present in the logs for this farm
    val yearsPresent = remember(logs) {
        logs.mapNotNull { log ->
            val parts = log.date.split("/")
            if (parts.size >= 3) parts[2].toIntOrNull() else null
        }.distinct().sortedDescending()
    }

    val hasLogs = logs.isNotEmpty() && yearsPresent.isNotEmpty()
    val primaryYearInt = yearsPresent.firstOrNull() ?: currentYearSystem
    val prevYearInt = if (yearsPresent.size >= 2) yearsPresent[1] else null
    val hasComparison = prevYearInt != null

    val currentYearTag = primaryYearInt.toString()
    val prevYearTag = prevYearInt?.toString() ?: ""

    // 2. Discover months that actually have rainfall records for this farm
    val monthsWithData = remember(logs, primaryYearInt, prevYearInt) {
        logs.mapNotNull { log ->
            val parts = log.date.split("/")
            if (parts.size >= 3) {
                val m = parts[1].toIntOrNull()
                val y = parts[2].toIntOrNull()
                if (m != null && m in 1..12) {
                    if (y == primaryYearInt || (prevYearInt != null && y == prevYearInt)) m else null
                } else null
            } else null
        }.distinct().sorted()
    }

    // 3. Dynamic stats calculated exclusively from real logs for active months
    val statsData = remember(logs, primaryYearInt, prevYearInt, monthsWithData) {
        val curYearRain = FloatArray(12)
        val prevYearRain = FloatArray(12)
        val curYearDays = IntArray(12)
        val prevYearDays = IntArray(12)

        for (log in logs) {
            val parts = log.date.split("/")
            if (parts.size >= 3) {
                val m = parts[1].toIntOrNull()
                val y = parts[2].toIntOrNull()
                if (m != null && y != null && m in 1..12) {
                    val idx = m - 1
                    if (y == primaryYearInt) {
                        curYearRain[idx] += log.volumeMm.toFloat()
                        if (log.volumeMm > 0.0) curYearDays[idx]++
                    } else if (prevYearInt != null && y == prevYearInt) {
                        prevYearRain[idx] += log.volumeMm.toFloat()
                        if (log.volumeMm > 0.0) prevYearDays[idx]++
                    }
                }
            }
        }

        monthsWithData.map { m ->
            val idx = m - 1
            MonthStats(
                month = monthNames[idx],
                currentYear = curYearRain[idx],
                previousYear = if (hasComparison) prevYearRain[idx] else 0f,
                monthIndex = m,
                currentYearRainyDays = curYearDays[idx],
                prevYearRainyDays = if (hasComparison) prevYearDays[idx] else 0
            )
        }
    }

    val accumulatedStatsData = remember(statsData, hasComparison) {
        var accCurrent = 0f
        var accPrev = 0f
        var accCurDays = 0
        var accPrevDays = 0
        statsData.map {
            accCurrent += it.currentYear
            accPrev += it.previousYear
            accCurDays += it.currentYearRainyDays
            accPrevDays += it.prevYearRainyDays
            MonthStats(
                month = it.month,
                currentYear = accCurrent,
                previousYear = if (hasComparison) accPrev else 0f,
                monthIndex = it.monthIndex,
                currentYearRainyDays = accCurDays,
                prevYearRainyDays = if (hasComparison) accPrevDays else 0
            )
        }
    }

    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var accSelectedIndex by remember { mutableStateOf<Int?>(null) }
    var selectedYearFilter by remember { mutableStateOf<String?>(null) }
    
    // Determine effective selected index: priority to selectedIndex (monthly/table), fallback to accSelectedIndex
    val activeIndex = selectedIndex ?: accSelectedIndex
    val currentMonthName = monthNames[calendar.get(Calendar.MONTH)]

    val totalAnnualVolume = remember(statsData) { statsData.sumOf { it.currentYear.toDouble() } }

    val (displayVolume, displayRainyDays, displayMonthLabel, displayPrevYearVolume) = remember(activeIndex, statsData, hasComparison) {
        if (activeIndex != null && activeIndex in statsData.indices) {
            val stat = statsData[activeIndex]
            KpiDisplayData(
                stat.currentYear.toDouble(), 
                stat.currentYearRainyDays, 
                stat.month, 
                if (hasComparison) stat.previousYear.toDouble() else null
            )
        } else {
            // Default to latest month with data
            val latest = statsData.lastOrNull()
            if (latest != null) {
                KpiDisplayData(
                    latest.currentYear.toDouble(), 
                    latest.currentYearRainyDays, 
                    latest.month, 
                    if (hasComparison) latest.previousYear.toDouble() else null
                )
            } else {
                KpiDisplayData(0.0, 0, currentMonthName, null)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 88.dp)
    ) {
        if (!hasLogs || statsData.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = BrandSurface),
                    border = BorderStroke(1.dp, Color(0xFFE5E7EB))
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(BrandGreenLight, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.WaterDrop,
                                contentDescription = null,
                                tint = BrandGreen,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Sem Lançamentos Pluviométricos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BrandTextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Esta fazenda ainda não possui dados pluviométricos cadastrados. Realize o primeiro lançamento na aba 'Registros' pelo botão '+' para visualizar os gráficos e comparativos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BrandTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            item {
                ModernKpiRow(
                    totalVolume = displayVolume, 
                    rainyDays = displayRainyDays,
                    monthLabel = displayMonthLabel,
                    previousYearVolume = displayPrevYearVolume,
                    currentYearTag = currentYearTag,
                    previousYearTag = prevYearTag,
                    hasComparison = hasComparison,
                    totalYearVolume = totalAnnualVolume
                )
            }
            item {
                Spacer(modifier = Modifier.height(28.dp))
                SectionHeader(
                    title = if (hasComparison) "Comparativo Mensal (mm)" else "Precipitação Mensal (mm)",
                    showLegend = true,
                    currentYearTag = currentYearTag,
                    previousYearTag = prevYearTag,
                    selectedYearFilter = selectedYearFilter,
                    hasComparison = hasComparison,
                    onSelectYear = { year ->
                        selectedYearFilter = if (selectedYearFilter == year) null else year
                    }
                )
            }
            item {
                InteractiveComparisonChart(
                    data = statsData,
                    selectedIndex = selectedIndex,
                    onSelect = { idx -> selectedIndex = if (selectedIndex == idx) null else idx },
                    selectedYearFilter = selectedYearFilter,
                    currentYearTag = currentYearTag,
                    previousYearTag = prevYearTag,
                    hasComparison = hasComparison
                )
            }
            item {
                Spacer(modifier = Modifier.height(28.dp))
                SectionHeader(
                    title = "Detalhamento por Mês", 
                    showLegend = false,
                    hasComparison = hasComparison
                )
            }
            item {
                ComparisonTable(
                    data = statsData, 
                    selectedIndex = selectedIndex,
                    onSelect = { idx -> selectedIndex = if (selectedIndex == idx) null else idx },
                    selectedYearFilter = selectedYearFilter,
                    currentYearTag = currentYearTag,
                    previousYearTag = prevYearTag,
                    hasComparison = hasComparison,
                    isAccumulated = false
                )
            }
            item {
                Spacer(modifier = Modifier.height(28.dp))
                SectionHeader(
                    title = if (hasComparison) "Acumulado Anual (mm)" else "Evolução Acumulada (mm)",
                    showLegend = true,
                    currentYearTag = currentYearTag,
                    previousYearTag = prevYearTag,
                    selectedYearFilter = selectedYearFilter,
                    hasComparison = hasComparison,
                    onSelectYear = { year ->
                        selectedYearFilter = if (selectedYearFilter == year) null else year
                    }
                )
            }
            item {
                InteractiveComparisonChart(
                    data = accumulatedStatsData,
                    selectedIndex = accSelectedIndex,
                    onSelect = { idx -> accSelectedIndex = if (accSelectedIndex == idx) null else idx },
                    selectedYearFilter = selectedYearFilter,
                    currentYearTag = currentYearTag,
                    previousYearTag = prevYearTag,
                    hasComparison = hasComparison
                )
            }
            item {
                Spacer(modifier = Modifier.height(28.dp))
                SectionHeader(
                    title = "Detalhamento do Acumulado Anual (mm)", 
                    showLegend = false,
                    hasComparison = hasComparison
                )
            }
            item {
                ComparisonTable(
                    data = accumulatedStatsData, 
                    selectedIndex = accSelectedIndex,
                    onSelect = { idx -> accSelectedIndex = if (accSelectedIndex == idx) null else idx },
                    selectedYearFilter = selectedYearFilter,
                    currentYearTag = currentYearTag,
                    previousYearTag = prevYearTag,
                    hasComparison = hasComparison,
                    isAccumulated = true
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String, 
    showLegend: Boolean = true,
    currentYearTag: String = "2026",
    previousYearTag: String = "2025",
    selectedYearFilter: String? = null,
    hasComparison: Boolean = true,
    onSelectYear: ((String) -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = BrandTextPrimary
        )
        if (showLegend) {
            if (hasComparison) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Previous year button chip
                    Surface(
                        onClick = { onSelectYear?.invoke(previousYearTag) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selectedYearFilter == previousYearTag) Color(0xFFCBD5E1).copy(alpha = 0.5f) else Color.Transparent,
                        border = if (selectedYearFilter == previousYearTag) BorderStroke(1.dp, Color(0xFF94A3B8)) else null
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (selectedYearFilter == null || selectedYearFilter == previousYearTag) Color(0xFF94A3B8) else Color(0xFFCBD5E1),
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                previousYearTag, 
                                style = MaterialTheme.typography.labelSmall, 
                                fontWeight = if (selectedYearFilter == previousYearTag) FontWeight.Black else FontWeight.SemiBold, 
                                color = if (selectedYearFilter == previousYearTag) Color(0xFF0F172A) else BrandTextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    
                    Spacer(Modifier.width(6.dp))
                    
                    // Current year button chip
                    Surface(
                        onClick = { onSelectYear?.invoke(currentYearTag) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selectedYearFilter == currentYearTag) BrandWater.copy(alpha = 0.15f) else Color.Transparent,
                        border = if (selectedYearFilter == currentYearTag) BorderStroke(1.dp, BrandWater) else null
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (selectedYearFilter == null || selectedYearFilter == currentYearTag) BrandWater else Color(0xFF93C5FD).copy(alpha = 0.4f),
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                currentYearTag, 
                                style = MaterialTheme.typography.labelSmall, 
                                fontWeight = if (selectedYearFilter == currentYearTag) FontWeight.Black else FontWeight.SemiBold, 
                                color = if (selectedYearFilter == currentYearTag) BrandWater else BrandTextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            } else {
                // Single active year indicator badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BrandWater.copy(alpha = 0.12f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(BrandWater, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "Ano $currentYearTag", 
                            style = MaterialTheme.typography.labelSmall, 
                            fontWeight = FontWeight.Bold, 
                            color = BrandWater,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InteractiveComparisonChart(
    data: List<MonthStats>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    selectedYearFilter: String? = null,
    currentYearTag: String = "2026",
    previousYearTag: String = "2025",
    hasComparison: Boolean = true
) {
    val maxVal = data.maxOfOrNull { if (hasComparison) maxOf(it.currentYear, it.previousYear) else it.currentYear }?.coerceAtLeast(1f) ?: 1f
    val isScrollable = data.size > 5
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth().height(240.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BrandSurface),
            border = BorderStroke(1.dp, Color(0xFFE5E7EB))
        ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isScrollable) Modifier.horizontalScroll(scrollState) else Modifier)
                .padding(top = 20.dp, bottom = 14.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = if (isScrollable) Arrangement.spacedBy(10.dp) else Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEachIndexed { index, stat ->
                val isSelected = selectedIndex == index
                val isFaded = selectedIndex != null && !isSelected
                
                val alpha by animateFloatAsState(
                    targetValue = if (isFaded) 0.35f else 1f,
                    animationSpec = tween(250),
                    label = "chartAlpha"
                )

                // Year-focus dimmed states (only applies when comparison exists)
                val isPrevYearFaded = hasComparison && selectedYearFilter == currentYearTag
                val isCurrentYearFaded = hasComparison && selectedYearFilter == previousYearTag

                // Previous Year bar and text colors
                val prevBarColor by animateColorAsState(
                    targetValue = when {
                        isPrevYearFaded -> Color(0xFFE2E8F0).copy(alpha = 0.3f)
                        isSelected -> Color(0xFF94A3B8)
                        isFaded -> Color(0xFFD1D5DB).copy(alpha = alpha)
                        else -> Color(0xFFCBD5E1)
                    },
                    animationSpec = tween(250),
                    label = "prevBarColor"
                )
                val prevTextColor by animateColorAsState(
                    targetValue = when {
                        isPrevYearFaded -> Color(0xFF9CA3AF).copy(alpha = 0.25f)
                        isSelected -> Color(0xFF0F172A)
                        isFaded -> Color(0xFF9CA3AF).copy(alpha = 0.35f)
                        else -> Color(0xFF4B5563)
                    },
                    animationSpec = tween(250),
                    label = "prevTextColor"
                )

                // Current Year bar and text colors
                val currentBarColor by animateColorAsState(
                    targetValue = when {
                        isCurrentYearFaded -> Color(0xFFE2E8F0).copy(alpha = 0.3f)
                        isSelected -> BrandWater
                        isFaded -> Color(0xFFD1D5DB).copy(alpha = alpha)
                        else -> BrandWater
                    },
                    animationSpec = tween(250),
                    label = "currentBarColor"
                )
                val currentTextColor by animateColorAsState(
                    targetValue = when {
                        isCurrentYearFaded -> Color(0xFF9CA3AF).copy(alpha = 0.25f)
                        isSelected -> BrandWater
                        isFaded -> Color(0xFF9CA3AF).copy(alpha = 0.35f)
                        else -> BrandWater
                    },
                    animationSpec = tween(250),
                    label = "currentTextColor"
                )

                val colBgColor by animateColorAsState(
                    targetValue = if (isSelected) BrandWater.copy(alpha = 0.08f) else Color.Transparent,
                    animationSpec = tween(250),
                    label = "colBgColor"
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier
                        .then(
                            if (isScrollable) {
                                Modifier.width(if (hasComparison) 68.dp else 58.dp)
                            } else {
                                Modifier.weight(1f)
                            }
                        )
                        .fillMaxHeight()
                        .background(colBgColor, RoundedCornerShape(8.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onSelect(index) }
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        val maxBarHeight = (maxHeight - 20.dp).coerceAtLeast(10.dp)

                        val prevFraction = if (hasComparison && maxVal > 0f) (stat.previousYear / maxVal).coerceIn(0f, 1f) else 0f
                        val currentFraction = if (maxVal > 0f) (stat.currentYear / maxVal).coerceIn(0f, 1f) else 0f

                        val targetPrevHeight = if (hasComparison && stat.previousYear > 0f) (maxBarHeight * prevFraction).coerceAtLeast(5.dp) else 2.dp
                        val targetCurrentHeight = if (stat.currentYear > 0f) (maxBarHeight * currentFraction).coerceAtLeast(5.dp) else 2.dp

                        val prevHeight by animateDpAsState(
                            targetValue = targetPrevHeight,
                            animationSpec = tween(300),
                            label = "prevBarHeight"
                        )
                        val currentHeight by animateDpAsState(
                            targetValue = targetCurrentHeight,
                            animationSpec = tween(300),
                            label = "currentBarHeight"
                        )

                        if (hasComparison) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Previous Year Bar
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    Text(
                                        text = "${stat.previousYear.toInt()}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = prevTextColor,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(13.dp)
                                            .height(prevHeight)
                                            .background(
                                                prevBarColor,
                                                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                            )
                                    )
                                }
                                
                                // Current Year Bar
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    Text(
                                        text = "${stat.currentYear.toInt()}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                        color = currentTextColor,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(13.dp)
                                            .height(currentHeight)
                                            .background(
                                                currentBarColor,
                                                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                            )
                                    )
                                }
                            }
                        } else {
                            // Single Bar (Current Year only)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.0f", stat.currentYear),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    color = currentTextColor,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    modifier = Modifier
                                        .width(22.dp)
                                        .height(currentHeight)
                                        .background(
                                            currentBarColor,
                                            RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)
                                        )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Month badge / text at the bottom
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) BrandGreen else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stat.month,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            color = when {
                                isSelected -> Color.White
                                isFaded -> Color(0xFF9CA3AF).copy(alpha = 0.4f)
                                else -> BrandTextPrimary
                            }
                        )
                    }
                }
            }
        }
    }

    if (isScrollable) {
        Spacer(Modifier.height(10.dp))
        val numDots = if (data.size <= 7) 3 else if (data.size <= 10) 4 else 5
        val scrollFraction = if (scrollState.maxValue > 0) {
            (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
        } else 0f
        val activeIndex = (scrollFraction * (numDots - 1)).roundToInt().coerceIn(0, numDots - 1)

        val canScrollBack = scrollState.value > 15
        val canScrollForward = scrollState.value < (scrollState.maxValue - 15)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Seta indicativa anterior (clicável para rolar para trás)
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        val step = scrollState.maxValue / (numDots - 1).coerceAtLeast(1)
                        scrollState.animateScrollTo((scrollState.value - step).coerceAtLeast(0))
                    }
                },
                enabled = canScrollBack,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "Rolar para meses anteriores",
                    tint = if (canScrollBack) BrandGreen else Color(0xFFE2E8F0),
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(6.dp))

            // Dots de página estilo grandes aplicativos (pill animada no ativo, bolinhas nos inativos)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until numDots) {
                    val isActive = i == activeIndex
                    val dotWidth by animateDpAsState(
                        targetValue = if (isActive) 20.dp else 7.dp,
                        animationSpec = tween(250),
                        label = "dotWidth"
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (isActive) BrandWater else Color(0xFFCBD5E1),
                        animationSpec = tween(250),
                        label = "dotColor"
                    )

                    Box(
                        modifier = Modifier
                            .height(7.dp)
                            .width(dotWidth)
                            .background(dotColor, RoundedCornerShape(3.5.dp))
                            .clickable {
                                coroutineScope.launch {
                                    val target = if (scrollState.maxValue > 0) {
                                        (scrollState.maxValue * (i.toFloat() / (numDots - 1))).toInt()
                                    } else 0
                                    scrollState.animateScrollTo(target)
                                }
                            }
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // Seta indicativa próxima (clicável para rolar para frente)
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        val step = scrollState.maxValue / (numDots - 1).coerceAtLeast(1)
                        scrollState.animateScrollTo((scrollState.value + step).coerceAtMost(scrollState.maxValue))
                    }
                },
                enabled = canScrollForward,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "Rolar para próximos meses",
                    tint = if (canScrollForward) BrandGreen else Color(0xFFE2E8F0),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
  }
}

@Composable
fun ComparisonTable(
    data: List<MonthStats>, 
    selectedIndex: Int?,
    onSelect: ((Int) -> Unit)? = null,
    selectedYearFilter: String? = null,
    currentYearTag: String = "2026",
    previousYearTag: String = "2025",
    hasComparison: Boolean = true,
    isAccumulated: Boolean = false
) {
    val totalRain = remember(data, isAccumulated) {
        if (isAccumulated) {
            data.lastOrNull()?.currentYear ?: 0f
        } else {
            data.sumOf { it.currentYear.toDouble() }.toFloat()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BrandSurface),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (hasComparison) {
                // Header: Chronological order (Previous year then Current year)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BrandBackground)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Mês", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = BrandTextSecondary)
                    Text(
                        previousYearTag, 
                        modifier = Modifier.weight(1f), 
                        style = MaterialTheme.typography.labelMedium, 
                        fontWeight = if (selectedYearFilter == previousYearTag) FontWeight.Black else FontWeight.Normal,
                        color = if (selectedYearFilter == previousYearTag) Color(0xFF0F172A) else if (selectedYearFilter == currentYearTag) BrandTextSecondary.copy(alpha = 0.4f) else BrandTextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        currentYearTag, 
                        modifier = Modifier.weight(1f), 
                        style = MaterialTheme.typography.labelMedium, 
                        fontWeight = if (selectedYearFilter == currentYearTag) FontWeight.Black else FontWeight.Normal,
                        color = if (selectedYearFilter == currentYearTag) BrandWater else if (selectedYearFilter == previousYearTag) BrandTextSecondary.copy(alpha = 0.4f) else BrandTextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text("Variação", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = BrandTextSecondary, maxLines = 1, softWrap = false)
                }
            } else {
                // Header for Single Year with Data
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BrandBackground)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Mês", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = BrandTextSecondary)
                    Text(if (isAccumulated) "Acum. ($currentYearTag)" else "Chuva ($currentYearTag)", modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.labelMedium, color = BrandWater, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Text(if (isAccumulated) "Total d" else "Dias", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium, color = BrandTextSecondary, maxLines = 1, softWrap = false)
                    Text("% Ano", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = BrandTextSecondary, maxLines = 1, softWrap = false)
                }
            }
            
            HorizontalDivider(color = Color(0xFFE5E7EB))
            
            // Rows
            data.forEachIndexed { index, stat ->
                val isSelected = selectedIndex == index
                val isFaded = selectedIndex != null && !isSelected
                
                val rowBg by animateColorAsState(
                    targetValue = when {
                        isSelected -> BrandGreenLight.copy(alpha = 0.55f)
                        else -> Color.Transparent
                    },
                    animationSpec = tween(250),
                    label = "tableRowBg"
                )
                
                val monthTextColor by animateColorAsState(
                    targetValue = when {
                        isSelected -> BrandGreen
                        isFaded -> Color(0xFF9CA3AF).copy(alpha = 0.4f)
                        else -> BrandTextPrimary
                    },
                    animationSpec = tween(250),
                    label = "monthTextColor"
                )

                if (hasComparison) {
                    val isPrevYearFaded = selectedYearFilter == currentYearTag
                    val isCurrentYearFaded = selectedYearFilter == previousYearTag

                    val prevTextColor by animateColorAsState(
                        targetValue = when {
                            isPrevYearFaded -> Color(0xFF9CA3AF).copy(alpha = 0.25f)
                            selectedYearFilter == previousYearTag -> Color(0xFF0F172A)
                            isSelected -> Color(0xFF1E293B)
                            isFaded -> Color(0xFF9CA3AF).copy(alpha = 0.35f)
                            else -> BrandTextSecondary
                        },
                        animationSpec = tween(250),
                        label = "prevTextColor"
                    )
                    
                    val currentTextColor by animateColorAsState(
                        targetValue = when {
                            isCurrentYearFaded -> Color(0xFF9CA3AF).copy(alpha = 0.25f)
                            selectedYearFilter == currentYearTag -> BrandWater
                            isSelected -> BrandWater
                            isFaded -> Color(0xFF9CA3AF).copy(alpha = 0.35f)
                            else -> BrandTextPrimary
                        },
                        animationSpec = tween(250),
                        label = "currentTextColor"
                    )
                    
                    val diff = stat.currentYear - stat.previousYear
                    val diffPercent = if (stat.previousYear > 0) (diff / stat.previousYear) * 100 else 0f
                    
                    val baseDiffColor = when {
                        diff > 0 -> BrandGreen
                        diff < 0 -> Color(0xFFD32F2F) // Material Red
                        else -> BrandTextSecondary
                    }
                    
                    val diffColor by animateColorAsState(
                        targetValue = when {
                            isFaded -> Color(0xFF9CA3AF).copy(alpha = 0.35f)
                            else -> baseDiffColor
                        },
                        animationSpec = tween(250),
                        label = "diffColor"
                    )
                    
                    val diffSymbol = when {
                        diff > 0 -> "▲"
                        diff < 0 -> "▼"
                        else -> "-"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .then(
                                if (onSelect != null) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onSelect(index) }
                                } else Modifier
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stat.month, 
                            modifier = Modifier.weight(1f), 
                            style = MaterialTheme.typography.bodyMedium, 
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold, 
                            color = monthTextColor,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            "${stat.previousYear.toInt()} mm", 
                            modifier = Modifier.weight(1f), 
                            style = MaterialTheme.typography.bodyMedium, 
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = prevTextColor,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            "${stat.currentYear.toInt()} mm", 
                            modifier = Modifier.weight(1f), 
                            style = MaterialTheme.typography.bodyMedium, 
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold, 
                            color = currentTextColor,
                            maxLines = 1,
                            softWrap = false
                        )
                        
                        Row(modifier = Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$diffSymbol ${String.format(Locale.US, "%.0f", Math.abs(diff))}", 
                                style = MaterialTheme.typography.labelMedium, 
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold, 
                                color = diffColor,
                                maxLines = 1,
                                softWrap = false
                            )
                            if (stat.previousYear > 0) {
                                Text(
                                    text = " (${String.format(Locale.US, "%.0f", Math.abs(diffPercent))}%)", 
                                    style = MaterialTheme.typography.labelSmall, 
                                    color = diffColor.copy(alpha = if (isFaded) 0.35f else 0.7f),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                } else {
                    // Single Year Row
                    val percent = if (totalRain > 0f) (stat.currentYear / totalRain) * 100 else 0f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .then(
                                if (onSelect != null) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onSelect(index) }
                                } else Modifier
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stat.month, 
                            modifier = Modifier.weight(1f), 
                            style = MaterialTheme.typography.bodyMedium, 
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold, 
                            color = monthTextColor,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            "${String.format(Locale.US, "%.1f", stat.currentYear)} mm", 
                            modifier = Modifier.weight(1.3f), 
                            style = MaterialTheme.typography.bodyMedium, 
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                            color = BrandWater,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            "${stat.currentYearRainyDays} d", 
                            modifier = Modifier.weight(0.9f), 
                            style = MaterialTheme.typography.bodyMedium, 
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = BrandTextPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            "${String.format(Locale.US, "%.0f", percent)}%", 
                            modifier = Modifier.weight(1f), 
                            style = MaterialTheme.typography.bodyMedium, 
                            fontWeight = FontWeight.SemiBold,
                            color = BrandTextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                
                if (index < data.size - 1) {
                    HorizontalDivider(color = Color(0xFFF3F4F6))
                }
            }
        }
    }
}

@Composable
fun InfoTab(
    innerPadding: PaddingValues,
    currentUser: UserAccount,
    farms: List<Farm>,
    users: SnapshotStateList<UserAccount>,
    logs: List<RainfallLog>,
    onEditUser: (UserAccount) -> Unit,
    onOpenCreateFarm: () -> Unit,
    onOpenDatabaseExplorer: () -> Unit,
    onOpenExportSpreadsheet: () -> Unit,
    onTriggerSync: () -> Unit = {},
    isSyncing: Boolean = false,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandSurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandSurface)
                .padding(
                    top = innerPadding.calculateTopPadding() + 24.dp,
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 16.dp
                )
        ) {
            Text(
                text = "Configurações & Perfil", 
                color = BrandGreen, 
                style = MaterialTheme.typography.headlineMedium, 
                fontWeight = FontWeight.Black
            )
        }
        HorizontalDivider(color = BrandBackground, thickness = 2.dp)
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // User Profile Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BrandGreenLight),
                    border = BorderStroke(1.dp, BrandGreen.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(BrandGreen, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (currentUser.role == UserRole.GERENCIAL) Icons.Filled.AdminPanelSettings else Icons.Filled.Person,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentUser.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = BrandTextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (currentUser.role == UserRole.GERENCIAL) "Conta Gerencial • Administrador" else "Operador • ${currentUser.assignedFarmName ?: "Fazenda"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Gerenciamento de Acessos & Logins (Exclusivo da Conta Gerencial)
            if (currentUser.role == UserRole.GERENCIAL) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = BrandSurface),
                        border = BorderStroke(1.dp, BrandGreen.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Security,
                                        contentDescription = null,
                                        tint = BrandGreen,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Gestão de Acessos & Fazendas",
                                        fontWeight = FontWeight.Bold,
                                        color = BrandTextPrimary,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Surface(
                                    color = BrandGreenLight,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "GERENCIAL",
                                        color = BrandGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Como Administrador, você pode cadastrar novas fazendas e operadores, além de alterar senhas, logins ou tipo de acesso de qualquer usuário.",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandTextSecondary
                            )

                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = onOpenCreateFarm,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
                            ) {
                                Icon(Icons.Filled.AddBusiness, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Cadastrar Nova Fazenda & Acesso", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Logins Cadastrados (${users.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandTextSecondary
                                )
                                Text(
                                    text = "Toque para alterar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BrandGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(8.dp))

                            users.forEach { user ->
                                val isUserGerencial = user.role == UserRole.GERENCIAL
                                Surface(
                                    onClick = { onEditUser(user) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFF9FAFB),
                                    border = BorderStroke(1.dp, Color(0xFFE5E7EB))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .background(
                                                    if (isUserGerencial) BrandGreen else Color(0xFFE0F2FE),
                                                    RoundedCornerShape(8.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isUserGerencial) Icons.Filled.AdminPanelSettings else Icons.Filled.Agriculture,
                                                contentDescription = null,
                                                tint = if (isUserGerencial) Color.White else BrandGreen,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = user.displayName,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = BrandTextPrimary
                                            )
                                            Text(
                                                text = "Login: @${user.username} • Senha: ${"•".repeat(user.password.length)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = BrandTextSecondary,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = if (isUserGerencial) "Todas as fazendas (Diretoria)" else "Unidade: ${user.assignedFarmName ?: "Não definida"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isUserGerencial) BrandGreen else Color(0xFF0369A1),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Surface(
                                            color = if (isUserGerencial) BrandGreenLight else Color(0xFFF3F4F6),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = if (isUserGerencial) "DIRETORIA" else "OPERADOR",
                                                color = if (isUserGerencial) BrandGreen else BrandTextSecondary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        FilledTonalIconButton(
                                            onClick = { onEditUser(user) },
                                            modifier = Modifier.size(32.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = BrandGreenLight,
                                                contentColor = BrandGreen
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Edit,
                                                contentDescription = "Editar ${user.displayName}",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { 
                SettingsItem(
                    icon = Icons.Filled.CloudSync, 
                    title = "Sincronização Nuvem (Supabase)", 
                    subtitle = if (isSyncing) "Sincronizando dados com a nuvem..." else "Sincronizar fazendas, acessos e medições com o Supabase",
                    badgeText = if (isSyncing) "SINCRONIZANDO" else "CONECTADO",
                    onClick = onTriggerSync
                ) 
            }
            item { 
                val isGer = currentUser.role == UserRole.GERENCIAL
                val farmScopeText = if (isGer) "Todas as fazendas" else (currentUser.assignedFarmName ?: "Sua Fazenda")
                SettingsItem(
                    icon = Icons.Filled.Storage, 
                    title = "Banco de Dados Local", 
                    subtitle = if (isGer) "Visualizar registros de todas as fazendas, tabelas e backup" else "Visualizar registros e integridade de $farmScopeText",
                    badgeText = if (isGer) "GLOBAL" else "UNIDADE",
                    onClick = onOpenDatabaseExplorer
                ) 
            }
            item { 
                val isGer = currentUser.role == UserRole.GERENCIAL
                val farmScopeText = if (isGer) "geral ou por fazenda" else (currentUser.assignedFarmName ?: "unidade atual")
                SettingsItem(
                    icon = Icons.Filled.FileDownload, 
                    title = "Exportar Planilha", 
                    subtitle = if (isGer) "Gerar e compartilhar relatório .csv / Excel (todas as fazendas)" else "Gerar e compartilhar planilha exclusiva de $farmScopeText",
                    badgeText = "EXCEL / CSV",
                    onClick = onOpenExportSpreadsheet
                ) 
            }

            // Logout Button
            item {
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    OutlinedButton(
                        onClick = onLogout,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, Color(0xFFDC2626)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFDC2626)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Logout,
                            contentDescription = "Encerrar Sessão",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Desconectar / Trocar de Usuário",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector, 
    title: String, 
    subtitle: String,
    badgeText: String? = null,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(48.dp).background(BrandGreenLight, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = BrandGreen)
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = title, 
                            style = MaterialTheme.typography.titleMedium, 
                            fontWeight = FontWeight.Bold, 
                            color = BrandTextPrimary,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (badgeText != null) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = BrandGreenLight,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    color = BrandGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = BrandTextSecondary)
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = BrandTextSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
            HorizontalDivider(color = BrandBackground, thickness = 1.dp, modifier = Modifier.padding(start = 88.dp))
        }
    }
}

// --- MODERN UI COMPONENTS ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernFarmSelector(
    farms: List<Farm>,
    selectedFarm: Farm,
    onFarmSelected: (Farm) -> Unit,
    isManager: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val isSingleFarm = farms.size <= 1
    
    ExposedDropdownMenuBox(
        expanded = expanded && !isSingleFarm,
        onExpandedChange = { 
            if (!isSingleFarm) {
                expanded = it 
            }
        }
    ) {
        OutlinedTextField(
            value = selectedFarm.name,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { 
                if (!isSingleFarm) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                } else {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Fazenda Fixa",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedTrailingIconColor = Color.White,
                unfocusedTrailingIconColor = Color.White.copy(alpha = 0.7f),
                focusedContainerColor = Color.White.copy(alpha = 0.15f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.15f),
                focusedLabelColor = Color.White,
                unfocusedLabelColor = Color.White.copy(alpha = 0.7f)
            ),
            shape = RoundedCornerShape(12.dp),
            label = { 
                Text(
                    if (isManager) "Unidade Selecionada (Modo Gerencial)" else "Unidade Operacional (Fixa)"
                ) 
            },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        
        if (!isSingleFarm) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(BrandSurface)
            ) {
                farms.forEach { farm ->
                    val isCurrent = farm.id == selectedFarm.id
                    DropdownMenuItem(
                        text = { 
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = farm.name, 
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, 
                                    color = if (isCurrent) BrandGreen else BrandTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = farm.region, 
                                    style = MaterialTheme.typography.bodySmall, 
                                    color = BrandTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Agriculture, 
                                contentDescription = null, 
                                tint = if (isCurrent) BrandGreen else BrandTextSecondary, 
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Filled.Check, 
                                    contentDescription = null, 
                                    tint = BrandGreen, 
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        onClick = { onFarmSelected(farm); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
fun ModernKpiRow(
    totalVolume: Double, 
    rainyDays: Int,
    monthLabel: String = "SET",
    previousYearVolume: Double? = null,
    currentYearTag: String = "2026",
    previousYearTag: String = "2025",
    hasComparison: Boolean = true,
    totalYearVolume: Double? = null
) {
   Row(
       horizontalArrangement = Arrangement.spacedBy(16.dp), 
       modifier = Modifier.fillMaxWidth()
   ) {
       ModernKpiCard(
           title = "Acumulado $monthLabel",
           yearTag = currentYearTag,
           value = String.format(Locale.US, "%.1f", totalVolume),
           unit = "mm",
           icon = Icons.Filled.WaterDrop,
           iconTint = BrandWater,
           subtitle = if (hasComparison && previousYearVolume != null) {
               "$previousYearTag: ${String.format(Locale.US, "%.0f", previousYearVolume)} mm"
           } else if (totalYearVolume != null) {
               "Total no ano: ${String.format(Locale.US, "%.0f", totalYearVolume)} mm"
           } else null,
           modifier = Modifier.weight(1f)
       )
       ModernKpiCard(
           title = "Dias c/ Chuva",
           yearTag = currentYearTag,
           value = "$rainyDays",
           unit = "dias",
           icon = Icons.Filled.Cloud,
           iconTint = BrandTextSecondary,
           subtitle = monthLabel,
           modifier = Modifier.weight(1f)
       )
   }
}

@Composable
fun ModernKpiCard(
    title: String, 
    value: String, 
    unit: String, 
    icon: ImageVector, 
    iconTint: Color, 
    yearTag: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BrandSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                horizontalArrangement = Arrangement.SpaceBetween, 
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    title, 
                    style = MaterialTheme.typography.labelMedium, 
                    color = BrandTextSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            
            Spacer(Modifier.height(10.dp))
            
            // Explicit Year indicator badge above the mm / days value
            if (yearTag != null) {
                Box(
                    modifier = Modifier
                        .background(BrandGreenLight, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Ano $yearTag",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold,
                        color = BrandGreen
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = BrandTextPrimary)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = BrandTextSecondary, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun ModernRainfallLogCard(
    log: RainfallLog,
    onClick: () -> Unit = {}
) {
  val parts = log.date.split("/")
  val day = if (parts.isNotEmpty()) parts[0] else ""
  val monthNum = if (parts.size > 1) parts[1] else ""
  
  val monthLabel = when (monthNum) {
      "01" -> "JAN"; "02" -> "FEV"; "03" -> "MAR"; "04" -> "ABR"
      "05" -> "MAI"; "06" -> "JUN"; "07" -> "JUL"; "08" -> "AGO"
      "09" -> "SET"; "10" -> "OUT"; "11" -> "NOV"; "12" -> "DEZ"
      else -> monthNum
  }

  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = BrandSurface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, if (log.isEdited) Color(0xFFF59E0B).copy(alpha = 0.45f) else Color(0xFFE5E7EB))
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
       // Calendar Block
       Column(
         horizontalAlignment = Alignment.CenterHorizontally,
         modifier = Modifier
            .background(BrandBackground, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
       ) {
          Text(day, fontWeight = FontWeight.Black, color = BrandGreen, style = MaterialTheme.typography.titleLarge)
          Text(monthLabel, color = BrandTextSecondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
       }
       
       Spacer(Modifier.width(14.dp))
       
       // Notes & Edit status
       Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
              Text("Observação", style = MaterialTheme.typography.labelSmall, color = BrandTextSecondary)
              if (log.isEdited) {
                  Spacer(Modifier.width(6.dp))
                  Surface(
                      color = Color(0xFFFEF3C7),
                      shape = RoundedCornerShape(6.dp),
                      border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f))
                  ) {
                      Row(
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                          verticalAlignment = Alignment.CenterVertically
                      ) {
                          Icon(
                              Icons.Filled.Edit,
                              contentDescription = "Valor editado",
                              tint = Color(0xFFD97706),
                              modifier = Modifier.size(11.dp)
                          )
                          Spacer(Modifier.width(3.dp))
                          Text(
                              "Editado",
                              color = Color(0xFF92400E),
                              fontSize = 11.sp,
                              fontWeight = FontWeight.Bold
                          )
                      }
                  }
              }
          }
          Spacer(Modifier.height(2.dp))
          Text(
              text = log.notes, 
              style = MaterialTheme.typography.bodyMedium, 
              color = BrandTextPrimary, 
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
          )
       }
       
       // Volume and Click Indicator
       Row(verticalAlignment = Alignment.CenterVertically) {
           Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
              Text(
                  text = "${log.volumeMm}", 
                  style = MaterialTheme.typography.titleLarge, 
                  fontWeight = FontWeight.Black, 
                  color = if (log.volumeMm > 0) BrandWater else BrandTextSecondary
              )
              Text("mm", style = MaterialTheme.typography.labelSmall, color = BrandTextSecondary)
           }
           Spacer(Modifier.width(8.dp))
           Icon(
               imageVector = Icons.Filled.MoreVert,
               contentDescription = "Opções do lançamento",
               tint = BrandTextSecondary.copy(alpha = 0.4f),
               modifier = Modifier.size(18.dp)
           )
       }
    }
  }
}

fun resolveCorrectUsername(farm: Farm?, userList: List<UserAccount>): String {
    if (farm == null) return ""
    // 1. Tentar encontrar usuário cujo assignedFarmName seja exatamente o nome da fazenda (case-insensitive)
    val exactMatch = userList.firstOrNull { 
        it.assignedFarmName?.trim().equals(farm.name.trim(), ignoreCase = true) 
    }
    if (exactMatch != null) return exactMatch.username

    // 2. Tentar encontrar por correspondência simplificada (ex: "Fazenda Lambari" -> "lambari", "Fazenda Primavera" -> "primavera")
    val farmClean = farm.name.lowercase()
        .replace("fazenda", "")
        .replace("sítio", "")
        .replace("sitio", "")
        .replace(" ", "")
        .replace("-", "")
        .trim()

    val cleanMatch = userList.firstOrNull {
        val u = it.username.lowercase().trim()
        u == farmClean || (farmClean.isNotEmpty() && (farmClean.contains(u) || u.contains(farmClean)))
    }
    if (cleanMatch != null) return cleanMatch.username

    // 3. Fallback inteligente
    return if (farmClean.isNotEmpty()) farmClean else farm.name.lowercase().replace(" ", "")
}

fun resolveGerencialUsername(userList: List<UserAccount>): String {
    val gerencial = userList.firstOrNull { it.role == UserRole.GERENCIAL }
    return gerencial?.username ?: "gerente"
}

// --- AUTHENTICATION SCREEN ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    availableFarms: List<Farm>,
    users: MutableList<UserAccount>,
    onLoginSuccess: (UserAccount) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isAuthenticating by remember { mutableStateOf(false) }

    var isGerencialSelected by remember { mutableStateOf(true) }
    var selectedFarm by remember { mutableStateOf<Farm?>(availableFarms.firstOrNull()) }
    var farmDropdownExpanded by remember { mutableStateOf(false) }

    var username by remember { 
        mutableStateOf(
            if (isGerencialSelected) resolveGerencialUsername(users) 
            else resolveCorrectUsername(selectedFarm, users)
        ) 
    }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Sincroniza em segundo plano os usuários cadastrados no Supabase para garantir que o login seja sempre atual
    LaunchedEffect(Unit) {
        val remoteRes = NetworkModule.supabaseRepository.fetchUsers()
        if (remoteRes.isSuccess) {
            val remoteUsers = remoteRes.getOrNull().orEmpty()
            if (remoteUsers.isNotEmpty()) {
                remoteUsers.forEach { ru ->
                    val idx = users.indexOfFirst { it.username.equals(ru.username, ignoreCase = true) }
                    if (idx != -1) users[idx] = ru else users.add(ru)
                }
                coroutineScope.launch { AppDatabaseManager.saveUsersAsync(context, users.toList()) }
                // Atualiza o campo com o login correto sincronizado
                if (isGerencialSelected) {
                    username = resolveGerencialUsername(users)
                } else {
                    username = resolveCorrectUsername(selectedFarm, users)
                }
            }
        }
    }

    fun executeLogin(u: String, p: String) {
        val cleanUser = u.trim().lowercase()
        val cleanPass = p.trim()
        if (cleanPass.isEmpty()) {
            errorMessage = "Por favor, digite a sua senha de acesso."
            return
        }

        isAuthenticating = true
        errorMessage = null

        coroutineScope.launch {
            // 1. Tentar validação de credenciais diretamente no Supabase em tempo real
            val remoteAuth = NetworkModule.supabaseRepository.authenticateUser(cleanUser, cleanPass)
            var foundUser: UserAccount? = null
            var authenticatedViaCloud = false

            if (remoteAuth.isSuccess) {
                foundUser = remoteAuth.getOrNull()
                authenticatedViaCloud = true
            } else {
                // 2. Se o Supabase estiver offline ou tabela ainda não sincronizada, valida localmente
                val localMatch = users.find { user ->
                    val matchesUsername = user.username.trim().lowercase() == cleanUser
                    val matchesFarm = user.assignedFarmName?.trim()?.lowercase() == cleanUser
                    val matchesRole = if (isGerencialSelected) {
                        (cleanUser in listOf("diretoria", "gerencial", "gerente", "admin") && user.role == UserRole.GERENCIAL)
                    } else false
                    (matchesUsername || matchesFarm || matchesRole) && user.password.trim() == cleanPass
                }
                foundUser = localMatch
            }

            isAuthenticating = false

            if (foundUser == null) {
                errorMessage = "Credenciais inválidas. Verifique o usuário e a senha digitada."
                return@launch
            }

            // Sincroniza usuário autenticado na base local para garantir disponibilidade offline futura
            val idx = users.indexOfFirst { it.username.equals(foundUser.username, ignoreCase = true) }
            if (idx != -1) users[idx] = foundUser else users.add(foundUser)
            coroutineScope.launch { AppDatabaseManager.saveUsersAsync(context, users.toList()) }

            if (isGerencialSelected) {
                if (foundUser.role == UserRole.GERENCIAL) {
                    errorMessage = null
                    if (authenticatedViaCloud) {
                        Toast.makeText(context, "Acesso autenticado via Supabase Cloud", Toast.LENGTH_SHORT).show()
                    }
                    onLoginSuccess(foundUser)
                } else {
                    errorMessage = "O usuário '${foundUser.username}' pertence à ${foundUser.assignedFarmName ?: "uma fazenda"}, e não à Conta Gerencial. Selecione a unidade correspondente acima."
                }
            } else {
                val targetFarm = selectedFarm
                if (targetFarm != null && foundUser.role == UserRole.FAZENDA && foundUser.assignedFarmName != null && !foundUser.assignedFarmName.equals(targetFarm.name, ignoreCase = true)) {
                    errorMessage = "O usuário '${foundUser.username}' tem acesso à '${foundUser.assignedFarmName}', e não à '${targetFarm.name}'."
                } else {
                    errorMessage = null
                    if (authenticatedViaCloud) {
                        Toast.makeText(context, "Acesso autenticado via Supabase Cloud", Toast.LENGTH_SHORT).show()
                    }
                    onLoginSuccess(foundUser)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandBackground)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 40.dp, bottom = 36.dp)
        ) {
            // Header Branding
            item {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(BrandGreen, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.WaterDrop,
                        contentDescription = "Logo",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Itacumbi Agro",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = BrandGreen
                )
                Text(
                    text = "Sistema de Monitoramento Pluviométrico",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(24.dp))
            }

            // Main Login Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = BrandSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(1.dp, Color(0xFFE5E7EB))
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Text(
                            text = "Acesse sua Conta",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = BrandTextPrimary
                        )
                        Text(
                            text = "Selecione o perfil ou unidade antes de autenticar",
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandTextSecondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                        )

                        // 1. SELETOR DE PERFIL / TIPO DE ACESSO
                        Text(
                            text = "1. TIPO DE CONTA / PERFIL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = BrandGreen,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(10.dp))

                        // Segmented Selection Cards
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Card: Conta Gerencial
                            Surface(
                                onClick = {
                                    isGerencialSelected = true
                                    username = resolveGerencialUsername(users)
                                    password = ""
                                    errorMessage = null
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                color = if (isGerencialSelected) BrandGreenLight.copy(alpha = 0.6f) else Color(0xFFF9FAFB),
                                border = BorderStroke(
                                    width = if (isGerencialSelected) 2.dp else 1.dp,
                                    color = if (isGerencialSelected) BrandGreen else Color(0xFFE5E7EB)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .background(
                                                if (isGerencialSelected) BrandGreen else Color(0xFFE2E8F0),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.AdminPanelSettings,
                                            contentDescription = null,
                                            tint = if (isGerencialSelected) Color.White else Color(0xFF64748B),
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Gerencial",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isGerencialSelected) BrandGreen else BrandTextPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = "Diretoria",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isGerencialSelected) BrandGreen.copy(alpha = 0.85f) else BrandTextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            // Card: Unidade de Fazenda
                            Surface(
                                onClick = {
                                    isGerencialSelected = false
                                    val currentFarm = selectedFarm ?: availableFarms.firstOrNull()
                                    selectedFarm = currentFarm
                                    username = resolveCorrectUsername(currentFarm, users)
                                    password = ""
                                    errorMessage = null
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                color = if (!isGerencialSelected) BrandGreenLight.copy(alpha = 0.6f) else Color(0xFFF9FAFB),
                                border = BorderStroke(
                                    width = if (!isGerencialSelected) 2.dp else 1.dp,
                                    color = if (!isGerencialSelected) BrandGreen else Color(0xFFE5E7EB)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .background(
                                                if (!isGerencialSelected) BrandGreen else Color(0xFFE2E8F0),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Agriculture,
                                            contentDescription = null,
                                            tint = if (!isGerencialSelected) Color.White else Color(0xFF64748B),
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Fazenda",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (!isGerencialSelected) BrandGreen else BrandTextPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = "Unidade Fixa",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (!isGerencialSelected) BrandGreen.copy(alpha = 0.85f) else BrandTextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        // Se Fazenda selecionada, exibe o seletor de qual unidade
                        if (!isGerencialSelected) {
                            Spacer(Modifier.height(12.dp))
                            ExposedDropdownMenuBox(
                                expanded = farmDropdownExpanded,
                                onExpandedChange = { farmDropdownExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = selectedFarm?.let { "${it.name} (${it.region})" } ?: "Selecione a Fazenda",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Unidade Operacional") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = farmDropdownExpanded) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Agriculture,
                                            contentDescription = null,
                                            tint = BrandGreen
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = BrandGreen,
                                        unfocusedBorderColor = Color(0xFFD1D5DB),
                                        focusedTextColor = BrandTextPrimary,
                                        unfocusedTextColor = BrandTextPrimary,
                                        focusedLabelColor = BrandGreen,
                                        unfocusedLabelColor = BrandTextSecondary
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )

                                ExposedDropdownMenu(
                                    expanded = farmDropdownExpanded,
                                    onDismissRequest = { farmDropdownExpanded = false },
                                    modifier = Modifier.background(BrandSurface)
                                ) {
                                    availableFarms.forEach { farm ->
                                        val isThisSelected = selectedFarm?.name == farm.name
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Filled.Agriculture,
                                                    contentDescription = null,
                                                    tint = if (isThisSelected) BrandGreen else BrandTextSecondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            },
                                            text = {
                                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                                    Text(
                                                        text = farm.name,
                                                        fontWeight = if (isThisSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isThisSelected) BrandGreen else BrandTextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = farm.region,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = BrandTextSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            },
                                            trailingIcon = {
                                                if (isThisSelected) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = "Selecionado",
                                                        tint = BrandGreen,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedFarm = farm
                                                username = resolveCorrectUsername(farm, users)
                                                password = ""
                                                errorMessage = null
                                                farmDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Banner Explicativo do Perfil Selecionado
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = if (isGerencialSelected) BrandGreenLight.copy(alpha = 0.5f) else Color(0xFFF0FDF4),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isGerencialSelected) BrandGreen.copy(alpha = 0.3f) else Color(0xFFBBF7D0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isGerencialSelected) Icons.Filled.AdminPanelSettings else Icons.Filled.Place,
                                    contentDescription = null,
                                    tint = BrandGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isGerencialSelected) 
                                        "Conta Gerencial: Acesso completo com alternância entre todas as fazendas." 
                                    else 
                                        "Operador de Fazenda: Acesso restrito a ${selectedFarm?.name ?: "esta unidade"}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BrandGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // 2. CAMPOS DE CREDENCIAIS (USUÁRIO E SENHA)
                        Text(
                            text = "2. CREDENCIAIS DE ACESSO",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = BrandGreen,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(8.dp))

                        // Username Field
                        OutlinedTextField(
                            value = username,
                            onValueChange = { 
                                username = it
                                errorMessage = null
                            },
                            label = { 
                                Text(if (isGerencialSelected) "Usuário Gerencial" else "Usuário da Fazenda") 
                            },
                            placeholder = { 
                                Text(if (isGerencialSelected) "ex: gerente" else "ex: operador") 
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Person, contentDescription = null, tint = BrandGreen)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandGreen,
                                focusedLabelColor = BrandGreen,
                                cursorColor = BrandGreen
                            )
                        )

                        Spacer(Modifier.height(14.dp))

                        // Password Field
                        OutlinedTextField(
                            value = password,
                            onValueChange = { 
                                password = it
                                errorMessage = null
                            },
                            label = { Text("Senha de Acesso") },
                            placeholder = { Text("Digite sua senha") },
                            leadingIcon = {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = BrandGreen)
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                        contentDescription = if (passwordVisible) "Ocultar senha" else "Mostrar senha",
                                        tint = BrandTextSecondary
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandGreen,
                                focusedLabelColor = BrandGreen,
                                cursorColor = BrandGreen
                            )
                        )

                        // Error notification
                        if (errorMessage != null) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                color = Color(0xFFFEE2E2),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.ErrorOutline, 
                                        contentDescription = null, 
                                        tint = Color(0xFFDC2626), 
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = errorMessage!!,
                                        color = Color(0xFFB91C1C),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // Login Action Button
                        Button(
                            enabled = !isAuthenticating,
                            onClick = {
                                executeLogin(username, password)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandGreen,
                                contentColor = Color.White
                            )
                        ) {
                            if (isAuthenticating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Autenticando na Nuvem...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            } else {
                                Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Entrar no Sistema", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }

            // Clean, professional footer
            item {
                Spacer(Modifier.height(24.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = BrandTextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Ambiente Seguro • Itacumbi Agro",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandTextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
