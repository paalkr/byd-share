package no.stink.bydshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The share-sheet target. Receives a shared place, resolves it to coordinates,
 * lets the user tweak the name/type, and sends it to the car (Navigate or Save to
 * Favourites) via [CarClient].
 */
class ShareReceiverActivity : AppCompatActivity() {

    // Telenav's external addFavorite only ever persists the "Normal" bucket (the
    // heart list). Home/Work are real but set through Telenav's own UI; the other
    // types (School/Gym/Daycare/Custom) accept the call but silently drop it.
    // Verified live on the car 2026-08-23 — so there is no type picker any more.
    private val favoriteType = "Normal"

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var nameView: EditText
    private lateinit var coordsView: TextView
    private lateinit var urlView: TextView
    private lateinit var jsonView: TextView
    private lateinit var rawView: TextView
    private lateinit var navigateButton: Button
    private lateinit var addRouteButton: Button
    private lateinit var saveButton: Button
    private lateinit var copyButton: Button
    private lateinit var sendResult: TextView

    private var resolved: MapsLinkResolver.Resolved? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(applicationContext)
        setContentView(R.layout.activity_share)

        statusView = findViewById(R.id.status)
        detailView = findViewById(R.id.detail)
        nameView = findViewById(R.id.name)
        coordsView = findViewById(R.id.coords)
        urlView = findViewById(R.id.resolvedUrl)
        jsonView = findViewById(R.id.json)
        rawView = findViewById(R.id.raw)
        navigateButton = findViewById(R.id.navigateButton)
        addRouteButton = findViewById(R.id.addRouteButton)
        saveButton = findViewById(R.id.saveButton)
        copyButton = findViewById(R.id.copyButton)
        sendResult = findViewById(R.id.sendResult)

        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        navigateButton.setOnClickListener { send(navigate = true, replace = true) }   // fresh route
        addRouteButton.setOnClickListener { send(navigate = true, replace = false) }  // add as stop
        saveButton.setOnClickListener { send(navigate = false) }
        setActionsEnabled(false)

        val shared = readSharedText(intent)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        rawView.text = shared.ifEmpty { "(nothing)" }

        if (shared.isBlank()) {
            statusView.text = getString(R.string.status_nothing)
            return
        }

        statusView.text = getString(R.string.status_resolving)
        lifecycleScope.launch {
            val r = MapsLinkResolver.resolve(shared, subject)
            render(r)
        }
    }

    private fun render(r: MapsLinkResolver.Resolved) {
        resolved = r
        nameView.setText(r.name ?: "")
        urlView.text = r.resolvedUrl ?: "—"

        val detailParts = listOfNotNull(r.source?.let { "Source: $it" }, r.note)
        detailView.text = detailParts.joinToString("\n")
        detailView.visibility = if (detailParts.isEmpty()) View.GONE else View.VISIBLE

        if (r.hasCoords) {
            statusView.text = getString(R.string.status_ready)
            coordsView.text = "%.6f, %.6f".format(java.util.Locale.US, r.lat, r.lng)
            jsonView.text = MapsLinkResolver.toCarJson(r)
            setActionsEnabled(true)
            copyButton.setOnClickListener {
                copyToClipboard(MapsLinkResolver.toCarJson(r))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            if (!Settings.isConfigured) {
                sendResult.text = getString(R.string.not_configured_hint)
            }
        } else {
            statusView.text = r.note ?: getString(R.string.status_failed)
            coordsView.text = "—"
            jsonView.text = "—"
            setActionsEnabled(false)
        }
    }

    private fun send(navigate: Boolean, replace: Boolean = false) {
        val r = resolved ?: return
        val lat = r.lat ?: return
        val lng = r.lng ?: return
        val name = nameView.text.toString().trim().ifEmpty { "Shared location" }

        setActionsEnabled(false)
        sendResult.text = getString(R.string.sending)
        lifecycleScope.launch {
            val result = if (navigate) CarClient.navigate(name, lat, lng, replace)
            else CarClient.addFavorite(name, lat, lng, favoriteType)
            sendResult.text = result.message
            setActionsEnabled(true)
            if (result.ok) Toast.makeText(this@ShareReceiverActivity, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        navigateButton.isEnabled = enabled
        addRouteButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        copyButton.isEnabled = enabled
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
