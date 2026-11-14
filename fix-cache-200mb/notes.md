# Giới hạn bộ nhớ đệm 200 MB (cho bản sau 0.6.27)
Nền: thucac-0.6.27. Chép đè 2 file:
- App.kt -> app/src/main/java/vn/thucac/reader/App.kt (OkHttp cache 150->200 MB; App là ImageLoaderFactory, DiskCache ảnh 200 MB ở cacheDir/image_cache)
- ComicReaderScreen.kt -> app/src/main/java/vn/thucac/reader/ui/ComicReaderScreen.kt (bản 0.6.27 + loader truyện tranh dùng chung cache ảnh)
Cả hai cache tự xoá phần dùng lâu nhất (LRU) khi vượt. Không đụng chương/bản dịch/audio.
Nhớ tăng versionCode/versionName khi build.
