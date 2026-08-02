package com.redouaneinstall.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // تجهيز محرك التحميل في الخلفية من أول تشغيل
        appScope.launch {
            Engine.ensureInit(this@App)
            if (Engine.ready.value) Engine.updateAsync(this@App)
        }
    }
}
