# HD Story - Anti-Blur Social Media Story Optimizer

Aplikasi Android native untuk mengoptimalkan foto dan video sebelum diunggah ke WhatsApp Status, Instagram Story/Reels, dan TikTok agar terhindar dari kompresi agresif platform sosial media.

---

## 🔬 Mengapa Story / Video Sering Pecah & Buram?

1. **4K & High-Megapixel Trap:**
   - Mengunggah video 4K (3840x2160) atau foto 48-108MP memicu algoritma kompresi downscaling cepat (*bilinear*) di server Instagram/WhatsApp. Server memangkas resolusi secara kasar sehingga hasil akhir menjadi pecah dan berbayang.
   - **Solusi HD Story:** Memotong dan mengubah skala tepat ke **1080x1920 (rasio 9:16)** menggunakan filter berkualitas tinggi sebelum diunggah.

2. **60 FPS Bitrate Starvation Trap:**
   - Instagram dan WhatsApp menerapkan batas bitrate total (misal ~4 Mbps di Instagram, ~2-3 Mbps di WhatsApp).
   - Pada **60 FPS**, jatah bit per frame dibagi 2x lipat lebih sedikit dibanding **30 FPS**. Akibatnya saat ada gerakan, video langsung mengalami artefak kotak-kotak (*macroblocking*).
   - **Solusi HD Story:** Mengunci framerate video ke **30 FPS** stabil dengan alokasi bitrate maksimal per frame.

3. **WhatsApp 16MB Hard Cap:**
   - WhatsApp Status memiliki batas keras file sebesar ~16 MB.
   - Jika video melebihi 16 MB, server WhatsApp akan melakukan transcode brutal dan memangkas ukuran file menjadi ~2-3 MB saja (sangat buram).
   - **Solusi HD Story:** Menghitung bitrate video secara dinamis berdasarkan durasi agar ukuran file akhir tetap di kisaran **12 - 14.2 MB** (lolos tanpa memicu re-encode berat WhatsApp).

4. **Edge Softening pada Kompresi JPEG:**
   - Foto yang diunggah ke Instagram / WhatsApp akan dikompresi ulang dengan kuantisasi JPEG.
   - **Solusi HD Story:** Menerapkan filter *Smart Unsharp Mask* (peningkatan mikrokontras pada tepian objek) sehingga saat terkena kompresi JPEG sosial media, detail ketajaman tetap utuh.

---

## 🚀 Fitur Aplikasi

- **Preset Siap Pakai:**
  - `Instagram (Story & Reels)`: 1080x1920, 30fps, AVC High Profile 4.1, 4.5 Mbps.
  - `WhatsApp Status HD`: 1080x1920, kalkulasi bitrate dinamis aman di bawah 15 MB.
  - `TikTok HD`: 1080x1920, 8.5 Mbps untuk ketajaman gerakan tinggi.
- **Smart Edge Sharpening:** Filter ketajaman adaptif untuk foto.
- **Hardware-Accelerated Video Transformer:** Menggunakan AndroidX `Media3 Transformer` berbasis encoder hardware bawaan HP.
- **Direct Share & Save:** Simpan langsung ke Galeri atau kirim langsung ke aplikasi sosial media target.

---

## 🛠️ Struktur Project

```
hd-story/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/hdstory/app/
│   │   │   ├── MainActivity.kt
│   │   │   ├── engine/
│   │   │   │   ├── ImageOptimizer.kt
│   │   │   │   └── VideoOptimizer.kt
│   │   │   ├── model/
│   │   │   │   └── PlatformConfig.kt
│   │   │   └── utils/
│   │   │       └── FileUtils.kt
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       └── values/{colors,strings,themes}.xml
│   └── build.gradle.kts
├── .github/workflows/
│   └── android.yml          # GitHub Actions Auto-Build APK
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

---

## 📦 Cara Build Menjadi File `.apk`

### Opsi 1: Otomatis via GitHub Actions (Rekomendasi)
Karena STB memiliki RAM 2GB, build Gradle di cloud runner GitHub sangat cepat dan gratis:
1. Buat repository baru di GitHub (misal `hd-story`).
2. Push folder project ini ke repository:
   ```bash
   cd /home/hermes/projects/hd-story
   git init
   git add .
   git commit -m "feat: initial HD Story app"
   git branch -M main
   git remote add origin https://github.com/<username>/<repo>.git
   git push -u origin main
   ```
3. Buka tab **Actions** di GitHub. Pipeline akan otomatis mengompilasi APK dan menghasilkan artifact file `HDStory-Debug-APK` siap unduh.

### Opsi 2: Build Lokal di PC / Laptop
Buka folder `/home/hermes/projects/hd-story` di **Android Studio**, lalu klik menu:
`Build > Build Bundle(s) / APK(s) > Build APK(s)`.
