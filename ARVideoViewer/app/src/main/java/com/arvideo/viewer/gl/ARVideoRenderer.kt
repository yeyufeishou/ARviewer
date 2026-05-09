package com.arvideo.viewer.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.arvideo.viewer.sensor.SensorHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders a wide virtual screen anchored in space using the gyroscope's rotation
 * matrix as the view matrix. The screen is composed of:
 *   - A solid blue-white border quad (drawn first, slightly larger)
 *   - The video OES texture quad (drawn on top, sized by video aspect)
 *
 * Depth test is intentionally disabled. The two quads sit at the same Z and
 * the draw order is what matters.
 */
class ARVideoRenderer(
    private val context: Context,
    private val sensorHelper: SensorHelper
) : GLSurfaceView.Renderer {

    var onVideoSurfaceReady: ((Surface) -> Unit)? = null

    // ---- video texture ----
    private var videoTextureId = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    private val texTransform = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // ---- programs ----
    private var videoProgram = 0
    private var borderProgram = 0

    // ---- geometry buffers ----
    private var quadVBO = 0   // 4 verts: pos(3) + uv(2)
    private var quadIBO = 0   // 6 indices

    // matrices
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)

    // scene config
    @Volatile private var videoAspect = 16f / 9f  // updated when video size known
    private val screenHeight = 1.4f               // world units
    private val screenZ = -2.0f                   // distance in front of user
    private val borderThickness = 0.04f

    // shaders ----------------------------------------------------------------
    private val commonVertSrc = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uMVP;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVP * aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val videoFragSrc = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uVideoTex;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uVideoTex, vTexCoord);
        }
    """.trimIndent()

    private val borderFragSrc = """
        precision mediump float;
        uniform vec4 uColor;
        varying vec2 vTexCoord;
        void main() {
            float dx = abs(vTexCoord.x - 0.5) * 2.0;
            float dy = abs(vTexCoord.y - 0.5) * 2.0;
            float d  = max(dx, dy);
            float glow = pow(clamp(d, 0.0, 1.0), 2.0);
            gl_FragColor = mix(uColor, vec4(uColor.rgb * 1.6, uColor.a), glow);
        }
    """.trimIndent()

    // -------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)         // <-- key fix #1
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        videoProgram  = GlUtils.createProgram(commonVertSrc, videoFragSrc)
        borderProgram = GlUtils.createProgram(commonVertSrc, borderFragSrc)

        createVideoTexture()
        createUnitQuad()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        // 70° vertical FOV — matches a typical phone rear camera
        Matrix.perspectiveM(proj, 0, 70f, aspect, 0.1f, 50f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // pull latest video frame and its transform (handles vertical flip etc.)
        videoSurfaceTexture?.let {
            it.updateTexImage()
            it.getTransformMatrix(texTransform)
        }

        // view matrix from sensor — direct, no filtering
        sensorHelper.getViewMatrix(view)

        // draw border (slightly bigger), then video on top
        val halfW = videoAspect * screenHeight / 2f
        val halfH = screenHeight / 2f

        drawQuad(
            program = borderProgram,
            halfW = halfW + borderThickness,
            halfH = halfH + borderThickness,
            useVideoTex = false
        )
        drawQuad(
            program = videoProgram,
            halfW = halfW,
            halfH = halfH,
            useVideoTex = true
        )
    }

    // -------------------------------------------------------------------------
    // setup helpers
    // -------------------------------------------------------------------------

    private fun createVideoTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        videoTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(videoTextureId)
        st.setDefaultBufferSize(1920, 1080)
        videoSurfaceTexture = st
        val surface = Surface(st)
        Handler(Looper.getMainLooper()).post { onVideoSurfaceReady?.invoke(surface) }
    }

    /** Single unit quad reused for both border & video at different scales via model matrix. */
    private fun createUnitQuad() {
        // Verts: x, y, z, u, v   (-1..1 quad)
        val verts = floatArrayOf(
            -1f,  1f, 0f,   0f, 0f,   // TL
            -1f, -1f, 0f,   0f, 1f,   // BL
             1f, -1f, 0f,   1f, 1f,   // BR
             1f,  1f, 0f,   1f, 0f    // TR
        )
        val idx = shortArrayOf(0, 1, 2, 0, 2, 3)

        val vBuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vBuf.put(verts).position(0)
        val iBuf = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        iBuf.put(idx).position(0)

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        quadVBO = ids[0]; quadIBO = ids[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVBO)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vBuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, quadIBO)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, iBuf, GLES20.GL_STATIC_DRAW)
    }

    private fun drawQuad(program: Int, halfW: Float, halfH: Float, useVideoTex: Boolean) {
        GLES20.glUseProgram(program)

        // model = scale × translate(0, 0, screenZ)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, screenZ)
        Matrix.scaleM(model, 0, halfW, halfH, 1f)

        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)

        val stride = 5 * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVBO)
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, stride, 3 * 4)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVP"), 1, false, mvp, 0)

        if (useVideoTex) {
            // Apply SurfaceTexture's transform so video is upright/uncropped
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texTransform, 0
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uVideoTex"), 0)
        } else {
            // Border ignores tex matrix, but still needs a valid one for the shared shader
            val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, identity, 0
            )
            GLES20.glUniform4f(
                GLES20.glGetUniformLocation(program, "uColor"),
                0.40f, 0.65f, 1.0f, 0.85f
            )
        }

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, quadIBO)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    /** Called from the main thread when video size becomes known. */
    fun updateAspectRatio(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoAspect = width.toFloat() / height.toFloat()
            videoSurfaceTexture?.setDefaultBufferSize(width, height)
        }
    }
}
