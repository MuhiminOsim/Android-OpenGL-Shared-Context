package com.example.sharedcontextopengl

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharedcontextopengl.ui.theme.SharedContextOpenGLTheme

class MainActivity : ComponentActivity() {
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var myRenderer: MyGLRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        myRenderer = MyGLRenderer(this) // Pass application context

        setContent {
            SharedContextOpenGLTheme {
                MainScreen(myRenderer)
            }
        }
    }

    @Composable
    fun MainScreen(renderer: MyGLRenderer) {
        val offScreenBitmap: Bitmap? by renderer.offScreenBitmapFlow.collectAsStateWithLifecycle()
        var colorIndex by remember { mutableStateOf(0) }
        val colors = listOf(
            floatArrayOf(0.0f, 1.0f, 0.0f), // Green
            floatArrayOf(0.0f, 0.0f, 1.0f), // Blue
            floatArrayOf(1.0f, 1.0f, 0.0f)  // Yellow
        )


        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    glSurfaceView = GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2) // Use OpenGL ES 2.0
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY // Changed
                    }
                    glSurfaceView
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            offScreenBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Off-screen render",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentScale = ContentScale.Fit
                )
            }
            Button(onClick = {
                colorIndex = (colorIndex + 1) % colors.size
                val newColor = colors[colorIndex]
                renderer.setTriangleColor(newColor[0], newColor[1], newColor[2])
                if (::glSurfaceView.isInitialized) { // Check if initialized
                    glSurfaceView.requestRender() // Added
                }
            }) {
                Text("Change Color")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::glSurfaceView.isInitialized) {
            glSurfaceView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::glSurfaceView.isInitialized) {
            glSurfaceView.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::myRenderer.isInitialized) {
            myRenderer.cleanup()
        }
    }
}
