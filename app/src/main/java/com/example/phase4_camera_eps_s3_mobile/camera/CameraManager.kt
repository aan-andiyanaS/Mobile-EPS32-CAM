package com.example.phase4_camera_eps_s3_mobile.camera

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * CameraManager — konsumer WebSocket binary stream dari ESP32-S3 via OkHttp
 *
 * Frame protocol (binary):
 *   [0]     : frame type  (0x01 = JPEG, 0x03 = heartbeat)
 *   [1..8]  : timestamp_us little-endian (uint64_t)
 *   [9..]   : JPEG payload
 *
 * Endpoint:
 *   ws://[ipAddress]/ws
 *
 * Thread model:
 *   - WebSocket callback : OkHttp internal thread
 *   - Flow emission      : callbackFlow (dispatches to IO)
 *   - UI update          : collect di lifecycleScope (Main)
 */
class CameraManager {

    companion object {
        private const val TAG            = "CameraManager"
        private const val FRAME_TYPE_JPEG  = 0x01.toByte()
        private const val FRAME_HEADER_SZ  = 9    // 1B type + 8B timestamp_us
        private const val CONNECT_TIMEOUT  = 5L   // seconds
        private const val READ_TIMEOUT     = 10L  // seconds
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * Menghasilkan Flow<ByteArray> berisi frame JPEG satu per satu.
     * Flow aktif sampai dibatalkan dari luar (lifecycleScope cancel).
     *
     * @param ipAddress IP ESP32 yang didapat dari BLE provisioning (contoh: "192.168.1.100")
     */
    fun streamFrames(ipAddress: String): Flow<ByteArray> = callbackFlow {
        val url = "ws://$ipAddress/ws"
        Log.d(TAG, "Connecting WebSocket: $url")

        val request = Request.Builder().url(url).build()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()

                // Minimal: 1B type + 8B timestamp + minimal 1B JPEG
                if (raw.size < FRAME_HEADER_SZ + 1) return

                val frameType = raw[0]

                // Hanya proses frame JPEG (0x01), ignore heartbeat (0x03)
                if (frameType != FRAME_TYPE_JPEG) return

                // Ekstrak JPEG — skip 9 byte header
                val jpeg = raw.copyOfRange(FRAME_HEADER_SZ, raw.size)

                val result = trySend(jpeg)
                if (result.isFailure) {
                    Log.w(TAG, "Frame dropped — collector too slow")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // ESP32 tidak mengirim text frame, tapi log jika ada
                Log.d(TAG, "WS text (unexpected): $text")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                close()  // tutup Flow
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                close(t) // tutup Flow dengan error
            }
        }

        val webSocket = client.newWebSocket(request, wsListener)

        // Saat Flow di-cancel (lifecycleScope cancel), tutup WebSocket
        awaitClose {
            Log.d(TAG, "Flow cancelled — closing WebSocket")
            webSocket.close(1000, "Flow cancelled")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cek apakah ESP32 bisa dijangkau sebelum membuka CameraStreamActivity.
     * Menggunakan TCP socket connect ke port 80, kompatibel dengan WebSocket server.
     *
     * @param ipAddress IP ESP32
     * @return true jika port 80 bisa dijangkau
     */
    suspend fun isStreamReachable(ipAddress: String): Boolean {
        return try {
            withTimeout(4_000) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val socket = java.net.Socket()
                    try {
                        socket.connect(
                            java.net.InetSocketAddress(ipAddress, 80),
                            3_000
                        )
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
            Log.w(TAG, "Reachability check timeout: ${e.message}")
            false
        }
    }
}
