/*
 * ESP32-S3 CAM — BLE WiFi Provisioning + WebSocket Camera Stream
 * Phase 4: Unified Firmware
 *
 * Alur:
 *  Boot → Cek flash credentials
 *   ├─ Ada → Auto-connect WiFi → Start WebSocket camera server
 *   └─ Tidak ada → BLE provisioning
 *       └─ Android scan WiFi via BLE → pilih SSID → kirim password
 *           └─ ESP32 connect WiFi → kirim "IP:x.x.x.x" via BLE → BLE off
 *               └─ Start WebSocket camera server
 *
 * Reset WiFi Credentials:
 *   Tahan tombol BOOT (GPIO 0) selama 5 detik.
 *   Indikator LED selama tahan:
 *     0–1.6 s  → Orange  (phase 1/3)
 *     1.6–3.3 s → Kuning  (phase 2/3)
 *     3.3–5 s  → Merah   (phase 3/3)
 *   Setelah 5 s: 6x blink putih cepat → Magenta (sedang reset)
 *   Selesai   → LED biru berkedip (BLE advertising ulang)
 *
 * WebSocket Protocol (binary frame):
 *   [0]     : frame type  (0x01 = JPEG, 0x03 = heartbeat)
 *   [1..8]  : timestamp_us little-endian (uint64_t)
 *   [9..]   : JPEG payload
 *
 * Android endpoint:
 *   ws://[IP]/ws  — WebSocket binary stream
 *
 * Libraries (install via Arduino IDE Library Manager):
 *   - ESPAsyncWebServer by lacamera  (atau me-no-dev)
 *   - AsyncTCP by dvarrel            (atau me-no-dev)
 *   - Adafruit NeoPixel
 *
 * Arduino IDE Settings:
 *   Board        : ESP32S3 Dev Module
 *   PSRAM        : OPI PSRAM
 *   Partition    : Huge APP (3MB No OTA/1MB SPIFFS)
 *   CPU Freq     : 240 MHz
 */

// ======== INCLUDES ========
#include "esp_camera.h"
#include "esp_timer.h"
#include <WiFi.h>
#include <Preferences.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Adafruit_NeoPixel.h>
#include <AsyncTCP.h>
#include <ESPAsyncWebServer.h>

// ======== KAMERA PIN — ESP32-S3 WROOM N16R8 ========
#define PWDN_GPIO_NUM   -1
#define RESET_GPIO_NUM  -1
#define XCLK_GPIO_NUM   15
#define SIOD_GPIO_NUM   4
#define SIOC_GPIO_NUM   5
#define Y9_GPIO_NUM     16
#define Y8_GPIO_NUM     17
#define Y7_GPIO_NUM     18
#define Y6_GPIO_NUM     12
#define Y5_GPIO_NUM     10
#define Y4_GPIO_NUM     8
#define Y3_GPIO_NUM     9
#define Y2_GPIO_NUM     11
#define VSYNC_GPIO_NUM  6
#define HREF_GPIO_NUM   7
#define PCLK_GPIO_NUM   13

// ======== RGB LED — GPIO 48 (WS2812) ========
#define LED_PIN         48
#define NUM_LEDS        1
#define LED_BRIGHTNESS  50
#define BLINK_INTERVAL  500

// ======== RESET BUTTON — GPIO 0 (BOOT) ========
#define RESET_BUTTON_PIN 0
#define RESET_HOLD_TIME  5000   // ms — tahan 5 detik untuk reset
#define RESET_PHASE1_MS  1667   // 0     – 1.6 s → LED Orange
#define RESET_PHASE2_MS  3333   // 1.6 s – 3.3 s → LED Kuning
                                // 3.3 s – 5.0 s → LED Merah

// ======== BLE UUIDs — HARUS sama dengan BleManager.kt ========
#define SERVICE_UUID       "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_COMMAND_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define CHAR_RESPONSE_UUID "cba1d466-344c-4be3-ab3f-189f80dd7518"

