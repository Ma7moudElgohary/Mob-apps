# Mob-apps

Native Android experiments built with Kotlin, Jetpack Compose, on-device AI and GitHub Actions.

## Reality3D

`reality3d/` makes 3D models of real objects, entirely on the phone. It has two modes.

### 360° scan (ARCore)

Walk around the object and get a closed, coloured model at its real size:

1. Point the camera at the table until ARCore finds it, then tap the object. A box appears around it;
   set its size (15 cm to 1.2 m) and start.
2. Walk slowly around the object in three rings: at its height, from 45° above, from high above, and
   finish with a view from straight above. A coverage radar and dots floating around the object show
   which directions are done; photos are only taken while the phone is steady.
3. About four times a second, ARCore's depth map is fused into a truncated signed distance field over the
   box (3.5 mm voxels for small objects). Depth that lands on the table only clears the space in front of
   it, so the table never becomes part of the model but still shows what is empty under and around the object.
4. Building turns the field into one closed, manifold surface (marching tetrahedra, with unseen space under
   the object filled as solid), keeps the object's pieces, drops its base onto the table, smooths it and
   colours each vertex from the best photos that see it.
5. The result shows the model with its size in centimetres and exports **GLB** (vertex colours, meters),
   **STL** (millimetres), **OBJ** (vertex colours, meters) or **Photos**: a zip of the scan photos with their
   camera positions (JSON in ARCore's convention and COLMAP text files) for photogrammetry on a computer.

On phones without a depth sensor, expect about 1 to 2 cm of accuracy on real objects: good for
dimensions, printing and visualisation. Plain, shiny or transparent surfaces are hard for ARCore depth.
A newspaper under the object and soft, even light help tracking. ARCore is optional; the app installs
and runs without it.

### From one photo

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
7. Export and share or save:
   - **GLB**: one file with the photo texture (Windows 3D Viewer, Blender, online glTF viewers);
   - **STL**: for 3D printing, 10 cm on the longest side, standing on the build plate;
   - **OBJ**: OBJ + MTL + JPG in a zip for Blender, SketchUp or Unreal.

A single photo cannot show the back of an object, so Solid mode assumes it mirrors the front.

Both modes share an OpenGL ES 3 viewer: drag to orbit, pinch to zoom, double-tap to reset, or switch to
clay to judge the geometry.

### Tests

Unit tests cover the mesh builder (including watertightness on random shapes), the exporters, the
Gemini Nano answer parser, coverage tracking, the photo-set export and the scan reconstruction: a
synthetic scan of a ball and a block, rendered as ARCore depth maps from rings of camera poses, must fuse
into a closed surface within 1 mm on average (with and without depth noise), keep round objects' shape
down to the table and take its colours from the photos. Robolectric tests run both screens end to end
with fake engines.

## Neon Drift

`app/` contains the Neon Drift Android game.

## CI

Every push and pull request builds, tests and lints Reality3D. Main-branch builds publish `Reality3D.apk`
to the `latest-build` GitHub Release.
