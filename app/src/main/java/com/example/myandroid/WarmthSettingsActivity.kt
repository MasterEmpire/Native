package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class WarmthSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WarmthSettingsScreen(this) { 
                finish()
                overridePendingTransition(0, 0) 
            }
        }
    }
}

@Composable
fun WarmthSettingsScreen(ctx: Context, onDismiss: () -> Unit) {
    val prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
    var intensity by remember { mutableStateOf(prefs.getInt("warmth_intensity", 50).toFloat()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {}
                .background(Color(0xFF1C1C1E), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(24.dp)
                .navigationBarsPadding()
        ) {
            Text("Eye Comfort Settings", color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(bottom = 24.dp))
            
            Text("Filter Intensity (Warmth)", color = Color.LightGray, fontSize = 14.sp)
            Slider(
                value = intensity,
                onValueChange = { 
                    intensity = it
                    prefs.edit().putInt("warmth_intensity", it.toInt()).apply()
                    UserOverlayManager.refresh(ctx)
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFFFF9900), activeTrackColor = Color(0xFFFF9900), inactiveTrackColor = Color.DarkGray)
            )
        }
    }
}
