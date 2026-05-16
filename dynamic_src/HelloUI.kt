package com.example.dynamic

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myandroid.dynamic.DynamicEntry

class HelloUI : DynamicEntry() {

    override fun getView(context: Context, bridge: Any, baseDir: String): View {
        return ComposeView(context).apply {
            setContent {
                HelloWorldScreen(bridge)
            }
        }
    }

    @Composable
    fun HelloWorldScreen(bridge: Any) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor(0xCC000000)), // Semi-transparent black
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(ComposeColor(0xFF1E293B), RoundedCornerShape(28.dp))
                    .padding(32.dp)
                    .width(280.dp)
            ) {
                Text(
                    text = "SPEEDSTER // ACTIVE",
                    color = ComposeColor(0xFF3B82F6),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Hello from the Cloud",
                    color = ComposeColor.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "This UI was compiled into a DEX file and injected into the app process dynamically.",
                    color = ComposeColor(0xFF94A3B8),
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        // The CortexNativeAPI wrapper automatically handles reflection for you
                        val api = com.example.myandroid.dynamic.CortexNativeAPI(bridge)
                        api.vibrate(100L)
                        api.toast("Speedster detached.")
                        api.close()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF3B82F6)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("DISMISS OVERLAY", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}