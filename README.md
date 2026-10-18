# NStorm 2 Mobile

Aplikasi Android (APK) untuk menjalankan **Naruto Shippuden: Ultimate Ninja Storm 2 versi PC (NSUNS2.exe)** di perangkat Android ARM64, dengan target utama **Xiaomi Pad 6 (Snapdragon 870, Adreno 650)** dan driver **Mesa Turnip**.

> Game **tidak** disertakan. Anda harus menyalin file game PC milik Anda sendiri ke perangkat.

## Apa ini sebenarnya

Kode sumber game tidak tersedia, jadi "port" yang realistis bukan kompilasi ulang, melainkan **lapisan kompatibilitas** yang menjalankan file `.exe` PC apa adanya:

```
NSUNS2.exe (x86-64, Direct3D 11, XInput)
   │  Windows API ............ Wine / Proton 9.0
   │  CPU x86-64 → ARM64 ..... FEXCore (ARM64EC, bawaan) atau Box64 (cadangan)
   │  Direct3D 11 → Vulkan ... DXVK (2.3.1 GPL-Async ARM64EC / 2.4.1 GPL-Async)
   │  Vulkan → Adreno 650 .... Mesa Turnip 25.x (Adrenotools) atau driver Qualcomm
   ▼
Android (X server internal, audio ALSA/PulseAudio, gamepad XInput virtual)
```

