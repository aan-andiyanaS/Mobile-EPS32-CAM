# Issue Report & Fix: Bug Auto-Connect Selalu "Mencari Perangkat"

## Deskripsi Masalah (Bug)
Setelah fitur auto-connect cerdas di `MainActivity` diimplementasikan, aplikasi terus saja memunculkan pesan *"Mencari perangkat tersimpan..."* dan tidak mau berpindah ke `CameraStreamActivity`, meskipun ESP32 sebenarnya sudah menyala dan terkoneksi ke jaringan WiFi.

## Analisis Akar Masalah (Root Cause Analysis)
Bug ini disebabkan oleh **ketidakcocokan port TCP** yang di-ping oleh Android dengan port server WebSocket yang sebenarnya terbuka di ESP32.
- Pada implementasi awal di `MainActivity.kt`, background ping menggunakan kode `Socket(ipAddress, 81)` dengan asumsi bahwa ESP32 membuka WebSocket di port `81`.
- Kenyataannya, di firmware `esp32s3_camera_with_mobile.ino`, server diinisialisasi dengan perintah `AsyncWebServer server(80);`.
- Dan di sisi klien (Android OkHttp), URI yang dipakai adalah `"ws://$ip/ws"`. Jika kita tidak menspesifikasikan port pada URI (misal `:81`), maka secara *default* protokol akan mengarah ke HTTP port `80`.
- Akibatnya, saat `MainActivity` mencoba melakukan TCP ping ke port `81`, koneksi selalu ditolak (`Connection Refused`), sehingga akan masuk ke blok `catch` dan terus terjebak dalam *loop* "Mencari Perangkat".

## Solusi yang Telah Diterapkan (Fix)
Perbaikannya sangat sederhana. Port tujuan pada pengecekan Socket di `MainActivity.kt` telah diubah dari `81` menjadi `80`.

**Kode Lama (`MainActivity.kt`):**
```kotlin
val isOnline = try {
    socket = Socket()
    socket.connect(InetSocketAddress(ipAddress, 81), 1000)
    true
} ...
```

**Kode Baru (`MainActivity.kt`):**
```kotlin
val isOnline = try {
    socket = Socket()
    socket.connect(InetSocketAddress(ipAddress, 80), 1000)
    true
} ...
```

## Catatan untuk Junior Developer
1. **Pentingnya Memahami Default Port:** Ingatlah bahwa URL berawalan `http://` atau `ws://` secara *default* akan menggunakan port `80`, sedangkan `https://` atau `wss://` menggunakan port `443`.
2. **Validasi End-to-End:** Selalu lakukan pengecekan ganda (*cross-check*) terhadap variabel konfigurasi jaringan (seperti port, path, dan alamat IP) antara kode backend/firmware dan klien/mobile app.
3. Tindakan ini sudah dieksekusi secara *live* pada baris kode bersangkutan, Anda bisa me-review *commit* terakhir untuk melihat perubahannya.
