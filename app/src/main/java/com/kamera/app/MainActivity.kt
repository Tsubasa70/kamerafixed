package com.kamera.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity(), CameraController.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private var gl: GlPipeline? = null
    private var controller: CameraController? = null

    private var mode = CamMode.PHOTO
    private var lensType = LensType.MAIN
    private var look: LeicaLook = LeicaLook.STANDARD
    @Volatile private var shotLook: LeicaLook = LeicaLook.STANDARD
    private var flashOn = false
    private var hiRes = false
    private var recording = false
    private var surfaceReady = false
    private var report = "Henuz kamera taramasi yapilmadi."
    private var available: Set<LensType> = emptySet()
    private var lastUri: Uri? = null
    private var recorder: VideoRecorder? = null
    private var videoTarget: MediaSaver.VideoTarget? = null
    private var recStart = 0L

    private val saveExecutor = Executors.newSingleThreadExecutor()

    // UI
    private lateinit var previewView: SurfaceView
    private lateinit var previewLp: FrameLayout.LayoutParams
    private lateinit var flashBtn: TextView
    private lateinit var chipStd: TextView
    private lateinit var chipAuth: TextView
    private lateinit var chipVib: TextView
    private lateinit var infoBtn: TextView
    private lateinit var timerBox: LinearLayout
    private lateinit var timerText: TextView
    private lateinit var recDot: View
    private lateinit var hiResChip: TextView
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var shutter: ShutterView
    private lateinit var galleryThumb: ImageView
    private val lensButtons = LinkedHashMap<LensType, TextView>()
    private var screenW = 0
    private var downX = 0f
    private var downY = 0f

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) {
                    controller?.deviceOrientation = ((orientation + 45) / 90 * 90) % 360
                }
            }
        }
    }

    // ------------------------------------------------------------------ yasam dongusu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenW = resources.displayMetrics.widthPixels
        setContentView(buildUi())
        updateAllUi()

        if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQ_PERMS)
        }
        refreshThumbnail()
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        val g = GlPipeline()
        g.setLook(look)
        gl = g
        controller = CameraController(this, g, this).also { it.flashOn = flashOn }
        if (surfaceReady) g.setPreviewSurface(previewView.holder.surface)
        tryStart()
    }

    override fun onPause() {
        if (recording) stopRecording()
        orientationListener.disable()
        controller?.release()
        controller = null
        gl?.release()
        gl = null
        super.onPause()
    }

    override fun onDestroy() {
        saveExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasPermission(Manifest.permission.CAMERA)) {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                toast("Mikrofon izni yok: videolar sessiz kaydedilecek.")
            }
            tryStart()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Kamera izni gerekli")
                .setMessage("Uygulamanin calismasi icin Ayarlar > Uygulamalar > Kamera > Izinler bolumunden Kamera iznini vermelisin.")
                .setPositiveButton("Ayarlari ac") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Kapat", null)
                .show()
        }
    }

    private fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun tryStart() {
        if (hasPermission(Manifest.permission.CAMERA) && surfaceReady && controller != null) restartCamera()
    }

    private fun restartCamera(cycle: Boolean = false) {
        controller?.configure(lensType, mode, flashOn, hiRes, cycle)
    }

    // ------------------------------------------------------------------ CameraController.Listener

    override fun onDiscovered(available: Set<LensType>, report: String) {
        runOnUiThread {
            this.available = available
            this.report = report
            updateLensUi()
        }
    }

    override fun onLensActive(type: LensType, strategy: Strategy, previewSize: Size, jpegSize: Size?, hiRes: Boolean) {
        val msg = "Aktif lens: ${type.label}  |  ${strategy.describe()}" +
            (if (jpegSize != null) "  |  foto ${jpegSize.width}x${jpegSize.height}" else "  |  video ${previewSize.width}x${previewSize.height}")
        Log.i(TAG, msg)
        runOnUiThread {
            if (this.hiRes && mode == CamMode.PHOTO && !hiRes) toast("Bu lensde yuksek cozunurluk (50MP) modu sunulmuyor; standart boyut kullaniliyor.")
            toast(msg, long = true)
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, message)
        runOnUiThread { toast(message, long = true) }
    }

    override fun onPhoto(jpeg: ByteArray, orientation: Int) {
        val l = shotLook
        saveExecutor.execute {
            var data = jpeg
            if (!l.isIdentity) {
                try {
                    data = l.processJpeg(jpeg, orientation)
                } catch (t: Throwable) {
                    Log.e(TAG, "Filtre uygulanamadi, orijinal kaydediliyor", t)
                    data = jpeg
                    runOnUiThread { toast("Filtre uygulanamadi (bellek?). Orijinal foto kaydedildi.") }
                }
            }
            val uri = MediaSaver.savePhoto(this, data)
            runOnUiThread {
                if (uri != null) {
                    lastUri = uri
                    refreshThumbnail()
                } else toast("Foto kaydedilemedi!")
            }
        }
    }

    // ------------------------------------------------------------------ kayit

    private fun toggleRecording() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val c = controller ?: return
        val g = gl ?: return
        val target = MediaSaver.newVideoTarget(this)
        if (target == null) {
            toast("Video dosyasi olusturulamadi")
            return
        }
        val size = c.videoSize()
        try {
            val r = VideoRecorder(this, size.width, size.height, target.pfd.fileDescriptor, hasPermission(Manifest.permission.RECORD_AUDIO))
            val surface = r.prepare()
            r.start()
            g.startEncoding(surface)
            recorder = r
            videoTarget = target
            recording = true
            recStart = SystemClock.elapsedRealtime()
            ui.post(timerTick)
            updateAllUi()
        } catch (e: Exception) {
            Log.e(TAG, "Kayit baslatilamadi", e)
            toast("Kayit baslatilamadi: $e", long = true)
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            MediaSaver.finishVideo(this, target, false)
            recorder = null
            videoTarget = null
            recording = false
            updateAllUi()
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        ui.removeCallbacks(timerTick)
        gl?.stopEncoding()
        val ok = recorder?.stop() ?: false
        val t = videoTarget
        recorder = null
        videoTarget = null
        if (t != null) {
            MediaSaver.finishVideo(this, t, ok)
            if (ok) {
                lastUri = t.uri
                refreshThumbnail()
                toast("Video galeriye kaydedildi")
            } else toast("Kayit cok kisaydi, kaydedilmedi")
        }
        updateAllUi()
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val sec = (SystemClock.elapsedRealtime() - recStart) / 1000
            timerText.text = String.format("%02d:%02d", sec / 60, sec % 60)
            recDot.alpha = if (sec % 2 == 0L) 1f else 0.25f
            ui.postDelayed(this, 250)
        }
    }

    // ------------------------------------------------------------------ arayuz olaylari

    private fun onShutter() {
        if (mode == CamMode.PHOTO) {
            shotLook = look
            previewView.animate().alpha(0.35f).setDuration(70).withEndAction {
                previewView.animate().alpha(1f).setDuration(120).start()
            }.start()
            controller?.takePhoto()
        } else {
            toggleRecording()
        }
    }

    private fun onLensClick(t: LensType) {
        if (recording) {
            toast("Kayit sirasinda lens degistirilemez")
            return
        }
        if (t !in available) {
            toast("${t.label} lensi bu cihazda bulunamadi. (i) raporuna bak.")
            return
        }
        lensType = t
        updateLensUi()
        restartCamera()
    }

    private fun onLensLongClick(t: LensType) {
        if (recording || t !in available) return
        lensType = t
        updateLensUi()
        toast("${t.label}: sonraki acma yontemi deneniyor...")
        restartCamera(cycle = true)
    }

    private fun setMode(m: CamMode) {
        if (recording || m == mode) return
        mode = m
        updateAllUi()
        restartCamera()
    }

    private fun setLook(l: LeicaLook) {
        look = l
        gl?.setLook(l)
        updateLookUi()
    }

    private fun openGallery() {
        val u = lastUri
        try {
            if (u != null) {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, contentType(u)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
            }
        } catch (e: ActivityNotFoundException) {
            toast("Galeri acilamadi")
        }
    }

    private fun contentType(u: Uri): String = contentResolver.getType(u) ?: "image/*"

    private fun showInfo() {
        val events = controller?.recentEventsText() ?: "  (kamera henuz baslamadi)"
        val fullReport = report + "\n\nSon calisma-zamani olaylari (yontem degisimleri/hatalar):\n" + events
        AlertDialog.Builder(this)
            .setTitle("Kamera bilgisi")
            .setMessage(fullReport + "\n\nIpucu: Bir lens dugmesine UZUN BASARAK o lens icin sonraki acma yontemini deneyebilirsin.")
            .setPositiveButton("Kapat", null)
            .setNeutralButton("Kopyala") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Kamera raporu", fullReport))
                toast("Rapor panoya kopyalandi")
            }
            .show()
    }

    private fun refreshThumbnail() {
        val u = lastUri ?: MediaSaver.latest(this)
        if (u != null) {
            lastUri = u
            saveExecutor.execute {
                val bmp = MediaSaver.thumbnail(this, u)
                if (bmp != null) runOnUiThread { galleryThumb.setImageBitmap(bmp) }
            }
        }
    }

    // ------------------------------------------------------------------ arayuz kurulumu (kod ile)

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun chip(text: String, size: Float = 12f): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = size
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun styleChip(t: TextView, selected: Boolean) {
        t.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (selected) 0xFFFFD54F.toInt() else 0x33FFFFFF)
        }
        t.setTextColor(if (selected) Color.BLACK else Color.WHITE)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Onizleme
        previewView = SurfaceView(this)
        previewLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, screenW * 4 / 3).apply {
            gravity = Gravity.TOP
            topMargin = dp(52)
        }
        root.addView(previewView, previewLp)
        previewView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                gl?.setPreviewSurface(holder.surface)
                tryStart()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                gl?.setPreviewSurface(null)
            }
        })
        // Sag/sol kaydirma ile Foto <-> Video gecisi
        previewView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > dp(80) && abs(dx) > abs(dy) * 1.5f) {
                        setMode(if (dx < 0) CamMode.VIDEO else CamMode.PHOTO)
                    }
                }
            }
            true
        }

        // Ust cubuk
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(10), 0, dp(10), 0)
        }
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { gravity = Gravity.TOP })

        flashBtn = chip("\u26A1 Kapali").apply { setOnClickListener { toggleFlash() } }
        top.addView(flashBtn)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        chipStd = chip("Standart", 11f).apply { setOnClickListener { setLook(LeicaLook.STANDARD) } }
        chipAuth = chip("Leica\nAuthentic", 11f).apply { setOnClickListener { setLook(LeicaLook.AUTHENTIC) } }
        chipVib = chip("Leica\nVibrant", 11f).apply { setOnClickListener { setLook(LeicaLook.VIBRANT) } }
        for (c in listOf(chipStd, chipAuth, chipVib)) {
            c.setPadding(dp(8), dp(4), dp(8), dp(4))
            top.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }

        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        infoBtn = chip("\u24D8", 16f).apply { setOnClickListener { showInfo() } }
        top.addView(infoBtn)

        // Kayit sayaci + kirmizi gosterge
        timerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x99000000.toInt())
            }
            setPadding(dp(12), dp(5), dp(14), dp(5))
        }
        recDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE53935.toInt())
            }
        }
        timerBox.addView(recDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        timerText = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        timerBox.addView(timerText)
        root.addView(timerBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(60)
        })

        // Alt panel
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        // Lens dugmeleri
        val lensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (t in LensType.values()) {
            val b = TextView(this).apply {
                text = t.label
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { onLensClick(t) }
                setOnLongClickListener { onLensLongClick(t); true }
            }
            lensButtons[t] = b
            lensRow.addView(b, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
                marginStart = dp(7)
                marginEnd = dp(7)
            })
        }
        hiResChip = chip("50MP", 12f).apply { setOnClickListener { toggleHiRes() } }
        lensRow.addView(hiResChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(14)
        })
        bottom.addView(lensRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Mod cubugu
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        photoTab = tab("FOTO\u011eRAF") { setMode(CamMode.PHOTO) }
        videoTab = tab("V\u0130DEO") { setMode(CamMode.VIDEO) }
        modeRow.addView(photoTab)
        modeRow.addView(videoTab)
        bottom.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        // Galeri - deklansor
        val shutterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = FrameLayout(this)
        galleryThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF2A2A2A.toInt())
                setStroke(dp(2), 0x66FFFFFF)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setOnClickListener { openGallery() }
        }
        left.addView(galleryThumb, FrameLayout.LayoutParams(dp(54), dp(54)).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        shutterRow.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        shutter = ShutterView(this).apply { setOnClickListener { onShutter() } }
        shutterRow.addView(shutter, LinearLayout.LayoutParams(dp(82), dp(82)))
        shutterRow.addView(FrameLayout(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(shutterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        return root
    }

    private fun tab(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding(dp(18), dp(6), dp(18), dp(6))
        setOnClickListener { onClick() }
    }

    private fun toggleFlash() {
        flashOn = !flashOn
        controller?.setFlash(flashOn)
        updateFlashUi()
    }

    private fun toggleHiRes() {
        if (recording) return
        hiRes = !hiRes
        updateHiResUi()
        restartCamera()
    }

    // ------------------------------------------------------------------ arayuz guncelleme

    private fun updateAllUi() {
        updateFlashUi()
        updateLookUi()
        updateLensUi()
        updateModeUi()
        updateHiResUi()
        timerBox.visibility = if (recording) View.VISIBLE else View.GONE
        if (!recording) timerText.text = "00:00"
        shutter.recording = recording
        for (t in listOf(photoTab, videoTab)) t.isEnabled = !recording
    }

    private fun updateFlashUi() {
        flashBtn.text = if (flashOn) "\u26A1 A\u00E7\u0131k" else "\u26A1 Kapal\u0131"
        styleChip(flashBtn, flashOn)
    }

    private fun updateLookUi() {
        styleChip(chipStd, look.type == LookType.STANDARD)
        styleChip(chipAuth, look.type == LookType.AUTHENTIC)
        styleChip(chipVib, look.type == LookType.VIBRANT)
    }

    private fun updateHiResUi() {
        hiResChip.visibility = if (mode == CamMode.PHOTO) View.VISIBLE else View.GONE
        styleChip(hiResChip, hiRes)
    }

    private fun updateLensUi() {
        for ((t, b) in lensButtons) {
            val sel = t == lensType
            val ok = available.isEmpty() || t in available
            b.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (sel) 0x55FFFFFF else 0x66000000)
                setStroke(dp(if (sel) 2 else 1), if (sel) 0xFFFFD54F.toInt() else 0x55FFFFFF)
            }
            b.setTextColor(if (sel) 0xFFFFD54F.toInt() else Color.WHITE)
            b.textSize = if (sel) 15f else 13f
            b.alpha = if (ok) 1f else 0.35f
        }
    }

    private fun updateModeUi() {
        photoTab.setTextColor(if (mode == CamMode.PHOTO) 0xFFFFD54F.toInt() else 0xAAFFFFFF.toInt())
        videoTab.setTextColor(if (mode == CamMode.VIDEO) 0xFFFFD54F.toInt() else 0xAAFFFFFF.toInt())
        shutter.videoMode = mode == CamMode.VIDEO
        previewLp.height = if (mode == CamMode.PHOTO) screenW * 4 / 3 else screenW * 16 / 9
        previewView.layoutParams = previewLp
    }

    /** Deklansor / kayit dugmesi */
    class ShutterView(ctx: Context) : View(ctx) {
        var videoMode = false
            set(v) { field = v; invalidate() }
        var recording = false
            set(v) { field = v; invalidate() }

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f
            ring.strokeWidth = r * 0.09f
            c.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
            if (videoMode) {
                fill.color = 0xFFE53935.toInt()
                if (recording) {
                    val s = r * 0.40f
                    c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, r * 0.12f, r * 0.12f, fill)
                } else {
                    c.drawCircle(cx, cy, r * 0.70f, fill)
                }
            } else {
                fill.color = Color.WHITE
                c.drawCircle(cx, cy, r * 0.70f, fill)
            }
        }
    }

    companion object {
        private const val TAG = "KameraApp"
        private const val REQ_PERMS = 42
    }
}
