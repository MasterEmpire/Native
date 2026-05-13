package com.example.myandroid.dynamic

import android.content.Context
import android.view.View

/**
 * The Contract Interface.
 * Every Speedster payload MUST implement this interface.
 * It allows the main app to safely invoke the dynamic UI.
 */
interface DynamicEntry {
    fun getView(context: Context, bridge: Any, baseDir: String): View
}
