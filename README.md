# Shared Context OpenGL Android App

This Android application demonstrates the use of shared OpenGL ES contexts for rendering graphics both on-screen (using `TextureView`) and off-screen to a Bitmap. The rendered off-screen Bitmap is then displayed in a Jetpack Compose `Image` view. The application allows users to change the color of a triangle, and this color change is reflected in both the on-screen and off-screen renderings simultaneously.

## Features

*   **On-Screen Rendering**: Utilizes `TextureView` with a custom rendering thread to render a triangle directly to the screen. This provides more control over the EGL context and rendering loop compared to `GLSurfaceView`.
*   **Off-Screen Rendering**: Employs a separate thread (`OffScreenRenderThread`) to render the same triangle to an off-screen Framebuffer Object (FBO), which is then converted to a Bitmap.
*   **Shared EGL Context**: Demonstrates how to create and share an EGL context between the `TextureViewRenderer`'s on-screen rendering thread and the custom `OffScreenRenderThread`. This allows both threads to share GPU resources.
*   **Jetpack Compose UI**: The main UI is built with Jetpack Compose, showcasing how to integrate `TextureView` using `AndroidView` and display the off-screen rendered Bitmap in an `Image` composable.
*   **Dynamic Color Change**: A button allows the user to cycle through different colors for the triangle, updating both renderings on demand.
*   **Thread Synchronization**: `OffScreenRenderThread` uses `renderLock` and `wait`/`notifyAll` for managing its rendering loop, rendering only when requested. `TextureViewRenderer` manages its own render thread.
*   **Bitmap Flow**: Uses `kotlinx.coroutines.flow.MutableStateFlow` to communicate the newly rendered off-screen Bitmap to the Compose UI for display.

## Project Structure

*   **`MainActivity.kt`**: The main entry point of the application.
    *   Sets up the Jetpack Compose UI.
    *   Initializes `TextureViewRenderer`.
    *   Manages the `TextureView` lifecycle via `TextureViewRenderer` and Android Activity lifecycle callbacks.
    *   Contains the `MainScreen` composable which includes the `TextureView` (via `AndroidView`) and the `Image` for off-screen rendering, along with a button to change colors.
*   **`TextureViewRenderer.kt`**: Manages rendering to a `TextureView`.
    *   Implements `TextureView.SurfaceTextureListener`.
    *   Manages a dedicated rendering thread (`RenderThread`) for on-screen OpenGL operations.
    *   Handles EGL context setup for the `TextureView`. The EGL context created here is shared with `OffScreenRenderThread`.
    *   Manages the on-screen OpenGL shader program and vertex data for the triangle.
    *   Handles the creation and lifecycle of the `OffScreenRenderThread`.
    *   Provides a `setTriangleColor` method to update the color in both renderers and `requestRender` to trigger on-screen redraws.
    *   Exposes `offScreenBitmapFlow` to provide the off-screen Bitmap to the UI.
    *   Includes methods for lifecycle management (e.g., `onActivityResume`, `onActivityPause`, `onActivityDestroy`) to correctly manage GL resources.
*   **`OffScreenRenderThread.kt`**: A custom standalone thread for off-screen OpenGL rendering.
    *   Its constructor now accepts a shared `android.opengl.EGLContext` from `TextureViewRenderer`.
    *   Initializes its own EGL display and surface but uses the shared context.
    *   Sets up a Framebuffer Object (FBO) to render into.
    *   Manages its own OpenGL shader program and vertex data for the triangle.
    *   Renders the triangle to the FBO and then reads the pixels into a Bitmap.
    *   Uses a callback (`onBitmapReady`) to pass the generated Bitmap back to `TextureViewRenderer`.
    *   Manages its rendering loop (render-on-demand) and synchronization.
*   **`MyGLRenderer.kt`**: (Replaced) Previously used with `GLSurfaceView`, this class is no longer central to the rendering process. Its functionalities for on-screen rendering and off-screen thread management have been migrated to `TextureViewRenderer.kt` and `OffScreenRenderThread.kt` respectively.

## How it Works

1.  **Initialization**:
    *   `MainActivity` creates an instance of `TextureViewRenderer`.
    *   The `MainScreen` composable embeds a `TextureView` using `AndroidView`. `textureViewRenderer` is set as its `surfaceTextureListener`.
    *   Lifecycle methods in `MainActivity` (e.g., `onResume`, `onPause`, `onDestroy`) call corresponding methods in `textureViewRenderer` to manage resources.