// ======== WebSocket FRAME PROTOCOL ========
// Tipe frame — extensible untuk sensor masa depan
#define FRAME_TYPE_JPEG  0x01  // Kamera JPEG
#define FRAME_TYPE_IMU   0x02  // IMU/EKF (MPU6050) — reserved, belum aktif
#define FRAME_TYPE_HBEAT 0x03  // Heartbeat / keepalive
#define FRAME_TYPE_TOF   0x04  // ToF sensor (VL53L5CX) — reserved, belum aktif
#define FRAME_TYPE_CTRL  0x05  // Control / config command
#define FRAME_HEADER_SZ  9     // 1B type + 8B timestamp_us (little-endian)

// ======== TUNING ========
static constexpr uint8_t  JPEG_QUALITY      = 15;      // 0=best, 63=worst
static constexpr uint32_t TARGET_FRAME_US   = 66666;   // ~15 FPS
static constexpr uint32_t WS_PING_INTERVAL  = 10000;   // ms — heartbeat setiap 10 detik
static constexpr size_t   WS_BUF_MAX        = 130*1024;
static constexpr uint32_t HEAP_GUARD_BYTES  = 30000;

// Mode hemat daya: jika tidak ada client selama X ms, skip capture frame
// Kamera tetap init (reinit mahal), hanya frame tidak dikirim
static constexpr uint32_t POWER_SAVE_TIMEOUT = 30000;  // 30 detik tanpa client → hemat daya

// ======== GLOBAL STATE ========
Adafruit_NeoPixel rgbLed(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);
Preferences        preferences;

// WebSocket server
AsyncWebServer  server(80);
AsyncWebSocket  ws("/ws");
volatile bool   wsClientConnected = false;

// BLE
BLEServer*         pServer       = nullptr;
BLECharacteristic* pCommandChar  = nullptr;
BLECharacteristic* pResponseChar = nullptr;
bool bleActive         = false;
bool deviceConnected   = false;
bool oldDeviceConnected = false;

// WiFi
bool   wifiConnected = false;
String deviceIP      = "";
unsigned long wifiDisconnectTime = 0;
bool          isWifiDisconnected   = false;
bool          isCameraActive       = true;
String        currentSSID          = "";
String        currentPassword      = "";

// BLE command flags
bool   shouldScanWifi    = false;
bool   shouldConnectWifi = false;
String pendingSSID       = "";
String pendingPassword   = "";

// Misc
unsigned long previousMillis       = 0;
bool          ledState             = false;
unsigned long resetButtonPressTime = 0;
bool          resetButtonPressed   = false;
bool          resetTriggered       = false;
uint8_t       resetLedPhase        = 0;

// Buffer WebSocket pre-allocated di PSRAM — hindari malloc/free per frame
static uint8_t* g_wsBuf     = nullptr;
static size_t   g_wsBufSize = 0;

// Mode hemat daya
static bool     powerSaveMode       = false;    // true = tidak ada client, skip capture
static uint32_t lastClientLostTime  = 0;        // kapan client terakhir disconnect
static bool     hadClientBefore     = false;    // pernah ada client (untuk trigger power save)

// ======== LED HELPERS ========
void setLedColor(uint8_t r, uint8_t g, uint8_t b) {
    rgbLed.setPixelColor(0, rgbLed.Color(r, g, b));
    rgbLed.show();
}
void ledOff()    { setLedColor(0,   0,   0);   }
void ledRed()    { setLedColor(255, 0,   0);   }
void ledGreen()  { setLedColor(0,   255, 0);   }
void ledBlue()   { setLedColor(0,   0,   255); }
void ledYellow()  { setLedColor(255, 255, 0);   }
void ledOrange()  { setLedColor(255, 80,  0);   }
void ledMagenta() { setLedColor(255, 0,   180); }
void ledWhite()   { setLedColor(255, 255, 255); }

// ======== PREFERENCES ========
void saveWiFiCredentials(const String& ssid, const String& pass) {
    preferences.begin("wifi", false);
    preferences.putString("ssid",      ssid);
    preferences.putString("password",  pass);
    preferences.putBool("configured",  true);
    preferences.end();
    Serial.println("[STORAGE] Credentials saved.");
}

bool loadWiFiCredentials(String& ssid, String& pass) {
    preferences.begin("wifi", true);
    bool ok = preferences.getBool("configured", false);
    if (ok) {
        ssid = preferences.getString("ssid",     "");
        pass = preferences.getString("password", "");
    }
    preferences.end();
    return ok && ssid.length() > 0;
}

