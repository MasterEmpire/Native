package com.example.dynamic

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class HotUI {
    @Composable
    fun Render() {
        Text(
            text = "Cortex Speedster: Hot Reload Active \uD83D\uDE80",
            color = Color.Green,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
