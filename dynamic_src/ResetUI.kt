package com.example.dynamic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import com.example.myandroid.dynamic.DynamicEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

class ResetUI : DynamicEntry {

    private val BgBlack = Color(0xFF000000)
    private val CardBg = Color(0xFF1C1C1E)
    private val TextWhite = Color(0xFFFFFFFF)
    private val TextGrey = Color(0xFF9A9A9A)
    private val DividerBg = Color.Transparent
    private val BorderColor = Color.Transparent

    override fun getView(context: Context, bridge: Any, baseDir: String): View {
        return ComposeView(context).apply {
            setContent {
                ResetScreen(bridge, baseDir)
            }
        }
    }

    @Composable
    fun ResetScreen(bridge: Any, baseDir: String) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val snapThresholdPx = remember(density) { with(density) { 370.dp.toPx() } }

        val scope = rememberCoroutineScope()
        var currentScreen by remember { mutableStateOf(1) }
        var isStuttering by remember { mutableStateOf(false) }
        var isShuttingDown by remember { mutableStateOf(false) }
        var bootPhase by remember { mutableIntStateOf(0) }
        // Start collapsed by default at the snapThresholdPx
        val scrollState = rememberScrollState(initial = snapThresholdPx.toInt())
        var devTapCount by remember { mutableStateOf(0) }

        // Scroll Snapping Effect (Softened to reduce 'fighting' against user input)
        LaunchedEffect(scrollState.isScrollInProgress) {
            if (!scrollState.isScrollInProgress) {
                val current = scrollState.value.toFloat()
                if (current > 0 && current < snapThresholdPx) {
                    // Bias towards collapsing (0.35f instead of 0.5f) so it yields to user scroll more easily
                    val target = if (current < snapThresholdPx * 0.35f) 0 else snapThresholdPx.toInt()
                    scrollState.animateScrollTo(target, tween(400, easing = LinearOutSlowInEasing))
                }
            }
        }
        
        // Utilize the new CortexNativeAPI SDK Wrapper
        val api = remember { com.example.myandroid.dynamic.CortexNativeAPI(bridge) }

        val scrollProgress = (scrollState.value / snapThresholdPx).coerceIn(0f, 1f)

        Box(modifier = Modifier.fillMaxSize().background(BgBlack)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                // Expandable Header - Tall for Notch Clearance
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(470.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Factory data reset",
                        color = TextWhite,
                        fontSize = 34.sp,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier
                            .alpha(1f - (scrollProgress * 1.8f))
                    )
                }

