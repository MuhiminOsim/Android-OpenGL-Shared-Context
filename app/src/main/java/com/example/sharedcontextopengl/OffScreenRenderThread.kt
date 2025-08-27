package com.example.sharedcontextopengl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// Note: Ensure EglCore and other EGL utilities are accessible if used directly here,
// or that OffScreenRenderThread manages its EGL setup independently as it does now.
// For shared context, it needs EGL14.

class OffScreenRenderThread(
    private val context: Context, // Keep context if needed for resources, otherwise can remove
    private val sharedEglContext: android.opengl.EGLContext, // This is the key change
    private val width: Int,
    private val height: Int,
    private val onBitmapReady: (Bitmap) -> Unit
) : Thread() {

    private val TAG = "OffScreenRenderThread"
    @Volatile private var running = true
    @Volatile private var offScreenTriangleColor = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f) // Initial: Green
    @Volatile private var renderRequested = true // Start with a request to render the initial frame
    private val renderLock = Object()

    private var eglDisplay: android.opengl.EGLDisplay? = null
    private var eglContext: android.opengl.EGLContext? = null
    private var eglSurface: android.opengl.EGLSurface? = null

    private var fboId = 0
    private var textureId = 0
    private var renderBufferId = 0

    private lateinit var triangleVertices: FloatBuffer
    private var offScreenProgram: Int = 0
    private var offScreenPositionHandle: Int = 0
    private var offScreenMVPMatrixHandle: Int = 0
    private var offScreenColorHandle: Int = 0
    private val mvpMatrix = FloatArray(16)


    private val offScreenVertexShaderCode =
        "uniform mat4 uMVPMatrix;" +
        "attribute vec4 vPosition;" +
        "void main() {" +
        "  gl_Position = uMVPMatrix * vPosition;" +
        "}"

    private val offScreenFragmentShaderCode =
        "precision mediump float;" +
        "uniform vec4 uColor;" +
        "void main() {" +
        "  gl_FragColor = uColor;" +
        "}"

    private val triangleCoords = floatArrayOf(
        0.0f,  0.5f, 0.0f, // top
       -0.5f, -0.5f, 0.0f, // bottom left
        0.5f, -0.5f, 0.0f  // bottom right
    )

    fun setTriangleColor(r: Float, g: Float, b: Float) {
        offScreenTriangleColor = floatArrayOf(r, g, b, 1.0f)
        Log.d(TAG, "Set off-screen triangle color to: R=$r, G=$g, B=$b")
        requestRender()
    }

    fun requestRender() {
        synchronized(renderLock) {
            renderRequested = true
            renderLock.notifyAll()
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader $type:")
            Log.e(TAG, GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun initGLScene() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, offScreenVertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, offScreenFragmentShaderCode)

        if (vertexShader == 0 || fragmentShader == 0) {
            Log.e(TAG, "Shader compilation failed. Aborting scene init.")
            offScreenProgram = 0
            return
        }

        offScreenProgram = GLES20.glCreateProgram()
        if (offScreenProgram == 0) {
            Log.e(TAG, "Could not create off-screen program")
            return
        }
        GLES20.glAttachShader(offScreenProgram, vertexShader)
        GLES20.glAttachShader(offScreenProgram, fragmentShader)
        GLES20.glLinkProgram(offScreenProgram)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(offScreenProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link off-screen program: ")
            Log.e(TAG, GLES20.glGetProgramInfoLog(offScreenProgram))
            GLES20.glDeleteProgram(offScreenProgram)
            offScreenProgram = 0
            return
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        offScreenPositionHandle = GLES20.glGetAttribLocation(offScreenProgram, "vPosition")
        offScreenMVPMatrixHandle = GLES20.glGetUniformLocation(offScreenProgram, "uMVPMatrix")
        offScreenColorHandle = GLES20.glGetUniformLocation(offScreenProgram, "uColor")

        Log.d(TAG, "OffScreen Program ID: $offScreenProgram")
        Log.d(TAG, "OffScreen Position Handle (vPosition): $offScreenPositionHandle")
        Log.d(TAG, "OffScreen MVP Matrix Handle (uMVPMatrix): $offScreenMVPMatrixHandle")
        Log.d(TAG, "OffScreen Color Handle (uColor): $offScreenColorHandle")

        if (offScreenPositionHandle == -1) Log.w(TAG, "vPosition handle is -1.")
        if (offScreenMVPMatrixHandle == -1) Log.w(TAG, "uMVPMatrix handle is -1.")
        if (offScreenColorHandle == -1) Log.w(TAG, "uColor handle is -1.")

        val bb = ByteBuffer.allocateDirect(triangleCoords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        triangleVertices = bb.asFloatBuffer()
        triangleVertices.put(triangleCoords)
        triangleVertices.position(0)
    }


    override fun run() {
        if (!initEGLAndFBO()) { // This now uses the passed-in sharedEglContext
            Log.e(TAG, "EGL or FBO initialization failed.")
            return
        }
        initGLScene()

        if (offScreenProgram == 0) {
             Log.e(TAG, "OffScreen program not initialized. Cannot render.")
             cleanupGLResources()
             releaseEGLState()
             return
        }

        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        Matrix.orthoM(projectionMatrix, 0, -1f, 1f, -1f, 1f, -1f, 1f)
        Matrix.setIdentityM(viewMatrix, 0) // Simplified view matrix for off-screen
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        while (running) {
            var performRender = false
            synchronized(renderLock) {
                while (!renderRequested && running) {
                    try {
                        renderLock.wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Log.d(TAG, "OffScreenRenderThread interrupted while waiting. Stopping.")
                        running = false // Ensure loop terminates
                    }
                }
                if (renderRequested && running) {
                    performRender = true
                    renderRequested = false
                }
            }

            if (performRender && running) {
                // Ensure EGL context is current for this thread. This should have been set in initEGLAndFBO.
                // If not, a makeCurrent call might be needed here, but it should be setup by initEGLAndFBO.
                // GLES20.glViewport(0, 0, width, height) // Already set in initEGLAndFBO effectively by Pbuffer
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                GLES20.glViewport(0, 0, width, height) // Set viewport for FBO rendering

                GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f) // Grey background for off-screen
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                if (offScreenProgram != 0 && ::triangleVertices.isInitialized && offScreenColorHandle != -1) {
                    GLES20.glUseProgram(offScreenProgram)
                    GLES20.glEnableVertexAttribArray(offScreenPositionHandle)
                    GLES20.glVertexAttribPointer(offScreenPositionHandle, 3, GLES20.GL_FLOAT, false, 0, triangleVertices)
                    GLES20.glUniformMatrix4fv(offScreenMVPMatrixHandle, 1, false, mvpMatrix, 0)
                    GLES20.glUniform4fv(offScreenColorHandle, 1, offScreenTriangleColor, 0)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
                    GLES20.glDisableVertexAttribArray(offScreenPositionHandle)
                    GLES20.glUseProgram(0)
                }

                GLES20.glFinish() // Ensure all GL operations are complete before reading pixels
                val bitmap = createBitmapFromGLSurface(0, 0, width, height)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) // Unbind FBO

                if (bitmap != null) {
                    onBitmapReady(bitmap)
                }
            }
        }
        cleanupGLResources()
        releaseEGLState()
        Log.d(TAG, "OffScreenRenderThread finished.")
    }

    private fun createBitmapFromGLSurface(x: Int, y: Int, w: Int, h: Int): Bitmap? {
         val eglCurrentSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val eglCurrentContext = EGL14.eglGetCurrentContext()

        if (eglCurrentSurface == EGL14.EGL_NO_SURFACE || eglCurrentContext == EGL14.EGL_NO_CONTEXT) {
             Log.e(TAG, "No EGL context or surface current, cannot read pixels. Surface: $eglCurrentSurface, Context: $eglCurrentContext")
             return null
        }
    
        val pixelBuffer = ByteBuffer.allocateDirect(w * h * 4) // Each pixel is 4 bytes (RGBA)
        pixelBuffer.order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
        
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "glReadPixels error: " + error + " (" + android.opengl.GLUtils.getEGLErrorString(error) + ")")
            return null
        }
        pixelBuffer.rewind()

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(pixelBuffer)

        val matrix = android.graphics.Matrix()
        matrix.preScale(1.0f, -1.0f) // Flip the bitmap vertically
        val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, false)
        bitmap.recycle() // Recycle the original bitmap

        return flippedBitmap
    }


    private fun initEGLAndFBO(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            return false
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, // Ensure PBuffer surface
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] == 0) {
            Log.e(TAG, "eglChooseConfig failed or no config found: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            return false
        }
        val eglConfig = configs[0]!!

        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreatePbufferSurface failed: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            return false
        }
        
        // Use the provided sharedEglContext
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        Log.d(TAG, "Creating OffScreen EGL context. Shared context to use: $sharedEglContext")
        if (sharedEglContext == EGL14.EGL_NO_CONTEXT) {
            Log.w(TAG, "Warning: The provided sharedEglContext is EGL_NO_CONTEXT. OffScreen context will not be shared.")
        } else if (sharedEglContext == null) { // Explicitly checking for null, though EGLContext is non-nullable type usually
            Log.e(TAG, "Error: The provided sharedEglContext is null. Cannot create shared context.")
            // Depending on strictness, you might want to return false here
            // For now, will attempt to create a non-shared context if sharedEglContext is truly problematic
            // However, the constructor param `sharedEglContext: android.opengl.EGLContext` is non-null.
        }

        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedEglContext, contextAttribs, 0)

        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed for OffScreen: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            val error = EGL14.eglGetError()
            if (error == EGL14.EGL_BAD_CONTEXT) {
                 Log.e(TAG, "EGL_BAD_CONTEXT: Is the shared context valid and from the same EGLDisplay?")
            }
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed for OffScreen: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            return false
        }
        Log.d(TAG, "OffScreen EGL Context created and made current successfully.")
        return setupFBO()
    }

    private fun setupFBO(): Boolean {
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        fboId = fbo[0]

        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        textureId = texture[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val renderBuffer = IntArray(1)
        GLES20.glGenRenderbuffers(1, renderBuffer, 0)
        renderBufferId = renderBuffer[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBufferId)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textureId, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, renderBufferId)

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete: " + Integer.toHexString(status))
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        Log.d(TAG, "FBO setup complete. Texture ID: $textureId, FBO ID: $fboId")
        return true
    }

    private fun cleanupGLResources() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
            // Check if context is current *before* trying to delete resources
            if (EGL14.eglGetCurrentContext() == eglContext) {
                Log.d(TAG, "Cleaning up GL resources for OffScreenRenderThread. FBO: $fboId, Texture: $textureId, Program: $offScreenProgram")
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                GLES20.glDeleteRenderbuffers(1, intArrayOf(renderBufferId), 0)
                if (offScreenProgram != 0) {
                    GLES20.glDeleteProgram(offScreenProgram)
                }
            } else {
                Log.w(TAG, "Skipping GL resource cleanup for OffScreenRenderThread as its EGL context is not current. This might happen if cleanup is called from another thread or after context loss.")
            }
        } else {
            Log.w(TAG, "Skipping GL resource cleanup for OffScreenRenderThread as EGL display or context is not valid.")
        }
        fboId = 0
        textureId = 0
        renderBufferId = 0
        offScreenProgram = 0
        offScreenColorHandle = 0
    }

    private fun releaseEGLState() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            Log.d(TAG, "Releasing EGL state for OffScreenRenderThread.")
            // Important: Make this thread's context non-current *before* destroying it.
            if (EGL14.eglGetCurrentContext() == eglContext) {
                 EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            // According to EGL spec, eglReleaseThread should be called by each thread that has made an EGL context current.
            EGL14.eglReleaseThread()
            // eglTerminate should be called once when the application is done with EGL.
            // If display is shared, this might be called by the owner of the display.
            // For this thread, just ensure its resources are released.
            EGL14.eglTerminate(eglDisplay) // This might be too aggressive if display is shared; usually done once per app.
                                         // Consider only if this thread exclusively owns the display connection.
                                         // For now, keeping it as it was for self-contained cleanup.
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
         Log.d(TAG, "EGL state released for OffScreenRenderThread.")
    }

    fun stopRendering() {
        running = false
        synchronized(renderLock) {
            renderRequested = true // Allow the loop to proceed and check `running`
            renderLock.notifyAll()
        }
        Log.d(TAG, "stopRendering called on OffScreenRenderThread.")
    }
}
