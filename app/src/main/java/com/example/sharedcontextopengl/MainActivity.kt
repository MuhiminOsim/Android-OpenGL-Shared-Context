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
import androidx.compose.ui.platform.LocalContext // Keep for _offScreenBitmapFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharedcontextopengl.ui.theme.SharedContextOpenGLTheme

class MainActivity : ComponentActivity() {

    private lateinit var textureViewRenderer: TextureViewRenderer
    private var appTextureView: TextureView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate")

        // Initialize TextureViewRenderer here, tied to Activity's context
        // TextureViewRenderer now requires a Context for loading assets
        textureViewRenderer = TextureViewRenderer(applicationContext)

        setContent {
            SharedContextOpenGLTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(textureViewRenderer) { textureView ->
                        appTextureView = textureView // Keep a reference
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
        textureViewRenderer.onDestroy()
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
    // val context = LocalContext.current // Not strictly needed here anymore for button logic
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> renderer.onResume()
                Lifecycle.Event.ON_PAUSE -> renderer.onPause()
                Lifecycle.Event.ON_DESTROY -> renderer.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
            renderer.switchToNextImage()
        }) {
            Text("Switch Image")
        }
    }
}
