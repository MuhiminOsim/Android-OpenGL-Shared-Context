# Shared Context OpenGL Android App

This Android application demonstrates the use of shared OpenGL ES contexts for rendering graphics both on-screen (using `TextureView`) and off-screen to a Bitmap. The rendered off-screen Bitmap is then displayed in a Jetpack Compose `Image` view. The application allows users to switch between different images, and this change is reflected in both the on-screen and off-screen renderings simultaneously.

## Features

*   **On-Screen Rendering**: Utilizes `TextureView` with a custom rendering thread to render a textured quad (image) directly to the screen. This provides more control over the EGL context and rendering loop compared to `GLSurfaceView`.
*   **Off-Screen Rendering**: Employs a separate thread (`OffScreenRenderThread`) to render the same textured quad to an off-screen Framebuffer Object (FBO), which is then converted to a Bitmap.
*   **Shared EGL Context**: Demonstrates how to create and share an EGL context between the `TextureViewRenderer`'s on-screen rendering thread and the custom `OffScreenRenderThread`. This allows both threads to share GPU resources like textures.
*   **Jetpack Compose UI**: The main UI is built with Jetpack Compose, showcasing how to integrate `TextureView` using `AndroidView` and display the off-screen rendered Bitmap in an `Image` composable.
*   **Dynamic Image Change**: A button allows the user to cycle through different images, updating both renderings on demand.
*   **Thread Synchronization**: `OffScreenRenderThread` uses `renderLock` and `wait`/`notifyAll` for managing its rendering loop, rendering only when requested. `TextureViewRenderer` manages its own render thread.
*   **Bitmap Flow**: Uses `kotlinx.coroutines.flow.MutableStateFlow` to communicate the newly rendered off-screen Bitmap to the Compose UI for display.
*   **Texture Loading**: Demonstrates loading images from drawable resources into OpenGL textures.

## Project Structure

*   **`MainActivity.kt`**: The main entry point of the application.
    *   Sets up the Jetpack Compose UI.
    *   Initializes `TextureViewRenderer`.
    *   Manages the `TextureView` lifecycle via `TextureViewRenderer` and Android Activity lifecycle callbacks.
    *   Contains the `MainScreen` composable which includes the `TextureView` (via `AndroidView`) and the `Image` for off-screen rendering, along with a button to change images.
*   **`TextureViewRenderer.kt`**: Manages rendering to a `TextureView`.
    *   Implements `TextureView.SurfaceTextureListener`.
    *   Manages a dedicated rendering thread (`RenderThread`) for on-screen OpenGL operations.
    *   Handles EGL context setup for the `TextureView`. The EGL context created here is shared with `OffScreenRenderThread`.
    *   Loads textures from drawable resources.
    *   Manages the on-screen OpenGL shader program and vertex data for the textured quad.
    *   Handles the creation and lifecycle of the `OffScreenRenderThread`.
    *   Provides a `switchToNextTexture()` method to change the displayed image in both renderers and `requestRender` to trigger on-screen redraws.
    *   Exposes `offScreenBitmapFlow` to provide the off-screen Bitmap to the UI.
    *   Includes methods for lifecycle management (e.g., `onActivityResume`, `onActivityPause`, `onActivityDestroy`) to correctly manage GL resources.
*   **`OffScreenRenderThread.kt`**: A custom standalone thread for off-screen OpenGL rendering.
    *   Its constructor now accepts a shared `android.opengl.EGLContext` from `TextureViewRenderer` and the initial texture ID.
    *   Initializes its own EGL display and surface but uses the shared context.
    *   Sets up a Framebuffer Object (FBO) to render into.
    *   Manages its own OpenGL shader program and vertex data for the textured quad.
    *   Renders the textured quad to the FBO and then reads the pixels into a Bitmap.
    *   Uses a callback (`onBitmapReady`) to pass the generated Bitmap back to `TextureViewRenderer`.
    *   Manages its rendering loop (render-on-demand) and synchronization.
    *   Includes a `setTextureId()` method to update the texture it renders.
*   **`MyGLRenderer.kt`**: (No longer central) Previously used with `GLSurfaceView`, this class is not the primary component for the `TextureView` implementation.

## How it Works

1.  **Initialization**:
    *   `MainActivity` creates an instance of `TextureViewRenderer`.
    *   The `MainScreen` composable embeds a `TextureView` using `AndroidView`. `textureViewRenderer` is set as its `surfaceTextureListener`.
    *   Lifecycle methods in `MainActivity` call corresponding methods in `textureViewRenderer`.
