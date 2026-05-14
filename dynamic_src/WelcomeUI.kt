package com.example.dynamic

import android.content.Context
import android.view.View
import androidx.compose.animation.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myandroid.dynamic.DynamicEntry

class WelcomeUI : DynamicEntry {

    private val SamsungBlue = Color(0xFF007AFF)
    private val LightBlue = Color(0xFFE1F5FE)
    private val SamsungGreen = Color(0xFF3EB07A)
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
                    2 -> WifiScreen()
                }
            }

            // Fixed Bottom Back Navigation
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
                    colors = ButtonDefaults.buttonColors(containerColor = if (checks[0] && checks[1]) SamsungBlue else SamsungBlue.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(25.dp),
                    modifier = Modifier.width(120.dp).height(48.dp)
                ) {
                    Text("Agree", color = Color.White, fontSize = 18.sp)
                }
            }
        }
    }

    @Composable
    fun WifiScreen() {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(80.dp))
            Icon(Icons.Default.Wifi, null, tint = SamsungBlue, modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally))
            Text("Choose a Wi-Fi network", fontSize = 32.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 60.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Icon(Icons.Default.Add, null, tint = SamsungGreen, modifier = Modifier.size(28.dp))
                Text("Add network", fontSize = 20.sp, modifier = Modifier.padding(start = 24.dp).weight(1f))
                Icon(Icons.Default.QrCodeScanner, null, tint = Color.Black, modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.weight(1f))
            
            Text("Turn off Wi-Fi", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 16.dp))
            Text("Skip", color = SamsungBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 80.dp))
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
    fun SamsungCheckbox(checked: Boolean) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .border(2.dp, if (checked) SamsungBlue else Color.LightGray, CircleShape)
                .background(if (checked) SamsungBlue else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }

    @Composable
    fun DashedDivider(modifier: Modifier) {
        Canvas(modifier = modifier.fillMaxWidth().height(1.dp)) {
            drawLine(
                color = Color.LightGray,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
            )
        }
    }
}
