# Shared Context OpenGL Android App

This Android application demonstrates the use of shared OpenGL ES contexts for rendering graphics both on-screen (using `GLSurfaceView`) and off-screen to a Bitmap. The rendered off-screen Bitmap is then displayed in a Jetpack Compose `Image` view. The application allows users to change the color of a triangle, and this color change is reflected in both the on-screen and off-screen renderings simultaneously.

## Features

*   **On-Screen Rendering**: Utilizes `GLSurfaceView` to render a triangle directly to the screen.
*   **Off-Screen Rendering**: Employs a separate thread (`OffScreenRenderThread`) to render the same triangle to an off-screen Framebuffer Object (FBO), which is then converted to a Bitmap.
*   **Shared EGL Context**: Demonstrates how to create and share an EGL context between the main `GLSurfaceView` rendering thread and the custom `OffScreenRenderThread`. This allows both threads to share GPU resources like shader programs and textures (though not explicitly shown for textures in this example).
*   **Jetpack Compose UI**: The main UI is built with Jetpack Compose, showcasing how to integrate `GLSurfaceView` using `AndroidView` and display the off-screen rendered Bitmap in an `Image` composable.
*   **Dynamic Color Change**: A button allows the user to cycle through different colors for the triangle, updating both renderings.
*   **Thread Synchronization**: Uses `renderLock` and `wait`/`notifyAll` for managing the rendering loop in the `OffScreenRenderThread`, ensuring it only renders when requested.
*   **Bitmap Flow**: Uses `kotlinx.coroutines.flow.MutableStateFlow` to communicate the newly rendered off-screen Bitmap to the Compose UI for display.

## Project Structure

*   **`MainActivity.kt`**: The main entry point of the application.
    *   Sets up the Jetpack Compose UI.
    *   Initializes `MyGLRenderer`.
    *   Manages the `GLSurfaceView` lifecycle.
    *   Contains the `MainScreen` composable which includes the `GLSurfaceView` and the `Image` for off-screen rendering, along with a button to change colors.
*   **`MyGLRenderer.kt`**: Implements `GLSurfaceView.Renderer` for on-screen rendering.
    *   Manages the on-screen OpenGL shader program and vertex data for the triangle.
    *   Handles the creation and lifecycle of the `OffScreenRenderThread`.
    *   Shares its EGL context with the `OffScreenRenderThread`.
    *   Provides a `setTriangleColor` method to update the color in both renderers.
    *   Exposes `offScreenBitmapFlow` to provide the off-screen Bitmap to the UI.
    *   Includes `cleanup()` method for releasing OpenGL resources.
*   **`OffScreenRenderThread.kt`** (inner class within `MyGLRenderer.kt`): A custom thread for off-screen OpenGL rendering.
    *   Initializes its own EGL context, sharing resources with the context provided by `MyGLRenderer`.
    *   Sets up a Framebuffer Object (FBO) to render into.
    *   Manages its own OpenGL shader program and vertex data for the triangle.
    *   Renders the triangle to the FBO and then reads the pixels into a Bitmap.
    *   Uses a callback (`onBitmapReady`) to pass the generated Bitmap back to `MyGLRenderer`.
    *   Manages its rendering loop and synchronization.

## How it Works

1.  **Initialization**:
    *   `MainActivity` creates an instance of `MyGLRenderer`.
    *   The `MainScreen` composable embeds a `GLSurfaceView` and provides it with `myRenderer`.
2.  **On-Screen Rendering (`MyGLRenderer`)**:
    *   `onSurfaceCreated()`:
        *   Sets up the OpenGL shaders and program for drawing a triangle on the screen.
        *   Crucially, it retrieves the current EGL context (`EGL14.eglGetCurrentContext()`).
        *   It then creates and starts an `OffScreenRenderThread`, passing the shared EGL context.
    *   `onSurfaceChanged()`: Sets up the viewport and projection matrix for on-screen rendering.
    *   `onDrawFrame()`: Clears the screen and draws the triangle using its shader program and vertex data. The color is updated via a uniform.
3.  **Off-Screen Rendering (`OffScreenRenderThread`)**:
    *   **EGL Setup**: In its `run()` method, before entering the rendering loop, it calls `initEGLAndFBO()`.
        *   `initEGLAndFBO()`: Creates a new EGL display and initializes it. It then selects an EGL configuration and creates an EGL context using the `sharedEglContext` passed from `MyGLRenderer`. A Pbuffer surface is created to make this context current.
        *   An FBO is created, along with a texture to render into and a depth renderbuffer. These are attached to the FBO.
    *   **Scene Setup**: `initGLScene()` sets up the OpenGL shaders and program for drawing the triangle (similar to the on-screen renderer).
    *   **Rendering Loop**:
        *   The thread waits until `requestRender()` is called (e.g., when the color changes or for the initial frame).
        *   When a render is requested, it binds the FBO.
        *   It clears the FBO and draws the triangle using its shader program and vertex data. The color is updated via a uniform.
        *   `readPixelsToBitmap()`: After drawing, `glReadPixels` is used to read the content of the FBO's color attachment (the texture) into a `ByteBuffer`. This buffer is then used to create a `Bitmap`.
        *   The generated `Bitmap` is passed back to `MyGLRenderer` via the `onBitmapReady` callback, which updates `offScreenBitmapFlow`.
4.  **UI Update**:
    *   The `MainScreen` composable collects updates from `offScreenBitmapFlow`.
    *   When a new Bitmap is emitted, the `Image` composable is recomposed to display the latest off-screen rendering.
5.  **Color Change**:
    *   Clicking the "Change Color" button in `MainActivity` calls `myRenderer.setTriangleColor()`.
    *   `MyGLRenderer` updates its `onScreenTriangleColor` and calls `offScreenRenderThread?.setTriangleColor()`.
    *   `MyGLRenderer` also calls `glSurfaceView.requestRender()` to trigger an on-screen redraw.
    *   `OffScreenRenderThread.setTriangleColor()` updates its `offScreenTriangleColor` and calls `requestRender()` to trigger an off-screen redraw.

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
*   **EGL Contexts**: The environment for OpenGL ES rendering.
*   **EGL Surfaces**: Drawing surfaces (like the one provided by `GLSurfaceView` or an off-screen Pbuffer).
*   **Framebuffer Objects (FBOs)**: For off-screen rendering to a texture.
*   `glReadPixels()`: Reading pixel data from the framebuffer.

## Potential Improvements/Further Exploration

*   Share shader programs and VBOs between the contexts instead of recreating them.
*   Implement more complex scenes.
*   Use the off-screen rendered texture directly in the on-screen rendering pipeline (e.g., render-to-texture).
*   Explore error handling and resource management in more depth.