# Giới hạn bộ nhớ đệm 200 MB (cho bản sau 0.6.27)
Nền: thucac-0.6.27. Chép đè 2 file:
- App.kt -> app/src/main/java/vn/thucac/reader/App.kt (OkHttp cache 150->200 MB; App là ImageLoaderFactory, DiskCache ảnh 200 MB ở cacheDir/image_cache)
- ComicReaderScreen.kt -> app/src/main/java/vn/thucac/reader/ui/ComicReaderScreen.kt (bản 0.6.27 + loader truyện tranh dùng chung cache ảnh)
Cả hai cache tự xoá phần dùng lâu nhất (LRU) khi vượt. Không đụng chương/bản dịch/audio.
Nhớ tăng versionCode/versionName khi build.

## Trình bày kiểu sách (gom chung bản này)
Nền 0.6.26 (0.6.27 không đổi 2 file này). Chép đè:
- ReaderScreen.kt -> app/src/main/java/vn/thucac/reader/ui/ReaderScreen.kt
- Extras.kt -> app/src/main/java/vn/thucac/reader/data/Extras.kt (pref bookLayout, mặc định bật)
Thụt đầu dòng 2em, căn đều, cách đoạn theo cỡ chữ, tiêu đề chương to, tên chương nhỏ trên cùng, chân trang chương x/y · trang x/y (ước lượng theo màn hình) · % cả truyện. Bật/tắt trong Giao diện.

## Kéo qua chương (cùng ReaderScreen.kt)
Ở cuối chương kéo tiếp ~120dp thì sang chương sau; ở đầu chương kéo ngược thì về cuối chương trước. Không chạy khi đang nghe TTS.

## Tải trước 2 chương (cùng ReaderScreen.kt)
Đang đọc chương N: tải nguyên văn N+1, N+2 vào ChapterCache ở mọi chế độ; Convert/AI dịch trước luôn.
