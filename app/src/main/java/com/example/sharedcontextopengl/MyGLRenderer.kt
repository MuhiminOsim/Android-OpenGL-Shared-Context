package com.example.sharedcontextopengl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix // Added for Matrix operations
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig // Still used by GLSurfaceView.Renderer
import javax.microedition.khronos.opengles.GL10 // Still used by GLSurfaceView.Renderer

class MyGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val TAG = "MyGLRenderer"
    val offScreenBitmapFlow = MutableStateFlow<Bitmap?>(null)

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var offScreenRenderThread: OffScreenRenderThread? = null
    private val offScreenWidth = 1024
    private val offScreenHeight = 1024

    // Properties for on-screen rendering
    private var onScreenProgram: Int = 0
    private var onScreenPositionHandle: Int = 0
    private var onScreenMVPMatrixHandle: Int = 0
    private var onScreenColorHandle: Int = 0 // New: Handle for color uniform
    private lateinit var onScreenTriangleVertices: FloatBuffer
    private val onScreenMvpMatrix = FloatArray(16)
    private var onScreenTriangleColor = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f) // Initial: Green

    private val onScreenVertexShaderCode =
        "uniform mat4 uMVPMatrix;" +
        "attribute vec4 vPosition;" +
        "void main() {" +
        "  gl_Position = uMVPMatrix * vPosition;" +
        "}"

    private val onScreenFragmentShaderCode = // Modified for uColor
        "precision mediump float;" +
        "uniform vec4 uColor;" + // New: Color uniform
        "void main() {" +
        "  gl_FragColor = uColor;" +
        "}"

    private val onScreenTriangleCoords = floatArrayOf(
        0.0f,  0.5f, 0.0f, // top
       -0.5f, -0.5f, 0.0f, // bottom left
        0.5f, -0.5f, 0.0f  // bottom right
    )

    fun setTriangleColor(r: Float, g: Float, b: Float) {
        onScreenTriangleColor = floatArrayOf(r, g, b, 1.0f)
        offScreenRenderThread?.setTriangleColor(r, g, b)
        Log.d(TAG, "Set triangle color to: R=$r, G=$g, B=$b")
    }


    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f) // Red background for on-screen TEST

        // Initialize for on-screen rendering
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, onScreenVertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, onScreenFragmentShaderCode)

        if (vertexShader == 0 || fragmentShader == 0) {
            Log.e(TAG, "On-screen shader compilation failed. Aborting on-screen scene init.")
            onScreenProgram = 0
        } else {
            onScreenProgram = GLES20.glCreateProgram()
            if (onScreenProgram == 0) {
                Log.e(TAG, "Could not create on-screen program")
            } else {
                GLES20.glAttachShader(onScreenProgram, vertexShader)
                GLES20.glAttachShader(onScreenProgram, fragmentShader)
                GLES20.glLinkProgram(onScreenProgram)

                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(onScreenProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] != GLES20.GL_TRUE) {
                    Log.e(TAG, "Could not link on-screen program: ")
                    Log.e(TAG, GLES20.glGetProgramInfoLog(onScreenProgram))
                    GLES20.glDeleteProgram(onScreenProgram)
                    onScreenProgram = 0
                } else {
                    GLES20.glDeleteShader(vertexShader)
                    GLES20.glDeleteShader(fragmentShader)

                    onScreenPositionHandle = GLES20.glGetAttribLocation(onScreenProgram, "vPosition")
                    onScreenMVPMatrixHandle = GLES20.glGetUniformLocation(onScreenProgram, "uMVPMatrix")
                    onScreenColorHandle = GLES20.glGetUniformLocation(onScreenProgram, "uColor") // New

                    Log.d(TAG, "OnScreen Program ID: $onScreenProgram")
                    Log.d(TAG, "OnScreen Position Handle (vPosition): $onScreenPositionHandle")
                    Log.d(TAG, "OnScreen MVP Matrix Handle (uMVPMatrix): $onScreenMVPMatrixHandle")
                    Log.d(TAG, "OnScreen Color Handle (uColor): $onScreenColorHandle") // New

                    if (onScreenPositionHandle == -1) Log.w(TAG, "OnScreen vPosition handle is -1.")
                    if (onScreenMVPMatrixHandle == -1) Log.w(TAG, "OnScreen uMVPMatrix handle is -1.")
                    if (onScreenColorHandle == -1) Log.w(TAG, "OnScreen uColor handle is -1.") // New


                    val bb = ByteBuffer.allocateDirect(onScreenTriangleCoords.size * 4)
                    bb.order(ByteOrder.nativeOrder())
                    onScreenTriangleVertices = bb.asFloatBuffer()
                    onScreenTriangleVertices.put(onScreenTriangleCoords)
                    onScreenTriangleVertices.position(0)
                }
            }
        }

        // Clean up any existing off-screen render thread
        offScreenRenderThread?.stopRendering()
        try {
            offScreenRenderThread?.join(500) // Wait for it to finish
            Log.d(TAG, "Previous OffScreenRenderThread joined.")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while joining previous off-screen thread", e)
            Thread.currentThread().interrupt()
        }
        offScreenRenderThread = null
        // Also clear the old bitmap if the thread is being recreated
        // offScreenBitmapFlow.value?.recycle() // Be cautious with recycling if bitmap is still in use by UI
        offScreenBitmapFlow.value = null


        val sharedEglContext = EGL14.eglGetCurrentContext()
        if (sharedEglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Failed to get current EGL context for sharing.")
            // Cannot proceed to create OffScreenRenderThread without a shared context
            // Or, OffScreenRenderThread must be designed to create a non-shared context if this happens.
            // For now, we assume sharing is essential.
        } else {
             offScreenRenderThread = OffScreenRenderThread(
                context,
                sharedEglContext, // Pass the successfully obtained context
                offScreenWidth,
                offScreenHeight
            ) { newBitmap ->
                offScreenBitmapFlow.value = newBitmap
            }
            // Set the initial color of the off-screen thread to match the on-screen color
            offScreenRenderThread?.setTriangleColor(onScreenTriangleColor[0], onScreenTriangleColor[1], onScreenTriangleColor[2])
            offScreenRenderThread?.start()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: $width x $height")
        screenWidth = width
        screenHeight = height
        GLES20.glViewport(0, 0, width, height)

        val aspectRatio: Float = if (height == 0) 1f else width.toFloat() / height.toFloat()
        val projectionMatrix = FloatArray(16)
        if (width > height) {
            Matrix.orthoM(projectionMatrix, 0, -aspectRatio, aspectRatio, -1f, 1f, -1f, 1f)
        } else {
            Matrix.orthoM(projectionMatrix, 0, -1f, 1f, -1f/aspectRatio, 1f/aspectRatio, -1f, 1f)
        }

        val viewMatrix = FloatArray(16)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
        Matrix.multiplyMM(onScreenMvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (onScreenProgram != 0 && ::onScreenTriangleVertices.isInitialized && onScreenColorHandle != -1) { // Check color handle
            GLES20.glUseProgram(onScreenProgram)
            GLES20.glEnableVertexAttribArray(onScreenPositionHandle)
            GLES20.glVertexAttribPointer(onScreenPositionHandle, 3, GLES20.GL_FLOAT, false, 0, onScreenTriangleVertices)
            GLES20.glUniformMatrix4fv(onScreenMVPMatrixHandle, 1, false, onScreenMvpMatrix, 0)
            GLES20.glUniform4fv(onScreenColorHandle, 1, onScreenTriangleColor, 0) // New: Set color
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            GLES20.glDisableVertexAttribArray(onScreenPositionHandle)
            GLES20.glUseProgram(0)
        }
    }

    fun cleanup() {
        Log.d(TAG, "MyGLRenderer cleanup called")
        offScreenRenderThread?.stopRendering()
        try {
            offScreenRenderThread?.join(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while joining off-screen thread", e)
            Thread.currentThread().interrupt()
        }
        offScreenRenderThread = null

        offScreenBitmapFlow.value?.recycle()
        offScreenBitmapFlow.value = null

        if (onScreenProgram != 0) {
             Log.d(TAG, "On-screen program ($onScreenProgram) would be deleted here if safe.")
            // GLES20.glDeleteProgram(onScreenProgram) // Context might not be current
            onScreenProgram = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader $type (MyGLRenderer's loadShader):")
            Log.e(TAG, GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
