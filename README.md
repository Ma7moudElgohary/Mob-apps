# Mob-apps

Native Android experiments built with Kotlin, Jetpack Compose, on-device AI and GitHub Actions.

## Reality3D

`reality3d/` makes 3D models of real objects on the phone: by walking around them with ARCore, from a
single photo, or from a photo sent to an image-to-3D AI on your own computer. Models can be measured,
compared with the photo, saved to **My models**, seen at real size in the room with AR, and exported as
GLB, STL, OBJ, PLY or a game-ready Unreal pack.

### 360° scan (ARCore)

1. Point the camera at the table until ARCore finds it, then tap the object. A box appears around it; set its
   size (15 cm to 1.2 m) and start.
2. Walk around the object in three rings (at its height, from 45° above, from high above) and finish from straight
   above. A **coach** gives one instruction at a time: tracking problems, "Too fast", "Too close" / "Come closer",
   "Low detail here", which ring to do and "Move left/right" towards the nearest gap, "Top view missing", and
   "Scan complete" at 85% coverage. A radar and dots around the object show what is covered.
3. About four times a second ARCore's **raw depth** is fused into a truncated signed distance field over the box
   (3.5 mm voxels for small objects), each pixel weighted by ARCore's **confidence** (smoothed depth is the fallback
   until raw depth arrives). An ARCore **anchor** at the box follows ARCore's corrections to its map, so the volume
   doesn't smear when ARCore adjusts its idea of the room. Depth that lands on the table only clears space.
4. Building gives one closed, manifold surface, coloured per vertex from the best photos, at its real size, with a
   **quality score** (coverage, photos, depth maps and confidence) that says what held the scan back. **Add more
   views** goes back to the camera with everything captured so far.

### From one photo

1. Take or pick a photo. ML Kit subject segmentation finds every object: the photo card dims what is left out, and
   a **tap chooses the object** to model (later taps add or remove others). **Edit outline** paints the outline by
   hand: Add and Erase brushes with undo, two-finger zoom, **Snap to edges** (a guided filter against the photo) and
   **Soften edge**. A **photo quality** score rates sharpness, light, framing and separation before anything is made.
2. **Depth Anything V2** Small (LiteRT, 28 MB int8, downloaded once and checked with SHA-256) estimates depth on the
   fastest of CPU, GPU and NPU whose result matches the CPU's. The depth is cleaned edge-aware against the photo.
3. The outline is **inflated** into a rounded shape, bent by the depth. **Solid** mirrors the front into a closed,
   printable model; **Relief** keeps the front, cut out along the outline with the texture's alpha. Standard detail
   spends its triangles where the surface bends (a quadric-error simplifier).
4. **Gemini Nano** (AICore, on the phone) acts as a reconstruction advisor. It answers in strict JSON: the object,
   its shape and thickness, and whether it is shiny, see-through, has thin parts or holes. It also says whether the
   360° scan would do better and gives capture tips, and it sets the shape to match.
5. **AI Full 3D on your computer**: a photo can't show the back, so the cut-out can go to
   [`reality3d-server`](reality3d-server/) on a computer with a GPU (Stable Fast 3D, TripoSR or Hunyuan3D-2),
   which returns a complete textured model. Plain http is only used for addresses on the local network.

### Viewer, measuring and AR

- Looks: photo or scan colours, clay, wireframe, normals, depth; front/back/left/right/top views, auto-rotate,
  two-finger pan, a light-direction slider, studio or environment lighting, and PNG screenshots.
- **Measure** by tapping two points on the model; for photo models, **Set real length** turns a known length into
  the model's scale, which every export follows. **Compare with photo** lines the photo up with an orthographic
  front view under a draggable divider.
- **View in AR** stands the model on a real table or floor at its real size: drag to turn, pinch to resize, with a
  soft shadow and brightness that follows the room.
- **My models** keeps models on the phone (mesh, texture, photo, thumbnail and quality) to reopen, rename, measure,
  export or delete later.

### Exports

**GLB** (textured or vertex colours, meters), **STL** (millimetres), **OBJ** (zip with MTL and texture), **PLY**
(binary, points with colour and normal plus triangles), **Unreal** (three levels of detail, a convex collision hull
and import notes) and, for scans, **Photos** (the scan photos with camera poses for photogrammetry).

### Tests

115 JVM tests cover the mesh builder and simplifier, depth input and refinement, exporters and the GLB reader,
segmentation and the mask editor, the viewer's picking and framing, the Gemini Nano parser, scan fusion (synthetic
scans must fuse into a closed surface within about 1 mm, also from sparse raw depth with outliers), the scan coach
and quality, photo quality, saved projects, the server client, and Robolectric runs of every screen with fake
engines. The server has its own pytest suite; CI runs both.

## Neon Drift

`app/` contains the Neon Drift Android game.

## CI

Every pull request and every push to main builds, tests and lints Reality3D and tests the server. Main-branch
builds publish `Reality3D.apk` to the `latest-build` GitHub Release.
