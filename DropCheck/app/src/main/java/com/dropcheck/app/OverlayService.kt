package com.dropcheck.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var button: View? = null
    private var card: View? = null
    @Volatile private var last: Bitmap? = null
    private var lastTs = 0L
    private val main = Handler(Looper.getMainLooper())
    private val bg = HandlerThread("dropcheck-capture").apply { start() }
    private val bgHandler = Handler(bg.looper)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (projection != null) return START_NOT_STICKY
        // Must be foreground (type mediaProjection) before the projection is created.
        val ch = "dropcheck"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "DropCheck", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("DropCheck is running")
            .setContentText("Tap the floating € button on an offer")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        @Suppress("DEPRECATION")
        val data = i?.getParcelableExtra<Intent>("data")
        val code = i?.getIntExtra("code", 0) ?: 0
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startCapture(code, data)
        showButton()
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)
        val (w, h, dpi) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), resources.displayMetrics.densityDpi)
        } else Triple(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels, resources.displayMetrics.densityDpi)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTs > 400) { lastTs = now; last = toBitmap(img, w, h) }
            } finally { img.close() }
        }, bgHandler)
        display = mp.createVirtualDisplay("dropcheck", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bgHandler)
    }

    private fun toBitmap(img: Image, w: Int, h: Int): Bitmap {
        val p = img.planes[0]
        val padding = p.rowStride - p.pixelStride * w
        val full = Bitmap.createBitmap(w + padding / p.pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(full, 0, 0, w, h)
    }

    private fun showButton() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "€"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE6171717.toInt()) }
        }
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 24; y = 500
        }
        var sx = 0; var sy = 0; var dx = 0f; var dy = 0f; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; dx = e.rawX; dy = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dx) > 12 || abs(e.rawY - dy) > 12) moved = true
                    if (moved) { lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) analyze()
            }
            true
        }
        wm.addView(tv, lp); button = tv
    }

    private fun analyze() {
        val bmp = last ?: return Toast.makeText(this, "No screen captured yet", Toast.LENGTH_SHORT).show()
        recognizer.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { t ->
            val offer = Analyzer.parse(t.text)
            if (offer == null) {
                showCard("Couldn’t read the offer.\nOpen the offer screen and tap again.", 0xFF333333.toInt())
            } else {
                val r = Analyzer.analyze(offer, Profile.load(this))
                val color = when (r.verdict) {
                    Verdict.ACCEPT -> 0xFF17814A; Verdict.REVIEW -> 0xFFB27800; Verdict.REJECT -> 0xFFC0392B
                }.toInt()
                showCard("${r.verdict}   ${"%.1f".format(r.score)}/10\n" +
                    "Net ${eur(r.net)} · ${eur(r.perKm)}/km · ${eur(r.perH)}/h\n" +
                    "Read: ${eur(offer.pay)}, ${offer.pickKm}+${offer.dropKm} km, ${offer.mins.toInt()} min\n" +
                    r.reasons.joinToString("\n"), color)
            }
        }.addOnFailureListener { showCard("Text reading failed.", 0xFF333333.toInt()) }
    }

    private fun showCard(msg: String, color: Int) {
        card?.let { runCatching { wm.removeView(it) } }
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = msg; textSize = 16f; setTextColor(Color.WHITE)
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(color) }
            setOnClickListener { runCatching { wm.removeView(this) }; card = null }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = (60 * d).toInt(); x = 0 }
        wm.addView(tv, lp); card = tv
        main.postDelayed({ if (card === tv) { runCatching { wm.removeView(tv) }; card = null } }, 12000)
    }

    override fun onDestroy() {
        button?.let { runCatching { wm.removeView(it) } }
        card?.let { runCatching { wm.removeView(it) } }
        display?.release(); reader?.close()
        val mp = projection; projection = null; mp?.stop()
        recognizer.close(); bg.quitSafely()
        super.onDestroy()
    }
}
