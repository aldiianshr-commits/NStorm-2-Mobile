# NStorm 2 Mobile (native)

Aplikasi **Android native** (Java + OpenGL ES 3) yang membaca dan menampilkan data asli **Naruto Shippuden: Ultimate Ninja Storm 2 versi PC** langsung di perangkat Android, dengan target utama **Xiaomi Pad 6 (Snapdragon 870, Adreno 650)**.

**Tanpa emulator.** Aplikasi ini tidak memakai Wine, Proton, Box64, FEX, DXVK, Winlator atau penerjemah CPU apa pun. Arsip dan model game dibaca oleh kode Java dan digambar dengan GPU perangkat.

> Data game **tidak** disertakan. Anda harus menyalin file game PC milik Anda sendiri.
> Versi lama berbasis Winlator (v1.0.0) tetap tersedia di branch [`winlator-legacy`](../../tree/winlator-legacy) dan rilis v1.0.0.

## Status: alpha — engine & penampil aset, belum bisa dimainkan

Kode sumber Storm 2 tidak tersedia, jadi versi native harus dibangun ulang dari nol di atas data asli. Pendekatannya sama seperti proyek *engine reimplementation* (mis. OpenMW): engine baru membaca format file aslinya.

| Bagian | Status |
|---|---|
| Arsip CRI **CPK** (TOC & ITOC, tabel @UTF terenkripsi, kompresi **CRILAYLA**) | ✅ selesai, diverifikasi byte-identik terhadap sampel CPK nyata |
| Kontainer CyberConnect2 **XFBIN** (halaman, chunk, referensi) | ✅ selesai, diuji dengan file yang ditulis xfbin_lib |
| Tekstur **NUT/NTP3** (DXT1/3/5, BC4/5, RGB565, RGBA4444, ARGB/ABGR8888) | ✅ dekode CPU + ekspor PNG |
| Model **NUD/NDP3** + kerangka **clump/coord** + material → tekstur | ✅ pose dasar (bind pose) |
| Penampil 3D (OpenGL ES 3): kamera orbit, wireframe, MSAA, skala render, batas FPS | ✅ |
| Kontrol layar sentuh & gamepad di penampil | ✅ |
| Animasi (nuccChunkAnm), efek, kamera game | ⏳ berikutnya |
| Gameplay pertarungan, AI, menu, audio (CRI ADX/ACB), video, save progres | ❌ belum dibuat |

## Unduh & instal

1. Buka **[Releases](../../releases)** dan unduh `NStorm2Mobile-v2.0.0-alpha.1.apk` (beberapa MB saja, karena tidak lagi membawa sistem Wine). Build tiap commit juga ada di tab **Actions** sebagai *artifact*.
2. Instal APK (izinkan sumber tidak dikenal). Versi ini memakai ID & kunci yang sama dengan v1, sehingga bisa diinstal menimpanya; sisa runtime Wine v1 (~2 GB) dihapus otomatis saat pertama dibuka.
3. Salin folder instalasi PC **NARUTO SHIPPUDEN Ultimate Ninja STORM 2** (berisi `NSUNS2.exe` dan folder data) ke `/storage/emulated/0/nstorm2/`.
4. Buka aplikasi → ketuk *Izin penyimpanan* → aktifkan **akses semua file**.
5. Ketuk **PINDAI DATA GAME**, lalu buka **Galeri Model 3D** atau **Jelajahi Aset**.

## Fitur

- **Folder game**: bawaan `/storage/emulated/0/nstorm2/`, bisa diganti ke folder lain (internal, kartu SD, USB). Semua arsip CPK dideteksi dari isinya (magic `CPK `), apa pun nama/ekstensinya. Data game hanya dibaca.
- **Folder save**: `/storage/emulated/0/nstorm2/saves/` berisi data engine — indeks model (`model_index.tsv`) dan ekspor (`export/`). Save progres game akan disimpan di sini begitu mode permainan ada.
- **Galeri Model 3D**: memindai semua XFBIN, mencari yang berisi clump (karakter, stage, properti), dengan pencarian nama.
- **Jelajahi Aset**: daftar seluruh isi arsip dengan filter & pencarian; isi XFBIN per chunk; penampil tekstur (zoom, alpha) dan ekspor PNG/mentah.
- **Penampil 3D**: tekstur + pencahayaan, wireframe, alpha test, sumbu Y/Z, sembunyikan/tampilkan bagian mesh, statistik FPS/segitiga.
- **Kontrol**
  - Sentuh: geser 1 jari = putar · cubit = zoom · geser 2 jari = geser kamera · ketuk 2× = reset.
  - Gamepad (USB/Bluetooth): stik kiri = putar · stik kanan = geser · LT/RT atau L1/R1 = zoom · A = wireframe · X = cahaya · Y = reset · B = kembali · Select = daftar bagian.