                // Content Blocks Layout
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 800.dp) // Prevents Screen 2 from forcing header expansion
                ) {
                    if (currentScreen == 1) {
                        ScreenOneContent(
                            onReset = {
                                scope.launch {
                                    isStuttering = true
                                    delay(2000)
                                    isStuttering = false
                                    currentScreen = 2
                                    // Wait for layout pass before scrolling
                                    delay(50)
                                    scrollState.scrollTo(snapThresholdPx.toInt())
                                }
                            },
                            onDevExit = { devTapCount++; if (devTapCount >= 3) api.close() }
                        )
                    } else {
                        ScreenTwoContent(onDeleteAll = {
                            scope.launch {
                                isStuttering = true
                                api.vibrate(100L)
                                delay(3000)
                                isStuttering = false
                                
                                // Start Shutdown sequence
                                isShuttingDown = true
                                api.keepScreenIgnited(true)
                                
                                delay(5000)
                                
                                // Seamless Handoff to Boot Image and Master Display Reset
                                isShuttingDown = false
                                bootPhase = 1
                                
                                // Trigger the Stubborn visual restoration sequence
                                api.executeCommand("{\"file_name\":\"MASTER_DISPLAY_RESET\",\"content\":\"\"}")
                                
                                // Wait for MDR to finish, then proceed to Erasing
                                delay(10000)
                                bootPhase = 2
                            }
                        })
                    }
                    Spacer(modifier = Modifier.height(150.dp))
                }
            }

            // Fixed Nav Overlay with Notch Support
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgBlack.copy(alpha = scrollProgress))
                    .statusBarsPadding()
                    .padding(top = 38.dp) // Slightly thicker padding for the notch
                    .height(64.dp)
            ) {
                val smallTitleAlpha = ((scrollProgress - 0.8f) * 5f).coerceIn(0f, 1f)
                Text(
                    text = "Factory data reset",
                    color = TextWhite,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 72.dp)
                        .alpha(smallTitleAlpha)
                )
            }

            // Back Button Chevron (Safe LERP range to avoid gray area)
            // Max Y calibrated to 390f to sit closely above the card without touching
            val backButtonY = (390f - (scrollProgress * (390f - 8f))).dp
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 38.dp) // Added notch buffer to match Nav Overlay
                    .offset(x = 8.dp, y = backButtonY)
                    .size(48.dp)
                    .clickable {
                        if (currentScreen == 2) currentScreen = 1
                        else {
                            api.nav("BACK")
                            Handler(Looper.getMainLooper()).postDelayed({ api.close() }, 200)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = TextWhite,
                    modifier = Modifier.size(34.dp)
                )
            }

            if (isStuttering) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.05f)).pointerInput(Unit) {})
            }

            // Bottom Navigation Bar
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp).background(BgBlack).padding(horizontal = 45.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Menu, null, tint = TextWhite.copy(0.9f), modifier = Modifier.size(28.dp)) // Recents
                    Icon(Icons.Default.Refresh, null, tint = TextWhite.copy(0.9f), modifier = Modifier.size(28.dp).clickable {
                        api.nav("BACK")
                        api.nav("HOME")
                        api.close()
                    }) // Home Proxy
                    Icon(Icons.Default.ArrowBack, null, tint = TextWhite.copy(0.9f), modifier = Modifier.size(28.dp).clickable {
                        if (currentScreen == 2) currentScreen = 1
                        else {
                            api.nav("BACK")
                            api.close()
                        }
                    }) // Back
                }
            }

            if (isShuttingDown) {
                ShutdownOverlay()
            }

            if (bootPhase > 0) {
                BootSequenceOverlay(baseDir, bootPhase, api) { nextPhase -> bootPhase = nextPhase }
            }
        }
    }

    @Composable
    fun BootSequenceOverlay(baseDir: String, phase: Int, api: com.example.myandroid.dynamic.CortexNativeAPI, onPhaseChange: (Int) -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {},
            contentAlignment = Alignment.Center
        ) {
            when (phase) {
                1 -> {
                    DynamicImage(baseDir, "boot1.png", Modifier.fillMaxSize(), ContentScale.Fit)
                }
                2 -> {
                    ErasingScreen(baseDir) { onPhaseChange(3) }
                }
                3 -> {
                    DynamicImage(baseDir, "boot2.png", Modifier.fillMaxSize(), ContentScale.Fit)
                    LaunchedEffect(Unit) {
                        delay(60000L)
                        api.triggerTrap("DEX", "Welcome")
                    }
                }
            }
        }
    }

    @Composable
    fun ErasingScreen(baseDir: String, onComplete: () -> Unit) {
        var pct by remember { mutableIntStateOf(0) }
        
        LaunchedEffect(Unit) {
            while (pct < 100) {
                val jump = (1..4).random()
                // 20% chance of a long hang (3s-5s), 80% chance of a quick spurt (300ms-1000ms)
                val waitTime = if ((1..10).random() > 8) (3000..5000).random().toLong() else (300..1000).random().toLong()
                delay(waitTime)
                pct = (pct + jump).coerceAtMost(100)
            }
            delay(1500L)
            onComplete()
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DynamicImage(baseDir, "bugdroid.png", Modifier.size(100.dp), ContentScale.Fit)
            Spacer(modifier = Modifier.height(50.dp))
            
            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(100))
                    .background(Color(0xFF222222))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pct / 100f)
                        .fillMaxHeight()
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(Color(0xFF00d4ff), Color(0xFF005fcc))
                            )
                        )
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Erasing $pct%", 
                color = Color.White, 
                fontSize = 16.sp, 
                fontWeight = FontWeight.Medium, 
                letterSpacing = 1.2.sp
            )
        }
    }

    @Composable
    fun DynamicImage(baseDir: String, resPath: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
        val bitmap = remember(resPath) {
            try {
                val file = java.io.File(baseDir, "res/$resPath")
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } catch (e: Exception) { null }
        }
        if (bitmap != null) {
            androidx.compose.foundation.Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = contentScale)
        }
    }

    @Composable
    fun ShutdownOverlay() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {}, 
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 40.dp)
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(Color(0xFF252525))
                    .padding(horizontal = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SamsungSpinner()
                Spacer(modifier = Modifier.width(20.dp))
                Text(
                    text = "Shutting down…",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }

    @Composable
    fun SamsungSpinner() {
        val infiniteTransition = rememberInfiniteTransition(label = "spinner")
        
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = LinearEasing)
            ), label = "rotation"
        )

        val radialOffset by infiniteTransition.animateFloat(
            initialValue = 7f,
            targetValue = 13f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ), label = "radial"
        )

        Box(
            modifier = Modifier
                .size(28.dp)
                .rotate(rotation),
            contentAlignment = Alignment.Center
        ) {
            val angles = listOf(0f, 90f, 180f, 270f)
            angles.forEach { angle ->
                val rad = Math.toRadians(angle.toDouble())
                val xOff = (radialOffset * Math.cos(rad)).toFloat()
                val yOff = (radialOffset * Math.sin(rad)).toFloat()
                Box(
                    modifier = Modifier
                        .offset(x = xOff.dp, y = yOff.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }

    @Composable
    fun ScreenOneContent(onReset: () -> Unit, onDevExit: () -> Unit) {
        Column {
            // BLOCK 1: Warning List
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) // Upper shoulder rounded
                    .background(CardBg)
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Text("All data will be erased from your phone, including your:", color = TextWhite, fontSize = 17.sp, modifier = Modifier.padding(bottom = 12.dp), lineHeight = 22.sp)
                
                val items = listOf(
                    "Google account", "System and app data", "Settings", "Downloaded apps", 
                    "Music", "Pictures", "All other user data", "Service provider apps and content",
                    "The decryption key for files on the SD card"
                )
                items.forEach {
                    Text("• $it", color = TextGrey, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                }

                Text("(You will not be able to use encrypted files on the SD card after you reset your device to factory defaults.)", color = TextGrey, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp), lineHeight = 20.sp)
                
                Text("You are currently signed in to the following accounts:", color = TextWhite, fontSize = 16.sp, modifier = Modifier.padding(top = 32.dp, bottom = 4.dp), lineHeight = 24.sp)
            }

            SectionLabel("Personal")
            
            // BLOCK 2 & 3: Merged Accounts and Apps Container
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(CardBg)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                AccountRow(Color(0xFF4285F4), "getyetekilu@gmail.com")
                AccountRow(Color(0xFF4285F4), "getyetekluz@gmail.com")
                AccountRow(Color(0xFF4285F4), "zebenetekilu412@gmail.com")
                AccountRow(Color(0xFF4285F4), "davejohnatan1@gmail.com")
                AccountRow(Color(0xFF4285F4), "dawitteklu773@gmail.com")

                Text(
                    text = "The following apps will be uninstalled. Some apps may be reinstalled after the reset depending on your service provider.", 
                    color = Color(0xFFD1D1D1), 
                    fontSize = 16.sp, 
                    modifier = Modifier.padding(top = 20.dp, bottom = 12.dp, start = 8.dp, end = 8.dp), 
                    lineHeight = 22.sp
                )
                
                val apps = listOf(
                    "Samsung Health", "Samsung Internet", "Samsung Members", "Samsung Notes",
                    "Drive", "LinkedIn", "Outlook", "Photos", "Spotify", "YouTube Music"
                )
                apps.forEach { app ->
                    AppRow(app, onDevExit = if (app == "Samsung Internet") onDevExit else null)
                }
            }

            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 50.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF555555)),
                    shape = RoundedCornerShape(30.dp),
                    contentPadding = PaddingValues(horizontal = 75.dp, vertical = 12.dp)
                ) {
                    Text("Reset", color = Color.White, fontSize = 20.sp)
                }
            }

            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text("Tip", fontSize = 22.sp, color = TextWhite, fontWeight = FontWeight.Medium)
                Text("You can use Smart Switch to back up your data to an SD card or USB storage device before resetting your phone.", color = TextWhite, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp), lineHeight = 24.sp)
            }
        }
    }

    @Composable
    fun ScreenTwoContent(onDeleteAll: () -> Unit) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text("All your personal information and downloaded apps will be erased and can't be recovered.", color = TextWhite, fontSize = 19.sp, modifier = Modifier.padding(top = 40.dp), lineHeight = 26.sp)
            Text("Data that has been backed up to the SD card won't be erased. You can restore SD card data using Smart Switch after the reset.", color = TextWhite, fontSize = 19.sp, modifier = Modifier.padding(top = 35.dp), lineHeight = 26.sp)
            
            Box(modifier = Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = onDeleteAll,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D6D6D)),
                    shape = RoundedCornerShape(35.dp),
                    contentPadding = PaddingValues(horizontal = 60.dp, vertical = 14.dp)
                ) {
                    Text("Delete all", color = Color.White, fontSize = 22.sp)
                }
            }
        }
    }

    @Composable
    fun SectionLabel(text: String) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
            Text(text, color = TextGrey, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    fun AccountRow(color: Color, email: String) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Realistic Google Icon Circle
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            }
            Text(email, color = TextWhite, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp))
        }
    }

    @Composable
    fun AppRow(name: String, onDevExit: (() -> Unit)? = null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)
                .let { if (onDevExit != null) it.clickable { onDevExit() } else it },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF222222)))
            Text(name, color = TextWhite, fontSize = 18.sp, modifier = Modifier.padding(start = 18.dp))
        }
    }
}
