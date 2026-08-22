package no.stink.bydshare

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Launcher screen. The app's real job runs from the share sheet (ShareReceiverActivity). */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.version).text =
            getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
    }
}
