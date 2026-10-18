## NStorm 2 Mobile — versi native (alpha)

Aplikasi Android **native** (Java + OpenGL ES 3) yang membaca data asli Naruto Shippuden: Ultimate Ninja Storm 2 versi PC. **Tanpa emulator**: tidak ada Wine, Box64, FEX, DXVK atau Winlator.

### Yang sudah bisa
- Membaca arsip CRI CPK (termasuk tabel terenkripsi & kompresi CRILAYLA) dan kontainer XFBIN.
- Galeri model 3D karakter/stage (kerangka pose dasar, mesh, tekstur, pencahayaan) dengan kontrol sentuh & gamepad.
- Penampil tekstur NUT (DXT1/3/5, BC4/5, RGBA) + ekspor PNG.
- Pengaturan grafis/performa (skala render, MSAA, filter, batas FPS, thread dekode) dengan bawaan untuk Xiaomi Pad 6.

### Belum bisa
- **Belum bisa dimainkan**: gameplay, animasi, AI, menu, audio dan save progres belum dibuat.
- Belum diuji dengan file Storm 2 asli di perangkat fisik — mohon laporkan hasilnya di Issues.

### Cara pakai
1. Instal APK (bisa menimpa v1.0.0; sisa runtime Wine lama dihapus otomatis).
2. Salin folder instalasi PC game ke `/storage/emulated/0/nstorm2/`.
3. Buka aplikasi → beri izin akses semua file → **Pindai Data Game** → **Galeri Model 3D**.

Data engine & ekspor disimpan di `/storage/emulated/0/nstorm2/saves/`. Cocokkan SHA-256 APK dengan file `.sha256`.