2.  **On-Screen Rendering (`TextureViewRenderer`)**:
    *   When the `TextureView`'s surface texture is available (`onSurfaceTextureAvailable`), `TextureViewRenderer` starts its internal `RenderThread`.
    *   The `RenderThread` initializes an EGL display, an EGL window surface, and a new EGL context. This context (`sharedEglContext`) is stored and passed to `OffScreenRenderThread`.
    *   `loadTextures()`: Loads images (e.g., from drawables) into OpenGL textures. Texture IDs are stored.
    *   `setupGLScene()`: The `RenderThread` sets up OpenGL shaders (for texturing), program, and vertex/texture coordinate buffers for drawing the on-screen textured quad.
    *   `updateMvpMatrix()`: Sets up the projection matrix (can be identity for simple 2D texture rendering).
    *   The `RenderThread` enters a loop, waiting for `requestRender()`.
    *   When a render is requested, it makes its EGL context current, clears the surface, binds the current texture, and draws the textured quad. The frame is presented via `eglSwapBuffers`.
    *   When the surface is destroyed, the `RenderThread` cleans up its EGL resources and textures, then terminates.
3.  **Off-Screen Rendering (`OffScreenRenderThread`)**:
    *   Once `TextureViewRenderer`'s `RenderThread` has established its EGL context and loaded textures, `TextureViewRenderer` creates and starts an `OffScreenRenderThread`, passing the `sharedEglContext` and the initial `textureId`.
    *   **EGL Setup**: `OffScreenRenderThread` uses the shared context to create its EGL resources and a Pbuffer surface for off-screen rendering.
    *   An FBO is created and a texture is attached to it for rendering.
    *   **Scene Setup**: Sets up OpenGL shaders and program for drawing the textured quad.
    *   **Rendering Loop**:
        *   The thread waits until its `requestRender()` is called.
        *   When requested, it binds the FBO, clears it, binds the current texture (set via `setTextureId`), and draws the textured quad.
        *   `createBitmapFromGLSurface()`: Reads the FBO content into a `Bitmap`.
        *   The `Bitmap` is passed back to `TextureViewRenderer` via `onBitmapReady`, updating `offScreenBitmapFlow`.
4.  **UI Update**:
    *   `MainScreen` collects updates from `textureViewRenderer.offScreenBitmapFlow` and displays the `Bitmap` in an `Image` composable.
5.  **Image Change**:
    *   Clicking the "Change Image" button calls `textureViewRenderer.switchToNextTexture()`.
    *   `TextureViewRenderer` updates its current `textureId` and calls `setTextureId()` on the `offScreenRenderThread` with the new ID.
    *   `TextureViewRenderer` calls its own `requestRender()` for on-screen redraw.
    *   `OffScreenRenderThread.setTextureId()` updates its `currentTextureId` and calls its `requestRender()` for an off-screen redraw.

## Building and Running

1.  Clone the repository.
2.  Open the project in Android Studio.
3.  Build and run the application on an Android device or emulator that supports OpenGL ES 2.0 or higher.

## Key OpenGL ES Concepts Demonstrated

*   **Vertex and Fragment Shaders**: Shaders for transforming vertices and applying textures to fragments.
*   **Shader Programs**: Linking shaders into a program.
*   **Vertex Attributes**: Passing vertex coordinates and texture coordinates to the vertex shader.
*   **Uniforms**: Passing MVP matrix and texture sampler to shaders.
*   **Textures**: Loading image data into OpenGL textures (`glTexImage2D`, `glBindTexture`).
*   **Samplers (`sampler2D`)**: Used in fragment shaders to read from textures.
*   **EGL Contexts**: Managed for `TextureView` and shared.
*   **EGL Surfaces**: `SurfaceTexture` for `TextureView` and an off-screen Pbuffer.
*   **Framebuffer Objects (FBOs)**: For off-screen rendering.
*   `glReadPixels()`: Reading pixel data.

## Potential Improvements/Further Exploration

*   Share shader programs and VBOs/IBOs between contexts.
*   Implement more complex scenes or image manipulations.
*   Use the off-screen rendered texture directly in other GL operations without converting to Bitmap.
*   More robust error handling and resource management.

(The section on switching between TextureView and GLSurfaceView has been omitted for brevity as the focus is now on the TextureView implementation rendering images, but the principles would be similar: adapting the relevant renderer to manage textures and share its context.)