void clearWiFiCredentials() {
    preferences.begin("wifi", false);
    preferences.clear();
    preferences.end();
    Serial.println("[STORAGE] Credentials cleared.");
}

// ======== CAMERA INIT ========
bool initCamera() {
    camera_config_t cfg = {};
    cfg.ledc_channel = LEDC_CHANNEL_0;
    cfg.ledc_timer   = LEDC_TIMER_0;
    cfg.pin_d0  = Y2_GPIO_NUM; cfg.pin_d1 = Y3_GPIO_NUM;
    cfg.pin_d2  = Y4_GPIO_NUM; cfg.pin_d3 = Y5_GPIO_NUM;
    cfg.pin_d4  = Y6_GPIO_NUM; cfg.pin_d5 = Y7_GPIO_NUM;
    cfg.pin_d6  = Y8_GPIO_NUM; cfg.pin_d7 = Y9_GPIO_NUM;
    cfg.pin_xclk     = XCLK_GPIO_NUM;
    cfg.pin_pclk     = PCLK_GPIO_NUM;
    cfg.pin_vsync    = VSYNC_GPIO_NUM;
    cfg.pin_href     = HREF_GPIO_NUM;
    cfg.pin_sccb_sda = SIOD_GPIO_NUM;
    cfg.pin_sccb_scl = SIOC_GPIO_NUM;
    cfg.pin_pwdn     = PWDN_GPIO_NUM;
    cfg.pin_reset    = RESET_GPIO_NUM;
    cfg.xclk_freq_hz = 24000000;        // 24MHz: max stable clock, readout lebih cepat
    cfg.frame_size   = FRAMESIZE_HVGA;  // 480x320: balance kualitas vs latensi
                                        // Ganti ke FRAMESIZE_QVGA (320x240) jika masih lag
    cfg.pixel_format = PIXFORMAT_JPEG;
    cfg.jpeg_quality = JPEG_QUALITY;

    if (psramFound()) {
        cfg.fb_location = CAMERA_FB_IN_PSRAM;
        cfg.fb_count    = 3;            // 3 buffer: pipeline lebih smooth, latency berkurang
        cfg.grab_mode   = CAMERA_GRAB_LATEST;
    } else {
        cfg.fb_location = CAMERA_FB_IN_DRAM;
        cfg.fb_count    = 1;
        cfg.frame_size  = FRAMESIZE_QVGA;
        cfg.grab_mode   = CAMERA_GRAB_WHEN_EMPTY;
    }

    if (esp_camera_init(&cfg) != ESP_OK) {
        Serial.println("[CAM] Init FAILED!");
        return false;
    }

    sensor_t* s = esp_camera_sensor_get();
    if (s) {
        s->set_whitebal(s, 1);
        s->set_awb_gain(s, 1);
        s->set_exposure_ctrl(s, 1);
        s->set_aec2(s, 1);
        s->set_gain_ctrl(s, 1);
        s->set_bpc(s, 1);
        s->set_wpc(s, 1);
        s->set_raw_gma(s, 1);
        s->set_lenc(s, 1);
        s->set_gainceiling(s, (gainceiling_t)6);
    }
    Serial.println("[CAM] Init OK.");
    return true;
}

// ======== WEBSOCKET EVENT ========
void onWsEvent(AsyncWebSocket* server, AsyncWebSocketClient* client,
               AwsEventType type, void* arg, uint8_t* data, size_t len) {
    switch (type) {
        case WS_EVT_CONNECT:
            Serial.printf("[WS] Client #%u connected from %s\n",
                          client->id(), client->remoteIP().toString().c_str());
            client->client()->setNoDelay(true);
            wsClientConnected = true;
            hadClientBefore   = true;
            // Keluar dari power save mode saat client baru connect
            if (powerSaveMode) {
                powerSaveMode = false;
                Serial.println("[PWR] Client terhubung — keluar dari mode hemat daya");
                ledGreen();
            }
            break;
        case WS_EVT_DISCONNECT:
            Serial.printf("[WS] Client #%u disconnected\n", client->id());
            // Cek apakah masih ada client lain
            wsClientConnected = (ws.count() > 1);
            if (!wsClientConnected && hadClientBefore) {
                // Semua client disconnect — catat waktu untuk timer power save
                lastClientLostTime = millis();
                Serial.printf("[PWR] Semua client disconnect — power save dalam %d detik\n",
                              POWER_SAVE_TIMEOUT / 1000);
            }
            break;
        case WS_EVT_ERROR:
            Serial.printf("[WS] Error client #%u\n", client->id());
            break;
        case WS_EVT_DATA: {
            AwsFrameInfo* info = (AwsFrameInfo*)arg;
            if (info->opcode == WS_BINARY && len >= 2 && data[0] == 0xA1) {
                sensor_t* s = esp_camera_sensor_get();
                if (s) s->set_quality(s, data[1]);
                Serial.printf("[WS] JPEG quality → %d\n", data[1]);
            }
            break;
        }
        default: break;
    }
}

