package com.sightline.app.telemetry

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends lightweight telemetry to the Command Center over local WiFi.
 *
 * Design rule: this must NEVER block, crash, or retry-storm.
 * Every send is wrapped in a try/catch. If the server is unreachable,
 * the app keeps working exactly as before.
 */
class TelemetryClient(
    private val serverIp: String = "192.168.1.100",
    private val serverPort: Int = 3001,
) {
    private var webSocket: WebSocket? = null
    private var isConnected = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * Connect to the Command Center server.
     * Non-blocking. Fails silently.
     */
    fun connect() {
        try {
            val request = Request.Builder()
                .url("ws://$serverIp:$serverPort/telemetry")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected = true
                    Log.i(TAG, "Connected to Command Center")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    isConnected = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    Log.w(TAG, "Command Center connection failed: ${t.message}")
                    // No retry — app continues without dashboard
                }
            })
        } catch (e: Exception) {
            isConnected = false
            Log.w(TAG, "Failed to initiate WebSocket connection: ${e.message}")
        }
    }

    /**
     * Send a telemetry payload. Non-blocking, fire-and-forget.
     */
    fun send(payload: JSONObject) {
        if (!isConnected) return
        try {
            webSocket?.send(payload.toString())
        } catch (e: Exception) {
            // Silent fail — don't let telemetry break the main app
        }
    }

    /**
     * Build a telemetry JSON from the current world model state.
     */
    fun buildPayload(
        goal: String,
        targetLabel: String,
        confidence: Double,
        pathStatus: String,
        objects: List<JSONObject>,
        lastResponse: String,
    ): JSONObject {
        return JSONObject().apply {
            put("goal", goal)
            put("target", targetLabel)
            put("confidence", confidence)
            put("pathStatus", pathStatus)
            put("objects", org.json.JSONArray(objects))
            put("lastResponse", lastResponse)
            put("timestamp", System.currentTimeMillis())
        }
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "App closing")
        } catch (e: Exception) { /* silent */ }
        isConnected = false
    }

    companion object {
        private const val TAG = "SightlineTelemetry"
    }
}
