package ai.hermes.mama.feature.browser

import android.app.Activity
import android.os.Bundle

/**
 * Activity mínima que hospeda el [AndroidWebViewDriver] bajo test (F2): la
 * vista del driver ocupa toda la pantalla para que `draw` tenga superficie
 * real y `onPageFinished`/recursos lleguen como en producción.
 */
class ControllerTestActivity : Activity() {
    lateinit var driver: AndroidWebViewDriver
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driver = AndroidWebViewDriver(this)
        setContentView(driver.webView)
    }

    override fun onDestroy() {
        driver.destroy()
        super.onDestroy()
    }
}