// ======== CAPTURE & SEND via WebSocket ========
void captureAndSend() {
    // Skip jika kamera dinonaktifkan sementara
    if (!isCameraActive) return;
    // Skip jika tidak ada client atau dalam mode hemat daya
    if (ws.count() == 0 || powerSaveMode) return;

    if (esp_get_free_heap_size() < HEAP_GUARD_BYTES) {
        Serial.printf("[MEM] Heap kritis (%u B) — frame dilewati\n", esp_get_free_heap_size());
        return;
    }

    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) return;

    uint8_t* jpg_buf   = fb->buf;
    size_t   jpg_len   = fb->len;
    bool     converted = false;

    if (fb->format != PIXFORMAT_JPEG) {
        converted = frame2jpg(fb, JPEG_QUALITY, &jpg_buf, &jpg_len);
        esp_camera_fb_return(fb);
        fb = nullptr;
        if (!converted) return;
    }

    const size_t   total = FRAME_HEADER_SZ + jpg_len;
    const uint64_t ts_us = esp_timer_get_time();

    if (!g_wsBuf || total > g_wsBufSize) {
        if (g_wsBuf) heap_caps_free(g_wsBuf);
        g_wsBuf = (uint8_t*)heap_caps_malloc(
            total + 8192, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT
        );
        if (!g_wsBuf) g_wsBuf = (uint8_t*)malloc(total + 4096);
        g_wsBufSize = g_wsBuf ? total + 8192 : 0;
        if (!g_wsBuf) {
            if (fb)             esp_camera_fb_return(fb);
            else if (converted) free(jpg_buf);
            return;
        }
    }

    g_wsBuf[0] = FRAME_TYPE_JPEG;
    memcpy(g_wsBuf + 1, &ts_us, 8);
    memcpy(g_wsBuf + FRAME_HEADER_SZ, jpg_buf, jpg_len);

    if (fb)             esp_camera_fb_return(fb);
    else if (converted) free(jpg_buf);

    ws.binaryAll(g_wsBuf, total);
}

// ======== START WEBSOCKET SERVER ========
void startCameraServer() {
    ws.onEvent(onWsEvent);
    server.addHandler(&ws);
    server.begin();
    Serial.printf("[WS] Server ready — ws://%s/ws\n", deviceIP.c_str());
}

// ======== WIFI CONNECT ========
bool connectToWifi(const String& ssid, const String& pass) {
    Serial.printf("[WiFi] Connecting to: %s\n", ssid.c_str());
    ledYellow();
    WiFi.mode(WIFI_STA);

    // Konfigurasi WiFi untuk koneksi stabil
    WiFi.setAutoReconnect(true);  // Auto-reconnect jika sinyal hilang sebentar
    WiFi.persistent(false);       // Jangan simpan ke flash (kita punya NVS sendiri)

    WiFi.begin(ssid.c_str(), pass.c_str());

    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 40) {
        delay(500);
        Serial.print(".");
        attempts++;
        if (attempts % 2 == 0) ledYellow(); else ledOff();
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
        // KRITIKAL: Nonaktifkan WiFi power saving.
        // Default Android/ESP32: WiFi modem bisa masuk sleep mode → packet delay 20-300ms
        // yang menyebabkan TCP timeout dan WebSocket disconnect.
        WiFi.setSleep(false);

        deviceIP     = WiFi.localIP().toString();
        wifiConnected = true;
        currentSSID   = ssid;
        currentPassword = pass;
        isWifiDisconnected = false;

        // Aktifkan kembali kamera jika sebelumnya sempat mati
        if (!isCameraActive) {
            Serial.println("[CAM] Re-initializing camera on successful connection...");
            if (initCamera()) {
                isCameraActive = true;
            }
        }

        Serial.printf("[WiFi] Connected! IP: %s\n", deviceIP.c_str());
        Serial.printf("[WiFi] RSSI: %d dBm | Power saving: OFF\n", WiFi.RSSI());
        return true;
    }
    Serial.println("[WiFi] Connection FAILED.");
    return false;
}

