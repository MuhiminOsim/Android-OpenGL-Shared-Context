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
    // EglCore and WindowSurface are now managed by RenderThread primarily
    // private var eglCore: EglCore? = null // Managed by RenderThread
    // private var windowSurface: WindowSurface? = null // Managed by RenderThread

    @Volatile private var sharedEglContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT

    val offScreenBitmapFlow = MutableStateFlow<Bitmap?>(null)
    private var offScreenRenderThread: OffScreenRenderThread? = null
    private val offScreenWidth = 2048
    private val offScreenHeight = 2048

    // Texture related properties
    @Volatile private var earthTextureId: Int = 0
    @Volatile private var flowerTextureId: Int = 0
    @Volatile private var currentTextureId: Int = 0 // This will be the ID for the on-screen texture
    private var textureToggle = false // false for earth, true for flower

    // Shader and geometry for textured quad (on-screen)
    @Volatile private var onScreenProgram: Int = 0
    @Volatile private var onScreenPositionHandle: Int = 0
    @Volatile private var onScreenTexCoordHandle: Int = 0
    @Volatile private var onScreenMVPMatrixHandle: Int = 0
    @Volatile private var onScreenTextureSamplerHandle: Int = 0
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

    private val quadCoords = floatArrayOf(
        -1.0f,  1.0f, 0.0f, // top left
        -1.0f, -1.0f, 0.0f, // bottom left
        1.0f,  1.0f, 0.0f, // top right
        1.0f, -1.0f, 0.0f  // bottom right
    )
    private val texCoords = floatArrayOf(
        0.0f, 0.0f, // top left
        0.0f, 1.0f, // bottom left
        1.0f, 0.0f, // top right
        1.0f, 1.0f  // bottom right
    )


    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureAvailable: $width x $height")
        if (renderThread == null || !renderThread!!.isAlive) {
            renderThread = RenderThread(surface, context).apply {
                this.renderer = this@TextureViewRenderer
                start()
            }
            renderThread?.onSurfaceTextureAvailable(width, height) // Pass dimensions
        } else {
            Log.w(TAG, "onSurfaceTextureAvailable called but RenderThread already exists and is alive.")
            // Surface might be recreated, inform existing thread
            renderThread?.onSurfaceTextureAvailable(width, height) // Re-pass dimensions and surface
        }
        requestRender()
    }

    // Called by RenderThread when EGL and GL scene (including textures) are ready, or if setup failed
    fun onRenderThreadReady(isSuccess: Boolean, sharedCtx: android.opengl.EGLContext) {
        this.sharedEglContext = sharedCtx // Store the shared context (or EGL_NO_CONTEXT on failure)

        if (isSuccess && sharedEglContext != EGL14.EGL_NO_CONTEXT) {
            Log.d(TAG, "RenderThread fully ready. Shared context: $sharedEglContext. Initializing OffScreenRenderThread.")
            // Ensure textures are also loaded, currentTextureId should be set by RenderThread
            if (earthTextureId != 0 && flowerTextureId != 0 && currentTextureId != 0) {
                initOffScreenRenderThread()
            } else {
                Log.e(TAG, "Cannot initialize OffScreenRenderThread: Textures not loaded properly. Earth: $earthTextureId, Flower: $flowerTextureId, Current: $currentTextureId")
                // If shared context is valid but textures failed, we might not need to stop everything
                // but OffScreen cannot start.
            }
        } else {
            Log.e(TAG, "RenderThread setup failed or no valid shared context. SharedCtx: $sharedEglContext. Cannot initialize OffScreenRenderThread.")
            // If the on-screen render thread itself failed to provide a context, clean it up.
            if (sharedEglContext == EGL14.EGL_NO_CONTEXT) {
                stopAndReleaseRenderThread() // This will also nullify renderThread
            }
        }
    }

    private fun initOffScreenRenderThread() {
        Log.d(TAG, "initOffScreenRenderThread called. Current texture ID for off-screen: $currentTextureId")
        if (sharedEglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Aborting OffScreenRenderThread init: Shared EGL context is invalid.")
            return
        }
        if (currentTextureId == 0) {
            Log.e(TAG, "Aborting OffScreenRenderThread init: currentTextureId is 0. On-screen textures might have failed to load.")
            return
        }

        offScreenBitmapFlow.value?.recycle() // Recycle previous bitmap if any
        offScreenBitmapFlow.value = null

        offScreenRenderThread?.stopRendering()
        try {
            offScreenRenderThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while joining previous OffScreenRenderThread")
        }
        offScreenRenderThread = null

        Log.d(TAG, "Creating new OffScreenRenderThread with texture ID: $currentTextureId")
        offScreenRenderThread = OffScreenRenderThread(
            offScreenWidth,
            offScreenHeight,
            sharedEglContext // This must be valid
        ) { newBitmap ->
            offScreenBitmapFlow.value = newBitmap
        }
        offScreenRenderThread?.setCurrentTexture(currentTextureId) // Set initial texture
        offScreenRenderThread?.start()
        Log.d(TAG, "OffScreenRenderThread started with texture ID: $currentTextureId")
    }

    fun switchToNextImage() {
        textureToggle = !textureToggle
        val newTextureId = if (textureToggle) flowerTextureId else earthTextureId

        if (newTextureId == 0) {
            Log.w(TAG, "Cannot switch image: Target texture ID is 0. Earth: $earthTextureId, Flower: $flowerTextureId")
            return
        }
        currentTextureId = newTextureId
        Log.d(TAG, "Switching to texture ID: $currentTextureId (Earth: $earthTextureId, Flower: $flowerTextureId)")

        requestRender() // Request on-screen render with the new texture

        if (offScreenRenderThread != null && offScreenRenderThread!!.isAlive) {
            offScreenRenderThread?.setCurrentTexture(currentTextureId)
        } else {
            Log.w(TAG, "OffScreenRenderThread not available or not alive to switch texture.")
            // Optionally, re-initialize offScreenRenderThread if it's supposed to be running
            // if (sharedEglContext != EGL14.EGL_NO_CONTEXT && currentTextureId != 0) {
            //     Log.d(TAG, "Attempting to re-initialize OffScreenRenderThread after image switch.")
            //     initOffScreenRenderThread()
            // }
        }
    }


    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureSizeChanged: $width x $height")
        renderThread?.onSurfaceTextureSizeChanged(width, height)
        requestRender()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Log.d(TAG, "onSurfaceTextureDestroyed")
        stopAndReleaseRenderThread() // This now handles both threads
        return true
    }

    private fun stopAndReleaseRenderThread() {
        Log.d(TAG, "Stopping and releasing all render threads (TextureViewRenderer context).")

        offScreenRenderThread?.stopRendering()
        try {
            offScreenRenderThread?.join(1000) // Wait for off-screen thread to finish
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while joining OffScreenRenderThread during shutdown")
        }
        offScreenRenderThread = null
        offScreenBitmapFlow.value?.recycle()
        offScreenBitmapFlow.value = null
        Log.d(TAG, "OffScreenRenderThread stopped and resources released.")

        renderThread?.requestStop()
        try {
            renderThread?.join(1000) // Wait for on-screen render thread to finish
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while joining RenderThread during shutdown")
        }
        renderThread = null
        Log.d(TAG, "On-screen RenderThread stopped.")

        sharedEglContext = EGL14.EGL_NO_CONTEXT // Reset shared context state
        // Reset texture IDs as they are GL resources tied to the context
        earthTextureId = 0
        flowerTextureId = 0
        currentTextureId = 0
        onScreenProgram = 0
        Log.d(TAG, "All render threads and shared EGL context reset.")
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Typically not used for manual rendering
    }

    fun requestRender() {
        renderThread?.requestRender()
    }

    fun onResume() {
        Log.d(TAG, "onResume in TextureViewRenderer")
        renderThread?.let {
            if (it.isAlive) {
                it.setSurfaceReady(true)
                requestRender()
            } else {
                Log.w(TAG, "onResume: RenderThread is not alive. Surface may need to be recreated via onSurfaceTextureAvailable.")
                // If the thread is not alive, onSurfaceTextureAvailable should handle recreation.
            }
        }
        // If offScreenRenderThread was running and needs to resume, handle here
        // For now, it's driven by on-screen thread's readiness and image switches.
    }

    fun onPause() {
        Log.d(TAG, "onPause in TextureViewRenderer")
        renderThread?.setSurfaceReady(false)
        // offScreenRenderThread?.pauseRendering() // If such a method exists
    }

    fun onDestroy() {
        Log.d(TAG, "onDestroy called for TextureViewRenderer")
        stopAndReleaseRenderThread()
    }

    // This loadShader is for shaders used by TextureViewRenderer's RenderThread
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "glCreateShader failed for type $type. Shader code: $shaderCode")
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

    // This loadTexture is for textures used by TextureViewRenderer's RenderThread
    private fun loadTexture(context: Context, assetPath: String): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        if (textureIds[0] == 0) {
            Log.e(TAG, "Could not generate a new OpenGL texture object for $assetPath.")
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
        Log.d(TAG, "Loaded texture $assetPath (on-screen) with ID ${textureIds[0]}")
        return textureIds[0]
    }


    private inner class RenderThread(
        private var surfaceTextureInternal: SurfaceTexture, // Renamed to avoid confusion
        private val threadContext: Context
    ) : HandlerThread("TextureViewRenderThread") {

        private val TAG_THREAD = "RenderThread(TV)"
        @Volatile var renderer: TextureViewRenderer? = null // Outer class instance

        private var localEglCore: EglCore? = null // EGL setup for this thread
        private var localWindowSurface: WindowSurface? = null // Surface for this thread

        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        @Volatile private var isRunning = true
        @Volatile private var renderRequested = true
        @Volatile private var surfaceIsReady = false

        override fun run() {
            Log.d(TAG_THREAD, "RenderThread starting.")
            val r = renderer ?: run {
                Log.e(TAG_THREAD, "Renderer (outer class) not set. Thread stopping.")
                return
            }
            var eglSetupSuccess = false
            var glSceneSuccess = false
            var currentSharedCtx = EGL14.EGL_NO_CONTEXT

            try {
                if (!setupEGL(surfaceTextureInternal)) { // Pass current surfaceTexture
                    Log.e(TAG_THREAD, "EGL setup failed.")
                    // sharedEglContext in outer class will remain EGL_NO_CONTEXT or its old value
                } else {
                    eglSetupSuccess = true
                    currentSharedCtx = localEglCore?.getEGLContext() ?: EGL14.EGL_NO_CONTEXT
                    r.sharedEglContext = currentSharedCtx // Update outer class's shared context
                    Log.d(TAG_THREAD, "EGL Context for sharing: ${r.sharedEglContext}")

                    if (r.sharedEglContext == EGL14.EGL_NO_CONTEXT) {
                        Log.e(TAG_THREAD, "Shared EGL context is EGL_NO_CONTEXT even after EGL setup. This is a critical failure.")
                        eglSetupSuccess = false // Mark as failure
                    } else {
                        if (!setupGLScene(r)) { // Pass renderer instance
                            Log.e(TAG_THREAD, "GL scene setup failed.")
                            // Textures might not be loaded, currentTextureId might be 0
                        } else {
                            glSceneSuccess = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG_THREAD, "Exception during RenderThread EGL/GL setup", e)
                eglSetupSuccess = false
                glSceneSuccess = false
                currentSharedCtx = EGL14.EGL_NO_CONTEXT // Ensure it's marked invalid on exception
                r.sharedEglContext = currentSharedCtx
            } finally {
                // Notify the outer class about the readiness status (success or failure)
                // This call must happen regardless of success/failure to allow outer class to react.
                r.onRenderThreadReady(eglSetupSuccess && glSceneSuccess, currentSharedCtx)
            }

            if (!(eglSetupSuccess && glSceneSuccess)) {
                Log.e(TAG_THREAD, "RenderThread stopping due to setup failure. EGL: $eglSetupSuccess, GLScene: $glSceneSuccess")
                cleanup() // Clean up EGL resources if partially set up
                return // Exit thread if setup failed
            }

            surfaceIsReady = true // Mark surface ready for drawing after successful setup

            Log.d(TAG_THREAD, "RenderThread entering main loop.")
            while (isRunning) {
                synchronized(this) {
                    if (surfaceIsReady && renderRequested && screenWidth > 0 && screenHeight > 0) {
                        performRender(r)
                        renderRequested = false
                    } else {
                        try {
                            (this as Object).wait(if (surfaceIsReady && screenWidth > 0 && screenHeight > 0 && isRunning) 1000 else 100)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            isRunning = false
                        }
                    }
                }
            }
            // Cleanup GL resources first (needs current context)
            cleanupGLResources(r)
            // Then cleanup EGL
            cleanup()
            Log.d(TAG_THREAD, "RenderThread finished.")
        }

        private fun setupEGL(surface: SurfaceTexture): Boolean {
            try {
                localEglCore = EglCore()
                localWindowSurface = WindowSurface(localEglCore!!, surface)
                localWindowSurface!!.makeCurrent()
            } catch (e: EglCore.EglException) {
                Log.e(TAG_THREAD, "EGL setup failed: ${e.message}", e)
                localEglCore?.release() // Attempt to release if partially created
                localEglCore = null
                localWindowSurface = null // Ensure it's null on failure
                return false
            }
            Log.d(TAG_THREAD, "EGL Initialized successfully for TextureView.")
            return true
        }

        private fun setupGLScene(r: TextureViewRenderer): Boolean {
            Log.d(TAG_THREAD, "Setting up GL scene for TextureView...")

            r.earthTextureId = r.loadTexture(threadContext, "earth.jpg") // Use threadContext
            r.flowerTextureId = r.loadTexture(threadContext, "flower.jpg") // Use threadContext

            if (r.earthTextureId == 0 || r.flowerTextureId == 0) {
                Log.e(TAG_THREAD, "Failed to load one or more textures. Earth: ${r.earthTextureId}, Flower: ${r.flowerTextureId}")
                r.currentTextureId = 0 // Explicitly set to 0 on failure
                return false
            }
            // Successfully loaded textures, set default
            r.currentTextureId = r.earthTextureId
            Log.d(TAG_THREAD, "Textures loaded. Earth: ${r.earthTextureId}, Flower: ${r.flowerTextureId}. Initial currentTextureId: ${r.currentTextureId}")


            val vertexShader = r.loadShader(GLES20.GL_VERTEX_SHADER, r.texturedVertexShaderCode)
            val fragmentShader = r.loadShader(GLES20.GL_FRAGMENT_SHADER, r.texturedFragmentShaderCode)

            if (vertexShader == 0 || fragmentShader == 0) {
                Log.e(TAG_THREAD, "Failed to load shaders for on-screen rendering.")
                r.onScreenProgram = 0
                return false
            }

            r.onScreenProgram = GLES20.glCreateProgram()
            if (r.onScreenProgram == 0) {
                Log.e(TAG_THREAD, "Could not create on-screen program.")
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
                return false
            }

            GLES20.glAttachShader(r.onScreenProgram, vertexShader)
            GLES20.glAttachShader(r.onScreenProgram, fragmentShader)
            GLES20.glLinkProgram(r.onScreenProgram)
            GLES20.glDeleteShader(vertexShader) // Can delete after linking
            GLES20.glDeleteShader(fragmentShader)


            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(r.onScreenProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG_THREAD, "Could not link on-screen program: ${GLES20.glGetProgramInfoLog(r.onScreenProgram)}")
                GLES20.glDeleteProgram(r.onScreenProgram)
                r.onScreenProgram = 0
                return false
            }

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

            Log.d(TAG_THREAD, "GL scene setup complete for TextureView. Program: ${r.onScreenProgram}, CurrentTexture: ${r.currentTextureId}")
            return true
        }

        private fun performRender(r: TextureViewRenderer) {
            if (localWindowSurface == null || localEglCore == null) {
                Log.e(TAG_THREAD, "performRender: EGL Surface or Core is null. Skipping.")
                return
            }
            if (r.onScreenProgram == 0 || r.currentTextureId == 0) {
                // Log this state but still clear the screen to avoid stale frames
                Log.w(TAG_THREAD, "Program (${r.onScreenProgram}) or currentTextureId (${r.currentTextureId}) is 0. Clearing screen only.")
                localWindowSurface?.makeCurrent()
                GLES20.glViewport(0, 0, screenWidth, screenHeight)
                GLES20.glClearColor(0.0f, 0.0f, 0.1f, 1.0f) // Dark blue/grey for "no texture"
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                localWindowSurface?.swapBuffers()
                return
            }

            localWindowSurface!!.makeCurrent()

            GLES20.glViewport(0, 0, screenWidth, screenHeight)
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
            GLES20.glUniform1i(r.onScreenTextureSamplerHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(r.onScreenPositionHandle)
            GLES20.glDisableVertexAttribArray(r.onScreenTexCoordHandle)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glUseProgram(0)

            if (!localWindowSurface!!.swapBuffers()) {
                Log.e(TAG_THREAD, "eglSwapBuffers failed.")
            }
        }

        // Called when surface texture becomes available or is recreated
        fun onSurfaceTextureAvailable(newSurface: SurfaceTexture, width: Int, height: Int) {
            synchronized(this) {
                Log.d(TAG_THREAD, "Surface available/recreated in thread: $width x $height. New surface: $newSurface")
                this.surfaceTextureInternal = newSurface // Update to the new surface

                // If EGL is already set up, we might need to recreate the WindowSurface
                // For simplicity now, we assume full re-setup if onSurfaceTextureAvailable is called
                // after initial creation. If thread is already running, this logic might need refinement
                // or rely on onSurfaceTextureDestroyed -> new onSurfaceTextureAvailable cycle.

                this.screenWidth = width
                this.screenHeight = height
                updateMvpMatrix(renderer ?: return, width, height) // Pass renderer

                // If the thread is already past setup (i.e. isRunning and EGL/GL was ok),
                // it implies the surface was destroyed and recreated.
                // A full re-init of EGL surface might be needed if localWindowSurface is tied to old surface.
                // For now, setting surfaceIsReady to true and requesting render.
                // A more robust solution might involve recreating localWindowSurface here if localEglCore exists.
                if (localEglCore != null && isRunning) { // If EGL was set up
                    Log.d(TAG_THREAD, "EGL core exists, attempting to recreate window surface for new SurfaceTexture.")
                    localWindowSurface?.releaseEglSurface() // Release old EGL surface
                    try {
                        localWindowSurface = WindowSurface(localEglCore!!, newSurface) // Create with new one
                        surfaceIsReady = true // Mark as ready again
                    } catch (e: EglCore.EglException) {
                        Log.e(TAG_THREAD, "Failed to recreate window surface for new SurfaceTexture", e)
                        surfaceIsReady = false // Could not re-establish surface
                        isRunning = false // Critical failure, stop thread
                        renderer?.onRenderThreadReady(false, EGL14.EGL_NO_CONTEXT) // Signal failure
                    }
                }
                requestRender()
            }
        }

        fun onSurfaceTextureAvailable(width: Int, height: Int) { // Overload for initial call with existing ST
            synchronized(this) {
                Log.d(TAG_THREAD, "Surface available (initial) in thread: $width x $height")
                this.screenWidth = width
                this.screenHeight = height
                updateMvpMatrix(renderer ?: return, width, height)
                requestRender()
            }
        }


        fun onSurfaceTextureSizeChanged(width: Int, height: Int) {
            synchronized(this) {
                Log.d(TAG_THREAD, "Surface size changed in thread: $width x $height")
                this.screenWidth = width
                this.screenHeight = height
                updateMvpMatrix(renderer ?: return, width, height)
                requestRender()
            }
        }

        private fun updateMvpMatrix(r: TextureViewRenderer, width: Int, height: Int) {
            Matrix.setIdentityM(r.onScreenMvpMatrix, 0)
            // Aspect ratio correction could be done here if desired
        }

        fun requestRender() {
            synchronized(this) {
                if (!isRunning) {
                    Log.w(TAG_THREAD, "requestRender called but thread is not running.")
                    return
                }
                renderRequested = true
                (this as Object).notifyAll()
            }
        }

        fun requestStop() {
            synchronized(this) {
                Log.d(TAG_THREAD, "Stop requested for RenderThread.")
                isRunning = false
                (this as Object).notifyAll()
            }
        }

        fun setSurfaceReady(ready: Boolean) {
            synchronized(this) {
                surfaceIsReady = ready
                if (ready) {
                    Log.d(TAG_THREAD, "Surface marked as ready, requesting render.")
                    requestRender()
                } else {
                    Log.d(TAG_THREAD, "Surface marked as not ready.")
                }
            }
        }

        private fun cleanupGLResources(r: TextureViewRenderer) {
            if (localEglCore == null || localWindowSurface == null || localEglCore?.getEGLContext() == EGL14.EGL_NO_CONTEXT) {
                Log.w(TAG_THREAD, "Skipping GL resource cleanup: EGL context or surface not available/valid.")
                return
            }
            Log.d(TAG_THREAD, "Cleaning up GL resources in TextureView RenderThread.")
            try {
                localWindowSurface!!.makeCurrent()
                if (r.onScreenProgram != 0) {
                    GLES20.glDeleteProgram(r.onScreenProgram)
                    r.onScreenProgram = 0
                }
                val textureIds = intArrayOf(r.earthTextureId, r.flowerTextureId).filter { it != 0 }.toIntArray()
                if (textureIds.isNotEmpty()) {
                    GLES20.glDeleteTextures(textureIds.size, textureIds, 0)
                    Log.d(TAG_THREAD, "Deleted textures: ${textureIds.joinToString()}")
                }
                r.earthTextureId = 0
                r.flowerTextureId = 0
                r.currentTextureId = 0
                Log.d(TAG_THREAD, "On-screen program and textures deleted.")
                localEglCore!!.makeNothingCurrent()
            } catch (e: EglCore.EglException) {
                Log.e(TAG_THREAD, "Exception during GL resource cleanup: ${e.message}", e)
            }
        }

        private fun cleanup() { // EGL cleanup
            Log.d(TAG_THREAD, "Cleaning up TextureView RenderThread EGL resources.")
            localEglCore?.makeNothingCurrent() // Best effort
            localWindowSurface?.release()
            localWindowSurface = null
            localEglCore?.release()
            localEglCore = null
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
    private var eglConfig: android.opengl.EGLConfig? = null // Store the chosen config

    companion object {
        const val FLAG_RECORDABLE = 0x01
        const val FLAG_TRY_GLES3 = 0x02 // Not used here, but common
        private const val TAG = "EglCore"
    }
    class EglException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)


    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw EglException("unable to get EGL14 display. Error: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            val err = EGL14.eglGetError()
            eglDisplay = EGL14.EGL_NO_DISPLAY // Prevent use if initialization failed
            throw EglException("unable to initialize EGL14. Error: ${GLUtils.getEGLErrorString(err)}")
        }

        val wantGles3 = (flags and FLAG_TRY_GLES3) != 0
        val renderableType = EGL14.EGL_OPENGL_ES2_BIT

        val attribList = mutableListOf<Int>()
        attribList.add(EGL14.EGL_RED_SIZE)
        attribList.add(8)
        attribList.add(EGL14.EGL_GREEN_SIZE)
        attribList.add(8)
        attribList.add(EGL14.EGL_BLUE_SIZE)
        attribList.add(8)
        attribList.add(EGL14.EGL_ALPHA_SIZE)
        attribList.add(8)
        // attribList.add(EGL14.EGL_DEPTH_SIZE); attribList.add(16); // Uncomment if depth buffer needed for pbuffers/windows
        attribList.add(EGL14.EGL_RENDERABLE_TYPE)
        attribList.add(renderableType)
        if ((flags and FLAG_RECORDABLE) != 0) {
            // attribList.add(EGL14.EGL_RECORDABLE_ANDROID); attribList.add(1); // Needs EGL_ANDROID_recordable extension
        }
        attribList.add(EGL14.EGL_NONE)

        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList.toIntArray(), 0, configs, 0, configs.size, numConfigs, 0)) {
            throw EglException("eglChooseConfig failed. Error: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
        if (numConfigs[0] <= 0) {
            // Fallback for emulators or devices that don't support GLES3 well if FLAG_TRY_GLES3 was set
            if (wantGles3) {
                Log.w(TAG, "No GLES3 config found, trying GLES2.")
                // Remove GLES3 specific bit and try again (simplified, real fallback is more complex)
                val idx = attribList.indexOf(EGL14.EGL_RENDERABLE_TYPE)
                if (idx != -1 && idx + 1 < attribList.size) {
                    attribList[idx+1] = EGL14.EGL_OPENGL_ES2_BIT
                    if (!EGL14.eglChooseConfig(eglDisplay, attribList.toIntArray(), 0, configs, 0, configs.size, numConfigs, 0)) {
                        throw EglException("eglChooseConfig failed for GLES2 fallback. Error: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
                    }
                    if (numConfigs[0] <= 0) throw EglException("No EGL configs found even for GLES2.")
                } else {
                    throw EglException("No EGL GLES3 configs found, and fallback setup failed.")
                }
            } else {
                throw EglException("No EGL configs found.")
            }
        }
        eglConfig = configs[0]!! // Config must be non-null here

        val contextAttribsList = mutableListOf<Int>()
        contextAttribsList.add(EGL14.EGL_CONTEXT_CLIENT_VERSION)
        contextAttribsList.add(2)
        contextAttribsList.add(EGL14.EGL_NONE)

        val eglSharedContextParam = sharedContextToCreateWith ?: EGL14.EGL_NO_CONTEXT
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, eglSharedContextParam, contextAttribsList.toIntArray(), 0)
        checkEglError("eglCreateContext")
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            // Log details if context creation failed with a shared context
            if (eglSharedContextParam != EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext failed when trying to use shared context: $eglSharedContextParam. Error: ${GLUtils.getEGLErrorString(EGL14.eglGetError())}")
            }
            throw EglException("EGL context null after creation. Error: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        Log.d(TAG, "EglCore released.")
    }

    fun getEGLContext(): android.opengl.EGLContext = eglContext
    // fun getEGLDisplay(): android.opengl.EGLDisplay = eglDisplay // Less commonly needed externally
    // fun getEGLConfig(): android.opengl.EGLConfig? = eglConfig // Less commonly needed externally

    protected fun finalize() { // Use with caution, explicit release is better
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                Log.w(TAG, "EglCore was not explicitly released -- state may be leaked.")
                release()
            }
        } finally {
            // super.finalize() // If inheriting from a class with a meaningful finalize
        }
    }

    fun makeCurrent(surface: EglSurfaceBase) {
        makeCurrent(surface.getEGLSurface())
    }
    fun makeCurrent(eglSurface: android.opengl.EGLSurface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "makeCurrent called with no EGLDisplay.") // Should not happen if initialized
            return
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw EglException("eglMakeCurrent failed: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
    }

    fun makeNothingCurrent() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            // Log.d(TAG, "makeNothingCurrent called with no EGLDisplay (already released or not init).");
            return
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
            // This can happen if the context/display is already dead. Don't throw fatal error if trying to clean up.
            Log.w(TAG, "makeNothingCurrent failed: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
            // Consider not throwing EglException here if it's part of a cleanup sequence
            // where the EGL state might already be partially torn down.
            // throw EglException("makeNothingCurrent failed: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
    }

    fun swapBuffers(surface: EglSurfaceBase): Boolean {
        return swapBuffers(surface.getEGLSurface())
    }
    fun swapBuffers(eglSurface: android.opengl.EGLSurface): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
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
            throw EglException("invalid surface type: ${surface::class.java.name}")
        }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val resultSurface = EGL14.eglCreateWindowSurface(this.eglDisplay, this.eglConfig!!, surface, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
        if (resultSurface == EGL14.EGL_NO_SURFACE) {
            throw EglException("surface was null after eglCreateWindowSurface. Error: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
        return resultSurface
    }
    fun createOffscreenSurface(width: Int, height: Int): android.opengl.EGLSurface { // Also known as Pbuffer
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val resultSurface = EGL14.eglCreatePbufferSurface(this.eglDisplay, this.eglConfig!!, surfaceAttribs, 0)
        checkEglError("eglCreatePbufferSurface for OffScreenRenderThread")
        if (resultSurface == EGL14.EGL_NO_SURFACE) {
            throw EglException("Pbuffer surface was null after eglCreatePbufferSurface. Error: " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
        }
        return resultSurface
    }
    fun releaseSurface(eglSurface: android.opengl.EGLSurface) {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(this.eglDisplay, eglSurface)
        }
    }
    fun querySurface(surface: android.opengl.EGLSurface, what: Int): Int {
        val value = IntArray(1)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglQuerySurface(this.eglDisplay, surface, what, value, 0)
        }
        return value[0]
    }
}


