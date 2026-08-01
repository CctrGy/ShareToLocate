package com.sharetolocate.app

import android.app.Application
import org.osmdroid.config.Configuration

class ShareToLocateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = cacheDir
        Configuration.getInstance().osmdroidTileCache = java.io.File(cacheDir, "tiles")
    }
}
