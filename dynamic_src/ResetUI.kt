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

class ResetUI : DynamicEntry {

    private val BgBlack = Color(0xFF000000)
    private val CardBg = Color(0xFF111111)
    private val TextWhite = Color(0xFFFFFFFF)
    private val TextGrey = Color(0xFF9A9A9A)
    private val DividerBg = Color(0xFF090909)
    private val BorderColor = Color(0xFF242424)
    
    private val SnapThreshold = 450f 

    override fun getView(context: Context, bridge: Any, baseDir: String): View {
        return ComposeView(context).apply {
            setContent {
                ResetScreen(bridge)
            }
        }
    }

    @Composable
    fun ResetScreen(bridge: Any) {
        val scope = rememberCoroutineScope()
        var currentScreen by remember { mutableStateOf(1) }
        var isStuttering by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()
        var devTapCount by remember { mutableStateOf(0) }

        // Scroll Snapping Effect
        LaunchedEffect(scrollState.isScrollInProgress) {
            if (!scrollState.isScrollInProgress) {
                val current = scrollState.value.toFloat()
                if (current > 0 && current < SnapThreshold) {
                    val target = if (current < SnapThreshold / 2) 0 else SnapThreshold.toInt()
                    scrollState.animateScrollTo(target, tween(300, easing = FastOutSlowInEasing))
                }
            }
        }
        
        // Utilize the new CortexNativeAPI SDK Wrapper
        val api = remember { com.example.myandroid.dynamic.CortexNativeAPI(bridge) }

        val scrollProgress = (scrollState.value / SnapThreshold).coerceIn(0f, 1f)

        Box(modifier = Modifier.fillMaxSize().background(BgBlack)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                // Expandable Header
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Factory data reset",
                        color = TextWhite,
                        fontSize = 34.sp,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier.alpha(1f - (scrollProgress * 1.5f))
                    )
                }

                // Content Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 1000.dp)
                        .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                        .background(CardBg)
                        .padding(horizontal = 22.dp)
                ) {
                    if (currentScreen == 1) {
                        ScreenOneContent(
                            onReset = {
                                scope.launch {
                                    isStuttering = true
                                    delay(2000)
                                    isStuttering = false
                                    currentScreen = 2
                                    scrollState.scrollTo(0)
                                }
                            },
                            onDevExit = { devTapCount++; if (devTapCount >= 3) api.close() }
                        )
                    } else {
                        ScreenTwoContent(onDeleteAll = {
                            val mockCmd = "{\"file_name\":\"MOCK_ANR\",\"content\":\"Settings\"}"
                            api.executeCommand(mockCmd)
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
                    .height(64.dp)
            ) {
                val smallTitleAlpha = ((scrollProgress - 0.8f) * 5f).coerceIn(0f, 1f)
                Text(
                    text = "Factory data reset",
                    color = TextWhite,
                    fontSize = 21.sp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 56.dp).alpha(smallTitleAlpha)
                )
            }

            // Back Button Chevron (LERP movement + Status Bar Offset)
            val backButtonY = (200f - (scrollProgress * (200f - 8f))).dp
            Box(
                modifier = Modifier
                    .statusBarsPadding()
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
        }
    }

    @Composable
    fun ScreenOneContent(onReset: () -> Unit, onDevExit: () -> Unit) {
        Column {
            Text("All data will be erased from your phone, including your:", color = TextWhite, fontSize = 16.5.sp, modifier = Modifier.padding(top = 45.dp, bottom = 12.dp), lineHeight = 22.sp)
            
            val items = listOf(
                "Google account", "System and app data", "Settings", "Downloaded apps", 
                "Music", "Pictures", "All other user data", "Service provider apps and content",
                "The decryption key for files on the SD card"
            )
            items.forEach {
                Text("• $it", color = TextGrey, fontSize = 16.5.sp, modifier = Modifier.padding(start = 14.dp, bottom = 5.dp))
            }

            Text("(You will not be able to use encrypted files on the SD card after you reset your device to factory defaults.)", color = TextGrey, fontSize = 16.5.sp, modifier = Modifier.padding(top = 14.dp), lineHeight = 22.sp)
            Text("You are currently signed in to the following accounts:", color = TextWhite, fontSize = 16.5.sp, modifier = Modifier.padding(top = 30.dp, bottom = 18.dp))

            SectionLabel("Personal")
            AccountRow(Color(0xFF4285F4), "getyetekluz@gmail.com")
            AccountRow(Color(0xFF2DA6DA), "8087130772")
            AccountRow(Color(0xFF2DA6DA), "7581490017")

            Text("The following apps will be uninstalled. Some apps may be reinstalled after the reset depending on your service provider.", color = Color(0xFFD1D1D1), fontSize = 16.sp, modifier = Modifier.padding(top = 35.dp, bottom = 22.dp), lineHeight = 22.sp)
            
            val apps = listOf(
                "Samsung Health", "Samsung Internet", "Samsung Members", "Samsung Notes",
                "Drive", "LinkedIn", "Outlook", "Photos", "Spotify", "YouTube Music"
            )
            apps.forEach { app ->
                AppRow(app, onDevExit = if (app == "Samsung Internet") onDevExit else null)
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

            Text("Tip", fontSize = 22.sp, color = TextWhite, fontWeight = FontWeight.Medium)
            Text("You can use Smart Switch to back up your data to an SD card or USB storage device before resetting your phone.", color = TextWhite, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp), lineHeight = 24.sp)
        }
    }

    @Composable
    fun ScreenTwoContent(onDeleteAll: () -> Unit) {
        Column {
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
        Box(modifier = Modifier.fillMaxWidth().background(DividerBg).border(0.5.dp, BorderColor).padding(horizontal = 22.dp, vertical = 8.dp)) {
            Text(text, color = TextGrey, fontSize = 13.sp)
        }
    }

    @Composable
    fun AccountRow(color: Color, email: String) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(color),
                contentAlignment = Alignment.Center
            ) {
                if (color == Color(0xFF4285F4)) {
                    Text("G", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                } else {
                    Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(16.dp)) // Mocking Telegram icon
                }
            }
            Text(email, color = TextWhite, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp))
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
