package no.stink.bydshare

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Connection + auth settings for reaching the car. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(applicationContext)
        setContentView(R.layout.activity_settings)

        val baseUrl = findViewById<EditText>(R.id.baseUrl)
        val edgeType = findViewById<Spinner>(R.id.edgeType)
        val cfBox = findViewById<View>(R.id.cfBox)
        val cfId = findViewById<EditText>(R.id.cfClientId)
        val cfSecret = findViewById<EditText>(R.id.cfClientSecret)
        val accessCode = findViewById<EditText>(R.id.deviceToken)
        val noAuth = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swNoAuth)
        val authBox = findViewById<View>(R.id.authBox)

        noAuth.isChecked = Settings.noAuth
        fun refreshAuth() { authBox.visibility = if (noAuth.isChecked) View.GONE else View.VISIBLE }
        noAuth.setOnCheckedChangeListener { _, _ -> refreshAuth() }
        refreshAuth()

        val edgeLabels = listOf("None", "Cloudflare service token")
        edgeType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, edgeLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        edgeType.setSelection(if (Settings.edgeAuth == Settings.EdgeAuthType.CLOUDFLARE) 1 else 0)
        fun refreshCf() {
            cfBox.visibility = if (edgeType.selectedItemPosition == 1) View.VISIBLE else View.GONE
        }
        edgeType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshCf()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        baseUrl.setText(Settings.baseUrl)
        cfId.setText(Settings.cfClientId)
        cfSecret.setText(Settings.cfClientSecret)
        accessCode.setText(Settings.accessCode)
        refreshCf()

        findViewById<Button>(R.id.save).setOnClickListener {
            Settings.noAuth = noAuth.isChecked
            Settings.baseUrl = baseUrl.text.toString()
            Settings.edgeAuth = if (edgeType.selectedItemPosition == 1)
                Settings.EdgeAuthType.CLOUDFLARE else Settings.EdgeAuthType.NONE
            Settings.cfClientId = cfId.text.toString()
            Settings.cfClientSecret = cfSecret.text.toString()
            Settings.accessCode = accessCode.text.toString()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
