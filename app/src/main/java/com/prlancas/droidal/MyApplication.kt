package com.prlancas.droidal

import android.app.Application
import com.prlancas.droidal.config.Config

class MyApplication: Application() {

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
    }
}
