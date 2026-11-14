package vn.thucac.reader

import android.app.Application
import android.webkit.WebSettings
import okhttp3.Cache
import okhttp3.OkHttpClient
import vn.thucac.reader.data.Account
import vn.thucac.reader.data.AppUpdate
import vn.thucac.reader.data.ChapterCache
import vn.thucac.reader.data.Downloader
import vn.thucac.reader.data.TtsPrerender
import vn.thucac.reader.data.ImageOcr
import vn.thucac.reader.data.Library
import vn.thucac.reader.data.ReadingStats
import vn.thucac.reader.data.Settings
import vn.thucac.reader.data.SourceRepo
import vn.thucac.reader.data.TranslateService
import vn.thucac.reader.data.TtsController
import vn.thucac.reader.data.UpdateChecker
import vn.thucac.reader.data.WebViewCookieInterceptor
import java.io.File
import java.util.concurrent.TimeUnit

class App : Application(), coil.ImageLoaderFactory {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cache(Cache(File(cacheDir, "http"), CACHE_LIMIT))
            .addInterceptor(WebViewCookieInterceptor { userAgent })
            .build()
    }

    val settings: Settings by lazy { Settings(this) }
    val library: Library by lazy { Library(this) }
    val sources: SourceRepo by lazy { SourceRepo(this) }
    val translator: TranslateService by lazy { TranslateService(this) }
    val chapters: ChapterCache by lazy { ChapterCache(this) }
    val account: Account by lazy { Account(this) }
    val stats: ReadingStats by lazy { ReadingStats(this) }
    val tts: TtsController by lazy { TtsController(this) }
    val downloader: Downloader by lazy { Downloader(this) }
    val prerender: TtsPrerender by lazy { TtsPrerender(this) }
    val ocr: ImageOcr by lazy { ImageOcr(this) }
    val updater: UpdateChecker by lazy { UpdateChecker(this) }
    val appUpdate: AppUpdate by lazy { AppUpdate(this) }
    val prefs: vn.thucac.reader.data.ExtraPrefs by lazy { vn.thucac.reader.data.ExtraPrefs(this) }
    val bookmarks: vn.thucac.reader.data.Bookmarks by lazy { vn.thucac.reader.data.Bookmarks(this) }
    val batch: vn.thucac.reader.data.BatchTranslator by lazy { vn.thucac.reader.data.BatchTranslator(this) }

    val userAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(this) }
            .getOrDefault(
                "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
    }

    // Ảnh (bìa, ảnh plugin, trang truyện tranh): tối đa 200 MB, đầy thì Coil tự xoá ảnh dùng lâu nhất.
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient(http)
        .diskCache {
            coil.disk.DiskCache.Builder()
                .directory(File(cacheDir, "image_cache"))
                .maxSizeBytes(CACHE_LIMIT)
                .build()
        }
        .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        translator.reloadAsync()
        sources.seedDefaultsIfNeeded()
        updater.reschedule()
        appUpdate.scheduleDaily()
        appUpdate.checkOnLaunch()
    }

    companion object {
        /** Giới hạn cho bộ nhớ đệm Hình ảnh và Dữ liệu mạng (mỗi loại). */
        const val CACHE_LIMIT = 200L * 1024 * 1024

        lateinit var instance: App
            private set
    }
}
