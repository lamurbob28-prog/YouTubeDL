package dev.lamurbob.youtubedl

import android.app.Application

class YoutubeDlApp : Application() {
    override fun onCreate() {
        super.onCreate()

        DownloadStore.initialize(this)
    }
}