// ======== BLE SCAN WIFI ========
void scanWiFiNetworks() {
    Serial.println("[BLE] Scanning WiFi...");
    ledYellow();

    pResponseChar->setValue("STATUS:Scanning...");
    pResponseChar->notify();
    delay(500);

    int n = WiFi.scanNetworks();

    if (n == 0) {
        pResponseChar->setValue("COUNT:0");
        pResponseChar->notify();
    } else {
        String countMsg = "COUNT:" + String(n);
        pResponseChar->setValue(countMsg.c_str());
        pResponseChar->notify();
        delay(1500);

        String batchMsg  = "";
        int    batchCount = 0;
        const int MAX_BATCH = 180;

        for (int i = 0; i < n; i++) {
            String enc   = (WiFi.encryptionType(i) == WIFI_AUTH_OPEN) ? "O" : "S";
            String entry = String(i) + "|" + WiFi.SSID(i) + "|"
                         + String(WiFi.RSSI(i)) + "|" + enc;

            String test = batchMsg;
            if (test.length() > 0) test += ";";
            test += entry;

            if (test.length() > MAX_BATCH && batchMsg.length() > 0) {
                pResponseChar->setValue(("BATCH:" + batchMsg).c_str());
                pResponseChar->notify();
                delay(800);
                batchMsg   = entry;
                batchCount = 1;
            } else {
                if (batchMsg.length() > 0) batchMsg += ";";
                batchMsg += entry;
                batchCount++;
            }
        }

        if (batchMsg.length() > 0) {
            pResponseChar->setValue(("BATCH:" + batchMsg).c_str());
            pResponseChar->notify();
            delay(500);
        }
    }

    pResponseChar->setValue("STATUS:Done");
    pResponseChar->notify();
    ledGreen();
}

// ======== BLE CONNECT WIFI (dipanggil dari loop) ========
void bleConnectWifi() {
    pResponseChar->setValue("CONNECT:CONNECTING");
    pResponseChar->notify();
    delay(300);

    if (connectToWifi(pendingSSID, pendingPassword)) {
        saveWiFiCredentials(pendingSSID, pendingPassword);

        // Kirim IP agar Android bisa munculkan tombol "View Camera"
        pResponseChar->setValue(("IP:" + deviceIP).c_str());
        pResponseChar->notify();
        delay(800);

        pResponseChar->setValue("CONNECT:SUCCESS");
        pResponseChar->notify();
        delay(800);

        pResponseChar->setValue("BLE:DISCONNECT");
        pResponseChar->notify();
        delay(2000);

        // Matikan BLE
        ledOff();
        pServer->disconnect(pServer->getConnId());
        delay(300);
        BLEDevice::deinit(true);
        bleActive = false;

        // Start WebSocket camera server
        startCameraServer();

    } else {
        pResponseChar->setValue("CONNECT:FAILED:Connection timeout");
        pResponseChar->notify();
        ledRed();
        delay(2000);
        ledGreen();
    }

    pendingSSID     = "";
    pendingPassword = "";
}

// ======== BLE SERVER CALLBACKS ========
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer*)    override {
        deviceConnected = true;
        ledGreen();
        Serial.println("[BLE] Client connected.");
    }
    void onDisconnect(BLEServer*) override {
        deviceConnected = false;
        Serial.println("[BLE] Client disconnected.");
    }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        String value = c->getValue().c_str();
        value.trim();
        Serial.println("[BLE] Cmd: " + value);

        if (value.equalsIgnoreCase("SCAN")) {
            shouldScanWifi = true;
        } else if (value.startsWith("CONNECT:")) {
            String creds = value.substring(8);
            int sep = creds.indexOf('|');
            if (sep > 0) {
                pendingSSID       = creds.substring(0, sep);
                pendingPassword   = creds.substring(sep + 1);
                shouldConnectWifi = true;
            } else {
                pResponseChar->setValue("CONNECT:FAILED:Invalid format");
                pResponseChar->notify();
            }
        } else {
            pResponseChar->setValue("ERROR:Unknown command");
            pResponseChar->notify();
        }
    }
};

