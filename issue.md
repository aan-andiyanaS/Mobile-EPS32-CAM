# Update Dokumentasi: Siklus Hidup dan Power Save Mode

## Deskripsi Tugas
Melakukan sinkronisasi dokumentasi (`README.md`) utama dan dokumentasi *firmware* ESP32 agar sejalan dengan perubahan logika kode yang baru saja kita implementasikan.

## Rincian Pembaruan
1. **README.md Utama (Android):**
   - **Flowchart Utama:** Ditambahkan alur untuk *Background Ping TCP* (port 80) dan alur notifikasi persisten saat *"Sesi Dihentikan"*.
   - **Tabel Fitur:** Menambahkan informasi tentang *Exit Behavior* (Broadcast `ACTION_EXIT_APP`) pada `CameraStreamService`.
   - **MainActivity:** Memperjelas bahwa *BLE Scanner* sekarang akan selalu muncul, dan jika ada IP tersimpan, aplikasi akan mengandalkan pengecekan via *socket* alih-alih melakukan *jump* instan.
2. **README.md Firmware (ESP32):**
   - **Flowchart Utama:** Menyisipkan kondisi *Power Save Mode* yang akan menangguhkan pembacaan frame JPEG bila tidak ada klien WebSocket yang terkoneksi selama lebih dari 30 detik.
   - **Tabel Indikator LED:** Menambahkan status "🔴 Merah berkedip pelan" sebagai penanda visual ESP32 masuk ke mode hemat daya.

## Status
Telah dieksekusi secara otomatis dan dokumen ini disubmit sebagai *issue* ke repositori untuk *tracking* arsip pengembangan proyek.
