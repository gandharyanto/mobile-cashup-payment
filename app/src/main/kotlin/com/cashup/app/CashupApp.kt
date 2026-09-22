package com.cashup.app

import android.app.Application
import com.cashup.app.di.AppContainer
import com.cashup.feature.cardpayment.CardPaymentDependencies
import com.cashup.feature.cardpayment.CardPaymentDependenciesOwner

class CashupApp : Application(), CardPaymentDependenciesOwner {
    lateinit var container: AppContainer
        private set

    override val cardPaymentDependencies: CardPaymentDependencies
        get() = container

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
