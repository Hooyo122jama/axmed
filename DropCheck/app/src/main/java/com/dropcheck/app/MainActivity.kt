package com.dropcheck.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val fields = linkedMapOf<String, EditText>()
    private val REQ = 42
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Profile.load(this)
        val values = mapOf("cons" to p.cons, "fuel" to p.fuel, "wear" to p.wear, "fixedM" to p.fixedM,
            "kmM" to p.kmM, "targetH" to p.targetH, "minKm" to p.minKm)
        val labels = mapOf("cons" to "Energy use per 100 km (L or kWh)", "fuel" to "Energy price (€ per L or kWh)",
            "wear" to "Wear and tyres (€ per km)", "fixedM" to "Fixed costs per month (€)",
            "kmM" to "Km driven per month", "targetH" to "Target net € per hour", "minKm" to "Minimum net € per km")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Set your costs once. Open an Uber Eats offer, then tap the floating € button to get Accept, Review or Reject."
        })
        Profile.KEYS.forEach { k ->
            root.addView(TextView(this).apply { text = labels[k]; setPadding(0, dp(14), 0, 0) })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(values[k].toString())
            }
            fields[k] = e; root.addView(e)
        }
        root.addView(Button(this).apply {
            text = "Save and start"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun start() {
        Profile.save(this, fields.mapValues { (k, e) ->
            e.text.toString().replace(',', '.').toDoubleOrNull() ?: Profile.DEFAULTS[k]!!
        })
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps”, then press Start again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ && res == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java)
                .putExtra("code", res).putExtra("data", data))
            moveTaskToBack(true)
        }
    }
}