NStorm 2 Mobile dibangun di atas **[Winlator Cmod](https://github.com/coffincolors/winlator)** (lisensi MIT) dan menambahkan peluncur khusus satu game:

| Fitur | Implementasi |
|---|---|
| Folder game | Bawaan `/storage/emulated/0/nstorm2/`, bisa diganti (penyimpanan internal / SD / USB). `NSUNS2.exe` dicari otomatis sampai 4 tingkat subfolder. Folder dipetakan ke drive `D:` di Wine. |
| Save game | Disinkronkan ke `/storage/emulated/0/nstorm2/saves/` sebelum game dibuka dan setelah ditutup (Steam `userdata\<id>\543870`, `Documents`, `AppData\Roaming`). Cadangan otomatis di `saves/.backup/` (10 terakhir). |
| Kontrol sentuh | Profil gamepad Xbox virtual (stik kiri/kanan, D-pad, A/B/X/Y, LB/RB/LT/RT, L3/R3, BACK/START), bisa diedit; transparansi diatur. Game menampilkan prompt tombol Xbox, jadi tata letak mengikutinya. |
| Gamepad fisik | USB/Bluetooth otomatis menjadi pemain 1 (XInput). Mode kontrol: otomatis / sentuh / gamepad. |
| Grafis | Pilihan driver (Turnip 25.3.0 R3 Auto/GMEM, Turnip 25.1.0, Qualcomm), versi DXVK, kompilasi shader async, resolusi 960×600 – 1920×1200, regangkan layar, sinkronisasi frame, mode sysmem Turnip, batas memori video. |
| Performa | Batas FPS (30/45/60/bebas), penghitung FPS, afinitas CPU ke inti Cortex-A77 (4–7) Snapdragon 870, preset Box64, driver audio. |
| Mesin | ARM64EC + FEXCore (bawaan, lebih cepat) atau x86-64 + Box64. Masing-masing memakai container Wine sendiri; save dibagi lewat folder `saves/`. |
| Steam | Mode "Melalui klien Steam untuk Windows" (Steam dijalankan dulu, lalu game). Tidak ada crack/bypass DRM. |
| Mode lanjutan | Antarmuka Winlator Cmod lengkap tetap tersedia. |

## Unduh & instal

1. Buka **[Releases](../../releases)** dan unduh `NStorm2Mobile-v<versi>-<commit>.apk` (±700 MB, karena memuat sistem Wine, Proton, DXVK, dan driver Turnip). Build dari setiap commit juga tersedia sebagai *artifact* di tab **Actions**.
2. Izinkan instalasi dari sumber tidak dikenal untuk browser/file manager Anda, lalu buka APK.
   - Android 14+ dapat menampilkan peringatan "dibuat untuk versi Android lama". Ini wajar: `targetSdk` sengaja 28 agar Wine/Box64 boleh mengeksekusi program dari folder data aplikasi.
   - Jika Play Protect memperingatkan, pilih *Instal saja*.
3. Buka **NStorm 2 Mobile** → ketuk *Izin penyimpanan* → pilih **Akses semua file**.
4. Tunggu pemasangan sistem pertama kali (±1–3 menit, hanya sekali).
5. Salin isi folder instalasi PC **NARUTO SHIPPUDEN Ultimate Ninja STORM 2** (±8 GB) ke `/storage/emulated/0/nstorm2/` sehingga ada `/storage/emulated/0/nstorm2/NSUNS2.exe`.
6. Kembali ke aplikasi, status *File game* menjadi hijau → ketuk **MAINKAN**.

Saat bermain: **ketuk 4 jari** atau tombol Kembali untuk membuka menu (Keluar, keyboard, ganti profil kontrol). Pembukaan pertama lebih lama karena shader dikompilasi.

## Setelan yang disarankan untuk Xiaomi Pad 6

| Setelan | Nilai |
|---|---|
| Mesin | ARM64EC + FEXCore |
| Driver GPU | Turnip 25.3.0 R3 Auto |
| DXVK | Otomatis (2.3.1 GPL-Async ARM64EC) + shader asinkron aktif |
| Resolusi | 1280×800 (layar Pad 6 16:10) |
| Batas FPS | 60 (pilih 30 jika perangkat panas) |
| Inti CPU | 4–7 |
| Sistem | Aktifkan *Game Turbo*/mode performa HyperOS |

Jika muncul layar hitam/crash: coba DXVK 2.4.1 GPL-Async + mesin x86-64, atau aktifkan *Mode render sysmem Turnip*, atau driver Turnip 25.1.0.

## Batasan teknis (jujur)

- **Versi Steam butuh klien Steam.** `NSUNS2.exe` versi Steam memanggil Steamworks dan akan mencoba membuka ulang lewat Steam; tanpa klien Steam yang berjalan, game biasanya langsung tertutup. NStorm 2 mendukung menjalankan **Steam untuk Windows di dalam Wine** (taruh `SteamSetup.exe` resmi di folder game → *Jalankan Program Lain* → login → ubah *Cara menjalankan* ke *Melalui klien Steam*). Klien Steam berat di emulasi dan bisa lambat/tidak stabil. Aplikasi ini **tidak** menyertakan crack/emulator Steam.
- **Belum diuji di perangkat fisik oleh pembuat build ini.** Yang sudah diverifikasi: kompilasi APK di CI, isi APK, tanda tangan, dan bahwa setiap path sistem sudah dialihkan ke nama paket baru. Kompatibilitas aktual Storm 2 (FPS, stabilitas, audio/video cutscene) bergantung pada Wine/FEX/DXVK/Turnip dan perlu diuji di perangkat. Silakan laporkan hasil di *Issues*.
- **Performa:** CPU x86-64 diemulasikan. Snapdragon 870 kemungkinan memberi 30–60 FPS tergantung adegan; jurus besar/efek berat bisa turun.
- Online/jaringan tidak didukung secara khusus. Mod/trainer tidak didukung.
- Penyimpanan bersama Android (FUSE) lebih lambat dari penyimpanan internal aplikasi; waktu muat bisa lebih lama dari PC.
- Ukuran APK besar (±700 MB) karena seluruh sistem Linux/Wine dibundel agar bisa offline.

## Struktur folder di perangkat

```
/storage/emulated/0/nstorm2/
├── NSUNS2.exe, data game …      ← file game PC Anda (drive D: di Wine)
├── TARUH-FILE-GAME-DI-SINI.txt
└── saves/
    ├── steam_userdata/<id>/543870/…   ← save Steam
    ├── Documents/ , AppData/Roaming/
    ├── .backup/<tanggal-jam>/         ← cadangan sebelum ditimpa
    └── BACA-SAYA.txt
```

## Membangun dari kode sumber

Build dijalankan oleh GitHub Actions (`.github/workflows/build-apk.yml`):

1. Checkout repo + submodule OpenXR-SDK.
2. Ambil aset Winlator Cmod pada commit yang dipatok dan APK rilis Cmod v13.1.1, verifikasi SHA-256 (`tools/upstream-assets.lock.json`).
3. `tools/prepare_assets.py` mengganti `com.winlator.cmod` → `com.nstorm2mobile` di dalam rootfs, Proton, dan driver. Kedua nama sama-sama 17 byte, jadi biner ELF tetap valid.
4. `./gradlew assembleRelease` (AGP 8.8, NDK 27.0.12077973, JDK 17).

Secara lokal (Linux, Android SDK + NDK terpasang, `xz` dan `zstd` tersedia):

```bash
git clone --recursive https://github.com/aldiianshr-commits/NStorm-2-Mobile
cd NStorm-2-Mobile
# siapkan upstream-src (sparse checkout app/src/main/assets di commit terkunci) dan upstream-release.apk
python3 tools/prepare_assets.py --upstream upstream-src --apk upstream-release.apk
./gradlew assembleRelease
```

Setiap build sukses di branch `main` membuat/memperbarui GitHub Release `v<versionName>`; naikkan `versionName` di `app/build.gradle` untuk rilis baru. Jika build gagal, log Gradle disimpan di branch `ci-logs`.

### Keamanan kunci tanda tangan

APK ditandatangani dengan `app/nstorm2-release.jks` yang **sengaja disimpan publik** di repo (sandi `nstorm2mobile`) agar setiap build CI bisa diinstal menimpa versi sebelumnya. Artinya siapa pun bisa membuat APK dengan tanda tangan yang sama; unduh APK hanya dari Releases/Actions repo ini.

## Kode yang ditambahkan proyek ini

- `app/src/main/java/com/winlator/cmod/nstorm2/` — peluncur, setelan, deteksi game, sinkronisasi save.
- `app/src/main/java/com/winlator/cmod/core/AppPaths.java` — path data yang sebelumnya di-hardcode ke `com.winlator.cmod`.
- `app/src/main/java/com/winlator/cmod/contents/AssetContentsInstaller.java` — installer konten yang bisa dipakai di luar `MainActivity`.
- `app/src/main/assets/inputcontrols/profiles/controls-50.icp` — tata letak kontrol sentuh Storm 2.
- `tools/` — persiapan & penggantian nama paket di aset.

## Kredit & lisensi

- NStorm 2 Mobile dan Winlator Cmod: MIT (lihat `LICENSE`).
- [Winlator](https://github.com/brunodev85/winlator) oleh brunodev85; [Winlator Cmod](https://github.com/coffincolors/winlator) oleh coffincolors & Pipetto-crypto.
- Wine (LGPL-2.1), Proton (Valve), Box64 (MIT, ptitSeb), FEX-Emu (MIT), DXVK (zlib), VKD3D-Proton (LGPL-2.1), Mesa Turnip (MIT), PRoot (GPL-2.0), OpenXR SDK (Apache-2.0), PulseAudio (LGPL-2.1).
- NARUTO SHIPPUDEN: Ultimate Ninja STORM 2 adalah milik BANDAI NAMCO Entertainment / CyberConnect2 / Masashi Kishimoto. Proyek ini tidak berafiliasi dengan mereka dan tidak mendistribusikan file game.
