package com.example.phase4_camera_eps_s3_mobile.camera

import android.util.Log
import com.example.phase4_camera_eps_s3_mobile.model.ImuData
import com.example.phase4_camera_eps_s3_mobile.model.TofGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * CameraManager — WebSocket binary stream dari ESP32-S3
 *
 * Frame types:
 *   0x01 JPEG  : [1B][8B ts][JPEG bytes]
 *   0x02 IMU   : [1B][8B ts][4x float32: pitch,roll,yaw_rate,a_lin]
 *   0x04 TOF   : [1B][8B ts][64x uint16 mm]
 *   0x03 HBEAT : [1B][1B 0x01]
 *   0x10 ALERT : [1B][string bytes]
 */
class CameraManager {

    companion object {
        private const val TAG = "CameraManager"
        private const val FT_JPEG  = 0x01.toByte()
        private const val FT_IMU   = 0x02.toByte()
        private const val FT_TOF   = 0x04.toByte()
        private const val FT_HBEAT = 0x03.toByte()
        private const val FT_ALERT = 0x10.toByte()
        private const val HEADER   = 9  // 1B type + 8B timestamp_us
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5L, TimeUnit.SECONDS)
        .readTimeout(10L, TimeUnit.SECONDS)
        .build()

    // Flows untuk setiap jenis data sensor
    private val _imuFlow   = MutableSharedFlow<ImuData>(extraBufferCapacity = 10)
    private val _tofFlow   = MutableSharedFlow<TofGrid>(extraBufferCapacity = 5)
    private val _alertFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)

    val imuFlow:   SharedFlow<ImuData> = _imuFlow.asSharedFlow()
    val tofFlow:   SharedFlow<TofGrid> = _tofFlow.asSharedFlow()
    val alertFlow: SharedFlow<String>  = _alertFlow.asSharedFlow()

    /** Flow<ByteArray> berisi JPEG frames. IMU/ToF/Alert dikirim via SharedFlow. */
    fun streamFrames(ipAddress: String): Flow<ByteArray> = callbackFlow {
        val url = "ws://$ipAddress/ws"
        Log.d(TAG, "Connecting: $url")

        val wsListener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                if (raw.isEmpty()) return

                val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val frameType = buf.get()

                when (frameType) {
                    FT_JPEG -> {
                        if (raw.size < HEADER + 1) return
                        val jpeg = raw.copyOfRange(HEADER, raw.size)
                        trySend(jpeg)
                    }
                    FT_IMU -> {
                        if (raw.size < HEADER + 16) return
                        val ts    = buf.getLong()
                        val pitch = buf.getFloat()
                        val roll  = buf.getFloat()
                        val yaw   = buf.getFloat()
                        val alin  = buf.getFloat()
                        _imuFlow.tryEmit(ImuData(ts, pitch, roll, yaw, alin))
                    }
                    FT_TOF -> {
                        if (raw.size < HEADER + 128) return
                        val ts = buf.getLong()
                        val distances = ShortArray(64) { buf.getShort() }
                        _tofFlow.tryEmit(TofGrid(ts, distances))
                    }
                    FT_ALERT -> {
                        val msg = String(raw, 1, raw.size - 1, Charsets.UTF_8)
                        _alertFlow.tryEmit(msg)
                        Log.w(TAG, "ESP32 Alert: $msg")
                    }
                    FT_HBEAT -> Log.v(TAG, "Heartbeat")
                    else -> Log.v(TAG, "Unknown frame type: 0x${frameType.toInt().and(0xFF).toString(16)}")
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "WS text: $text")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code")
                close()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                close(t)
            }
        }

        val webSocket = client.newWebSocket(Request.Builder().url(url).build(), wsListener)
        awaitClose {
            Log.d(TAG, "Flow cancelled — closing WebSocket")
            webSocket.close(1000, "Flow cancelled")
        }
    }.flowOn(Dispatchers.IO)

    /** TCP reachability check sebelum buka stream */
    suspend fun isStreamReachable(ipAddress: String): Boolean {
        return try {
            withTimeout(4_000) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val socket = java.net.Socket()
                    try {
                        socket.connect(java.net.InetSocketAddress(ipAddress, 80), 3_000)
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "TCP connect failed: ${e.message}")
                        false
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reachability timeout: ${e.message}")
            false
        }
    }
}
