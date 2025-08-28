package com.example.sharedcontextopengl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class OffScreenRenderThread(
    private val width: Int,
    private val height: Int,
    private val sharedEglContext: android.opengl.EGLContext,
    private val onBitmapReady: (Bitmap) -> Unit
) : Thread() {

    private val TAG = "OffScreenRenderThrd" // Consistent Tag
    @Volatile private var running = true
    @Volatile private var renderRequested = true // Render once on start
    private val renderLock = Object()

    private var eglDisplay: android.opengl.EGLDisplay? = null
    private var eglContext: android.opengl.EGLContext? = null
    private var eglSurface: android.opengl.EGLSurface? = null

    // FBO
    private var fboId = 0
    private var fboTextureId = 0 // Texture attached to FBO for color
    private var renderBufferId = 0 // For FBO's depth buffer

    // GL Program and handles
    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var textureSamplerHandle: Int = 0
    private val mvpMatrix = FloatArray(16)

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer

    @Volatile private var currentTextureToRenderId: Int = 0

    private val vertexShaderCode =
        "uniform mat4 uMVPMatrix;" +
        "attribute vec4 aPosition;" +
        "attribute vec2 aTexCoord;" +
        "varying vec2 vTexCoord;" +
        "void main() {" +
        "  gl_Position = uMVPMatrix * aPosition;" +
        "  vTexCoord = aTexCoord;" +
        "}"

    private val fragmentShaderCode =
        "precision mediump float;" +
        "varying vec2 vTexCoord;" +
        "uniform sampler2D uTexture;" +
        "void main() {" +
        "  gl_FragColor = texture2D(uTexture, vTexCoord);" +
        "}"

    private val quadVertices = floatArrayOf(
        -1.0f,  1.0f, 0.0f, // top left
        -1.0f, -1.0f, 0.0f, // bottom left
         1.0f,  1.0f, 0.0f, // top right
         1.0f, -1.0f, 0.0f  // bottom right
    )

    private val quadTexCoords = floatArrayOf(
        0.0f, 0.0f, // top left
        0.0f, 1.0f, // bottom left
        1.0f, 0.0f, // top right
        1.0f, 1.0f  // bottom right
    )

    init {
        name = "OffScreenRenderThrd"
    }

    fun requestRender() {
        synchronized(renderLock) {
            renderRequested = true
            renderLock.notifyAll()
        }
    }

    fun setCurrentTexture(textureId: Int) {
        Log.d(TAG, "OffScreenRenderThread received texture ID: $textureId")
        currentTextureToRenderId = textureId
        requestRender()
    }

    fun stopRendering() {
        running = false
        requestRender() // Wake up the thread if it's waiting
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader != 0) {
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
        }
        return shader
    }

    private fun initGLScene(): Boolean {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        if (vertexShader == 0) {
            Log.e(TAG, "Failed to load vertex shader.")
            return false
        }

        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        if (fragmentShader == 0) {
            Log.e(TAG, "Failed to load fragment shader.")
            GLES20.glDeleteShader(vertexShader)
            return false
        }

        program = GLES20.glCreateProgram()
        if (program == 0) {
            Log.e(TAG, "Could not create GL program.")
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return false
        }

        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ")
            Log.e(TAG, GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            program = 0
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return false
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        textureSamplerHandle = GLES20.glGetUniformLocation(program, "uTexture")

        Log.d(TAG, "OffScreen Program ID: $program, Position: $positionHandle, TexCoord: $texCoordHandle, MVP: $mvpMatrixHandle, Sampler: $textureSamplerHandle")

        if (positionHandle == -1 || texCoordHandle == -1 || mvpMatrixHandle == -1 || textureSamplerHandle == -1) {
            Log.e(TAG, "Failed to get handle for one or more shader variables. Position: $positionHandle, TexCoord: $texCoordHandle, MVP: $mvpMatrixHandle, Sampler: $textureSamplerHandle")
            if (program != 0) {
                 GLES20.glDeleteProgram(program)
                 program = 0
            }
            return false
        }

        var bb = ByteBuffer.allocateDirect(quadVertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(quadVertices)
        vertexBuffer.position(0)

        bb = ByteBuffer.allocateDirect(quadTexCoords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        texCoordBuffer = bb.asFloatBuffer()
        texCoordBuffer.put(quadTexCoords)
        texCoordBuffer.position(0)

        Matrix.orthoM(mvpMatrix, 0, -1f, 1f, -1f, 1f, -1f, 1f)
        Log.d(TAG, "OffScreen GL scene setup complete.")
        return true
    }


    override fun run() {
        try {
            if (!initEGLAndFBO()) {
                Log.e(TAG, "EGL or FBO initialization failed.")
                return
            }
            if (!initGLScene()) { // Check return value
                Log.e(TAG, "GL scene initialization failed.")
                cleanupGLResources() // Clean up FBO
                releaseEGLState()    // Clean up EGL
                return
            }

            Log.d(TAG, "OffScreenRenderThread starting render loop. Initial texture ID: $currentTextureToRenderId")

            while (running) {
                var performRender = false
                synchronized(renderLock) {
                    while (!renderRequested && running) {
                        try {
                            renderLock.wait()
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            running = false // Exit if interrupted
                        }
                    }
                    if (renderRequested && running) {
                        performRender = true
                        renderRequested = false
                    }
                }

                if (performRender && running) {
                    if (program == 0) { // Double check program state
                        Log.e(TAG, "Render requested but program is 0. Skipping.")
                        continue
                    }
                     if (currentTextureToRenderId == 0) {
                        Log.w(TAG, "Render requested but currentTextureToRenderId is 0. Skipping draw, but will clear.")
                        // We can still clear to a color or do nothing.
                        // For now, let's just clear and try to generate a bitmap to see if the pipeline works.
                    }

                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                    GLES20.glViewport(0, 0, width, height)
                    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f) // Dark grey background
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                    if (currentTextureToRenderId != 0) { // Only draw if texture is valid
                        GLES20.glUseProgram(program)

                        vertexBuffer.position(0)
                        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                        GLES20.glEnableVertexAttribArray(positionHandle)

                        texCoordBuffer.position(0)
                        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
                        GLES20.glEnableVertexAttribArray(texCoordHandle)

                        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTextureToRenderId)
                        GLES20.glUniform1i(textureSamplerHandle, 0)

                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                        GLES20.glDisableVertexAttribArray(positionHandle)
                        GLES20.glDisableVertexAttribArray(texCoordHandle)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0) // Unbind texture
                        GLES20.glUseProgram(0)
                    } else {
                         Log.w(TAG, "Skipping GL draw commands as texture ID is 0.")
                    }

                    GLES20.glFinish() // Ensure all GL commands are processed

                    val bitmap = createBitmapFromGLSurface(0, 0, width, height)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) // Unbind FBO

                    if (bitmap != null) {
                        Log.d(TAG, "Bitmap created successfully, invoking onBitmapReady.")
                        onBitmapReady(bitmap)
                    } else {
                        Log.e(TAG, "Failed to create bitmap from GL surface.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OffScreenRenderThread encountered an error", e)
        } finally {
            cleanupGLResources()
            releaseEGLState()
            Log.d(TAG, "OffScreenRenderThread finished.")
        }
    }

    private fun createBitmapFromGLSurface(x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val pixelBuffer = ByteBuffer.allocateDirect(w * h * 4) // RGBA
        pixelBuffer.order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "glReadPixels error: " + error + " (" + android.opengl.GLUtils.getEGLErrorString(error) + ")")
            return null
        }
        pixelBuffer.rewind()

        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixelBuffer)

            // Flip bitmap vertically because GL Y-coordinates are often opposite to Bitmap Y-coordinates
            val matrix = android.graphics.Matrix()
            matrix.preScale(1.0f, -1.0f)
            val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, false)
            bitmap.recycle() // Recycle the original bitmap
            flippedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error creating bitmap from pixel buffer", e)
            null
        }
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
        Log.d(TAG, "EGL version: ${version[0]}.${version[1]}")

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, // We need a Pbuffer surface
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] == 0) {
            Log.e(TAG, "eglChooseConfig failed: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            return false
        }
        val eglConfig = configs[0]!!

        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreatePbufferSurface failed: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            return false
        }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        Log.d(TAG, "Creating EGL context with shared context: $sharedEglContext")
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedEglContext, contextAttribs, 0)

        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed for OffScreen: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            if (sharedEglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "The sharedEglContext provided was EGL_NO_CONTEXT!")
            }
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed for OffScreen: " + android.opengl.GLUtils.getEGLErrorString(EGL14.eglGetError()))
            return false
        }
        Log.d(TAG, "OffScreen EGL setup complete.")
        return setupFBO()
    }

    private fun setupFBO(): Boolean {
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        fboId = fbo[0]
        if (fboId == 0) {
            Log.e(TAG, "Failed to generate FBO ID.")
            return false
        }

        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        fboTextureId = texture[0]
        if (fboTextureId == 0) {
            Log.e(TAG, "Failed to generate FBO texture ID.")
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0) // Clean up FBO
            fboId = 0
            return false
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val renderBuffer = IntArray(1)
        GLES20.glGenRenderbuffers(1, renderBuffer, 0)
        renderBufferId = renderBuffer[0]
        if (renderBufferId == 0) {
            Log.e(TAG, "Failed to generate render buffer ID.")
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            fboTextureId = 0
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
            return false
        }
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBufferId)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, renderBufferId)

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete: " + Integer.toHexString(status))
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) // Unbind before cleanup
            GLES20.glDeleteRenderbuffers(1, intArrayOf(renderBufferId), 0)
            renderBufferId = 0
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            fboTextureId = 0
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
            return false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        Log.d(TAG, "FBO setup complete. FBO ID: $fboId, FBO Texture ID: $fboTextureId")
        return true
    }

    private fun cleanupGLResources() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (fboTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            fboTextureId = 0
        }
        if (renderBufferId != 0) {
            GLES20.glDeleteRenderbuffers(1, intArrayOf(renderBufferId), 0)
            renderBufferId = 0
        }
    }

    private fun releaseEGLState() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            // Release the current context
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            // EGL14.eglTerminate(eglDisplay) // Let TextureViewRenderer terminate the display potentially
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