// ======== INIT BLE ========
void initBLE() {
    BLEDevice::init("ESP32S3-WiFi-Config");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService* svc = pServer->createService(SERVICE_UUID);

    pCommandChar = svc->createCharacteristic(CHAR_COMMAND_UUID,
        BLECharacteristic::PROPERTY_WRITE);
    pCommandChar->setCallbacks(new CommandCallbacks());

    pResponseChar = svc->createCharacteristic(CHAR_RESPONSE_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    pResponseChar->addDescriptor(new BLE2902());
    pResponseChar->setValue("Ready");

    svc->start();

    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE_UUID);
    adv->setScanResponse(true);
    adv->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    bleActive = true;
    ledBlue();
    Serial.println("[BLE] Advertising as ESP32S3-WiFi-Config");
}

// ======== SETUP ========
void setup() {
    rgbLed.begin();
    rgbLed.setBrightness(LED_BRIGHTNESS);
    ledOff();
    pinMode(RESET_BUTTON_PIN, INPUT_PULLUP);

    Serial.begin(115200);
    delay(1000);
    Serial.println("\n===== ESP32-S3 CAM BLE Provisioning + WebSocket =====");

    Serial.println("[1/2] Initializing camera...");
    if (!initCamera()) {
        Serial.println("[FATAL] Camera init failed! Halting.");
        ledRed();
        while (true) delay(1000);
    }

    Serial.println("[2/2] Checking saved credentials...");
    String savedSSID, savedPass;
    if (loadWiFiCredentials(savedSSID, savedPass)) {
        Serial.println("[INFO] Found: " + savedSSID);
        if (connectToWifi(savedSSID, savedPass)) {
            ledOff();
            startCameraServer();
        } else {
            clearWiFiCredentials();
            WiFi.disconnect();
            delay(100);
            initBLE();
        }
    } else {
        WiFi.mode(WIFI_STA);
        WiFi.disconnect();
        delay(100);
        initBLE();
    }
}

