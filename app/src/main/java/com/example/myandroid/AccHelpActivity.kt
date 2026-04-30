package com.example.myandroid

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class AccHelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                HelpContent(onBack = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    finish()
                })
            }
        }
    }
}

@Composable
fun HelpContent(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Setup Guide", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Follow these steps to restore service", color = Color(0xFF94A3B8), modifier = Modifier.padding(bottom = 32.dp))

        val ctx = androidx.compose.ui.platform.LocalContext.current
        val assetManager = ctx.assets
        var loadedCount = 0

        for (i in 1..4) {
            // FIX: Removed the erroneous backslash escape so interpolation works properly
            val fileName = "acc_guide/step_$i.jpg"
            
            val bitmap = try {
                val inputStream = assetManager.open(fileName)
                val bmp = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                bmp
            } catch (e: Exception) {
                DebugLogger.log("ACC_HELP", "Failed to load asset $fileName: ${e.message}")
                null
            }

            if (bitmap != null) {
                loadedCount++
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Step $i",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }

        // FALLBACK UI: If no images are loaded, show text instructions
        if (loadedCount == 0) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("1. Tap 'BACK TO SETTINGS' below.", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                    Text("2. Look for 'Installed apps' or 'Downloaded apps'.", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                    Text("3. Find the required service in the list.", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                    Text("4. Toggle the switch to 'ON'.", color = Color.White, fontSize = 16.sp)
                }
            }
        }

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("BACK TO SETTINGS", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(40.dp))
    }
}