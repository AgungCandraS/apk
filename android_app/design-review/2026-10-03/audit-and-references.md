# Audit UI dan arah untuk diskusi — 03 Oktober 2026

Status awal: eksplorasi untuk diskusi. Setelah papan ini disampaikan, user memilih **B — Pearl + Cobalt**. Implementasi native berikutnya dicatat pada `../../anti-slop/audit-007-2026-10-03.md`; screenshot hasil ada di [galeri native](native/index.html). Isi audit di bawah tetap menjadi catatan eksplorasi sebelum keputusan tersebut.

[Buka perbandingan interaktif](directions.html). Tiga konsep menggunakan konten dan fungsi aplikasi yang ada. Data perangkat dan status pada konsep adalah contoh. Font disimpan lokal dengan lisensi OFL, sehingga papan dapat dibuka tanpa mengambil font dari internet.

Preview lokal berjalan pada [http://127.0.0.1:8092/directions.html](http://127.0.0.1:8092/directions.html), hanya terikat ke loopback. Jika proses sudah berhenti, jalankan `python -m http.server 8092 --bind 127.0.0.1` dari folder dokumen ini. File HTML juga bisa dibuka langsung.

## Batas bukti

Ditinjau: semua layout aplikasi (`activity_main.xml`, `item_ble_device.xml`, `item_notif_log.xml`), kedua palet dan tema, dimensi, menu navigasi, ikon utama, renderer kaca, serta handler UI dan callback koneksi/pengiriman pada `MainActivity.kt` dan `BleManager.kt`. Screenshot native versi saat ini dipakai untuk menilai komposisi Beranda terang dan Pengaturan gelap. Screenshot tambahan dan pemeriksaan layout dari putaran sebelumnya menjadi konteks; bukan pengujian ulang seluruh perangkat pada putaran ini.

Emulator tidak menyediakan adapter Bluetooth. Keberhasilan kirim ke ESP32 fisik belum diverifikasi. Diagnosis hierarki dan karakter visual merupakan penilaian desain, bukan hasil studi pengguna atau klaim kenaikan metrik.

## Temuan menurut dampak

| Prioritas | Area | Bukti/masalah saat ini | Usulan |
| --- | --- | --- | --- |
| Tinggi | Feedback pengiriman | Handler pesan, waktu, cuaca, dan navigasi langsung membuat log/toast “terkirim” setelah `sendData()`. Fungsi tersebut mengantrekan data saat terputus. `onDataSent()` pada Activity kosong. | Beri keadaan “Diantrekan”, “Mengirim”, “Terkirim”, “Gagal”; jangan menyebut sudah tampil di OLED sebelum ACK. Ini kebutuhan UX untuk implementasi berikutnya. |
| Tinggi | Alur koneksi | Beranda memiliki status tanpa tindakan langsung; scan/reconnect dan daftar perangkat ada di bagian bawah Pengaturan. | “Cari perangkat” pada panel pertama Beranda; buka permukaan detail koneksi untuk scan, sambung ulang, putuskan, dan lupa perangkat. |
| Tinggi | Arsitektur layar | Beranda memuat tujuh panel: koneksi, filter, log, pesan uji, waktu, cuaca, navigasi. Fungsi rutin dan diagnosis bercampur. | Tiga tab: Beranda, Alat, Pengaturan. Semua fungsi tetap memiliki tempat. |
| Menengah | Hierarki panel | Sebagian besar panel memakai radius 24 dp, padding 20–24 dp dan penekanan tepi serupa. | Panel koneksi paling menonjol; filter dan aktivitas berupa kelompok lebih tenang; detail teknis memakai pengungkapan bertahap. |
| Menengah | Peran warna | Light dan dark sama-sama dominan hijau untuk teks/aksi/material/status sehat. | Warna netral untuk canvas dan teks, satu aksen tindakan, status semantik terpisah; label dan ikon mendampingi warna. |
| Menengah | Tipografi | Font sistem sans-serif; judul 26 sp, status 24 sp, bagian 16 sp, isi 14 sp, metadata 12 sp. | Satu keluarga utama baru; hierarki berdasarkan ukuran dan bobot. Monospace hanya MAC/payload. Ukuran besar tetap membungkus dan mengikuti skala font Android. |
| Menengah | Material kaca | Kaca native sudah memakai backdrop statis yang diburamkan; lipatan diagonal dan kontur ikut bersaing dengan isi. Banyak garis dan inset menambah kepadatan. | Backdrop lebih tenang, highlight tepi lebih selektif, kedalaman berbeda untuk panel utama dan navigasi. Hindari blur mahal berulang setiap frame. |
| Menengah | Form dan kesalahan | Pesan kosong memakai toast; data payload muncul pada toast sukses beberapa alat. | Kesalahan pada field terkait, label terlihat, status pengiriman dekat tombol, detail payload dapat diperluas. |
| Menengah | Ikon | Ikon native tersedia, tetapi bobot filled dan outline bercampur pada navigasi dan modul. | Satu keluarga vector, misalnya Material Symbols Rounded outline. Penanda tab aktif konsisten; tanpa emoji. |
| Menengah | Daftar BLE | Nama, badge ESP32-C3, MAC, RSSI, dan tombol tersusun bertingkat di setiap baris. | Nama dan status mendominasi, alamat/RSSI sekunder. Status menghubungkan, tersimpan, aktif, dan scan kosong punya tindakan yang sesuai. Jangan menambah metrik perangkat yang tidak tersedia. |
| Menengah | Aktivitas | Log ditempatkan di tengah guliran Beranda, dengan kartu bertingkat. | Beranda menampilkan pratinjau singkat; riwayat penuh memiliki detail dan kontrol hapus. Keadaan kosong menjelaskan kapan log akan muncul. |
| Polish | Bahasa | “Sample”, “Terkirim”, payload, dan istilah teknis bercampur di feedback. | Bahasa Indonesia konsisten; data contoh dilabeli “Contoh”; istilah teknis di detail Alat. |
| Polish | Sistem permukaan | System bar, navbar aplikasi, input, tombol dan badge harus mengikuti palet baru. | Token untuk teks, canvas, surface, edge, aksi, disabled, focus/ripple, success/warning/error, status bar dan navigation bar. Tidak hanya mengganti warna kartu. |

Yang sudah berguna: switch satu baris dengan target sentuh besar, tombol native, dukungan tema terang/gelap, detail alat yang dapat ditutup, layout layar besar, serta penanganan teks panjang. Pemeriksaan kontras native sebelumnya lulus untuk sampel yang diuji; audit ini tidak mengklaim palet sekarang gagal kontras. Kualitas tersebut menjadi persyaratan rancangan berikutnya.

## Pilihan visual

| | A — Graphite + Ice (rekomendasi) | B — Pearl + Cobalt | C — Ink + Amber |
| --- | --- | --- | --- |
| Karakter | Pendamping perangkat yang tenang | Utilitas terang dan lapang | Panel alat yang lebih padat |
| Canvas utama | `#141A22` | `#EEF0F4` | `#1D2024` |
| Tint kaca | Graphite kebiruan | Putih / pearl | Abu hangat berasap |
| Aksen tindakan | `#8ABEFF` | `#245BC6` | `#F0B76A` |
| Teks utama | `#F2F5FA` | `#18202C` | `#F2EFE9` |
| Font | IBM Plex Sans | Plus Jakarta Sans | DM Sans |
| Metadata teknis | IBM Plex Mono | IBM Plex Mono | IBM Plex Mono |
| Susunan | Koneksi hero, filter berupa baris tenang, ringkasan kesiapan di hero | Koneksi dan kesiapan terpisah, filter dalam satu kelompok kaca | Heading instrumen, radius lebih kecil, baris lebih rapat |
| Tradeoff | Pasangan terang perlu dirancang; blur tetap perlu kontras yang stabil | Efek kaca lebih lembut; stroke dan tint penting supaya lapisan tetap terbaca | Amber perlu dipisahkan jelas dari peringatan; jangan menjadikan semua isi monospace |

A dipilih sebagai rekomendasi karena sesuai fungsi perangkat tunggal dan memisahkan peran aksi dari status. Ini keputusan desain yang saya usulkan, bukan hasil otomatis skill yang harus diikuti. Ketiga konsep sengaja memakai keluarga layout yang sama agar fungsi utama dapat dibandingkan, dengan perbedaan grouping, ritme, material, dan font. A dan C belum memiliki pasangan terang, B belum memiliki pasangan gelap.

Skala native awal yang diusulkan: judul halaman 28 sp, nama/status perangkat 22 sp, heading kelompok 18 sp, isi/label kontrol 15–16 sp, metadata 12 sp. Ukuran tersebut belum keputusan final. Radius membedakan panel utama, input, dan baris; target sentuh minimal 48 dp tetap mengikuti Android. Kaca menggunakan backdrop tenang, tint, highlight tepi dan bayangan ringan; bidang teks perlu dasar stabil agar blur tidak merusak kontras. Konsep HTML adalah eksplorasi material, bukan bukti biaya render atau hasil blur native.

## Susunan seluruh fungsi yang diusulkan

| Permukaan | Isi |
| --- | --- |
| Beranda | Identitas ESP32, koneksi dan tindakan utama, ringkasan kesiapan layanan/izin, filter WhatsApp/Telegram/SMS-Telepon/Instagram, aktivitas terbaru. |
| Detail perangkat | Scan, filter ESP32 saja, hasil dengan nama/MAC/RSSI, perangkat tersimpan, sambung ulang, putuskan, lupa perangkat. |
| Alat | Pesan uji dengan pengirim/isi, waktu dan baterai, cuaca langsung/contoh, navigasi dengan instruksi/jarak/ETA/stop; payload di detail. |
| Aktivitas | Log yang dapat dibaca, status pengiriman, isi panjang, hapus log dengan feedback yang jelas. |
| Pengaturan | Kontrol layanan, akses notifikasi Android, bantuan akses terkunci, info aplikasi, pengelolaan perangkat tersimpan. |

Keadaan yang perlu dirancang berikutnya: belum ada perangkat, Bluetooth mati/tidak tersedia, izin Bluetooth ditolak, izin notifikasi belum aktif, sedang mencari, hasil kosong, sedang menyambungkan, terhubung, terputus, reconnect, antrean kirim, gagal tulis, ACK, cuaca sedang diambil/gagal, pesan kosong, log kosong/terisi. Konsep HTML baru memperlihatkan belum terhubung, terhubung contoh, dan izin belum aktif; belum seluruh state runtime.

## Referensi lokal dan web

Lokasi yang ditemukan: `D:\Project\scrapper data\scrapper ui`. `templates.json` berisi 6.600 entri. Sumber tidak tersedia pada semua entri; klaim “trending”/“award-winning” tidak dipakai sebagai bukti kualitas.

- `mobile-fintech-vault`: hierarki aksi dan log ringkas; tidak mengambil metrik finansial atau kartu pembayaran.
- `mobile-dribbble-holographic-wallet`: tepi kaca/dock; tidak mengambil efek foil yang mengganggu keterbacaan. `source_url` tidak tersedia.
- `mobile-auth-otp`, `mobile-onboarding-flow`: fokus satu tindakan pada persiapan; bukan usulan menambah onboarding panjang.
- [Apple Home](https://www.apple.com/home-app/): kategori/status dan hubungan lapisan transparan. Screenshot resmi disimpan sebagai `references/apple-home.jpg`.
- [Nothing X di Google Play, penerbit NOTHING TECHNOLOGY](https://play.google.com/store/apps/details?id=com.nothing.smartcenter): kontrol mengikuti identitas perangkat dan fungsi dikelompokkan; screenshot kontrol resmi `references/nothing-x-2.webp`. Gambar promosi headphone tidak dianggap screenshot UI.
- [Panduan Sonos app](https://www.sonos.com/en-us/guides/sonosapp): system controls melekat pada produk/kelompok; diagram resmi `references/sonos-system.png`.

Gambar referensi adalah milik masing-masing pemilik, disimpan untuk review lokal; tidak menjadi aset aplikasi yang akan dirilis.

## Penggunaan skill

- `anti-ai-slop-design`: audit dahulu, pilihan visual terkait konteks, sumber jujur, tidak menyamakan pola populer dengan cacat. Kaca dipertahankan sesuai brief.
- `ui-ux-pro-max`: pencarian produk Smart Home/IoT dan glassmorphism memberi match terverifikasi. Hasil awal “device companion” kosong dan rekomendasi landing/conversion tidak cocok, sehingga tidak diterapkan. Palet A/B/C adalah usulan kontekstual, bukan salinan output otomatis. Native app tetap Kotlin/XML; tidak diasumsikan Compose atau React.
- `impeccable-design-polish`: audit/critique pada UI yang tersedia, lalu polish/harden papan HTML: hierarchy, grouping, copy, focus, gambar/font lokal, mobile overflow, dan kontrol simulasi. Tidak menganggap hasil QA HTML sebagai pengujian Android.

## Verifikasi papan diskusi

Browser Chromium headless: 1440, 390, dan 320 px; tidak ada overflow horizontal halaman, dan pada 320 px ketiga layar contoh juga tidak overflow horizontal. Keempat keluarga font tersedia, semua gambar referensi yang ditampilkan dimuat, tidak ada `pageerror`. Tab Alat, modal simulasi, switch, dan ketiga keadaan berhasil dioperasikan. Screenshot desktop dan mobile diperiksa visual. Pemeriksaan ini khusus artifact diskusi, bukan uji BLE atau sertifikasi aksesibilitas.

Setelah arah dipilih: tetapkan pasangan tema, skala type/spasi/radius, keluarga ikon native, dan semua state; kemudian implementasi serta verifikasi teks besar, layar kecil, kontras hasil komposisi kaca, focus/semantik, permission, service, dan alur perangkat fisik yang tersedia. Layout native dan logika feedback dibuat konkret pada tahap tersebut.

## Hal yang perlu kita putuskan

1. A, B, atau C sebagai identitas utama; boleh menggabungkan material dengan kepadatan dari arah lain.
2. Dominan mode gelap atau terang; kedua tema tetap akan mendapat pasangan yang konsisten.
3. Kaca lembut atau lebih transparan. Keterbacaan teks, status, dan input tetap menjadi batas material.

Keputusan user: **B — Pearl + Cobalt**. Implementasi memakai Plus Jakarta Sans, palet pearl/cobalt dengan pasangan tema gelap, serta Beranda / Alat / Pengaturan. Papan HTML awal tetap merupakan konsep perbandingan; [galeri native](native/index.html) memperlihatkan hasil APK.
