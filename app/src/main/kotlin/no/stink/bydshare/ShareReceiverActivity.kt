package no.stink.bydshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The share-sheet target. Receives a shared place (ACTION_SEND text/plain, or a geo: VIEW),
 * resolves it to coordinates, and shows exactly what would be sent to the car.
 *
 * POC: nothing is sent yet. The next step wires the shown JSON to OverDrive's endpoint.
 */
class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var nameView: TextView
    private lateinit var coordsView: TextView
    private lateinit var urlView: TextView
    private lateinit var jsonView: TextView
    private lateinit var rawView: TextView
    private lateinit var copyButton: Button

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate() // a fresh share should re-resolve, not show the previous result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        statusView = findViewById(R.id.status)
        detailView = findViewById(R.id.detail)
        nameView = findViewById(R.id.name)
        coordsView = findViewById(R.id.coords)
        urlView = findViewById(R.id.resolvedUrl)
        jsonView = findViewById(R.id.json)
        rawView = findViewById(R.id.raw)
        copyButton = findViewById(R.id.copyButton)
        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }

        val shared = readSharedText(intent)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        rawView.text = shared.ifEmpty { "(nothing)" }

        if (shared.isBlank()) {
            statusView.text = getString(R.string.status_nothing)
            return
        }

        statusView.text = getString(R.string.status_resolving)
        copyButton.isEnabled = false
        lifecycleScope.launch {
            val r = MapsLinkResolver.resolve(shared, subject)
            render(r)
        }
    }

    private fun render(r: MapsLinkResolver.Resolved) {
        nameView.text = r.name ?: "—"
        urlView.text = r.resolvedUrl ?: "—"

        val detailParts = listOfNotNull(
            r.source?.let { "Source: $it" },
            r.note,
        )
        detailView.text = detailParts.joinToString("\n")
        detailView.visibility = if (detailParts.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        if (r.hasCoords) {
            statusView.text = getString(R.string.status_ready)
            coordsView.text = "%.6f, %.6f".format(java.util.Locale.US, r.lat, r.lng)
            val json = MapsLinkResolver.toCarJson(r)
            jsonView.text = json
            copyButton.isEnabled = true
            copyButton.setOnClickListener {
                copyToClipboard(json)
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        } else {
            statusView.text = r.note ?: getString(R.string.status_failed)
            coordsView.text = "—"
            jsonView.text = "—"
            copyButton.isEnabled = false
        }
    }

    private fun readSharedText(intent: Intent): String = when (intent.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        Intent.ACTION_VIEW -> intent.dataString.orEmpty()
        else -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("byd-share", text))
    }
}
