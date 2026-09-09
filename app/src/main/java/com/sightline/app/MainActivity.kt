package com.sightline.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sightline.app.ui.SightlineApp
import com.sightline.app.ui.SightlineViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SightlineMain"

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

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

        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    val vm: SightlineViewModel = viewModel()
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

                        ready = true
                        Log.i(TAG, "==================== ALL INIT DONE ====================")
                        Toast.makeText(app, "🚀 Ready! Tap mic button to speak", Toast.LENGTH_LONG).show()
                        // Speak instructions so user knows what to do
                        kotlinx.coroutines.delay(500)
                        vm.voice.speak("Sightline is ready. Tap the microphone button, then say what you need. For example, say find a chair, or, what is around me.")
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
}
