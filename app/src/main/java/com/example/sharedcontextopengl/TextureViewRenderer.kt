package com.example.sharedcontextopengl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer


class TextureViewRenderer(private val context: Context) : TextureView.SurfaceTextureListener {

    private val TAG = "TextureViewRenderer"
    private var renderThread: RenderThread? = null
    var eglCore: EglCore? = null // Initialized by RenderThread
    var windowSurface: WindowSurface? = null // Initialized by RenderThread

    // Shared EGL context, obtained from our RenderThread
    private var sharedEglContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT

    // Off-screen rendering components
    val offScreenBitmapFlow = MutableStateFlow<Bitmap?>(null)
    private var offScreenRenderThread: OffScreenRenderThread? = null
    private val offScreenWidth = 1024 // Or from config
    private val offScreenHeight = 1024 // Or from config

    // On-screen rendering properties
    private var onScreenProgram: Int = 0
    private var onScreenPositionHandle: Int = 0
    private var onScreenMVPMatrixHandle: Int = 0
    private var onScreenColorHandle: Int = 0
    private lateinit var onScreenTriangleVertices: FloatBuffer
    private val onScreenMvpMatrix = FloatArray(16)
    @Volatile private var triangleColor = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f) // Unified color

    private val onScreenVertexShaderCode =
        "uniform mat4 uMVPMatrix;" +
        "attribute vec4 vPosition;" +
        "void main() {" +
        "  gl_Position = uMVPMatrix * vPosition;" +
        "}"

    private val onScreenFragmentShaderCode =
        "precision mediump float;" +
        "uniform vec4 uColor;" +
        "void main() {" +
        "  gl_FragColor = uColor;" +
        "}"

    private val onScreenTriangleCoords = floatArrayOf(
        0.0f,  0.5f, 0.0f, // top
       -0.5f, -0.5f, 0.0f, // bottom left
        0.5f, -0.5f, 0.0f  // bottom right
    )


    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureAvailable: $width x $height")
        renderThread = RenderThread(surface, context).apply {
            this.renderer = this@TextureViewRenderer
            start()
            // Wait for thread to initialize EGLCore and get shared context
            // This is a simplified wait, a more robust solution might use a CountDownLatch
            var attempts = 0
            val maxAttempts = 200 // Wait up to 2 seconds (200 * 10ms)
            while(this@TextureViewRenderer.sharedEglContext == EGL14.EGL_NO_CONTEXT && attempts < maxAttempts){
                try {
                    Thread.sleep(10) // Brief wait
                    attempts++
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.e(TAG, "Interrupted while waiting for shared context")
                    // Clean up and exit if critical initialization fails
                    stopAndReleaseRenderThread()
                    return
                }
            }
            if (this@TextureViewRenderer.sharedEglContext == EGL14.EGL_NO_CONTEXT) {
                 Log.e(TAG, "Failed to get shared EGL context from RenderThread after $attempts attempts. Cannot initialize OffScreenRenderThread.")
                 stopAndReleaseRenderThread() // Clean up on-screen thread as well
                 return
            } else {
                 Log.d(TAG, "Successfully got shared EGL context from RenderThread. Initializing OffScreenRenderThread.")
                 initOffScreenRenderThread() // Now initialize the off-screen thread
            }
        }
        renderThread?.onSurfaceTextureAvailable(width, height)
        requestRender() // Initial render for on-screen
    }

    private fun initOffScreenRenderThread() {
        if (sharedEglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Cannot init OffScreenRenderThread: sharedEglContext is EGL_NO_CONTEXT")
            return
        }

        // Clean up any existing off-screen render thread first
        offScreenRenderThread?.stopRendering()
        try {
            offScreenRenderThread?.join(500)
            Log.d(TAG, "Previous OffScreenRenderThread joined.")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while joining previous off-screen thread", e)
            Thread.currentThread().interrupt()
        }
        offScreenRenderThread = null
        offScreenBitmapFlow.value = null // Clear old bitmap

        Log.d(TAG, "Creating new OffScreenRenderThread with shared context: $sharedEglContext")
        offScreenRenderThread = OffScreenRenderThread(
            context,
            sharedEglContext,
            offScreenWidth,
            offScreenHeight
        ) { newBitmap ->
            offScreenBitmapFlow.value = newBitmap
        }
        offScreenRenderThread?.setTriangleColor(triangleColor[0], triangleColor[1], triangleColor[2])
        offScreenRenderThread?.start()
        Log.d(TAG, "OffScreenRenderThread started.")
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureSizeChanged: $width x $height")
        renderThread?.onSurfaceTextureSizeChanged(width, height)
        requestRender()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Log.d(TAG, "onSurfaceTextureDestroyed")
        stopAndReleaseRenderThread()
        return true // SurfaceTexture is released by TextureView
    }
    
    private fun stopAndReleaseRenderThread() {
        Log.d(TAG, "Stopping and releasing all render threads.")
        // Stop off-screen thread first
        offScreenRenderThread?.stopRendering()
        try {
            offScreenRenderThread?.join(1000)
            Log.d(TAG, "OffScreenRenderThread joined.")
        } catch (e: InterruptedException) {
            Log.w(TAG, "OffScreenRenderThread join interrupted", e)
            Thread.currentThread().interrupt()
        }
        offScreenRenderThread = null
        offScreenBitmapFlow.value?.recycle()
        offScreenBitmapFlow.value = null

        // Stop on-screen render thread
        renderThread?.let {
            it.requestStop()
            try {
                it.join(1000) // Wait for thread to finish
            } catch (e: InterruptedException) {
                Log.w(TAG, "OnScreen RenderThread join interrupted", e)
                Thread.currentThread().interrupt()
            }
        }
        renderThread = null
        sharedEglContext = EGL14.EGL_NO_CONTEXT // Reset shared context
        Log.d(TAG, "All render threads stopped and resources released.")
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // This is called when SurfaceTexture.updateTexImage() is called,
        // which we don't do directly for active rendering.
    }

    fun setTriangleColor(r: Float, g: Float, b: Float) {
        triangleColor = floatArrayOf(r, g, b, 1.0f)
        requestRender() // Request on-screen render
        offScreenRenderThread?.setTriangleColor(r, g, b) // Set for off-screen render
        Log.d(TAG, "Set triangle color (R=$r, G=$g, B=$b) for both views")
    }

    fun requestRender() {
        renderThread?.requestRender()
    }

    fun onResume() {
        Log.d(TAG, "onResume")
        renderThread?.let {
            if (it.isAlive) {
                 it.setSurfaceReady(true)
                 requestRender()
                 // If off-screen thread was also paused, resume it
                 // For now, OffScreenRenderThread manages its lifecycle based on requests
                 // and the shared context validity derived from on-screen thread.
                 // If surface was destroyed and recreated, onSurfaceTextureAvailable handles re-init.
            } else {
                Log.w(TAG, "onResume: RenderThread is not alive. Waiting for onSurfaceTextureAvailable.")
            }
        }
    }

    fun onPause() {
        Log.d(TAG, "onPause")
        renderThread?.setSurfaceReady(false)
        // OffScreenRenderThread doesn't have an explicit pause, relies on render requests.
        // If GL context is lost (onSurfaceTextureDestroyed), it will be cleaned up.
    }

    fun onDestroy() {
        Log.d(TAG, "onDestroy called for TextureViewRenderer")
        // This might be called by Activity's onDestroy.
        // onSurfaceTextureDestroyed should handle cleanup if TextureView is part of the view hierarchy
        // and is properly detached. If TextureViewRenderer is held longer, explicit cleanup here is vital.
        stopAndReleaseRenderThread()
    }


    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "glCreateShader failed for type $type")
            return 0
        }
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader $type (TextureViewRenderer):")
            Log.e(TAG, "Shader Info Log: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    // Inner class for the rendering thread
    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        private val context: Context
    ) : HandlerThread("TextureViewRenderThread") {

        private val TAG_THREAD = "RenderThread(TV)"
        @Volatile var renderer: TextureViewRenderer? = null // Reference to outer class

       // private var handler: Handler? = null // If using Handler for messages
        private var eglCore: EglCore? = null
        private var windowSurface: WindowSurface? = null

        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        @Volatile private var isRunning = true
        @Volatile private var renderRequested = true // Render once on start
        @Volatile private var surfaceReady = false

        init {
            // HandlerThread.start() must be called by the creator of the thread.
        }

        override fun run() {
            Log.d(TAG_THREAD, "RenderThread starting.")

            if (!setupEGL()) {
                Log.e(TAG_THREAD, "EGL setup failed. Thread stopping.")
                cleanup()
                // Notify outer class about failure to get shared context if applicable
                renderer?.sharedEglContext = EGL14.EGL_NO_CONTEXT // Ensure it's reset
                return
            }
            // Pass the EGL context to the outer TextureViewRenderer
            renderer?.sharedEglContext = eglCore?.getEGLContext() ?: EGL14.EGL_NO_CONTEXT
            renderer?.eglCore = this.eglCore // also pass eglCore itself if needed by outer
            renderer?.windowSurface = this.windowSurface // and windowSurface
            Log.d(TAG_THREAD, "EGL Context for sharing: ${renderer?.sharedEglContext}")

            if (!setupGLScene()) {
                Log.e(TAG_THREAD, "GL scene setup failed. Thread stopping.")
                cleanup()
                return
            }

            surfaceReady = true // Mark as ready after setup

            while (isRunning) {
                synchronized(this) {
                    if (surfaceReady && renderRequested && screenWidth > 0 && screenHeight > 0) {
                        performRender()
                        renderRequested = false
                    } else {
                        try {
                            (this as Object).wait(if (surfaceReady) 1000 else 100) // Adjust wait time
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            isRunning = false 
                        }
                    }
                }
            }
            cleanup()
            Log.d(TAG_THREAD, "RenderThread finished.")
        }

        private fun setupEGL(): Boolean {
            try {
                eglCore = EglCore() // No shared context for the primary display EGL context
                windowSurface = WindowSurface(eglCore!!, surfaceTexture)
                windowSurface!!.makeCurrent()
            } catch (e: EglCore.EglException) {
                Log.e(TAG_THREAD, "EGL setup failed: ${e.message}", e)
                return false
            }
            Log.d(TAG_THREAD, "EGL Initialized successfully for TextureView.")
            return true
        }

        private fun setupGLScene(): Boolean {
            val r = renderer ?: return false
            Log.d(TAG_THREAD, "Setting up GL scene for TextureView...")

            val vertexShader = r.loadShader(GLES20.GL_VERTEX_SHADER, r.onScreenVertexShaderCode)
            val fragmentShader = r.loadShader(GLES20.GL_FRAGMENT_SHADER, r.onScreenFragmentShaderCode)

            if (vertexShader == 0 || fragmentShader == 0) {
                r.onScreenProgram = 0
                return false
            }

            r.onScreenProgram = GLES20.glCreateProgram()
            if (r.onScreenProgram == 0) return false

            GLES20.glAttachShader(r.onScreenProgram, vertexShader)
            GLES20.glAttachShader(r.onScreenProgram, fragmentShader)
            GLES20.glLinkProgram(r.onScreenProgram)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(r.onScreenProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG_THREAD, "Could not link on-screen program: ${GLES20.glGetProgramInfoLog(r.onScreenProgram)}")
                GLES20.glDeleteProgram(r.onScreenProgram)
                r.onScreenProgram = 0
                return false
            }

            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            r.onScreenPositionHandle = GLES20.glGetAttribLocation(r.onScreenProgram, "vPosition")
            r.onScreenMVPMatrixHandle = GLES20.glGetUniformLocation(r.onScreenProgram, "uMVPMatrix")
            r.onScreenColorHandle = GLES20.glGetUniformLocation(r.onScreenProgram, "uColor")

            val bb = ByteBuffer.allocateDirect(r.onScreenTriangleCoords.size * 4)
            bb.order(ByteOrder.nativeOrder())
            r.onScreenTriangleVertices = bb.asFloatBuffer()
            r.onScreenTriangleVertices.put(r.onScreenTriangleCoords)
            r.onScreenTriangleVertices.position(0)
            Log.d(TAG_THREAD, "GL scene setup complete for TextureView.")
            return true
        }

        private fun performRender() {
            val r = renderer ?: return
            if (r.onScreenProgram == 0) return // Modified line
            
            windowSurface?.makeCurrent() // Ensure context is current

            GLES20.glViewport(0, 0, screenWidth, screenHeight)
            GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f) // Red background
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            GLES20.glUseProgram(r.onScreenProgram)
            GLES20.glEnableVertexAttribArray(r.onScreenPositionHandle)
            GLES20.glVertexAttribPointer(r.onScreenPositionHandle, 3, GLES20.GL_FLOAT, false, 0, r.onScreenTriangleVertices)
            GLES20.glUniformMatrix4fv(r.onScreenMVPMatrixHandle, 1, false, r.onScreenMvpMatrix, 0)
            GLES20.glUniform4fv(r.onScreenColorHandle, 1, r.triangleColor, 0) // Use unified color

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

            GLES20.glDisableVertexAttribArray(r.onScreenPositionHandle)
            GLES20.glUseProgram(0)

            windowSurface?.swapBuffers() // Present the frame
        }

        fun onSurfaceTextureAvailable(width: Int, height: Int) {
            synchronized(this) {
                Log.d(TAG_THREAD, "Surface available in thread: $width x $height")
                this.screenWidth = width
                this.screenHeight = height
                surfaceReady = true // It becomes ready *after* EGL setup in run()
                                  // This call might come before run() has fully initialized EGL.
                                  // The check in run() loop for screenWidth/Height handles this.
                updateMvpMatrix(width, height)
                requestRender() 
            }
        }

        fun onSurfaceTextureSizeChanged(width: Int, height: Int) {
             synchronized(this) {
                Log.d(TAG_THREAD, "Surface size changed in thread: $width x $height")
                this.screenWidth = width
                this.screenHeight = height
                updateMvpMatrix(width, height)
                requestRender()
            }
        }

        private fun updateMvpMatrix(width: Int, height: Int) {
            val r = renderer ?: return
            val aspectRatio: Float = if (height == 0) 1f else width.toFloat() / height.toFloat()
            val projectionMatrix = FloatArray(16)
            if (width > height) {
                Matrix.orthoM(projectionMatrix, 0, -aspectRatio, aspectRatio, -1f, 1f, -1f, 1f)
            } else {
                Matrix.orthoM(projectionMatrix, 0, -1f, 1f, -1f/aspectRatio, 1f/aspectRatio, -1f, 1f)
            }

            val viewMatrix = FloatArray(16)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
            Matrix.multiplyMM(r.onScreenMvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        }

        fun requestRender() {
            synchronized(this) {
                renderRequested = true
                (this as Object).notifyAll()
            }
        }

        fun requestStop() {
            isRunning = false
            synchronized(this) {
                (this as Object).notifyAll()
            }
        }

        fun setSurfaceReady(ready: Boolean) {
            synchronized(this) {
                surfaceReady = ready
                if (ready) {
                    (this as Object).notifyAll()
                } else {
                    Log.d(TAG_THREAD, "Surface marked as not ready.")
                }
            }
        }

        private fun cleanup() {
            Log.d(TAG_THREAD, "Cleaning up TextureView RenderThread resources.")
            // Detach context before releasing surface/core
             eglCore?.makeNothingCurrent()

            windowSurface?.release() // Releases EGLSurface
            windowSurface = null
            eglCore?.release() // Releases EGLDisplay and EGLContext
            eglCore = null

            val r = renderer
            if (r != null && r.onScreenProgram != 0) {
                // The EGL context is already released. Cannot make GL calls here.
                // GLES20.glDeleteProgram(r.onScreenProgram)
                r.onScreenProgram = 0
                Log.d(TAG_THREAD, "On-screen program ID reset.")
            }
            renderer?.sharedEglContext = EGL14.EGL_NO_CONTEXT // Ensure reset
        }
    }
}

