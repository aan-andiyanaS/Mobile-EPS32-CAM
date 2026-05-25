# ESP32-S3 CAM — BLE WiFi Provisioning + WebSocket Camera Stream

> **Bagian dari sistem**: Firmware ini adalah **sisi perangkat keras (ESP32-S3)** yang bekerja bersama aplikasi Android ([VNetra Android App](../../../README.md)) untuk membentuk sistem kamera nirkabel real-time.

---

## Daftar Isi

- [Gambaran Sistem](#gambaran-sistem)
- [Hardware yang Digunakan](#hardware-yang-digunakan)
- [Teknologi & Library](#teknologi--library)
- [Alur Kerja Sistem](#alur-kerja-sistem)
- [Protokol BLE Provisioning](#protokol-ble-provisioning)
- [Protokol WebSocket](#protokol-websocket)
- [Power Save Mode](#power-save-mode)
- [WiFi Auto-Reconnect](#wifi-auto-reconnect)
- [Mekanisme Reset Credential WiFi](#mekanisme-reset-credential-wifi)
- [Konfigurasi & Optimasi Kamera](#konfigurasi--optimasi-kamera)
- [Cara Setup Arduino IDE](#cara-setup-arduino-ide)
- [Pemetaan GPIO](#pemetaan-gpio)
- [Indikator LED Status](#indikator-led-status)

---

## Gambaran Sistem

```
┌─────────────────────────────────────────────────────────────┐
│                    SISTEM KAMERA NIRKABEL                   │
│                                                             │
│   ┌──────────────┐     BLE (Provisioning)    ┌───────────┐ │
│   │   ESP32-S3   │ ◄─────────────────────── │  Android  │ │
│   │  + OV2640    │ ─────────────────────── ► │   App     │ │
│   │  + WS2812    │     WebSocket (Camera)    │           │ │
│   └──────────────┘         via WiFi          └───────────┘ │
│                                                             │
│   [Firmware ini]                         [Android App]     │
└─────────────────────────────────────────────────────────────┘
```

ESP32-S3 bertindak sebagai **server kamera embedded** dengan dua mode operasi:
1. **Mode Provisioning (BLE)** — saat pertama kali atau setelah reset credential
2. **Mode Streaming (WiFi/WebSocket)** — setelah berhasil terkoneksi ke jaringan WiFi

---

## Hardware yang Digunakan

| Komponen | Spesifikasi | Keterangan |
|----------|-------------|------------|
| **MCU** | ESP32-S3 WROOM N16R8 | Dual-core Xtensa LX7, 240 MHz, 16MB Flash, 8MB OPI PSRAM |
| **Kamera** | OV2640 | Hardware JPEG encoder, resolusi maks 2MP |
| **LED** | WS2812 RGB (GPIO 48) | Indikator status sistem |
| **Reset Button** | GPIO 0 (BOOT button) | Tahan 5 detik untuk hapus credential WiFi |

---

## Teknologi & Library

| Library | Fungsi |
|---------|--------|
| `esp_camera.h` | Driver kamera OV2640, akses DMA frame buffer |
| `BLEDevice / BLEServer` | Bluetooth Low Energy server untuk WiFi provisioning |
| `ESPAsyncWebServer` | Async WebSocket server (non-blocking, event-driven) |
| `AsyncTCP` | TCP layer untuk ESPAsyncWebServer |
| `Adafruit NeoPixel` | Kontrol LED WS2812 RGB |
| `Preferences` | Simpan credential WiFi ke NVS (Non-Volatile Storage) |

---

## Alur Kerja Sistem

### Flowchart Utama (Boot)

```mermaid
flowchart TD
    A([Boot / Power ON]) --> B{Cek credential WiFi\ndi NVS?}

    B -->|Ada| C[Auto-connect WiFi]
    B -->|Tidak ada| D[Init BLE\nAdvertising sebagai\nESP32S3-WiFi-Config]

    C --> E{WiFi berhasil\nterkoneksi?}
    E -->|Ya| F[WiFi.setSleep OFF\nInit WebSocket Server]
    E -->|Tidak - timeout 20 detik| G1[Hapus credentials NVS\nDisconnect WiFi]
    G1 --> D

    D --> G[LED Biru berkedip\nTunggu koneksi Android]
    G --> H{Android scan\ndan connect via BLE}
    H --> I[Android kirim command SCAN]
    I --> J[ESP32 scan WiFi\nKirim COUNT dan BATCH via BLE]
    J --> K[User pilih SSID\ndan kirim CONNECT SSID+password]
    K --> L[ESP32 simpan ke NVS\nConnect WiFi]
    L --> M{Berhasil?}
    M -->|Ya| N[Kirim IP:x.x.x.x via BLE\nKirim CONNECT:SUCCESS\nKirim BLE:DISCONNECT]
    M -->|Tidak| O[Kirim CONNECT:FAILED\nvia BLE]
    N --> P[BLE Off\nHemat daya]
    P --> F
    O --> G

    F --> Q[LED Hijau\nServer siap - ws://IP/ws]
    Q --> R[Loop: Capture dan Stream\n15 FPS via WebSocket]

    R --> S{Klien Disconnect\nlebih dari 30 Detik?}
    S -->|Ya| T[Power Save Mode\nSuspend Capture\nLED Merah Pelan]
    T --> U{Klien Reconnect?}
    U -->|Ya| R
```

### Flowchart BLE Provisioning Detail

```mermaid
sequenceDiagram
    participant ESP as ESP32-S3
    participant APP as Android App

    APP->>ESP: BLE Connect ke ESP32S3-WiFi-Config
    ESP->>APP: BLE Connection OK, LED Hijau

    APP->>ESP: Command SCAN
    ESP->>APP: Response STATUS Scanning...
    ESP->>ESP: Scan jaringan WiFi
    ESP->>APP: Response COUNT 3
    Note over ESP,APP: BATCH format: index,SSID,RSSI,enkripsi (S=Secured O=Open)
    ESP->>APP: Response BATCH 0,SSID1,-65,S / 1,SSID2,-72,S / 2,SSID3,-80,O
    ESP->>APP: Response STATUS Done

    APP->>ESP: Command CONNECT SSID password
    ESP->>APP: Response CONNECT CONNECTING
    ESP->>ESP: Simpan ke NVS
    ESP->>ESP: WiFi.begin(ssid, pass)

    alt WiFi berhasil
        ESP->>APP: Response IP 192.168.1.xxx
        ESP->>APP: Response CONNECT SUCCESS
        ESP->>APP: Response BLE DISCONNECT
        ESP->>ESP: BLE Off, start WebSocket
        APP->>ESP: WebSocket Connect ws://192.168.1.xxx/ws
        ESP-->>APP: Binary stream JPEG frames
    else WiFi gagal
        ESP->>APP: Response CONNECT FAILED Connection timeout
    end
```

### Format BATCH Data WiFi

Setiap jaringan dalam response `BATCH:` dikirim dalam format berikut, dipisah titik koma (`;`):

```
BATCH:<index>|<SSID>|<RSSI>|<enkripsi>;<index>|<SSID>|<RSSI>|<enkripsi>...

Keterangan enkripsi:
  S = Secured (ada password)
  O = Open (tanpa password)

Contoh:
  BATCH:0|MyWiFi|-65|S;1|CafeGuest|-78|O;2|AndroidAP|-80|S
```

### Flowchart Reset Credential WiFi

```mermaid
flowchart LR
    A([Tekan BOOT Button]) --> B{Tahan > 0s<br/>< 1.6s}
    B --> C[LED Orange<br/>Phase 1/3]
    C --> D{Tahan > 1.6s<br/>< 3.3s}
    D --> E[LED Kuning<br/>Phase 2/3]
    E --> F{Tahan > 3.3s<br/>< 5.0s}
    F --> G[LED Merah<br/>Phase 3/3]
    G --> H{Tahan >= 5.0s?}
    H -->|Ya| I[6x Blink Putih Cepat]
    H -->|Tidak, lepas sebelum 5s| Z([Batal - Tidak ada reset])
    I --> J[LED Magenta<br/>Reset NVS credential]
    J --> K[Restart BLE Advertising]
    K --> L([LED Biru berkedip<br/>Siap provisioning ulang])
```

---

## Protokol BLE Provisioning

### BLE Service & Characteristics

| UUID | Nama | Properti | Fungsi |
|------|------|----------|--------|
| `4fafc201-1fb5-459e-8fcc-c5c9c331914b` | SERVICE | - | BLE Service utama |
| `beb5483e-36e1-4688-b7f5-ea07361b26a8` | CHAR_COMMAND | WRITE | Android → ESP32: kirim perintah |
| `cba1d466-344c-4be3-ab3f-189f80dd7518` | CHAR_RESPONSE | READ + NOTIFY | ESP32 → Android: kirim respons |

### BLE Device Name

```
ESP32S3-WiFi-Config
```

### Command dari Android ke ESP32 (via CHAR_COMMAND)

| Command | Format | Aksi di ESP32 |
|---------|--------|---------------|
| Scan WiFi | `SCAN` | ESP32 scan jaringan, kirim `COUNT:n` + `BATCH:...` |
| Koneksi WiFi | `CONNECT:<SSID>\|<password>` | ESP32 konek ke WiFi, kirim IP atau error |

### Response dari ESP32 ke Android (via CHAR_RESPONSE Notify)

| Response | Keterangan |
|----------|------------|
| `STATUS:Scanning...` | ESP32 sedang scan WiFi |
| `COUNT:<n>` | Jumlah total jaringan ditemukan |
| `BATCH:<data>` | Satu batch daftar SSID (format lihat di atas) |
| `STATUS:Done` | Scan selesai |
| `CONNECT:CONNECTING` | Sedang mencoba konek WiFi |
| `IP:<x.x.x.x>` | Sukses! Berisi IP address ESP32 |
| `CONNECT:SUCCESS` | Konfirmasi koneksi WiFi berhasil |
| `BLE:DISCONNECT` | Pemberitahuan BLE akan dimatikan |
| `CONNECT:FAILED:Connection timeout` | Gagal konek WiFi (timeout 20 detik) |
| `ERROR:Unknown command` | Command tidak dikenal |

---

## Protokol WebSocket

Semua data dikirim dalam **format binary frame** dengan struktur:

```
┌──────────────────────────────────────────────────────┐
│  Byte 0    │  Byte 1-8          │  Byte 9+           │
│  Frame     │  Timestamp (µs)    │  Payload           │
│  Type      │  uint64 LE         │                    │
├──────────────────────────────────────────────────────┤
│  0x01 JPEG │  esp_timer_get()   │  JPEG binary data  │
│  0x02 IMU  │  timestamp         │  [reserved - future MPU6050 + EKF]  │
│  0x03 HBEAT│  timestamp         │  (kosong)          │
│  0x04 TOF  │  timestamp         │  [reserved - future VL53L5CX]       │
│  0x05 CTRL │  timestamp         │  Control command   │
└──────────────────────────────────────────────────────┘

Total header = 9 bytes
JPEG payload = variable (biasanya 8-25 KB pada HVGA quality 15)
```

### Endpoint

```
WebSocket : ws://<IP_ESP32>/ws
Port      : 80 (default HTTP/WS)
Format    : Binary (bukan text)
```

### Fitur Protokol

| Fitur | Detail |
|-------|--------|
| **TCP_NODELAY** | Aktif saat client connect → eliminasi Nagle delay 200ms |
| **Heartbeat** | Frame `0x03` setiap 10 detik untuk keep-alive |
| **Frame drop** | Server skip frame jika heap < 30KB untuk hindari crash |
| **Quality control** | Client dapat kirim `0xA1 <quality>` untuk ubah kualitas JPEG real-time |
| **Extensible** | Frame type `0x02` (IMU) dan `0x04` (ToF) sudah disiapkan untuk sensor masa depan |
| **Pre-allocated buffer** | Buffer WebSocket dialokasikan di PSRAM, hindari malloc/free per frame |

---

## Power Save Mode

Jika tidak ada client WebSocket yang terhubung selama **30 detik**, ESP32 masuk ke **Power Save Mode**:

- Frame kamera **tidak diambil dan tidak dikirim** (kamera tetap aktif, hanya capture yang ditangguhkan)
- LED berkedip **merah pelan** (interval 1,5 detik)
- **Otomatis keluar** dari mode ini begitu ada client yang reconnect

```
Kondisi trigger : ws.count() == 0 selama > 30 detik (POWER_SAVE_TIMEOUT)
Kondisi keluar  : Client baru connect via WebSocket
```

---

## WiFi Auto-Reconnect

ESP32 memiliki dua lapisan mekanisme reconnect WiFi:

| Layer | Mekanisme | Detail |
|-------|-----------|--------|
| **Layer 1** | `WiFi.setAutoReconnect(true)` | Built-in ESP32: reconnect otomatis jika sinyal hilang sebentar |
| **Layer 2** | Monitoring manual setiap 1 detik | Jika `WiFi.status() != WL_CONNECTED`, panggil `WiFi.begin()` ulang |

Jika WiFi terputus selama **> 30 detik**, kamera dinonaktifkan sementara (`esp_camera_deinit()`) untuk menghemat daya. Saat WiFi berhasil tersambung kembali, kamera akan **diinisialisasi ulang otomatis**.

---

## Mekanisme Reset Credential WiFi

Reset dilakukan dengan menekan **tombol BOOT (GPIO 0)** selama tepat **5 detik**.

| Fase | Waktu | LED |
|------|-------|-----|
| Phase 1 | 0 – 1.6 detik | 🟠 Orange |
| Phase 2 | 1.6 – 3.3 detik | 🟡 Kuning |
| Phase 3 | 3.3 – 5.0 detik | 🔴 Merah |
| Konfirmasi | Setelah 5 detik | ⚪ 6× blink putih cepat |
| Proses reset | Selama hapus NVS | 🟣 Magenta |
| Selesai | BLE aktif kembali | 🔵 Biru berkedip |

> **Jika tombol dilepas sebelum 5 detik** — tidak ada yang terjadi, reset dibatalkan.

### Urutan proses saat reset berhasil:
1. Hapus credentials dari NVS (`Preferences.clear()`)
2. Tutup semua koneksi WebSocket aktif
3. Disconnect WiFi & matikan radio WiFi (`WiFi.mode(WIFI_OFF)`)
4. Deinit BLE stack yang lama (jika masih aktif)
5. Init ulang BLE dari kondisi bersih
6. LED Biru berkedip — siap provisioning ulang

---

## Konfigurasi & Optimasi Kamera

### Parameter Kamera

```cpp
cfg.xclk_freq_hz = 24000000;       // 24 MHz: readout sensor lebih cepat
cfg.frame_size   = FRAMESIZE_HVGA; // 480×320: balance kualitas vs latensi
cfg.pixel_format = PIXFORMAT_JPEG; // Hardware JPEG (OV2640 built-in encoder)
cfg.jpeg_quality = 15;             // 0=terbaik, 63=terburuk (15=balance)
cfg.fb_count     = 3;              // Triple buffer PSRAM: pipeline smooth
cfg.grab_mode    = CAMERA_GRAB_LATEST; // Selalu ambil frame terbaru
```

### Pengaturan Sensor Otomatis

```cpp
s->set_whitebal(s, 1);      // White balance otomatis
s->set_awb_gain(s, 1);      // Auto white balance gain
s->set_exposure_ctrl(s, 1); // Exposure control otomatis
s->set_aec2(s, 1);          // AEC DSP (auto exposure)
s->set_gain_ctrl(s, 1);     // Gain control otomatis
s->set_bpc(s, 1);           // Bad pixel correction
s->set_wpc(s, 1);           // White pixel correction
s->set_raw_gma(s, 1);       // Gamma correction
s->set_lenc(s, 1);          // Lens correction
s->set_gainceiling(s, (gainceiling_t)6); // Gain ceiling
```

### Jika Masih Lag

Ganti resolusi ke yang lebih kecil di `initCamera()`:
```cpp
// Level 1 (default): 480×320
cfg.frame_size = FRAMESIZE_HVGA;

// Level 2: 400×296
cfg.frame_size = FRAMESIZE_CIF;

// Level 3 (minimum lag): 320×240
cfg.frame_size = FRAMESIZE_QVGA;
```

### Optimasi WiFi

```cpp
WiFi.setSleep(false);        // KRITIKAL: nonaktifkan power saving WiFi
WiFi.setAutoReconnect(true); // Auto-reconnect jika sinyal hilang
WiFi.persistent(false);      // Jangan simpan ke flash (kita punya NVS sendiri)
```

---

## Cara Setup Arduino IDE

### 1. Install Board Package

```
File → Preferences → Additional Boards Manager URLs:
https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
```

Tools → Board Manager → cari **esp32** → Install

### 2. Install Library

Library Manager (`Ctrl+Shift+I`):
- `ESPAsyncWebServer` by lacamera (atau me-no-dev)
- `AsyncTCP` by dvarrel (atau me-no-dev)
- `Adafruit NeoPixel`

### 3. Board Settings

| Setting | Nilai |
|---------|-------|
| Board | ESP32S3 Dev Module |
| PSRAM | OPI PSRAM |
| Flash Size | 16MB |
| Partition Scheme | Huge APP (3MB No OTA/1MB SPIFFS) |
| CPU Frequency | 240 MHz |
| Upload Speed | 921600 |

### 4. Upload

1. Tahan tombol BOOT, tekan RESET, lepas BOOT
2. Klik Upload di Arduino IDE
3. Setelah upload selesai, tekan RESET sekali lagi

---

## Pemetaan GPIO

| GPIO | Fungsi | Keterangan |
|------|--------|------------|
| 0 | Reset Button | BOOT button bawaan |
| 4 | SIOD (SDA) | I2C kamera |
| 5 | SIOC (SCL) | I2C kamera |
| 6 | VSYNC | Kamera |
| 7 | HREF | Kamera |
| 8 | Y4 | Data kamera |
| 9 | Y3 | Data kamera |
| 10 | Y5 | Data kamera |
| 11 | Y2 | Data kamera |
| 12 | Y6 | Data kamera |
| 13 | PCLK | Kamera pixel clock |
| 15 | XCLK | Kamera master clock |
| 16 | Y9 | Data kamera |
| 17 | Y8 | Data kamera |
| 18 | Y7 | Data kamera |
| 48 | WS2812 RGB LED | Status indicator |

---

## Indikator LED Status

| Warna | Kondisi |
|-------|---------|
| 🔵 Biru berkedip | Menunggu koneksi BLE dari Android |
| 🟢 Hijau solid | BLE client terhubung / WiFi terkoneksi & WebSocket aktif |
| 🟡 Kuning | Sedang koneksi WiFi |
| 🔴 Merah berkedip cepat | Error (kamera gagal init, dll) |
| 🔴 Merah berkedip pelan | **Power Save Mode** (klien disconnect > 30 detik, capture ditangguhkan) |
| 🟣 Magenta | Proses reset credential |
| ⚪ Putih blink | Konfirmasi reset berhasil |

---

## Lihat Juga

- 📱 **Android App** → [`README.md`](../../../README.md)
- 📊 **Wiring Diagram** → [`wiring-diagram.md`](./wiring-diagram.md)
