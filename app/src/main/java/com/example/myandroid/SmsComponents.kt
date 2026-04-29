package com.example.myandroid

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Bundle

class SmsDeliverReceiver : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {} }
class MmsReceiver : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {} }
class ComposeSmsActivity : Activity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); finish() } }
class HeadlessSmsSendService : Service() { override fun onBind(intent: Intent?): IBinder? = null }
