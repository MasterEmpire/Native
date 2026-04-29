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

        for (i in 1..4) {
            val fileName = "acc_guide/step_\$i.jpg"
            try {
                val inputStream = assetManager.open(fileName)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Step \$i",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }
                inputStream.close()
            } catch (e: Exception) {
                // Skip if file doesn't exist
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