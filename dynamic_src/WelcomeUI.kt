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
import com.example.myandroid.dynamic.DynamicEntry
import kotlinx.coroutines.delay

class WelcomeUI : DynamicEntry {

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
        }
    }

    @Composable
    fun SetupWizard(bridge: Any, baseDir: String) {
        val api = remember { com.example.myandroid.dynamic.CortexNativeAPI(bridge) }
        var currentStep by remember { mutableStateOf(0) }
        var isProcessing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope() 
        val transientSteps = remember { listOf(4, 5, 7, 8, 9, 11, 12, 16, 17, 18) }

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
                        0 -> WelcomeScreen { navigateTo(1) }
                        1 -> ReviewScreen { navigateTo(2) }
                        2 -> PermissionsScreen(baseDir) { navigateTo(3) }
                        3 -> WifiScreen(baseDir, onSkip = { navigateTo(4) })
                        4 -> LoadingScreen(baseDir, null, "Checking for updates...", assetPath = "checking_info_icon.png", onComplete = { navigateTo(5) })
                        5 -> LoadingScreen(baseDir, null, "Getting your phone ready...", assetPath = "checking_info_icon.png", onComplete = { navigateTo(6) })
                        6 -> CopyDataScreen(baseDir, onNext = { navigateTo(7) })
                        7 -> LoadingScreen(baseDir, null, "Checking info...", assetPath = "checking_info_icon.png", onComplete = { navigateTo(8) })
                        8 -> LoadingScreen(baseDir, null, "Getting your account info...", isGoogle = true, onComplete = { navigateTo(9) })
                        9 -> LoadingScreen(baseDir, null, "Google services", isGoogle = true, onComplete = { navigateTo(10) })
                        10 -> ProtectPhoneScreen(onSkip = { navigateTo(11) })
                        11 -> LoadingScreen(baseDir, null, "", onComplete = { navigateTo(12) })
                        12 -> LoadingScreen(baseDir, null, "Checking...", isAssistant = true, onComplete = { navigateTo(13) })
                        13 -> AssistantHeyGoogleScreen(baseDir, onNext = { navigateTo(14) })
                        14 -> AssistantLockScreen(baseDir, onNext = { navigateTo(15) })
                        15 -> AppReviewScreen(baseDir, onOk = { navigateTo(16) })
                        16 -> LoadingScreen(baseDir, null, "Getting your phone ready...", assetPath = "checking_info_icon.png", onComplete = { navigateTo(17) })
                        17 -> LoadingScreen(baseDir, null, "Please wait...", assetPath = "samsung_dots_header.png", onComplete = { navigateTo(18) })
                        18 -> LoadingScreen(baseDir, null, "Get recommended apps", assetPath = "samsung_dots_header.png", onComplete = { navigateTo(19) })
                        19 -> RecommendedAppsScreen(baseDir, onNext = { navigateTo(20) })
                        20 -> FinalSetupScreen(onFinish = { api.close() })
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
                    Icon(Icons.Default.KeyboardArrowLeft, null, tint = Color.Black, modifier = Modifier.size(32.dp))
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
    fun WelcomeScreen(onStart: () -> Unit) {
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
            Text("Emergency call", fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline, modifier = Modifier.padding(12.dp))
            Text("Accessibility", fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline, modifier = Modifier.padding(bottom = 60.dp))
        }
    }

    @Composable
    fun ReviewScreen(onAgree: () -> Unit) {
        var checks by remember { mutableStateOf(listOf(false, false, false, false)) }
        val agreeAll = checks.all { it }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(80.dp))
            Icon(Icons.Outlined.Info, null, tint = SamsungBlue, modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally))
            Text("For your review", fontSize = 32.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 40.dp))
            ReviewItem("End User Licence Agreement", "This includes your agreement that Samsung may update your phone software automatically from time to time to ensure the safety, security, and functionality of your phone.", checks[0]) { checks = checks.toMutableList().apply { set(0, !checks[0]) } }
            ReviewItem("Privacy Policy", null, checks[1]) { checks = checks.toMutableList().apply { set(1, !checks[1]) } }
            ReviewItem("Sending of Diagnostic Data (optional)", null, checks[2]) { checks = checks.toMutableList().apply { set(2, !checks[2]) } }
            ReviewItem("Information Linking (optional)", null, checks[3]) { checks = checks.toMutableList().apply { set(3, !checks[3]) } }
            DashedDivider(modifier = Modifier.padding(vertical = 20.dp))
            ReviewItem("Agree to all (optional)", null, agreeAll, isBold = true) { checks = List(4) { !agreeAll } }
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
    fun WifiScreen(baseDir: String, onSkip: () -> Unit) {
        var isWifiEnabled by remember { mutableStateOf(true) }
        
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(80.dp))
            DynamicImage(baseDir, "wifi_logo.png", modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally))
            Text("Choose a Wi-Fi network", fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 40.dp))
            
            if (isWifiEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Icon(Icons.Default.Add, null, tint = SamsungGreen, modifier = Modifier.size(28.dp))
                    Text("Add network", fontSize = 20.sp, modifier = Modifier.padding(start = 24.dp).weight(1f))
                    Icon(Icons.Default.Search, null, tint = Color.Black, modifier = Modifier.size(24.dp))
                }
                
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    SamsungOrbitSpinner()
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Text(
                text = if (isWifiEnabled) "Turn off Wi-Fi" else "Turn on Wi-Fi",
                color = SamsungBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(vertical = 16.dp).clickable { isWifiEnabled = !isWifiEnabled }
            )
            Text("Skip", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 80.dp).clickable { onSkip() })
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
                Text("All of the following apps", fontSize = 18.sp, modifier = Modifier.weight(1f)); SamsungCheckbox(allSelected)
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
            Text(name, fontSize = 18.sp, modifier = Modifier.weight(1f).padding(horizontal = 16.dp)); SamsungCheckbox(checked)
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
                SamsungCheckbox(true); DynamicImage(baseDir, "samsung_max_logo.png", modifier = Modifier.padding(start = 16.dp).size(48.dp).clip(RoundedCornerShape(8.dp)))
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
    fun SamsungSpinner(baseDir: String) {
        val infiniteTransition = rememberInfiniteTransition()
        val rotation by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)))
        DynamicImage(baseDir, "spinner_static.png", modifier = Modifier.size(40.dp).rotate(rotation))
    }

    @Composable
    fun SamsungOrbitSpinner() {
        val infiniteTransition = rememberInfiniteTransition()
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f, 
            targetValue = 360f, 
            animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing))
        )

        Canvas(modifier = Modifier.size(60.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2.5f
            val dotCount = 8
            
            for (i in 0 until dotCount) {
                val angleInDegrees = (i * 360f / dotCount) + rotation
                val angleInRadians = Math.toRadians(angleInDegrees.toDouble()).toFloat()
                
                // The "Contract and Relax" effect logic:
                // We calculate a scale factor based on the dot's current angle in the rotation
                val scale = 0.6f + (Math.sin(Math.toRadians((angleInDegrees * 1.5).toDouble())).toFloat() + 1f) * 0.4f
                val alpha = 0.3f + (scale - 0.6f) * 1.5f

                val x = center.x + radius * Math.cos(angleInRadians.toDouble()).toFloat()
                val y = center.y + radius * Math.sin(angleInRadians.toDouble()).toFloat()

                drawCircle(
                    color = SamsungBlue,
                    radius = 6.dp.toPx() * scale,
                    center = Offset(x, y),
                    alpha = alpha.coerceIn(0.2f, 1f)
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
    fun ReviewItem(title: String, sub: String?, checked: Boolean, isBold: Boolean = false, onToggle: () -> Unit) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable { onToggle() }, verticalAlignment = Alignment.CenterVertically) { SamsungCheckbox(checked); Column(modifier = Modifier.padding(start = 16.dp)) { Text(title, fontSize = 18.sp, fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal); if (sub != null) Text(sub, fontSize = 14.sp, color = TextGrey); Text("Details", fontSize = 16.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline) } } }

    @Composable
    fun PermissionItem(baseDir: String, title: String, description: String, iconAsset: String? = null) { var isChecked by remember { mutableStateOf(true) }; Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.Top) { Box(modifier = Modifier.size(24.dp).padding(top = 4.dp)) { if (iconAsset != null) DynamicImage(baseDir, iconAsset) else Icon(Icons.Default.Phone, null, tint = Color.DarkGray) }; Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) { Text(title, fontSize = 18.sp); Text(description, fontSize = 14.sp, color = TextGrey) }; Switch(checked = isChecked, onCheckedChange = { isChecked = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SamsungBlue)) } }

    @Composable
    fun PermissionSection(title: String) { Column(modifier = Modifier.fillMaxWidth()) { Divider(color = DividerGrey, thickness = 1.dp, modifier = Modifier.padding(horizontal = 24.dp)); Text(text = title, fontSize = 15.sp, color = TextGrey, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) } }

    @Composable
    fun SamsungCheckbox(checked: Boolean) {
        val checkboxColor by animateColorAsState(if (checked) SamsungBlue else Color.Transparent, animationSpec = tween(200))
        val borderColor by animateColorAsState(if (checked) SamsungBlue else Color.LightGray, animationSpec = tween(200))
        val checkScale by animateFloatAsState(if (checked) 1f else 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        
        val shape = RoundedCornerShape(4.dp)
        Box(modifier = Modifier.size(22.dp).clip(shape).border(2.dp, borderColor, shape).background(checkboxColor), contentAlignment = Alignment.Center) {
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