// Minimal EGLCore and WindowSurface helper classes (can be expanded)
// These are often more complex in production code (e.g., from Grafika)

/**
 * Core EGL state (display, context, config).
 */
class EglCore(sharedContextToCreateWith: android.opengl.EGLContext? = null, flags: Int = 0) {
    private var eglDisplay: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: android.opengl.EGLConfig? = null

    companion object {
        const val FLAG_RECORDABLE = 0x01
        const val FLAG_TRY_GLES3 = 0x02 // Not used here, but common
    }
    class EglException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)


    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw EglException("unable to get EGL14 display. Error: " + EGL14.eglGetError())
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            val err = EGL14.eglGetError()
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw EglException("unable to initialize EGL14. Error: $err")
        }

        val wantGles3 = (flags and FLAG_TRY_GLES3) != 0
        var renderableType = EGL14.EGL_OPENGL_ES2_BIT
        // if (wantGles3) { ... GLES3 setup ... }

        val attribList = mutableListOf<Int>()
        attribList.add(EGL14.EGL_RED_SIZE)
        attribList.add(8)
        attribList.add(EGL14.EGL_GREEN_SIZE)
        attribList.add(8)
        attribList.add(EGL14.EGL_BLUE_SIZE)
        attribList.add(8)
        attribList.add(EGL14.EGL_ALPHA_SIZE)
        attribList.add(8)
        // attribList.add(EGL14.EGL_DEPTH_SIZE); attribList.add(16);
        attribList.add(EGL14.EGL_RENDERABLE_TYPE)
        attribList.add(renderableType)
        if ((flags and FLAG_RECORDABLE) != 0) {
            // attribList.add(EGL14.EGL_RECORDABLE_ANDROID); attribList.add(1);
        }
        attribList.add(EGL14.EGL_NONE)
        
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList.toIntArray(), 0, configs, 0, configs.size, numConfigs, 0)) {
            throw EglException("eglChooseConfig failed. Error: " + EGL14.eglGetError())
        }
        if (numConfigs[0] <= 0) {
            throw EglException("No EGL configs found.")
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, if (wantGles3) 3 else 2, 
            EGL14.EGL_NONE
        )
        // Use sharedContextToCreateWith for creating this EGL context
        val eglSharedContextParam = sharedContextToCreateWith ?: EGL14.EGL_NO_CONTEXT
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, eglSharedContextParam, contextAttribs, 0)
        checkEglError("eglCreateContext")
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw EglException("EGL context null after creation. Error: " + EGL14.eglGetError())
        }
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            // Clear current context before destroying anything.
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglReleaseThread() // Call for the current thread.
            EGL14.eglTerminate(eglDisplay) // Terminates the display connection.
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    fun getEGLContext(): android.opengl.EGLContext = eglContext
    fun getEGLDisplay(): android.opengl.EGLDisplay = eglDisplay
    fun getEGLConfig(): android.opengl.EGLConfig? = eglConfig

    protected fun finalize() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                Log.w("EglCore", "EglCore was not explicitly released -- state may be leaked")
                release()
            }
        } finally {
            // super.finalize() behavior if needed
        }
    }

    fun makeCurrent(surface: EglSurfaceBase) {
        makeCurrent(surface.getEGLSurface())
    }
    fun makeCurrent(eglSurface: android.opengl.EGLSurface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) Log.d("EglCore", "NOTE: makeCurrent w/o display")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw EglException("eglMakeCurrent failed: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
    }

    fun makeNothingCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
            throw EglException("makeNothingCurrent failed: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
    }

    fun swapBuffers(surface: EglSurfaceBase): Boolean {
        return swapBuffers(surface.getEGLSurface())
    }
    fun swapBuffers(eglSurface: android.opengl.EGLSurface): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    // Other methods like setPresentationTime, isCurrent, querySurface, queryString, getGlVersion omitted for brevity
    // but should be implemented similarly to previous EglCore if needed.

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw EglException("$msg: EGL error: 0x${Integer.toHexString(error)} (${GLUtils.getEGLErrorString(error)})")
        }
    }
    fun createWindowSurface(surface: Any): android.opengl.EGLSurface {
        if (surface !is SurfaceTexture && surface !is android.view.Surface) {
            throw EglException("invalid surface type: $surface")
        }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val resultSurface = EGL14.eglCreateWindowSurface(this.eglDisplay, this.eglConfig, surface, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
        if (resultSurface == EGL14.EGL_NO_SURFACE) {
            throw EglException("surface was null after eglCreateWindowSurface. Error: " + EGL14.eglGetError())
        }
        return resultSurface
    }
    fun createOffscreenSurface(width: Int, height: Int): android.opengl.EGLSurface {
        val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
        )
        val resultSurface = EGL14.eglCreatePbufferSurface(this.eglDisplay, this.eglConfig, surfaceAttribs, 0)
        checkEglError("eglCreatePbufferSurface")
        if (resultSurface == EGL14.EGL_NO_SURFACE) {
            throw EglException("surface was null after eglCreatePbufferSurface. Error: " + EGL14.eglGetError())
        }
        return resultSurface
    }
    fun releaseSurface(eglSurface: android.opengl.EGLSurface) {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(this.eglDisplay, eglSurface)
        } // No error check, destroying unknown surface is not an EGL error
    }
}


