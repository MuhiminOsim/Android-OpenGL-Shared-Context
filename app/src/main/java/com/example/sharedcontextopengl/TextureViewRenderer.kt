package com.example.sharedcontextopengl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer


class TextureViewRenderer(private val context: Context) : TextureView.SurfaceTextureListener {

    private val TAG = "TextureViewRenderer"
    private var renderThread: RenderThread? = null
    var eglCore: EglCore? = null
    var windowSurface: WindowSurface? = null

    private var sharedEglContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT

    val offScreenBitmapFlow = MutableStateFlow<Bitmap?>(null)
    private var offScreenRenderThread: OffScreenRenderThread? = null
    private val offScreenWidth = 2048
    private val offScreenHeight = 2048

    // Texture related properties
    private var earthTextureId: Int = 0
    private var flowerTextureId: Int = 0
    @Volatile private var currentTextureId: Int = 0
    private var textureToggle = false // false for earth, true for flower

    // Shader and geometry for textured quad
    private var onScreenProgram: Int = 0
    private var onScreenPositionHandle: Int = 0
    private var onScreenTexCoordHandle: Int = 0
    private var onScreenMVPMatrixHandle: Int = 0
    private var onScreenTextureSamplerHandle: Int = 0
    private lateinit var onScreenQuadVertices: FloatBuffer
    private lateinit var onScreenTexCoords: FloatBuffer
    private val onScreenMvpMatrix = FloatArray(16)

    private val texturedVertexShaderCode =
        "uniform mat4 uMVPMatrix;" +
        "attribute vec4 vPosition;" +
        "attribute vec2 aTexCoord;" +
        "varying vec2 vTexCoord;" +
        "void main() {" +
        "  gl_Position = uMVPMatrix * vPosition;" +
        "  vTexCoord = aTexCoord;" +
        "}"

    private val texturedFragmentShaderCode =
        "precision mediump float;" +
        "varying vec2 vTexCoord;" +
        "uniform sampler2D uTextureSampler;" +
        "void main() {" +
        "  gl_FragColor = texture2D(uTextureSampler, vTexCoord);" +
        "}"

