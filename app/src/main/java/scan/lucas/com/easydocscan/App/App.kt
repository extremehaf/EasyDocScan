package scan.lucas.com.easydocscan.App

import android.app.Application
import io.realm.Realm
import io.realm.RealmConfiguration


class App : Application() {


    private val dbNome = "document.realm"

    override fun onCreate() {
        super.onCreate()

        //Config Realm for the application
        Realm.init(this)
        val realmConfiguration = RealmConfiguration.Builder()
                .name(dbNome)
                .build()

        Realm.setDefaultConfiguration(realmConfiguration)

    }
}