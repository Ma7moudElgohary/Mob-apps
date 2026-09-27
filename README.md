# Mob-apps

Native Android experiments built with Kotlin, Jetpack Compose, on-device AI and GitHub Actions.

## Reality3D

`reality3d/` is an on-device photo-to-3D prototype:

1. Capture a photo or choose one from Gallery.
2. Use Android AICore / Gemini Nano to analyze the object locally.
3. Download the MiDaS-small TFLite depth model on first use.
4. Generate a dense monocular depth map on-device.
5. Convert that depth field into a textured triangular 3D mesh.
6. Orbit the mesh in an OpenGL ES 3 viewer.
7. Export OBJ + MTL + JPG texture for Blender/Unreal or other 3D tools.

This first release is intentionally a 2.5D depth mesh. A single photo cannot reveal hidden surfaces. Planned upgrades are full single-image reconstruction and ARCore multi-view/depth fusion.

## Neon Drift

`app/` contains the Neon Drift Android game.

## CI

Every push/PR builds, tests and lints all Android modules. Main-branch builds publish both APKs to the `latest-build` GitHub Release.