open class EglSurfaceBase(protected var eglCore: EglCore) {
    protected var eglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceWidth = -1 // Store dimensions if created with them (pbuffer)
    private var surfaceHeight = -1

    // For window surfaces (SurfaceTexture, SurfaceView)
    fun createWindowSurface(surface: Any) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) throw IllegalStateException("surface already created")
        eglSurface = eglCore.createWindowSurface(surface)
        // Width and height for window surfaces are typically queried
        surfaceWidth = -1
        surfaceHeight = -1
    }

    // For pbuffer surfaces
    fun createOffscreenSurface(width: Int, height: Int) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) throw IllegalStateException("surface already created")
        eglSurface = eglCore.createOffscreenSurface(width, height)
        this.surfaceWidth = width
        this.surfaceHeight = height
    }
    fun getEGLSurface(): android.opengl.EGLSurface = eglSurface

    fun getWidth(): Int {
        return if (surfaceWidth < 0 && eglSurface != EGL14.EGL_NO_SURFACE) {
            eglCore.querySurface(eglSurface, EGL14.EGL_WIDTH)
        } else {
            surfaceWidth
        }
    }

    fun getHeight(): Int {
        return if (surfaceHeight < 0 && eglSurface != EGL14.EGL_NO_SURFACE) {
            eglCore.querySurface(eglSurface, EGL14.EGL_HEIGHT)
        } else {
            surfaceHeight
        }
    }

    fun releaseEglSurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            eglCore.releaseSurface(eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        surfaceWidth = -1 // Reset stored dimensions
        surfaceHeight = -1
    }

    fun makeCurrent() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw EglCore.EglException("Cannot make current on an uninitialized EGL surface")
        eglCore.makeCurrent(eglSurface)
    }

    fun swapBuffers(): Boolean {
        return if (eglSurface != EGL14.EGL_NO_SURFACE) eglCore.swapBuffers(eglSurface) else false
    }
}

class WindowSurface(eglCore: EglCore, surface: SurfaceTexture, recordable: Boolean = false) : EglSurfaceBase(eglCore) {
    // Keep a reference to the Android surface. Release doesn't release it, just the EGLSurface.
    // The SurfaceTexture (or Surface) is managed by the Android framework.
    private var androidSurface: SurfaceTexture? = surface
    init {
        createWindowSurface(surface) // Call superclass method
    }

    fun release() { // Releases EGL surface
        releaseEglSurface()
        androidSurface = null // Nullify our reference, not the actual SurfaceTexture
    }
}
