# Mob-apps

Native Android experiments built with Kotlin, Jetpack Compose, on-device AI and GitHub Actions.

## Reality3D

`reality3d/` turns one photo of an object into a 3D model, entirely on the phone:

1. Take a photo or pick one from the gallery (large photos are decoded straight at 1600 px).
2. ML Kit subject segmentation cuts the object out. Its model comes from Google Play services; the
   app asks for it at install time and shows its download on first use.
3. MiDaS v2.1 small (LiteRT, 34 MB, downloaded once, resumable and checked with SHA-256) estimates depth.
4. The mesh builder turns the outline and the depth into a textured triangle mesh:
   - the silhouette is **inflated** (a Poisson solve), so every part gets a rounded thickness that matches its width;
   - the MiDaS depth, normalised inside the subject, tilts and bends the model and adds relief;
   - in **Solid** mode a mirrored back is joined to the front along the outline, so the model is closed
     (watertight) and can be 3D printed. **Relief** mode keeps the front surface only.
5. Shape controls rebuild the model instantly: Solid/Relief, Round/Boxy, thickness, depth strength and detail.
6. Optional: Gemini Nano (ML Kit GenAI Prompt API over Android AICore) recognises the object and sets the
   shape and thickness for it.
7. An OpenGL ES 3 viewer shows the lit model: drag to orbit, pinch to zoom, double-tap to reset, or switch
   to clay to judge the geometry.
8. Export and share or save:
   - **GLB**: one file with the photo texture (Windows 3D Viewer, Blender, online glTF viewers);
   - **STL**: for 3D printing, 10 cm on the longest side, standing on the build plate;
   - **OBJ**: OBJ + MTL + JPG in a zip for Blender, SketchUp or Unreal.

A single photo cannot show the back of an object, so Solid mode assumes it mirrors the front. Real
multi-view capture (ARCore depth fusion) is the next step.

Unit tests cover the mesh builder (including watertightness on random shapes), the exporters and the
Gemini Nano answer parser, and a Robolectric test runs the whole screen with fake engines.

## Neon Drift

`app/` contains the Neon Drift Android game.

## CI

Every push and pull request builds, tests and lints Reality3D. Main-branch builds publish `Reality3D.apk`
to the `latest-build` GitHub Release.