- **Pengaturan grafis & performa**: skala render 50–100 %, MSAA mati/2×/4×, filter tekstur, batas ukuran tekstur, batas FPS 30/60/120/bebas, jumlah thread dekode, sensitivitas sentuh/gamepad, deadzone, balik sumbu Y.

### Xiaomi Pad 6 / Adreno 650

Nilai bawaan disetel untuk Snapdragon 870: render 100 %, MSAA 4×, trilinear, 60 FPS, 4 thread dekode tekstur (sesuai 4 inti Cortex-A77). Adreno tidak mendukung tekstur DXT/S3TC di OpenGL ES, jadi tekstur didekode di CPU sekali saat model dimuat. **Turnip tidak diperlukan**: aplikasi native memakai driver GPU bawaan sistem lewat OpenGL ES 3 — driver Turnip hanya relevan untuk lapisan emulasi Vulkan/DXVK.

## Arsitektur

```
core/  (Java murni, tanpa Android — diuji dengan JUnit di CI)
  cpk/      CpkArchive, UtfTable, CriLayla     arsip CRI
  xfbin/    Xfbin, NuccChunks                  kontainer CyberConnect2
  texture/  NutTexture, BcDecoder              NTP3 + dekoder BCn
  model/    NudModel                           mesh NDP3 (strip → segitiga)
  scene/    SceneModel, Mat4                   kerangka → matriks dunia, mesh + material
  vfs/      GameLibrary                        pemindai folder game & pembaca aset
app/   (Android)
  MainActivity, ModelGalleryActivity, AssetBrowserActivity, XfbinActivity, TextureActivity
  ModelViewerActivity + viewer/ModelRenderer   OpenGL ES 3
```

### Verifikasi yang dilakukan

- Pembaca CPK dan CRILAYLA diuji terhadap arsip CPK sungguhan (termasuk yang terenkripsi) dan sampel CRILAYLA dari proyek CriFsV2Lib: semua file keluar byte-identik dengan aslinya.
- Parser XFBIN/NUD/NUT dan perakitan kerangka diuji terhadap file XFBIN yang ditulis oleh *writer* xfbin_lib (implementasi independen): urutan halaman, transform tulang, tautan material→tekstur dan piksel DXT1 cocok dengan nilai yang diharapkan (`core/src/test`).
- **Belum diuji dengan file Storm 2 asli** maupun di perangkat fisik oleh pembuat build ini, karena keduanya tidak tersedia di lingkungan build. Jika ada model yang gagal dimuat, layar pemuatan menampilkan pesan error — mohon laporkan di *Issues* beserta nama filenya.

## Membangun dari kode sumber

```bash
git clone https://github.com/aldiianshr-commits/NStorm-2-Mobile
cd NStorm-2-Mobile
./gradlew :core:test assembleRelease   # butuh Android SDK 34 & JDK 17
```

CI (`.github/workflows/build-apk.yml`) menjalankan unit test engine, membangun & menandatangani APK, mengunggahnya sebagai artifact, dan membuat/memperbarui GitHub Release `v<versionName>` dari setiap build sukses di `main`. Jika build gagal, log disimpan di branch `ci-logs`.

### Keamanan kunci tanda tangan

APK ditandatangani dengan `app/nstorm2-release.jks` yang sengaja publik (sandi `nstorm2mobile`) agar setiap build bisa diinstal menimpa versi sebelumnya. Unduh APK hanya dari Releases/Actions repo ini.

## Kredit

Format file dipelajari dari proyek terbuka berikut (kode NStorm 2 ditulis ulang dari nol dalam Java):

- [xfbin_lib](https://github.com/SutandoTsukai181/xfbin_lib) & [cc2_xfbin_blender](https://github.com/SutandoTsukai181/cc2_xfbin_blender) — SutandoTsukai181 (MIT)
- [NUCC 010 Template Library](https://github.com/KojoBailey/NUCC-010-Template-Library) — KojoBailey
- [CriFsV2Lib](https://github.com/Sewer56/CriFsV2Lib) — Sewer56; [CriPakTools](https://github.com/esperknight/CriPakTools)
- [Smash Forge](https://github.com/jam1garner/Smash-Forge) — format NUT/NUD Namco

NARUTO SHIPPUDEN: Ultimate Ninja STORM 2 © Masashi Kishimoto / BANDAI NAMCO Entertainment / CyberConnect2. Proyek ini tidak berafiliasi dengan mereka dan tidak mendistribusikan data game.
