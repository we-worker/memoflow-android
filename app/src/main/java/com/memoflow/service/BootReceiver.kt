package com.memoflow.service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
class BootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==Intent.ACTION_BOOT_COMPLETED){val prefs=context.getSharedPreferences("recording",0);if(prefs.getBoolean("auto_start",true)){ContextCompat.startForegroundService(context,Intent(context,RecordingForegroundService::class.java).setAction(RecordingForegroundService.ACTION_START))}}}}
