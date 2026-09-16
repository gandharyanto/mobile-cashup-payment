package com.cashup.app

import android.app.Application
import com.cashup.app.di.AppContainer

class CashupApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
