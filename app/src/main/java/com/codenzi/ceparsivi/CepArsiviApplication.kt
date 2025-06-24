package com.codenzi.ceparsivi

import android.app.Application

class CepArsiviApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val theme = ThemeManager.getTheme(this)
        ThemeManager.applyTheme(theme)
    }
}