open class EglSurfaceBase(protected var eglCore: EglCore) {
    protected var eglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
    private var width = -1
    private var height = -1

    fun createWindowSurface(surface: Any) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) throw IllegalStateException("surface already created")
        eglSurface = eglCore.createWindowSurface(surface)
    }

    fun createOffscreenSurface(width: Int, height: Int) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) throw IllegalStateException("surface already created")
        eglSurface = eglCore.createOffscreenSurface(width, height)
        this.width = width
        this.height = height
    }
    fun getEGLSurface(): android.opengl.EGLSurface = eglSurface

    fun getWidth(): Int {
        return if (width < 0 && eglSurface != EGL14.EGL_NO_SURFACE) eglCore.querySurface(eglSurface, EGL14.EGL_WIDTH) else width
    }

    fun getHeight(): Int {
        return if (height < 0 && eglSurface != EGL14.EGL_NO_SURFACE) eglCore.querySurface(eglSurface, EGL14.EGL_HEIGHT) else height
    }

    fun releaseEglSurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            eglCore.releaseSurface(eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        width = -1
        height = -1
    }

    fun makeCurrent() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw EglCore.EglException("Cannot make current on an uninitialized EGL surface")
        eglCore.makeCurrent(eglSurface)
    }

    fun swapBuffers(): Boolean {
        return if (eglSurface != EGL14.EGL_NO_SURFACE) eglCore.swapBuffers(eglSurface) else false
    }
     fun EglCore.querySurface(surface: android.opengl.EGLSurface, what: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(this.getEGLDisplay(), surface, what, value, 0)
        // No error check on query usually
        return value[0]
    }
}

class WindowSurface(eglCore: EglCore, surface: SurfaceTexture, recordable: Boolean = false) : EglSurfaceBase(eglCore) {
    private var surfaceTexture: SurfaceTexture? = surface // Keep a reference if needed for recreate
    init {
        createWindowSurface(surface)
    }

    fun release() {
        releaseEglSurface()
        surfaceTexture = null 
    }

    // Recreate might be needed if SurfaceTexture is destroyed and a new one is provided.
    // For now, assume TextureView manages SurfaceTexture lifetime relative to listener calls.
}
