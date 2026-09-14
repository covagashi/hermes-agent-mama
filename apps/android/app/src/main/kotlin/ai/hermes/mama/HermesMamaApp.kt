package ai.hermes.mama

import android.app.Application
import timber.log.Timber

class HermesMamaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // En el flavor mama (release) sólo se registra WARN+ y redactando
        // cookies/tickets/cuerpos; ese árbol llega con el endurecimiento de J2.
    }
}
