package id.gerry.gbook

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Opening splash: shows the G-Book logo first (Google-app style) on pure AMOLED black,
 * animated in with a soft fade, then transitions to [MainActivity]. v0.4.1 locks the splash
 * font scale to 1.0 so oversized Android system text does not distort the brand screen.
 */
class SplashActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply { fontScale = 1f }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK

        setContentView(R.layout.splash)

        // Force the edge-to-edge status/navigation icons to stay light on the black backplate.
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        val logo = findViewById<ImageView>(R.id.splash_logo)
        logo.alpha = 0f
        logo.animate()
            .alpha(1f)
            .setDuration(700L)
            .start()

        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }, SPLASH_HOLD_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val SPLASH_HOLD_MS = 1500L
    }
}
