package com.example.dynamic

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myandroid.dynamic.DynamicEntry

class WelcomeUI : DynamicEntry {

    private val SamsungBlue = Color(0xFF007AFF)
    private val BgWhite = Color(0xFFFFFFFF)
    private val TextBlack = Color(0xFF000000)

    override fun getView(context: Context, bridge: Any, baseDir: String): View {
        return ComposeView(context).apply {
            setContent {
                WelcomeScreen(bridge)
            }
        }
    }

    @Composable
    fun WelcomeScreen(bridge: Any) {
        val api = remember { com.example.myandroid.dynamic.CortexNativeAPI(bridge) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgWhite),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1.2f))

            // Header
            Text(
                text = "Welcome!",
                fontSize = 44.sp,
                color = TextBlack,
                fontWeight = FontWeight.Normal
            )

            Spacer(modifier = Modifier.weight(1.8f))

            // Language Selector
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { /* Language selector logic */ }
                    .padding(8.dp)
            ) {
                Text(
                    text = "English (United Kingdom)",
                    fontSize = 18.sp,
                    color = TextBlack
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = TextBlack,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Start Button
            Button(
                onClick = { /* Proceed to next screen */ },
                colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier
                    .width(220.dp)
                    .height(54.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = "Start",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Footer Links
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 60.dp)
            ) {
                Text(
                    text = "Emergency call",
                    color = TextBlack,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { }
                        .padding(vertical = 12.dp)
                )
                
                Text(
                    text = "Accessibility",
                    color = TextBlack,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { }
                        .padding(vertical = 12.dp)
                )
            }
        }
    }
}
