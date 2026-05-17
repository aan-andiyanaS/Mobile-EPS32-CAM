# Phase 4: ESP32-S3 Camera & BLE Provisioning Mobile App

Aplikasi Android pendamping untuk sistem navigasi tunanetra yang berfungsi untuk:
1. Melakukan koneksi BLE ke ESP32-S3 untuk WiFi Provisioning.
2. Memindai dan mengirimkan kredensial WiFi ke ESP32.
3. Menampilkan *live camera stream* dari ESP32 menggunakan protokol WebSocket.

---

## 🛠️ Persyaratan Sistem (Prerequisites)

Sebelum melakukan build, pastikan sistem Anda telah memiliki:

1. **Java Development Kit (JDK) 11 atau 17**
   - Pastikan `JAVA_HOME` sudah di-set ke direktori instalasi JDK.
   - Contoh di Windows: `C:\Program Files\Android\Android Studio\jbr` atau instalasi OpenJDK lainnya.
2. **Android SDK & Build Tools**
   - Anda memerlukan Android SDK Platform 35 dan Build Tools 34.0.0.
   - Jika belum ada, Gradle biasanya akan mencoba mengunduhnya secara otomatis asalkan lisensi SDK sudah disetujui, atau Anda bisa mengunduhnya melalui Android Studio SDK Manager.
3. **Android Device (Smartphone) untuk Testing**
   - Developer Options (Opsi Pengembang) harus **AKTIF**.
   - USB Debugging harus **AKTIF**.
4. **ADB (Android Debug Bridge)**
   - Jika Anda sudah menginstal Android Studio, ADB otomatis terinstal di folder `C:\Users\<NamaUser>\AppData\Local\Android\Sdk\platform-tools`.
   - **Cara Install / Setup Manual:**
     1. Unduh *SDK Platform-Tools* dari [Situs Resmi Android Developer](https://developer.android.com/tools/releases/platform-tools).
     2. Ekstrak file zip tersebut ke sebuah folder (misalnya ke `C:\platform-tools`).
     3. Tambahkan path folder ekstrak tersebut (atau path bawaan Android Studio) ke dalam **Environment Variables > Path** di Windows agar perintah `adb` dikenali secara global di terminal.

---

## 🚀 Cara Build di PC (Generate APK)

> **💡 Catatan Instalasi Dependensi:** Anda **tidak perlu** menginstal dependensi secara manual. Sistem *build* Gradle akan membaca file konfigurasi (`build.gradle.kts` dan `libs.versions.toml`) lalu **mengunduh semua library secara otomatis** (seperti AndroidX, OkHttp, Material Design, dsb) saat Anda menjalankan perintah *build* untuk pertama kalinya. Pastikan PC Anda terhubung ke internet.

Untuk melakukan kompilasi proyek dan menghasilkan file APK tanpa menginstalnya ke smartphone:

1. Buka terminal (Command Prompt / PowerShell).
2. Arahkan direktori aktif ke folder proyek ini:
   ```bash
   cd e:\Project\Skripsi\phase4-camera-eps-s3-mobile
   ```
3. (Opsional) Jika `JAVA_HOME` belum di-set di Environment Variables, set terlebih dahulu di session terminal Anda (contoh untuk PowerShell):
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   ```
4. Jalankan perintah build menggunakan Gradle Wrapper:
   - **Windows:**
     ```bash
     .\gradlew.bat assembleDebug
     ```
   - **Mac/Linux:**
     ```bash
     ./gradlew assembleDebug
     ```

Jika berhasil, file APK akan dihasilkan di folder:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📱 Cara Build dan Install Langsung ke Mobile Device

Untuk mengkompilasi dan langsung menginstal aplikasi ke smartphone yang terhubung via kabel USB:

1. Pastikan smartphone Anda sudah terhubung ke PC via USB.
2. Pastikan USB Debugging di smartphone sudah diaktifkan dan izinkan debugging dari komputer tersebut (jika muncul popup di HP).
3. Verifikasi apakah perangkat terdeteksi menggunakan ADB (opsional tapi disarankan):
   ```bash
   adb devices
   ```
   *(Harus muncul list device dengan status `device`)*
4. Jalankan perintah install melalui Gradle Wrapper:
   - **Windows:**
     ```bash
     .\gradlew.bat installDebug
     ```
   - **Mac/Linux:**
     ```bash
     ./gradlew installDebug
     ```

Setelah proses selesai dengan tulisan `BUILD SUCCESSFUL`, aplikasi akan otomatis terpasang di smartphone Anda.

---

## 🐞 Troubleshooting

- **Error JAVA_HOME is not set:** Pastikan variabel environment `JAVA_HOME` sudah diatur dan mengarah ke instalasi JDK yang valid.
- **Error SDK License not accepted:** Jika Gradle gagal mendownload komponen SDK karena masalah lisensi, buka terminal dan jalankan alat `sdkmanager` dari Android SDK:
  ```bash
  %ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat --licenses
  ```
  Kemudian tekan `y` untuk menyetujui semua lisensi.
- **Aplikasi tidak terinstal di HP (installDebug error):** Pastikan layar HP tidak terkunci saat proses instalasi, dan perhatikan layar HP jika muncul popup peringatan instalasi via USB yang meminta konfirmasi.
- **Error "adb is not recognized":** Ini berarti alat ADB belum ditambahkan ke *Environment Variables* PATH. Anda bisa menjalankan ADB menggunakan path lengkapnya. Contoh (di PowerShell):
  ```powershell
  & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
  ```
- **Melihat Log (Logcat):** Untuk melihat log error aplikasi, gunakan perintah ADB:
  ```bash
  adb logcat -s "CameraManager" "BleManager"
  ```
