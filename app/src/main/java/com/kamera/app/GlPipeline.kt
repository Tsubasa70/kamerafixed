package com.kamera.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Kamera karelerini (OES doku) alir, secili renk profili ile (LeicaLook) isleyip
 *   - ekrandaki onizleme yuzeyine ve
 *   - (varsa) MediaRecorder video yuzeyine
 * cizer. Tum GL isleri kendi thread'inde yapilir.
 */
class GlPipeline {

    private val thread = HandlerThread("gl-thread").also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var released = false
    @Volatile private var look: LeicaLook = LeicaLook.STANDARD

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurf: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var oesTex = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMat = 0
    private var uTex = 0
    private var uColorMat = 0
    private var uOffset = 0
    private var uMid = 0
    private var uVig = 0

    private var camTexture: SurfaceTexture? = null
    private var camSurface: Surface? = null
    private var rotation = 90
    private val texMat = FloatArray(16)

    private val posBuf: FloatBuffer = floatBuffer(floatArrayOf(-1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f))
    private var texBuf: FloatBuffer = floatBuffer(texCoords(90))

    init {
        call { initEgl() }
    }

    fun setLook(l: LeicaLook) {
        look = l
    }

    /** Onizleme yuzeyini baglar / (null ile) ayirir. */
    fun setPreviewSurface(surface: Surface?) {
        if (released) return
        call {
            if (previewSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                EGL14.eglDestroySurface(display, previewSurf)
                previewSurf = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && surface.isValid) {
                previewSurf = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            }
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        }
    }

    /** Kamera icin yeni bir hedef yuzey uretir. (Her oturum degisiminde cagrilir.) */
    fun createCameraSurface(width: Int, height: Int, sensorOrientation: Int): Surface {
        return call {
            camSurface?.release()
            camTexture?.release()
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            val st = SurfaceTexture(oesTex)
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({ drawFrame() }, handler)
            camTexture = st
            rotation = sensorOrientation
            texBuf = floatBuffer(texCoords(sensorOrientation))
            val s = Surface(st)
            camSurface = s
            s
        }
    }

