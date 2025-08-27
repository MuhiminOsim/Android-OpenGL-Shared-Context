package com.example.sharedcontextopengl

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharedcontextopengl.ui.theme.SharedContextOpenGLTheme

class MainActivity : ComponentActivity() {

    private lateinit var textureViewRenderer: TextureViewRenderer
    // private var glSurfaceView: GLSurfaceView? = null // No longer needed
    private var appTextureView: TextureView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate")

        // Initialize TextureViewRenderer here, tied to Activity's context
        textureViewRenderer = TextureViewRenderer(applicationContext)

        setContent {
            SharedContextOpenGLTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(textureViewRenderer) { textureView ->
                        appTextureView = textureView // Keep a reference if needed for direct manipulation
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume")
        textureViewRenderer.onResume()
        appTextureView?.let {
            // If TextureView was made invisible and surface destroyed, onSurfaceTextureAvailable will handle re-init.
            // If surface is still valid, ensure render thread is active.
            Log.d("MainActivity", "TextureView available onResume. Requesting render.")
             textureViewRenderer.requestRender() // Request a render if surface might be stale
        }

    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause")
        textureViewRenderer.onPause()
    }

    override fun onDestroy() {
        Log.d("MainActivity", "onDestroy")
        textureViewRenderer.onDestroy() // This should release all GL resources and stop threads
        appTextureView = null
        super.onDestroy()
    }
}

@Composable
fun MainScreen(
    renderer: TextureViewRenderer, 
    onTextureViewCreated: (TextureView) -> Unit
) {
    val offScreenBitmap: Bitmap? by renderer.offScreenBitmapFlow.collectAsStateWithLifecycle()
    var colorIndex by remember { mutableIntStateOf(0) }
    val colors = listOf(
        floatArrayOf(0.0f, 1.0f, 0.0f), // Green
        floatArrayOf(0.0f, 0.0f, 1.0f), // Blue
        floatArrayOf(1.0f, 1.0f, 0.0f)  // Yellow
    )
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe lifecycle events to forward to TextureViewRenderer
    // This is an alternative/complement to calling from Activity lifecycle methods directly
    // if the renderer is more tightly coupled to the Composable's lifecycle.
    // However, since TextureViewRenderer is created in Activity, direct calls are fine.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> renderer.onResume()
                Lifecycle.Event.ON_PAUSE -> renderer.onPause()
                Lifecycle.Event.ON_DESTROY -> renderer.onDestroy() // Might be redundant if Activity.onDestroy also calls it
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Consider if renderer.onDestroy() should be called here if MainScreen can be removed
            // from composition before Activity is destroyed.
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = renderer
                    onTextureViewCreated(this)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        offScreenBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Off-screen render",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
                contentScale = ContentScale.Fit
            )
        } ?: Box(modifier = Modifier.weight(1f).fillMaxWidth()) { Text("Off-screen bitmap not available yet") }

        Button(onClick = {
            colorIndex = (colorIndex + 1) % colors.size
            val newColor = colors[colorIndex]
            renderer.setTriangleColor(newColor[0], newColor[1], newColor[2])
            // renderer.requestRender() // setTriangleColor in TextureViewRenderer already calls requestRender
        }) {
            Text("Change Color")
        }
    }
}
