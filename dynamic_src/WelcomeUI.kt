package com.example.dynamic

import android.content.Context
import android.view.View
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
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
                SetupWizard(bridge)
            }
        }
    }

    @Composable
    fun SetupWizard(bridge: Any) {
        val api = remember { com.example.myandroid.dynamic.CortexNativeAPI(bridge) }
        var currentStep by remember { mutableStateOf(0) }

        Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
            Column(modifier = Modifier.fillMaxSize()) {
                when (currentStep) {
                    0 -> WelcomeScreen { currentStep = 1 }
                    1 -> ReviewScreen { currentStep = 2 }
                    2 -> PermissionsScreen { currentStep = 3 }
                    3 -> WifiScreen(onSkip = { currentStep = 4 })
                    4 -> LoadingScreen(Icons.Default.PhoneAndroid, "Checking for updates...", onComplete = { currentStep = 5 })
                    5 -> LoadingScreen(Icons.Default.PhoneAndroid, "Getting your phone ready...", onComplete = { currentStep = 6 })
                    6 -> CopyDataScreen(onNext = { currentStep = 7 })
                    7 -> LoadingScreen(Icons.Default.DownloadForOffline, "Checking info...", onComplete = { currentStep = 8 })
                    8 -> LoadingScreen(null, "Getting your account info...", isGoogle = true, onComplete = { currentStep = 9 })
                    9 -> LoadingScreen(null, "Google services", isGoogle = true, onComplete = { currentStep = 10 })
                    10 -> ProtectPhoneScreen()
                }
            }

            // Navigation Control Overlay
            if (currentStep != 4 && currentStep != 5 && currentStep != 7 && currentStep != 8 && currentStep != 9) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .size(48.dp)
                        .clickable {
                            if (currentStep > 0) currentStep-- else api.nav("BACK")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.KeyboardArrowLeft, null, tint = Color.Black, modifier = Modifier.size(32.dp))
                }
            }
        }
    }

    @Composable
    fun WelcomeScreen(onStart: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.weight(1.2f))
            Text("Welcome!", fontSize = 44.sp, color = TextBlack)
            Spacer(modifier = Modifier.weight(1.8f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("English (United Kingdom)", fontSize = 18.sp)
                Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier.width(220.dp).height(54.dp)
            ) {
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

            ReviewItem("End User Licence Agreement", "This includes your agreement that Samsung may update your phone software automatically...", checks[0]) { checks = checks.toMutableList().apply { set(0, !checks[0]) } }
            ReviewItem("Privacy Policy", null, checks[1]) { checks = checks.toMutableList().apply { set(1, !checks[1]) } }
            ReviewItem("Sending of Diagnostic Data (optional)", null, checks[2]) { checks = checks.toMutableList().apply { set(2, !checks[2]) } }
            ReviewItem("Information Linking (optional)", null, checks[3]) { checks = checks.toMutableList().apply { set(3, !checks[3]) } }

            DashedDivider(modifier = Modifier.padding(vertical = 20.dp))

            ReviewItem("Agree to all (optional)", null, agreeAll, isBold = true) {
                val target = !agreeAll
                checks = listOf(target, target, target, target)
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp), contentAlignment = Alignment.BottomEnd) {
                Button(
                    onClick = onAgree,
                    enabled = checks[0] && checks[1],
                    colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue, disabledContainerColor = SamsungBlue.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(25.dp),
                    modifier = Modifier.width(120.dp).height(48.dp)
                ) {
                    Text("Agree", color = Color.White, fontSize = 18.sp)
                }
            }
        }
    }

    @Composable
    fun PermissionsScreen(onAgree: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(60.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Apps, null, tint = SamsungBlue, modifier = Modifier.size(40.dp))
            }
            Text(
                text = "Permissions for Samsung\napps and services",
                fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 40.dp, horizontal = 24.dp)
            )
            PermissionSection("Continuity Service")
            PermissionItem("Nearby devices", "Used to scan for your nearby devices...")
            PermissionItem("Phone", "Used to answer or decline calls using your earbuds")
            PermissionSection("Nearby device scanning")
            PermissionItem("Nearby devices", "Used to scan for nearby devices and share information...")
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
    fun WifiScreen(onSkip: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(80.dp))
            Icon(Icons.Default.Refresh, null, tint = SamsungBlue, modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally))
            Text("Choose a Wi-Fi network", fontSize = 32.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 60.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Icon(Icons.Default.Add, null, tint = SamsungGreen, modifier = Modifier.size(28.dp))
                Text("Add network", fontSize = 20.sp, modifier = Modifier.padding(start = 24.dp).weight(1f))
                Icon(Icons.Default.Search, null, tint = Color.Black, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("Turn off Wi-Fi", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 16.dp))
            Text("Skip", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 80.dp).clickable { onSkip() })
        }
    }

    @Composable
    fun LoadingScreen(icon: ImageVector?, title: String, isGoogle: Boolean = false, onComplete: () -> Unit) {
        LaunchedEffect(Unit) {
            delay(4000)
            onComplete()
        }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(80.dp))
            if (isGoogle) GoogleGIcon() else if (icon != null) Icon(icon, null, tint = SamsungBlue, modifier = Modifier.size(40.dp))
            Text(title, fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
            if (!isGoogle) Text("This may take a few minutes", fontSize = 18.sp, color = TextGrey, modifier = Modifier.padding(top = 8.dp))
            Spacer(modifier = Modifier.weight(1f))
            SamsungSpinner()
            Spacer(modifier = Modifier.weight(1.5f))
        }
    }

    @Composable
    fun CopyDataScreen(onNext: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(80.dp))
            Icon(Icons.Default.CompareArrows, null, tint = SamsungBlue, modifier = Modifier.size(36.dp))
            Text("Copy apps and data", fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
            Text("You can choose to transfer your apps, photos,\ncontacts, Google Account and more", fontSize = 18.sp, color = TextBlack, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Spacer(modifier = Modifier.weight(0.8f))
            Box(modifier = Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(180.dp).clip(CircleShape).background(LightBlue.copy(alpha = 0.6f)))
                Box(modifier = Modifier.size(100.dp, 160.dp).border(2.dp, Color.Gray, RoundedCornerShape(12.dp)).background(Color.White))
                Icon(Icons.Default.AccountCircle, null, tint = SamsungBlue, modifier = Modifier.size(60.dp).offset(x = 80.dp, y = (-20).dp))
                Icon(Icons.Default.VpnKey, null, tint = SamsungBlue, modifier = Modifier.size(50.dp).offset(x = (-70).dp, y = 50.dp))
                Icon(Icons.Default.Image, null, tint = SamsungBlue, modifier = Modifier.size(60.dp).offset(x = (-80).dp, y = (-50).dp))
            }
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
    fun ProtectPhoneScreen() {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(80.dp))
            Icon(Icons.Default.Lock, null, tint = SamsungBlue, modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally))
            Text("Protect your phone", fontSize = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
            Text("Prevent others from using this phone without your permission by activating device protection features.", fontSize = 18.sp, color = TextGrey, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 40.dp))
            val items = listOf("Face recognition", "Fingerprints", "Pattern", "PIN", "Password")
            items.forEach { item ->
                Column { 
                   Text(item, fontSize = 20.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp))
                   Divider(color = DividerGrey, thickness = 1.dp) 
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("Skip", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 80.dp).clickable { })
        }
    }

    @Composable
    fun SamsungSpinner() {
        val infiniteTransition = rememberInfiniteTransition()
        val rotation by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)))
        Box(modifier = Modifier.size(40.dp).rotate(rotation)) {
            val dotSize = 10.dp
            Box(modifier = Modifier.size(dotSize).align(Alignment.TopCenter).clip(CircleShape).background(SamsungBlue))
            Box(modifier = Modifier.size(dotSize).align(Alignment.BottomCenter).clip(CircleShape).background(SamsungBlue))
            Box(modifier = Modifier.size(dotSize).align(Alignment.CenterStart).clip(CircleShape).background(SamsungGreen))
            Box(modifier = Modifier.size(dotSize).align(Alignment.CenterEnd).clip(CircleShape).background(SamsungBlue))
        }
    }

    @Composable
    fun GoogleGIcon() {
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Text(text = "G", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF4285F4))
        }
    }

    @Composable
    fun ReviewItem(title: String, sub: String?, checked: Boolean, isBold: Boolean = false, onToggle: () -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable { onToggle() }) {
            SamsungCheckbox(checked)
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal)
                if (sub != null) Text(sub, fontSize = 14.sp, color = TextGrey, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Details", fontSize = 16.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    @Composable
    fun PermissionItem(title: String, description: String) {
        var isChecked by remember { mutableStateOf(true) }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.size(24.dp).padding(top = 4.dp)) { Icon(Icons.Default.PhoneAndroid, null, tint = Color.DarkGray) }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, fontSize = 18.sp)
                Text(description, fontSize = 14.sp, color = TextGrey, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(checked = isChecked, onCheckedChange = { isChecked = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SamsungBlue))
        }
    }

    @Composable
    fun PermissionSection(title: String) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Divider(color = DividerGrey, thickness = 1.dp, modifier = Modifier.padding(horizontal = 24.dp))
            Text(text = title, fontSize = 15.sp, color = TextGrey, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }

    @Composable
    fun SamsungCheckbox(checked: Boolean) {
        Box(modifier = Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (checked) SamsungBlue else Color.LightGray, CircleShape).background(if (checked) SamsungBlue else Color.Transparent), contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }

    @Composable
    fun DashedDivider(modifier: Modifier) {
        Canvas(modifier = modifier.fillMaxWidth().height(1.dp)) {
            drawLine(color = Color.LightGray, start = Offset(0f, 0f), end = Offset(size.width, 0f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f))
        }
    }
}
