package dev.lamurbob.youtubedl

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YoutubeDlApp : Application() {
    override fun onCreate() {
        super.onCreate()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                YoutubeDL.getInstance().init(this@YoutubeDlApp)
                FFmpeg.getInstance().init(this@YoutubeDlApp)
            }.onFailure { error ->
                Log.e(TAG, "Failed to initialize yt-dlp/ffmpeg", error)
            }
        }
    }

    private companion object {
        const val TAG = "YoutubeDlApp"
    }
}