    // A full-screen quad
    private val quadCoords = floatArrayOf(
        -1.0f,  1.0f, 0.0f, // top left
        -1.0f, -1.0f, 0.0f, // bottom left
         1.0f,  1.0f, 0.0f, // top right
         1.0f, -1.0f, 0.0f  // bottom right
    )
    // Texture coordinates (flip Y for Android Bitmaps)
    private val texCoords = floatArrayOf(
        0.0f, 0.0f, // top left
        0.0f, 1.0f, // bottom left
        1.0f, 0.0f, // top right
        1.0f, 1.0f  // bottom right
    )


    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureAvailable: $width x $height")
        renderThread = RenderThread(surface, context).apply {
            this.renderer = this@TextureViewRenderer
            start()
        }
        // OffScreenRenderThread initialization is now deferred to onRenderThreadFullyReady()
        renderThread?.onSurfaceTextureAvailable(width, height)
        requestRender()
    }

    // Called by RenderThread when EGL and GL scene (including textures) are ready
    fun onRenderThreadFullyReady() {
        Log.d(TAG, "RenderThread fully ready. Shared context: $sharedEglContext. Initializing OffScreenRenderThread.")
        if (sharedEglContext != EGL14.EGL_NO_CONTEXT && earthTextureId != 0 && flowerTextureId != 0) {
            initOffScreenRenderThread()
        } else {
            Log.e(TAG, "Cannot initialize OffScreenRenderThread: shared context or textures not ready. SharedCtx: $sharedEglContext, Earth: $earthTextureId, Flower: $flowerTextureId")
            // Optionally, attempt to stop/cleanup renderThread if it failed to provide context
            if (sharedEglContext == EGL14.EGL_NO_CONTEXT) {
                stopAndReleaseRenderThread()
            }
        }
    }

    private fun initOffScreenRenderThread() {
        // Ensure this is called only when sharedEglContext and textures are valid
        Log.d(TAG, "initOffScreenRenderThread called. Current texture ID for off-screen: $currentTextureId")
        offScreenBitmapFlow.value = null // Reset bitmap flow

        offScreenRenderThread?.stopRendering()
        try {
            offScreenRenderThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while joining previous OffScreenRenderThread")
        }
        offScreenRenderThread = null

        offScreenRenderThread = OffScreenRenderThread(
            offScreenWidth,
            offScreenHeight,
            sharedEglContext
        ) { newBitmap ->
            offScreenBitmapFlow.value = newBitmap
        }
        // currentTextureId should be set by RenderThread.setupGLScene before this point
        // and is the initial texture (e.g., earthTextureId)
        offScreenRenderThread?.setCurrentTexture(currentTextureId)
        offScreenRenderThread?.start()
        Log.d(TAG, "OffScreenRenderThread started with texture ID: $currentTextureId")
    }
    
    fun switchToNextImage() {
        textureToggle = !textureToggle
        currentTextureId = if (textureToggle) flowerTextureId else earthTextureId
        Log.d(TAG, "Switching to texture ID: $currentTextureId (Earth: $earthTextureId, Flower: $flowerTextureId)")
        requestRender() // Request on-screen render
        offScreenRenderThread?.setCurrentTexture(currentTextureId) // Update off-screen texture
    }


    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureSizeChanged: $width x $height")
        renderThread?.onSurfaceTextureSizeChanged(width, height)
        requestRender()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Log.d(TAG, "onSurfaceTextureDestroyed")
        stopAndReleaseRenderThread()
        return true
    }
    
    private fun stopAndReleaseRenderThread() {
        Log.d(TAG, "Stopping and releasing all render threads.")
        offScreenRenderThread?.stopRendering()
        try {
            offScreenRenderThread?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while joining OffScreenRenderThread during shutdown")
        }
        offScreenRenderThread = null
        offScreenBitmapFlow.value?.recycle()
        offScreenBitmapFlow.value = null

        renderThread?.let {
            it.requestStop()
            try {
                it.join(1000) 
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted while joining RenderThread during shutdown")
            }
        }
        renderThread = null
        sharedEglContext = EGL14.EGL_NO_CONTEXT // Reset shared context state
        Log.d(TAG, "All render threads stopped and resources released.")
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun requestRender() {
        renderThread?.requestRender()
    }

    fun onResume() {
        Log.d(TAG, "onResume")
        // If renderThread exists and is alive, it means surface was created.
        // If it's not alive but exists, it might be in the process of stopping.
        // If surface is available again, onSurfaceTextureAvailable will be called.
        renderThread?.let {
            if (it.isAlive) { // Only interact if fully started and running
                 it.setSurfaceReady(true) // Signal surface is ready for rendering operations
                 requestRender()
            } else {
                Log.w(TAG, "onResume: RenderThread is not alive or not yet fully initialized.")
            }
        }
    }

    fun onPause() {
        Log.d(TAG, "onPause")
        renderThread?.setSurfaceReady(false) // Signal surface is not ready for rendering operations
    }

    fun onDestroy() {
        Log.d(TAG, "onDestroy called for TextureViewRenderer")
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
    
    private fun loadTexture(context: Context, assetPath: String): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        if (textureIds[0] == 0) {
            Log.e(TAG, "Could not generate a new OpenGL texture object.")
            return 0
        }

        val bitmap: Bitmap? = try {
            context.assets.open(assetPath).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error loading texture $assetPath", e)
            null
        }

        if (bitmap == null) {
            Log.e(TAG, "Could not decode bitmap from asset: $assetPath")
            GLES20.glDeleteTextures(1, textureIds, 0)
            return 0
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bitmap.recycle()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0) // Unbind
        Log.d(TAG, "Loaded texture $assetPath with ID ${textureIds[0]}")
        return textureIds[0]
    }


    private inner class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        private val context: Context // Inner class also needs context if it were to load its own
    ) : HandlerThread("TextureViewRenderThread") {

        private val TAG_THREAD = "RenderThread(TV)"
        @Volatile var renderer: TextureViewRenderer? = null 

        private var eglCore: EglCore? = null
        private var windowSurface: WindowSurface? = null

        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        @Volatile private var isRunning = true
        @Volatile private var renderRequested = true 
        @Volatile private var surfaceReady = false // Managed by onResume/onPause and after GL setup

        override fun run() {
            Log.d(TAG_THREAD, "RenderThread starting.")
            val r = renderer ?: run {
                Log.e(TAG_THREAD, "Renderer not set. Thread stopping.")
                return
            }

            if (!setupEGL()) {
                Log.e(TAG_THREAD, "EGL setup failed. Thread stopping.")
                cleanup()
                r.sharedEglContext = EGL14.EGL_NO_CONTEXT 
                // Notify outer class of failure if necessary, though onRenderThreadFullyReady won't be called
                return
            }
            // Assign shared context to the outer class instance
            r.sharedEglContext = this.eglCore?.getEGLContext() ?: EGL14.EGL_NO_CONTEXT
            r.eglCore = this.eglCore 
            r.windowSurface = this.windowSurface 
            Log.d(TAG_THREAD, "EGL Context for sharing: ${r.sharedEglContext}")

            if (r.sharedEglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG_THREAD, "Shared EGL context is EGL_NO_CONTEXT after EGL setup. Thread stopping.")
                cleanup()
                return
            }

            if (!setupGLScene()) {
                Log.e(TAG_THREAD, "GL scene setup failed. Thread stopping.")
                cleanup()
                // Shared context might be valid, but textures failed to load.
                // onRenderThreadFullyReady will still be called, but its check might fail.
                r.onRenderThreadFullyReady() // Notify even on partial failure so initOffScreen can be skipped if needed
                return
            }
            
            surfaceReady = true // Surface is ready for drawing after EGL and GL setup
            r.onRenderThreadFullyReady() // Notify that EGL, shared context, and textures are ready

            while (isRunning) {
                synchronized(this) {
                    if (surfaceReady && renderRequested && screenWidth > 0 && screenHeight > 0) {
                        performRender()
                        renderRequested = false
                    } else {
                        try {
                            // Wait longer if surface is ready but no render requested, shorter if surface not ready
                            (this as Object).wait(if (surfaceReady && screenWidth > 0 && screenHeight > 0) 1000 else 100)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            isRunning = false 
                        }
                    }
                }
            }
            cleanupGLResources() // Cleanup GL resources before EGL
            cleanup()
            Log.d(TAG_THREAD, "RenderThread finished.")
        }

        private fun setupEGL(): Boolean {
            try {
                eglCore = EglCore() 
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
            val r = renderer ?: return false // Should have been checked earlier
            Log.d(TAG_THREAD, "Setting up GL scene for TextureView...")

            r.earthTextureId = r.loadTexture(r.context, "earth.jpg")
            r.flowerTextureId = r.loadTexture(r.context, "flower.jpg")
            if (r.earthTextureId == 0 || r.flowerTextureId == 0) {
                Log.e(TAG_THREAD, "Failed to load one or more textures.")
                // Don't set currentTextureId if loading failed
                return false
            }
            r.currentTextureId = r.earthTextureId // Default to earth

            val vertexShader = r.loadShader(GLES20.GL_VERTEX_SHADER, r.texturedVertexShaderCode)
            val fragmentShader = r.loadShader(GLES20.GL_FRAGMENT_SHADER, r.texturedFragmentShaderCode)

            if (vertexShader == 0 || fragmentShader == 0) {
                r.onScreenProgram = 0
                return false
            }

            r.onScreenProgram = GLES20.glCreateProgram()
            if (r.onScreenProgram == 0) {
                Log.e(TAG_THREAD, "Could not create on-screen program.")
                return false
            }

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
            r.onScreenTexCoordHandle = GLES20.glGetAttribLocation(r.onScreenProgram, "aTexCoord")
            r.onScreenMVPMatrixHandle = GLES20.glGetUniformLocation(r.onScreenProgram, "uMVPMatrix")
            r.onScreenTextureSamplerHandle = GLES20.glGetUniformLocation(r.onScreenProgram, "uTextureSampler")

            var bb = ByteBuffer.allocateDirect(r.quadCoords.size * 4)
            bb.order(ByteOrder.nativeOrder())
            r.onScreenQuadVertices = bb.asFloatBuffer()
            r.onScreenQuadVertices.put(r.quadCoords)
            r.onScreenQuadVertices.position(0)

            bb = ByteBuffer.allocateDirect(r.texCoords.size * 4)
            bb.order(ByteOrder.nativeOrder())
            r.onScreenTexCoords = bb.asFloatBuffer()
            r.onScreenTexCoords.put(r.texCoords)
            r.onScreenTexCoords.position(0)
            
            Log.d(TAG_THREAD, "GL scene setup complete for TextureView. Earth: ${r.earthTextureId}, Flower: ${r.flowerTextureId}, Current: ${r.currentTextureId}")
            return true
        }

        private fun performRender() {
            val r = renderer ?: return
            if (r.onScreenProgram == 0 || r.currentTextureId == 0) {
                Log.w(TAG_THREAD, "Program (${r.onScreenProgram}) or currentTextureId (${r.currentTextureId}) is 0, skipping render.")
                return
            }
            
            windowSurface?.makeCurrent() 

            GLES20.glViewport(0, 0, screenWidth, screenHeight)
            // GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f) // Red background for on-screen TextureView
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f) // Black background
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            GLES20.glUseProgram(r.onScreenProgram)
            
            r.onScreenQuadVertices.position(0)
            GLES20.glVertexAttribPointer(r.onScreenPositionHandle, 3, GLES20.GL_FLOAT, false, 0, r.onScreenQuadVertices)
            GLES20.glEnableVertexAttribArray(r.onScreenPositionHandle)

            r.onScreenTexCoords.position(0)
            GLES20.glVertexAttribPointer(r.onScreenTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, r.onScreenTexCoords)
            GLES20.glEnableVertexAttribArray(r.onScreenTexCoordHandle)

            GLES20.glUniformMatrix4fv(r.onScreenMVPMatrixHandle, 1, false, r.onScreenMvpMatrix, 0)
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, r.currentTextureId)
            GLES20.glUniform1i(r.onScreenTextureSamplerHandle, 0) // Tell sampler to use texture unit 0

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4) // Draw quad as triangle strip

            GLES20.glDisableVertexAttribArray(r.onScreenPositionHandle)
            GLES20.glDisableVertexAttribArray(r.onScreenTexCoordHandle)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glUseProgram(0)

            windowSurface?.swapBuffers() 
        }

        fun onSurfaceTextureAvailable(width: Int, height: Int) {
            synchronized(this) {
                Log.d(TAG_THREAD, "Surface available in thread: $width x $height")
                this.screenWidth = width
                this.screenHeight = height
                updateMvpMatrix(width, height)
                // surfaceReady is set after GL setup in run()
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
            // Set to identity matrix since the quad coordinates are already in clip space
            Matrix.setIdentityM(r.onScreenMvpMatrix, 0)
            // If you want to preserve aspect ratio, calculations are needed here based on image and view dimensions.
            // For example:
            // if (width > 0 && height > 0) {
            //    val aspectRatioView = width.toFloat() / height.toFloat()
            //    // Assuming texture has its own aspectRatio (textureWidth / textureHeight)
            //    // Matrix.orthoM(r.onScreenMvpMatrix, 0, -aspectRatioView, aspectRatioView, -1f, 1f, -1f, 1f)
            //    // Or adjust based on texture aspect ratio to prevent stretching
            // } else {
            //    Matrix.setIdentityM(r.onScreenMvpMatrix, 0)
            // }
        }

        fun requestRender() {
            synchronized(this) {
                if (!isRunning) return // Don't request render if thread is stopping
                renderRequested = true
                (this as Object).notifyAll()
            }
        }

        fun requestStop() {
            synchronized(this) {
                isRunning = false
                (this as Object).notifyAll() // Wake up if waiting
            }
        }

        fun setSurfaceReady(ready: Boolean) {
            synchronized(this) {
                surfaceReady = ready
                if (ready) {
                    Log.d(TAG_THREAD, "Surface marked as ready, requesting render.")
                    requestRender() // Request a render when surface becomes ready again
                } else {
                    Log.d(TAG_THREAD, "Surface marked as not ready.")
                }
            }
        }
        
        private fun cleanupGLResources() {
            val r = renderer ?: return
            Log.d(TAG_THREAD, "Cleaning up GL resources in TextureView RenderThread.")
            if (eglCore != null && eglCore!!.getEGLContext() != EGL14.EGL_NO_CONTEXT && windowSurface != null) {
                 windowSurface!!.makeCurrent() // Need context to delete GL resources
                 if (r.onScreenProgram != 0) {
                    GLES20.glDeleteProgram(r.onScreenProgram)
                    r.onScreenProgram = 0
                }
                val textureIds = intArrayOf(r.earthTextureId, r.flowerTextureId).filter { it != 0 }.toIntArray()
                if (textureIds.isNotEmpty()) {
                    GLES20.glDeleteTextures(textureIds.size, textureIds, 0)
                }
                r.earthTextureId = 0
                r.flowerTextureId = 0
                r.currentTextureId = 0
                Log.d(TAG_THREAD, "On-screen program and textures deleted.")
                eglCore!!.makeNothingCurrent()
            } else {
                Log.w(TAG_THREAD, "Skipping GL resource cleanup: EGL context or surface not available.")
            }
        }

        private fun cleanup() {
            Log.d(TAG_THREAD, "Cleaning up TextureView RenderThread EGL resources.")
            eglCore?.makeNothingCurrent() // Ensure nothing is current before releasing
            windowSurface?.release() 
            windowSurface = null
            eglCore?.release() 
            eglCore = null
            // renderer?.sharedEglContext = EGL14.EGL_NO_CONTEXT // This is handled by stopAndReleaseRenderThread
            Log.d(TAG_THREAD, "TextureView EGL resources cleaned up.")
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
        } 
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
        return value[0]
    }
}

class WindowSurface(eglCore: EglCore, surface: SurfaceTexture, recordable: Boolean = false) : EglSurfaceBase(eglCore) {
    private var surfaceTexture: SurfaceTexture? = surface
    init {
        createWindowSurface(surface)
    }

    fun release() {
        releaseEglSurface()
        surfaceTexture = null 
    }
}