2.  **On-Screen Rendering (`TextureViewRenderer`)**:
    *   When the `TextureView`'s surface texture is available (`onSurfaceTextureAvailable`), `TextureViewRenderer` starts its internal `RenderThread`.
    *   The `RenderThread` initializes an EGL display and creates an EGL window surface using the `SurfaceTexture`.
    *   It creates a new EGL context. This context (`sharedEglContext`) is stored in `TextureViewRenderer` and will be passed to `OffScreenRenderThread`.
    *   `setupGLScene()`: The `RenderThread` sets up the OpenGL shaders, program, and vertex buffers for drawing the on-screen triangle.
    *   `onSurfaceTextureSizeChanged()`: Sets up the viewport and projection matrix for on-screen rendering.
    *   The `RenderThread` enters a loop. It waits until `requestRender()` is called on `TextureViewRenderer` (e.g., when the color changes or for an initial frame).
    *   When a render is requested, it makes its EGL context current, clears the surface, and draws the triangle using its shader program. The color is updated via a uniform. The frame is then presented using `eglSwapBuffers`.
    *   When the surface is destroyed (`onSurfaceTextureDestroyed`), the `RenderThread` cleans up its EGL resources and terminates.
3.  **Off-Screen Rendering (`OffScreenRenderThread`)**:
    *   Once `TextureViewRenderer`'s `RenderThread` has established its EGL context, `TextureViewRenderer` creates and starts an `OffScreenRenderThread`, passing the `sharedEglContext`.
    *   **EGL Setup**: In `OffScreenRenderThread`'s `run()` method, it calls `initEGLAndFBO()`.
        *   `initEGLAndFBO()`: Creates a new EGL display and initializes it. It then selects an EGL configuration and creates its EGL context using the `sharedEglContext` passed from `TextureViewRenderer`. A Pbuffer surface is created to make this context current for off-screen rendering.
        *   An FBO is created, along with a texture to render into and a depth renderbuffer. These are attached to the FBO.
    *   **Scene Setup**: `initGLScene()` sets up the OpenGL shaders and program for drawing the triangle.
    *   **Rendering Loop**:
        *   The thread waits until its `requestRender()` is called (e.g., when the color changes or for the initial frame).
        *   When a render is requested, it binds the FBO, clears it, and draws the triangle. The color is updated via a uniform.
        *   `createBitmapFromGLSurface()`: After drawing, `glReadPixels` is used to read the content of the FBO's color attachment into a `ByteBuffer`, which is then used to create a `Bitmap`.
        *   The generated `Bitmap` is passed back to `TextureViewRenderer` via the `onBitmapReady` callback, which updates `offScreenBitmapFlow`.
4.  **UI Update**:
    *   The `MainScreen` composable collects updates from `textureViewRenderer.offScreenBitmapFlow`.
    *   When a new Bitmap is emitted, the `Image` composable is recomposed to display the latest off-screen rendering.
5.  **Color Change**:
    *   Clicking the "Change Color" button in `MainActivity` calls `textureViewRenderer.setTriangleColor()`.
    *   `TextureViewRenderer` updates its `triangleColor` and calls `setTriangleColor()` on the `offScreenRenderThread`.
    *   `TextureViewRenderer` also calls its own `requestRender()` to trigger an on-screen redraw.
    *   `OffScreenRenderThread.setTriangleColor()` updates its `offScreenTriangleColor` and calls its own `requestRender()` to trigger an off-screen redraw.

## Building and Running

1.  Clone the repository.
2.  Open the project in Android Studio.
3.  Build and run the application on an Android device or emulator that supports OpenGL ES 2.0 or higher.

## Key OpenGL ES Concepts Demonstrated

*   **Vertex and Fragment Shaders**: Basic shaders for transforming vertices and coloring fragments.
*   **Shader Programs**: Linking vertex and fragment shaders into a program.
*   **Vertex Attributes**: Passing vertex coordinate data to the vertex shader.
*   **Uniforms**: Passing MVP matrix and color data to shaders.
*   **MVP Matrix**: Model-View-Projection matrix for transforming 3D coordinates to 2D screen space.
*   **EGL Contexts**: The environment for OpenGL ES rendering, including manual management for `TextureView`.
*   **EGL Surfaces**: Drawing surfaces (the `SurfaceTexture` from `TextureView` and an off-screen Pbuffer).
*   **Framebuffer Objects (FBOs)**: For off-screen rendering to a texture.
*   `glReadPixels()`: Reading pixel data from the framebuffer.

## Potential Improvements/Further Exploration

*   Share shader programs and VBOs between the contexts instead of recreating them.
*   Implement more complex scenes.
*   Use the off-screen rendered texture directly in the on-screen rendering pipeline (e.g., render-to-texture).
*   Explore error handling and resource management in more depth, especially for EGL state.
*   Implement more robust thread synchronization mechanisms.