    /** MediaRecorder yuzeyine cizmeye basla. */
    fun startEncoding(surface: Surface) {
        call {
            if (encoderSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, encoderSurf)
            }
            encoderSurf = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        }
    }

    fun stopEncoding() {
        if (released) return
        call {
            if (encoderSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                EGL14.eglDestroySurface(display, encoderSurf)
                encoderSurf = EGL14.EGL_NO_SURFACE
            }
        }
    }

    fun release() {
        if (released) return
        try {
            call {
                released = true
                try {
                    camSurface?.release()
                    camTexture?.release()
                } catch (_: Exception) {
                }
                camSurface = null
                camTexture = null
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (previewSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewSurf)
                    if (encoderSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderSurf)
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
                previewSurf = EGL14.EGL_NO_SURFACE
                encoderSurf = EGL14.EGL_NO_SURFACE
                pbuffer = EGL14.EGL_NO_SURFACE
                context = EGL14.EGL_NO_CONTEXT
                display = EGL14.EGL_NO_DISPLAY
            }
        } catch (e: Exception) {
            Log.w(TAG, "GL release: $e")
        }
        released = true
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- GL thread

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize basarisiz" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        config = configs[0]
        check(config != null) { "EGL config bulunamadi" }

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uColorMat = GLES20.glGetUniformLocation(program, "uColorMat")
        uOffset = GLES20.glGetUniformLocation(program, "uOffset")
        uMid = GLES20.glGetUniformLocation(program, "uMid")
        uVig = GLES20.glGetUniformLocation(program, "uVig")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun drawFrame() {
        val st = camTexture ?: return
        if (released || display == EGL14.EGL_NO_DISPLAY) return
        try {
            val first = if (previewSurf != EGL14.EGL_NO_SURFACE) previewSurf else pbuffer
            EGL14.eglMakeCurrent(display, first, first, context)
            st.updateTexImage()
            st.getTransformMatrix(texMat)

            if (previewSurf != EGL14.EGL_NO_SURFACE) {
                renderScene(previewSurf)
                EGL14.eglSwapBuffers(display, previewSurf)
            }
            if (encoderSurf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, encoderSurf, encoderSurf, context)
                renderScene(encoderSurf)
                EGLExt.eglPresentationTimeANDROID(display, encoderSurf, System.nanoTime())
                EGL14.eglSwapBuffers(display, encoderSurf)
                EGL14.eglMakeCurrent(display, first, first, context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "drawFrame: $e")
        }
    }

    private fun renderScene(surface: EGLSurface) {
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, h, 0)
        GLES20.glViewport(0, 0, w[0], h[0])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)

        val l = look
        GLES20.glUniformMatrix3fv(uColorMat, 1, false, l.glMatrix(), 0)
        GLES20.glUniform3f(uOffset, l.offset[0], l.offset[1], l.offset[2])
        GLES20.glUniform1f(uMid, l.mid)
        GLES20.glUniform1f(uVig, l.vignette)

        posBuf.position(0)
        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    // ---------------------------------------------------------------- yardimcilar

    private fun <T> call(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        if (released && !thread.isAlive) throw IllegalStateException("GL pipeline kapali")
        val latch = CountDownLatch(1)
        var result: Any? = null
        var error: Throwable? = null
        handler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(4, TimeUnit.SECONDS)) throw IllegalStateException("GL thread zaman asimi")
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program baglanamadi: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader derlenemedi: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        private const val TAG = "KameraApp"

        private fun floatBuffer(data: FloatArray): FloatBuffer {
            val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(data)
            fb.position(0)
            return fb
        }

        /**
         * Sensor goruntusunu ekranda dik gostermek icin dokuyu saat yonunde [rotationDeg] dondurur.
         * Sira (triangle strip): sol-ust, sag-ust, sol-alt, sag-alt.
         */
        fun texCoords(rotationDeg: Int): FloatArray {
            // rotasyon 0 iken koseler: [SolUst, SagUst, SagAlt, SolAlt]
            val c = arrayOf(
                floatArrayOf(0f, 1f), floatArrayOf(1f, 1f),
                floatArrayOf(1f, 0f), floatArrayOf(0f, 0f)
            )
            // NOT: Xiaomi 13T Pro'da onizlemenin yan durdugu tespit edildi; bu yuzden
            // dondurme yonu ters cevrildi (4-k). Hala yanlis gorunuyorsa bu satirdaki
            // "(4 - k0) % 4" ifadesini "k0" ile degistirmek yonu tekrar tersine cevirir.
            val k0 = ((rotationDeg / 90) % 4 + 4) % 4
            val k = (4 - k0) % 4
            fun at(i: Int) = c[(((i - k) % 4) + 4) % 4]
            val tl = at(0)
            val tr = at(1)
            val br = at(2)
            val bl = at(3)
            return floatArrayOf(tl[0], tl[1], tr[0], tr[1], bl[0], bl[1], br[0], br[1])
        }

        private const val VERTEX_SHADER = """
attribute vec4 aPos;
attribute vec2 aTex;
uniform mat4 uTexMat;
varying vec2 vTex;
varying vec2 vPos;
void main() {
    gl_Position = aPos;
    vPos = aPos.xy;
    vTex = (uTexMat * vec4(aTex, 0.0, 1.0)).xy;
}
"""

        private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
varying vec2 vPos;
uniform samplerExternalOES uTex;
uniform mat3 uColorMat;
uniform vec3 uOffset;
uniform float uMid;
uniform float uVig;
void main() {
    vec3 c = texture2D(uTex, vTex).rgb;
    c = uColorMat * c + uOffset;
    c = clamp(c, 0.0, 1.0);
    c = c + uMid * c * (1.0 - c);
    float d = length(vPos);
    float v = smoothstep(0.55, 1.35, d);
    c = c * (1.0 - uVig * v);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
