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
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 renderer that:
 *  - Renders a transparent background (camera shows through below this GLSurfaceView).
 *  - Draws a wide virtual screen quad in 3D space with the video texture.
 *  - Draws a glowing border around the screen.
 *  - Animates the view matrix from SensorHelper yaw/pitch values.
 *
 * The virtual screen is 4.0 units wide × 1.0 unit tall, placed at z = -2.5.
 * A 70° vertical FOV means ~43° horizontal, so ~1.9 units visible at z=2.5.
 * That leaves the user panning across ~2× the visible width — good parallax.
 */
class ARVideoRenderer(
    private val context: Context,
    private val sensorHelper: SensorHelper
) : GLSurfaceView.Renderer {

    // Called on GL thread when video Surface is ready; activity connects ExoPlayer here
    var onVideoSurfaceReady: ((Surface) -> Unit)? = null

    private var videoTextureId = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    private val videoTransformMatrix = FloatArray(16)

    // Screen quad
    private var videoProgram = 0
    private var quadVBO = 0
    private var quadIBO = 0
    private val quadVertexCount = 6 // 2 triangles

    // Border quad (slightly larger, solid colour)
    private var borderProgram = 0
    private var borderVBO = 0
    private var borderIBO = 0

    // Projection / view / model matrices
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tmpMatrix = FloatArray(16)

    // Video aspect ratio (updated when video starts, default 4:1)
    @Volatile var videoAspectRatio = 4f

    // Screen geometry in world units
    private val screenZ = -2.5f
    private val screenHeight = 1.0f
    private val screenBorderThickness = 0.035f

    // GLSL — vertex shader (shared by video and border quads)
    private val vertSrc = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uMVP;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVP * aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    // GLSL — fragment shader for OES video texture
    private val videoFragSrc = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uVideoTex;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uVideoTex, vTexCoord);
        }
    """.trimIndent()

    // GLSL — fragment shader for solid-colour border + edge glow
    private val borderFragSrc = """
        precision mediump float;
        uniform vec4 uColor;
        varying vec2 vTexCoord;
        void main() {
            // Soft glow at edges using distance to border centre
            float dx = abs(vTexCoord.x - 0.5) * 2.0;
            float dy = abs(vTexCoord.y - 0.5) * 2.0;
            float d  = max(dx, dy);
            float glow = pow(clamp(d, 0.0, 1.0), 2.0);
            gl_FragColor = mix(uColor, vec4(uColor.rgb * 1.6, uColor.a), glow);
        }
    """.trimIndent()

    // -------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // -------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)   // fully transparent — camera shows through
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        videoProgram  = GlUtils.createProgram(vertSrc, videoFragSrc)
        borderProgram = GlUtils.createProgram(vertSrc, borderFragSrc)

        createVideoTexture()
        createBorderGeometry()
        // Video quad will be rebuilt in onSurfaceChanged once we know viewport size,
        // but we need geometry now too.
        createVideoGeometry()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 70f, aspect, 0.1f, 50f)
        createVideoGeometry()   // rebuild with current aspect ratio
        createBorderGeometry()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        videoSurfaceTexture?.updateTexImage()
        videoSurfaceTexture?.getTransformMatrix(videoTransformMatrix)

        buildViewMatrix()
        drawBorder()
        drawVideoScreen()
    }

    // -------------------------------------------------------------------------
    // Setup helpers
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
        videoSurfaceTexture = st
        val surface = Surface(st)

        // Notify activity on main thread so it can attach ExoPlayer
        Handler(Looper.getMainLooper()).post { onVideoSurfaceReady?.invoke(surface) }
    }

    private fun createVideoGeometry() {
        // Delete previous VBO/IBO if they exist
        if (quadVBO != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(quadVBO), 0)
            GLES20.glDeleteBuffers(1, intArrayOf(quadIBO), 0)
        }

        val halfW = screenHeight * videoAspectRatio / 2f
        val halfH = screenHeight / 2f

        // x, y, z,  u, v  (5 floats per vertex)
        val verts = floatArrayOf(
            -halfW,  halfH, 0f,   0f, 0f,   // TL
            -halfW, -halfH, 0f,   0f, 1f,   // BL
             halfW, -halfH, 0f,   1f, 1f,   // BR
             halfW,  halfH, 0f,   1f, 0f    // TR
        )
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

        val vBuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vBuf.put(verts).position(0)
        val iBuf = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        iBuf.put(indices).position(0)

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        quadVBO = ids[0]; quadIBO = ids[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVBO)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vBuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, quadIBO)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, iBuf, GLES20.GL_STATIC_DRAW)
    }

    private fun createBorderGeometry() {
        if (borderVBO != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(borderVBO), 0)
            GLES20.glDeleteBuffers(1, intArrayOf(borderIBO), 0)
        }

        val bt = screenBorderThickness
        val halfW = screenHeight * videoAspectRatio / 2f + bt
        val halfH = screenHeight / 2f + bt

        val verts = floatArrayOf(
            -halfW,  halfH, 0f,   0f, 0f,
            -halfW, -halfH, 0f,   0f, 1f,
             halfW, -halfH, 0f,   1f, 1f,
             halfW,  halfH, 0f,   1f, 0f
        )
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

        val vBuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vBuf.put(verts).position(0)
        val iBuf = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        iBuf.put(indices).position(0)

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        borderVBO = ids[0]; borderIBO = ids[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, borderVBO)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vBuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, borderIBO)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, iBuf, GLES20.GL_STATIC_DRAW)
    }

    // -------------------------------------------------------------------------
    // Rendering helpers
    // -------------------------------------------------------------------------

    private fun buildViewMatrix() {
        val yaw   = sensorHelper.relativeYaw    // degrees, right is positive
        val pitch = sensorHelper.relativePitch  // degrees, up is positive

        // Clamp pitch so the screen can't go fully above/below
        val clampedPitch = pitch.coerceIn(-30f, 30f)

        // Build view by rotating the scene opposite to phone rotation:
        // rotating phone right → scene rotates left in view → reveals right side
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.rotateM(viewMatrix, 0, clampedPitch, 1f, 0f, 0f)  // tilt up/down
        Matrix.rotateM(viewMatrix, 0, -yaw, 0f, 1f, 0f)          // pan left/right
    }

    private fun buildMVP(vbo: Int) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, screenZ)

        Matrix.multiplyMM(tmpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, tmpMatrix, 0)
    }

    private fun drawVideoScreen() {
        if (quadVBO == 0) return
        GLES20.glUseProgram(videoProgram)
        buildMVP(quadVBO)

        val stride = 5 * 4 // 5 floats × 4 bytes

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVBO)
        val posLoc = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, stride, 3 * 4)

        val mvpLoc = GLES20.glGetUniformLocation(videoProgram, "uMVP")
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        val texSamplerLoc = GLES20.glGetUniformLocation(videoProgram, "uVideoTex")
        GLES20.glUniform1i(texSamplerLoc, 0)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, quadIBO)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun drawBorder() {
        if (borderVBO == 0) return
        GLES20.glUseProgram(borderProgram)
        buildMVP(borderVBO)

        val stride = 5 * 4

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, borderVBO)
        val posLoc = GLES20.glGetAttribLocation(borderProgram, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(borderProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, stride, 3 * 4)

        val mvpLoc = GLES20.glGetUniformLocation(borderProgram, "uMVP")
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)

        val colorLoc = GLES20.glGetUniformLocation(borderProgram, "uColor")
        // Soft blue-white glow similar to Vision Pro chrome
        GLES20.glUniform4f(colorLoc, 0.55f, 0.78f, 1.0f, 0.85f)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, borderIBO)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    /** Called from activity (main thread) to update video aspect after media is ready. */
    fun updateAspectRatio(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoAspectRatio = width.toFloat() / height.toFloat()
        }
    }
}
