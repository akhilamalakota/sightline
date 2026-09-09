package com.sightline.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sightline.app.ui.SightlineApp
import com.sightline.app.ui.SightlineViewModel
import com.sightline.app.wake.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SightlineMain"

class MainActivity : ComponentActivity() {

    // Shared with the compose tree: onStart/onStop set app visibility.
    private val vm: SightlineViewModel by viewModels()

    private val requiredPermissions: Array<String> by lazy {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.i(TAG, "Permissions result: $results")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "==================== onCreate START ====================")

        // Request missing permissions
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.i(TAG, "Requesting: $missing")
            permissionLauncher.launch(requiredPermissions)
        } else {
            Log.i(TAG, "All permissions already granted")
        }

        promptOverlayPermissionOnce()

        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    var ready by remember { mutableStateOf(false) }

                    // ALL init happens in onCreate via lifecycleScope — no Compose timing issues
                    LaunchedEffect(Unit) {
                        Log.i(TAG, "LaunchedEffect STARTED")
                        val app = this@MainActivity

                        // Wait for permissions (max 10s)
                        var waitCount = 0
                        while (requiredPermissions.any {
                                ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED
                            } && waitCount < 30) {
                            delay(300)
                            waitCount++
                        }

                        val permsOk = requiredPermissions.all {
                            ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
                        }
                        Log.i(TAG, "Permissions check: $permsOk")
                        Toast.makeText(app, if (permsOk) "✅ Permissions OK" else "❌ Permissions missing", Toast.LENGTH_SHORT).show()

                        // Init voice
                        Log.i(TAG, "Init voice...")
                        vm.initVoice()
                        Toast.makeText(app, "✅ Voice initialized", Toast.LENGTH_SHORT).show()

                        // Init detector
                        Log.i(TAG, "Init detector...")
                        val detOk = vm.initDetectorSync()
                        Log.i(TAG, "Detector result: $detOk")
                        Toast.makeText(app, if (detOk) "✅ Detector loaded" else "⚠️ Detector failed", Toast.LENGTH_SHORT).show()

                        // Connect telemetry
                        vm.connectTelemetry()

                        // Wake service is started by onStart/onStop capture toggles —
                        // never start capture here while the app is on screen.

                        ready = true
                        Log.i(TAG, "==================== ALL INIT DONE ====================")
                        Toast.makeText(app, "🚀 Hands-free mode on", Toast.LENGTH_LONG).show()
                        // Greeting + auto-listen are handled by the ViewModel's initVoice.
                    }

                    if (ready) {
                        SightlineApp(vm)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color(0xFF2196F3))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Starting Sightline…", color = Color.White, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // App is visible: the in-app STT owns the mic, pause the wake word listener.
        WakeWordService.stopCapture(this)
        vm.setAppVisible(true)
    }

    override fun onStop() {
        super.onStop()
        // App left the screen: wake word listener takes over the mic.
        vm.setAppVisible(false)
        WakeWordService.startCapture(this)
    }

    /** One-time request for SYSTEM_ALERT_WINDOW so the wake word can bring the app to the front from background/post-lock. */
    private fun promptOverlayPermissionOnce() {
        if (Build.VERSION.SDK_INT < 29) return
        if (Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("sightline_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("overlay_prompt_shown", false)) return
        prefs.edit().putBoolean("overlay_prompt_shown", true).apply()
        Toast.makeText(
            this,
            "Allow 'Display over other apps' for Sightline to wake up by voice.",
            Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Overlay settings screen unavailable", e)
        }
    }
}
