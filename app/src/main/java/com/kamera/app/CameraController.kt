package com.kamera.app

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot

enum class CamMode { PHOTO, VIDEO }

enum class LensType(val label: String, val zoomRatio: Float) {
    ULTRA("0.6x", 0.6f),
    MAIN("1x", 1f),
    TELE("2x", 2f)
}

enum class StrategyKind { PHYSICAL, DIRECT, ZOOM }

/**
 * Bir lensi acmanin bir yolu.
 *  PHYSICAL: mantiksal kamerayi ac, akisi fiziksel kamera ID'sine bagla (setPhysicalCameraId)
 *  DIRECT  : fiziksel ID dogrudan cameraIdList'te varsa onu bagimsiz kamera gibi ac
 *  ZOOM    : mantiksal kamerada CONTROL_ZOOM_RATIO ile sistemin lensi secmesini sagla (yedek yontem)
 */
data class Strategy(val kind: StrategyKind, val openId: String, val physicalId: String?, val zoom: Float?) {
    fun describe(): String = when (kind) {
        StrategyKind.PHYSICAL -> "FIZIKSEL AKIS (mantiksal ID $openId -> fiziksel ID $physicalId)"
        StrategyKind.DIRECT -> "DOGRUDAN ID $openId"
        StrategyKind.ZOOM -> "ZOOM x$zoom (mantiksal ID $openId)"
    }
}

class Lens(val type: LensType, val strategies: List<Strategy>, val equivFocal: Float?)