// ======== LOOP ========
void loop() {
    // ---- RESET BUTTON: tahan 5 detik dengan indikator LED progresif ----
    if (digitalRead(RESET_BUTTON_PIN) == LOW) {
        if (resetTriggered) {
            // Reset sudah dieksekusi — abaikan sampai tombol dilepas terlebih dahulu
        } else if (!resetButtonPressed) {
            // Tombol baru ditekan — catat waktu, mulai phase 1
            resetButtonPressed   = true;
            resetButtonPressTime = millis();
            resetLedPhase        = 0;
            Serial.println("[RESET] Button pressed — tahan 5 detik untuk reset WiFi.");
        } else {
            unsigned long held = millis() - resetButtonPressTime;

            // --- Indikator LED progresif (countdown 3 phase) ---
            if (held < RESET_PHASE1_MS) {
                // Phase 1: 0 – 1.6 s → Orange
                if (resetLedPhase != 1) {
                    resetLedPhase = 1;
                    ledOrange();
                    Serial.println("[RESET] Phase 1/3 — Orange");
                }
            } else if (held < RESET_PHASE2_MS) {
                // Phase 2: 1.6 – 3.3 s → Kuning
                if (resetLedPhase != 2) {
                    resetLedPhase = 2;
                    ledYellow();
                    Serial.println("[RESET] Phase 2/3 — Kuning");
                }
            } else if (held < RESET_HOLD_TIME) {
                // Phase 3: 3.3 – 5.0 s → Merah
                if (resetLedPhase != 3) {
                    resetLedPhase = 3;
                    ledRed();
                    Serial.println("[RESET] Phase 3/3 — Merah (segera reset!)");
                }
            } else {
                // ======== TRIGGERED: 5 detik tercapai ========
                Serial.println("[SYSTEM] Reset button held 5s — Clearing WiFi credentials...");
                resetTriggered = true; // tandai agar loop berikutnya tidak restart countdown

                // A. Feedback visual: 6x blink putih cepat → magenta (sedang proses)
                for (int i = 0; i < 6; i++) {
                    ledWhite(); delay(80);
                    ledOff();   delay(80);
                }
                ledMagenta(); // indikator: sedang memproses reset

                // 1. Hapus kredensial dari flash (NVS/Preferences)
                clearWiFiCredentials();

                // 2. Tutup semua koneksi WebSocket yang masih aktif secara graceful
                ws.closeAll();
                wsClientConnected = false;
                delay(100);

                // 3. Putuskan WiFi dan matikan radio WiFi sepenuhnya
                //    WiFi & BLE berbagi radio (co-existence); WiFi harus MATI
                //    sebelum BLE stack diinisialisasi ulang, jika tidak bisa crash.
                WiFi.disconnect(true);  // true = juga clear AP/STA config internal
                WiFi.mode(WIFI_OFF);
                wifiConnected = false;
                deviceIP      = "";
                delay(300);

                // 4. Deinit BLE jika masih aktif (cegah double-init crash)
                if (bleActive) {
                    Serial.println("[BLE] Deinit existing BLE stack before re-init...");
                    BLEDevice::deinit(true);
                    bleActive          = false;
                    deviceConnected    = false;
                    oldDeviceConnected = false;
                    pServer            = nullptr;
                    pCommandChar       = nullptr;
                    pResponseChar      = nullptr;
                    delay(200);
                }

                // 5. Reset flag BLE command agar tidak ada perintah lama yang tertinggal
                shouldScanWifi    = false;
                shouldConnectWifi = false;
                pendingSSID       = "";
                pendingPassword   = "";
                currentSSID       = "";
                currentPassword   = "";
                isWifiDisconnected = false;

                // Aktifkan kembali kamera jika sebelumnya sempat mati sebelum masuk mode BLE provisioning
                if (!isCameraActive) {
                    Serial.println("[RESET] Re-initializing camera for BLE provisioning mode...");
                    if (initCamera()) {
                        isCameraActive = true;
                    }
                }

                // 6. Init ulang BLE dari kondisi bersih
                Serial.println("[BLE] Re-initializing BLE...");
                initBLE(); // set bleActive = true, LED biru di dalamnya

                Serial.println("[SYSTEM] WiFi reset done. BLE advertising aktif.");
            }
        }
    } else {
        // Tombol dilepas
        if (resetTriggered) {
            // Reset telah selesai & tombol baru dilepas — bersihkan semua flag
            resetTriggered     = false;
            resetButtonPressed = false;
            resetLedPhase      = 0;
            Serial.println("[RESET] Tombol dilepas — sistem siap.");
        } else if (resetButtonPressed) {
            // Tombol dilepas sebelum 5 detik — batalkan, kembalikan LED
            Serial.println("[RESET] Tombol dilepas sebelum 5 detik — reset dibatalkan.");
            if (wifiConnected)    ledGreen();
            else if (bleActive)   ledBlue();
            else                  ledOff();
            resetButtonPressed = false;
            resetLedPhase      = 0;
        }
    }

    // BLE provisioning
    if (bleActive) {
        if (shouldScanWifi && deviceConnected) {
            scanWiFiNetworks();
            shouldScanWifi = false;
        }
        if (shouldConnectWifi && deviceConnected) {
            bleConnectWifi();
            shouldConnectWifi = false;
        }

        // Blink biru saat menunggu koneksi BLE
        if (!deviceConnected && !wifiConnected) {
            unsigned long now = millis();
            if (now - previousMillis >= BLINK_INTERVAL) {
                previousMillis = now;
                ledState = !ledState;
                if (ledState) ledBlue(); else ledOff();
            }
        }

        // Re-advertise setelah disconnect
        if (!deviceConnected && oldDeviceConnected && !wifiConnected) {
            delay(500);
            pServer->startAdvertising();
            oldDeviceConnected = deviceConnected;
        }
        if (deviceConnected && !oldDeviceConnected) {
            oldDeviceConnected = deviceConnected;
        }
    }

    // WebSocket camera streaming
    if (wifiConnected && !bleActive) {
        static uint64_t lastFrameUs   = 0;
        static uint32_t lastCleanup   = 0;
        static uint32_t lastHbeat     = 0;
        static uint32_t framesSent    = 0;
        uint64_t nowUs = esp_timer_get_time();
        uint32_t nowMs = millis();

        // ── Power Save Mode: cek timeout jika tidak ada client ────────────
        if (!powerSaveMode && hadClientBefore && !wsClientConnected &&
            lastClientLostTime > 0 &&
            (nowMs - lastClientLostTime >= POWER_SAVE_TIMEOUT)) {
            powerSaveMode = true;
            Serial.println("[PWR] Masuk mode hemat daya — kamera tidak aktif");
            // LED berkedip merah pelan untuk indikasi power save
        }

        // LED indikator power save: berkedip merah pelan
        if (powerSaveMode) {
            static uint32_t lastPwrLed = 0;
            static bool     pwrLedOn   = false;
            if (nowMs - lastPwrLed >= 1500) {  // berkedip setiap 1.5 detik
                lastPwrLed = nowMs;
                pwrLedOn   = !pwrLedOn;
                if (pwrLedOn) ledRed(); else ledOff();
            }
        }

        // Capture & kirim frame (dilewati jika powerSaveMode atau tidak ada client)
        if (nowUs - lastFrameUs >= TARGET_FRAME_US) {
            lastFrameUs = nowUs;
            captureAndSend();
            if (!powerSaveMode && wsClientConnected) framesSent++;
        }

        // Bersihkan koneksi WS mati setiap 2 detik
        if (nowMs - lastCleanup >= 2000) {
            lastCleanup = nowMs;
            ws.cleanupClients();
        }

        // ── WiFi Reconnection & Camera Management ────────────────────────
        static uint32_t lastWifiCheck = 0;
        if (nowMs - lastWifiCheck >= 1000) { // Cek status WiFi setiap 1 detik
            lastWifiCheck = nowMs;
            if (WiFi.status() != WL_CONNECTED) {
                if (!isWifiDisconnected) {
                    isWifiDisconnected = true;
                    wifiDisconnectTime = nowMs;
                    Serial.println("[WiFi] Koneksi WiFi terputus! Mencoba menyambung kembali...");
                    WiFi.disconnect();
                    WiFi.begin(currentSSID.c_str(), currentPassword.c_str());
                } else {
                    unsigned long elapsed = nowMs - wifiDisconnectTime;
                    if (elapsed > 30000 && isCameraActive) {
                        Serial.println("[WiFi] Terputus > 30 detik. Menonaktifkan kamera sementara untuk hemat daya...");
                        esp_camera_deinit();
                        isCameraActive = false;
                    }
                }
            } else {
                if (isWifiDisconnected) {
                    isWifiDisconnected = false;
                    Serial.println("[WiFi] Koneksi WiFi berhasil tersambung kembali!");
                    if (!isCameraActive) {
                        Serial.println("[WiFi] Mengaktifkan kembali kamera...");
                        if (initCamera()) {
                            isCameraActive = true;
                        } else {
                            Serial.println("[FATAL] Gagal mengaktifkan kembali kamera!");
                        }
                    }
                }
            }
        }

        // Heartbeat setiap WS_PING_INTERVAL
        if (nowMs - lastHbeat >= WS_PING_INTERVAL) {
            lastHbeat = nowMs;

            // Log statistik
            Serial.printf("[STAT] Heap: %u B | WS clients: %u | FPS ~%.1f | PowerSave: %s\n",
                esp_get_free_heap_size(),
                ws.count(),
                (float)framesSent * 1000.0f / WS_PING_INTERVAL,
                powerSaveMode ? "ON" : "OFF");
            framesSent = 0;

            // Heartbeat ke client aktif
            if (ws.count() > 0) {
                uint8_t hbeat[FRAME_HEADER_SZ];
                const uint64_t ts = esp_timer_get_time();
                hbeat[0] = FRAME_TYPE_HBEAT;
                memcpy(hbeat + 1, &ts, 8);
                ws.binaryAll(hbeat, FRAME_HEADER_SZ);
            }
        }
    }

    // yield() agar FreeRTOS watchdog tidak trigger — lebih baik dari delay(5)
    yield();
}