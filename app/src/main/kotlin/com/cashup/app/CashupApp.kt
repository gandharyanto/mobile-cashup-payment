package com.cashup.app

import android.app.Application
import com.cashup.app.di.AppContainer
import com.cashup.feature.cardpayment.CardPaymentDependencies
import com.cashup.feature.cardpayment.CardPaymentDependenciesOwner
import com.cashup.feature.qris.QrisDependencies
import com.cashup.feature.qris.QrisDependenciesOwner

class CashupApp : Application(), CardPaymentDependenciesOwner, QrisDependenciesOwner {
    lateinit var container: AppContainer
        private set

    override val cardPaymentDependencies: CardPaymentDependencies
        get() = container

    override val qrisDependencies: QrisDependencies
        get() = container.qrisDependencies

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