class CameraController(
    private val context: Context,
    private val gl: GlPipeline,
    private val listener: Listener
) {
    interface Listener {
        fun onDiscovered(available: Set<LensType>, report: String)
        fun onLensActive(type: LensType, strategy: Strategy, previewSize: Size, jpegSize: Size?, hiRes: Boolean)
        fun onError(message: String)
        fun onPhoto(jpeg: ByteArray, orientation: Int)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera-thread").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val generation = AtomicInteger(0)

    private val lenses = LinkedHashMap<LensType, Lens>()
    private val chosen = ConcurrentHashMap<LensType, Int>()

    private var device: CameraDevice? = null
    private var deviceId: String? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var repeating: CaptureRequest.Builder? = null
    private var activeLogical: CameraCharacteristics? = null
    private var activeChars: CameraCharacteristics? = null
    private var activeStrategy: Strategy? = null
    private var activeMode = CamMode.PHOTO
    private var pixelMaxActive = false
    private var sessionReadyGen = -1
    private val pendingOrientation = ConcurrentLinkedQueue<Int>()

    /** Son calisma-zamani olaylari (basarisiz yontemler, gecisler) - (i) raporunda gosterilir. */
    private val eventLog = ArrayDeque<String>()
    private fun logEvent(msg: String) {
        synchronized(eventLog) {
            eventLog.addLast(msg)
            while (eventLog.size > 12) eventLog.removeFirst()
        }
    }
    fun recentEventsText(): String = synchronized(eventLog) { eventLog.joinToString("\n") { "  - $it" } }
        .ifEmpty { "  (henuz olay yok)" }

    @Volatile var flashOn = false
    @Volatile var deviceOrientation = 0
    @Volatile var sensorOrientation = 90
        private set
    @Volatile var previewSize = Size(1440, 1080)
        private set

    /** Kayit icin dik (portre) video boyutu */
    fun videoSize(): Size {
        val p = previewSize
        return if (sensorOrientation % 180 == 90) Size(p.height, p.width) else p
    }

    init {
        handler.post { discover() }
    }

    // ------------------------------------------------------------------ Kesif

    private class Cand(
        val id: String,
        val logicalId: String?,
        val chars: CameraCharacteristics,
        val equiv: Float,
        val listed: Boolean,
        val megapixels: Float,
        val ois: Boolean
    )

    private fun equivFocal(c: CameraCharacteristics): Float {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        val diag = hypot(s.width, s.height)
        return if (diag <= 0f) 0f else f * 43.27f / diag
    }

    private fun safeChars(id: String): CameraCharacteristics? = try {
        manager.getCameraCharacteristics(id)
    } catch (e: Exception) {
        null
    }

    private fun makeCand(id: String, logicalId: String?, c: CameraCharacteristics, listed: Boolean): Cand? {
        // NOT: Fiziksel alt-kameralarin REQUEST_AVAILABLE_CAPABILITIES listesi bircok
        // cihazda (ozellikle Xiaomi/MIUI) eksik veya BACKWARD_COMPATIBLE'i icermeyecek
        // sekilde raporlanabiliyor; bu yuzden capability filtresi KULLANILMIYOR - aksi
        // halde gecerli bir Ultra Genis Aci/Telefoto sensoru yanlislikla elenebiliyordu.
        val eq = equivFocal(c)
        if (eq < 8f || eq > 300f) return null
        val pa = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val mp = if (pa != null) pa.width.toLong() * pa.height / 1_000_000f else 0f
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        return Cand(id, logicalId, c, eq, listed, mp, ois)
    }

    private fun discover() {
        val sb = StringBuilder()
        lenses.clear()
        try {
            val ids = manager.cameraIdList.toList()
            sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) - ${Build.MANUFACTURER} ${Build.MODEL}")
            sb.appendLine("cameraIdList: ${ids.joinToString()}")
            val backIds = ids.filter {
                safeChars(it)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            sb.appendLine("Arka kameralar: ${backIds.joinToString()}")

            val logicalIds = backIds.filter { id ->
                safeChars(id)?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            }
            sb.appendLine("Mantiksal (multi-camera) ID'ler: ${if (logicalIds.isEmpty()) "yok" else logicalIds.joinToString()}")

            val cands = LinkedHashMap<String, Cand>()
            for (lid in logicalIds) {
                val lc = safeChars(lid) ?: continue
                val phys = lc.physicalCameraIds
                sb.appendLine("  ID $lid -> fiziksel ID'ler: ${phys.joinToString()}")
                for (pid in phys) {
                    val pc = safeChars(pid)
                    if (pc == null) {
                        sb.appendLine("    fiziksel $pid: karakteristik okunamadi (uygulamaya kapali olabilir)")
                        continue
                    }
                    makeCand(pid, lid, pc, ids.contains(pid))?.let { cands[pid] = it }
                }
            }
            for (id in backIds) {
                if (cands.containsKey(id) || logicalIds.contains(id)) continue
                val c = safeChars(id) ?: continue
                makeCand(id, null, c, true)?.let { cands[id] = it }
            }
            // Mantiksal kamerada fiziksel liste bossa: mantiksal kameranin kendisini ana kamera adayi yap
            if (cands.isEmpty()) {
                for (id in backIds) {
                    val c = safeChars(id) ?: continue
                    makeCand(id, null, c, true)?.let { cands[id] = it }
                }
            }

            sb.appendLine()
            sb.appendLine("Fiziksel lens adaylari (35mm esdeger odak uzakligina gore):")
            val sorted = cands.values.sortedBy { it.equiv }
            for (c in sorted) {
                sb.appendLine(
                    "  ID ${c.id}: ~%.0f mm, %.1f MP, OIS=%s, mantiksal=%s, cameraIdList'te=%s".format(
                        c.equiv, c.megapixels, if (c.ois) "var" else "yok", c.logicalId ?: "-", if (c.listed) "evet" else "hayir"
                    )
                )
            }

            var ultra: Cand? = null
            var main: Cand? = null
            var tele: Cand? = null
            when {
                sorted.size >= 3 -> {
                    ultra = sorted.first()
                    tele = sorted.last()
                    main = sorted.subList(1, sorted.size - 1).minByOrNull { abs(it.equiv - 24f) }
                }
                sorted.size == 2 -> {
                    val m = sorted.minByOrNull { abs(it.equiv - 24f) }!!
                    main = m
                    val other = sorted.first { it !== m }
                    if (other.equiv < m.equiv) ultra = other else tele = other
                }
                sorted.size == 1 -> main = sorted[0]
            }

            // Zoom yedegi icin mantiksal kamera
            val zoomId = logicalIds.firstOrNull() ?: backIds.firstOrNull { it == "0" } ?: backIds.firstOrNull()
            val zoomRange: Range<Float>? = zoomId?.let { safeChars(it)?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }
            sb.appendLine()
            sb.appendLine("Zoom yedegi: ID ${zoomId ?: "-"}, aralik ${zoomRange ?: "yok"}")

            fun build(type: LensType, c: Cand?): Lens {
                val list = ArrayList<Strategy>()
                if (c != null) {
                    if (c.logicalId != null) list.add(Strategy(StrategyKind.PHYSICAL, c.logicalId, c.id, null))
                    if (c.listed) list.add(Strategy(StrategyKind.DIRECT, c.id, null, null))
                }
                if (zoomId != null && zoomRange != null) {
                    val z = type.zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper)
                    if (abs(z - type.zoomRatio) < 0.15f) list.add(Strategy(StrategyKind.ZOOM, zoomId, null, type.zoomRatio))
                }
                return Lens(type, list, c?.equiv)
            }

            lenses[LensType.ULTRA] = build(LensType.ULTRA, ultra)
            lenses[LensType.MAIN] = build(LensType.MAIN, main)
            lenses[LensType.TELE] = build(LensType.TELE, tele)

            sb.appendLine()
            sb.appendLine("Lens eslemesi ve denenecek yontemler (sirayla):")
            for (l in lenses.values) {
                sb.appendLine("  ${l.type.label}: " + if (l.strategies.isEmpty()) "KULLANILAMAZ" else "")
                l.strategies.forEachIndexed { i, s -> sb.appendLine("     ${i + 1}) ${s.describe()}") }
            }
        } catch (e: Exception) {
            sb.appendLine("KESIF HATASI: $e")
            Log.e(TAG, "discover", e)
        }
        val report = sb.toString()
        Log.i(TAG, "\n$report")
        listener.onDiscovered(lenses.values.filter { it.strategies.isNotEmpty() }.map { it.type }.toSet(), report)
    }

    // ------------------------------------------------------------------ Acma / oturum

    /**
     * Lens + mod icin kamerayi (yeniden) yapilandirir.
     * @param cycle true ise bu lens icin bir sonraki yonteme gecer (uzun basma ile elle deneme).
     */
    fun configure(type: LensType, mode: CamMode, flash: Boolean, hiRes: Boolean, cycle: Boolean = false) {
        flashOn = flash
        val gen = generation.incrementAndGet()
        handler.post {
            val size = lenses[type]?.strategies?.size ?: 0
            if (cycle && size > 0) chosen[type] = ((chosen[type] ?: 0) + 1) % size
            wantHiRes = hiRes
            startInternal(type, mode, chosen[type] ?: 0, gen)
        }
    }

    @Volatile private var wantHiRes = false

    private fun startInternal(type: LensType, mode: CamMode, idx: Int, gen: Int) {
        if (gen != generation.get()) return
        val lens = lenses[type]
        if (lens == null || idx >= lens.strategies.size) {
            chosen[type] = 0
            closeAll()
            listener.onError("${type.label} lensi acilamadi (denenecek yontem kalmadi). Sag ustteki (i) dugmesinden raporu kontrol edin.")
            return
        }
        val strat = lens.strategies[idx]
        try {
            if (device != null && deviceId == strat.openId) {
                createSession(type, mode, idx, gen)
            } else {
                closeAll()
                manager.openCamera(strat.openId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cd: CameraDevice) {
                        if (gen != generation.get()) {
                            cd.close()
                            return
                        }
                        device = cd
                        deviceId = cd.id
                        createSession(type, mode, idx, gen)
                    }

                    override fun onDisconnected(cd: CameraDevice) {
                        cd.close()
                        if (device === cd) clearDeviceRefs()
                    }

                    override fun onError(cd: CameraDevice, error: Int) {
                        cd.close()
                        if (device === cd) clearDeviceRefs()
                        if (gen != generation.get()) return
                        if (sessionReadyGen == gen) listener.onError("Kamera hatasi (kod $error)")
                        else next(type, mode, idx, gen, "openCamera onError=$error")
                    }
                }, handler)
            }
        } catch (e: Exception) {
            next(type, mode, idx, gen, e.toString())
        }
    }

    private fun clearDeviceRefs() {
        device = null
        deviceId = null
        session = null
        repeating = null
    }

    private fun next(type: LensType, mode: CamMode, idx: Int, gen: Int, why: String?) {
        val strat = lenses[type]?.strategies?.getOrNull(idx)
        val msg = "${type.label}: yontem ${idx + 1} (${strat?.describe() ?: "?"}) basarisiz -> sonrakine geciliyor. Sebep: $why"
        Log.w(TAG, msg)
        logEvent(msg)
        if (gen != generation.get()) return
        chosen[type] = idx + 1
        closeAll()
        startInternal(type, mode, idx + 1, gen)
    }

    private fun pickPreviewSize(map: StreamConfigurationMap, mode: CamMode): Size {
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()
        val rw = if (mode == CamMode.PHOTO) 4 else 16
        val rh = if (mode == CamMode.PHOTO) 3 else 9
        val fit = sizes.filter { it.width * rh == it.height * rw && it.width <= 1920 }
        return fit.maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: Size(1280, if (mode == CamMode.PHOTO) 960 else 720)
    }

    private fun area(s: Size): Long = s.width.toLong() * s.height

    private fun pickJpegSize(map: StreamConfigurationMap): Size {
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val fourThree = sizes.filter { abs(it.width * 3 - it.height * 4) <= it.width / 50 }
        return fourThree.maxByOrNull { area(it) } ?: sizes.maxByOrNull { area(it) } ?: Size(4000, 3000)
    }

    private fun createSession(type: LensType, mode: CamMode, idx: Int, gen: Int) {
        val dev = device ?: return
        val strat = lenses[type]!!.strategies[idx]
        try {
            closeSessionOnly()
            val logical = manager.getCameraCharacteristics(strat.openId)
            val chars = if (strat.kind == StrategyKind.PHYSICAL) safeChars(strat.physicalId!!) ?: logical else logical
            activeLogical = logical
            activeChars = chars
            activeStrategy = strat
            activeMode = mode
            sensorOrientation = logical.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("StreamConfigurationMap yok")
            val pSize = pickPreviewSize(map, mode)
            previewSize = pSize

            var jpegSize: Size? = null
            var hiActive = false
            var pixelMax = false
            if (mode == CamMode.PHOTO) {
                val std = pickJpegSize(map)
                jpegSize = std
                if (wantHiRes && strat.kind != StrategyKind.ZOOM) {
                    var mr: Size? = null
                    if (Build.VERSION.SDK_INT >= 31) {
                        mr = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                            ?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { area(it) }
                    }
                    val hr = map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.maxByOrNull { area(it) }
                    if (mr != null && area(mr) > area(std)) {
                        jpegSize = mr; hiActive = true; pixelMax = true
                    } else if (hr != null && area(hr) > area(std)) {
                        jpegSize = hr; hiActive = true
                    }
                }
            }
            pixelMaxActive = pixelMax

            val previewSurface = gl.createCameraSurface(pSize.width, pSize.height, sensorOrientation)
            val outputs = ArrayList<OutputConfiguration>()
            val pc = OutputConfiguration(previewSurface)
            if (strat.kind == StrategyKind.PHYSICAL) pc.setPhysicalCameraId(strat.physicalId)
            outputs.add(pc)

            if (mode == CamMode.PHOTO && jpegSize != null) {
                val rd = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
                rd.setOnImageAvailableListener(imageListener, handler)
                val jc = OutputConfiguration(rd.surface)
                if (strat.kind == StrategyKind.PHYSICAL) jc.setPhysicalCameraId(strat.physicalId)
                if (pixelMax && Build.VERSION.SDK_INT >= 31) {
                    jc.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                }
                outputs.add(jc)
                reader = rd
            }

            val finalJpeg = jpegSize
            val finalHi = hiActive
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation.get() || device !== dev) {
                            s.close()
                            return
                        }
                        session = s
                        try {
                            val b = dev.createCaptureRequest(
                                if (mode == CamMode.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                            )
                            b.addTarget(previewSurface)
                            applyCommon(b, strat, mode, torch = flashOn && mode == CamMode.VIDEO)
                            repeating = b
                            s.setRepeatingRequest(b.build(), null, handler)
                            sessionReadyGen = gen
                            chosen[type] = idx
                            if (idx > 0) logEvent("${type.label}: ${idx + 1}. yontemle acildi -> ${strat.describe()}")
                            listener.onLensActive(type, strat, pSize, finalJpeg, finalHi)
                        } catch (e: Exception) {
                            next(type, mode, idx, gen, "setRepeatingRequest: $e")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        s.close()
                        if (gen == generation.get()) next(type, mode, idx, gen, "onConfigureFailed")
                    }
                }
            )
            dev.createCaptureSession(cfg)
        } catch (e: Exception) {
            next(type, mode, idx, gen, e.toString())
        }
    }

    private fun applyCommon(b: CaptureRequest.Builder, strat: Strategy, mode: CamMode, torch: Boolean) {
        val lc = activeLogical ?: return
        val c = activeChars ?: lc
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        val af = (c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: lc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES))?.toList() ?: emptyList()
        val wantAf = if (mode == CamMode.VIDEO) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        if (af.contains(wantAf)) b.set(CaptureRequest.CONTROL_AF_MODE, wantAf)
        else if (af.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)

        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)

        val ois = (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?: lc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION))?.toList() ?: emptyList()
        if (ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }

        val z = strat.zoom
        if (z != null) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)

        if (mode == CamMode.VIDEO) {
            val ranges = lc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
            val r = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            if (r != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
        }

        if (lc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
            b.set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        }
    }

    // ------------------------------------------------------------------ Flas / cekim

    private fun hasFlash(): Boolean = activeLogical?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    /** Video modunda fener (torch) aninda guncellenir; foto modunda cekim aninda kullanilir. */
    fun setFlash(on: Boolean) {
        flashOn = on
        handler.post {
            if (activeMode == CamMode.VIDEO) setTorch(on)
        }
    }

    private fun setTorch(on: Boolean) {
        val b = repeating ?: return
        val s = session ?: return
        try {
            if (hasFlash()) {
                b.set(CaptureRequest.FLASH_MODE, if (on) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
                s.setRepeatingRequest(b.build(), null, handler)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setTorch: $e")
        }
    }

    private fun jpegOrientation(): Int {
        val d = (deviceOrientation + 45) / 90 * 90
        return (sensorOrientation + d + 360) % 360
    }

    fun takePhoto() {
        handler.post {
            val strat = activeStrategy
            if (device == null || session == null || reader == null || strat == null || activeMode != CamMode.PHOTO) {
                listener.onError("Kamera henuz hazir degil")
                return@post
            }
            if (flashOn && hasFlash()) {
                // Flas: once fener yanar (pozlama uyum saglasin), sonra cekim
                setTorch(true)
                handler.postDelayed({ capture(strat, true) }, 700)
            } else {
                capture(strat, false)
            }
        }
    }

    private fun capture(strat: Strategy, torch: Boolean) {
        val dev = device
        val s = session
        val r = reader
        if (dev == null || s == null || r == null) {
            if (torch) setTorch(false)
            return
        }
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            applyCommon(b, strat, CamMode.PHOTO, torch)
            val ori = jpegOrientation()
            b.set(CaptureRequest.JPEG_ORIENTATION, ori)
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            if (pixelMaxActive && Build.VERSION.SDK_INT >= 31) {
                b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            pendingOrientation.add(ori)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    if (torch) setTorch(false)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    pendingOrientation.poll()
                    if (torch) setTorch(false)
                    listener.onError("Cekim basarisiz (sebep ${failure.reason})")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "capture", e)
            if (torch) setTorch(false)
            listener.onError("Cekim hatasi: $e")
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { r ->
        var img: Image? = null
        try {
            img = r.acquireNextImage()
            if (img != null) {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val ori = pendingOrientation.poll() ?: 90
                listener.onPhoto(bytes, ori)
            }
        } catch (e: Exception) {
            Log.e(TAG, "imageListener", e)
        } finally {
            img?.close()
        }
    }

    // ------------------------------------------------------------------ Kapatma

    private fun closeSessionOnly() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        repeating = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
    }

    private fun closeAll() {
        closeSessionOnly()
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        deviceId = null
        sessionReadyGen = -1
    }

    fun stop() {
        generation.incrementAndGet()
        val latch = CountDownLatch(1)
        handler.post {
            closeAll()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "KameraApp"
    }
}
