# Mob-apps

Native Android experiments built with Kotlin, Jetpack Compose, on-device AI and GitHub Actions.

## Reality3D

`reality3d/` makes 3D models of real objects: by walking around them with ARCore, from **many photos worked
out on your own computer** (photogrammetry, the most detailed), from a single photo, or from a photo sent to an
image-to-3D AI on your computer. Models can be measured, compared with the photo, saved to **My models**, seen at
real size in the room with AR, opened from a .glb another app made, and exported as GLB, STL, OBJ, PLY or a
game-ready Unreal pack.

### 3D from many photos (on your computer)

Photogrammetry, the way RealityScan, KIRI Engine and Polycam make their best models, is a set of ordinary still
photos from all around the object, worked out by a real solver. A phone without a depth sensor can't do that well
alone (ARCore's depth on the Galaxy S26 Ultra is 160 × 90), so the phone takes the photos and
[`reality3d-server`](reality3d-server/) does the work: COLMAP works out where each photo was taken, OpenMVS builds
the dense points, the mesh and the texture. No graphics card is needed; it takes minutes.

- **From a scan:** tap **Build on my computer · best quality** while scanning (12 photos or more, 40 or more is
  better). The photos go with ARCore's camera positions and the scan box, so the model comes back **upright, in real
  centimetres, cut down to the object with the table removed**, with its texture, and lands in the scan's result
  screen and in **My models**.
- **From the gallery:** **3D from many photos** on the home screen takes photos from the normal camera (full
  resolution, the lens details kept), for one object or a whole scene, at fast, standard or high quality. The model is
  upright but its size is unknown until you set a real length.
- **Other apps:** **Open a .glb** brings in a model made by KIRI Engine, Scaniverse, Polycam or RealityScan, to
  measure, view in AR, export or keep.

The server downloads OpenMVS the first time (checked against a known checksum), shows the job's progress and can be
told to stop. Tested on made-up photos of a known object, every side came back within 5% of its real size (the height, 0.1795 m
for 0.18 m, within 1%).

### 360° scan (ARCore)

1. Point the camera at the table until ARCore finds it, then tap the object. A box appears around it; set its
   size (15 cm to 1.2 m) and start.
2. The scan is four steps, one big instruction at a time (find the object, tap it, pick its size from
   "in a hand / shoebox / a chair", walk around it), and a how-to card the first time. **Two laps are enough**: at the
   object's height, then a bit higher looking down at 45°. The phone says "That's enough, tap Build model" and offers a
   third, high lap and a view from straight above only to clean up the top. A **coach** gives the advice, on the
   screen and **out loud** (the phone's own voice; it can be turned off), and taps the phone for every new side
   covered: tracking problems, "Too fast", "Too close" / "Come closer", "Low detail here", "Move left/right" towards
   the nearest gap. Advice has to hold for a moment before it changes or is spoken, so it doesn't flicker. A radar
   and dots around the object show what is covered. Leaving or starting over asks first once there is something to
   lose.
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

### When something crashes the app

Some of the work runs in native code inside the app (ML Kit's cut-out, the depth model on the GPU or NPU), where
no `try`/`catch` helps if a phone's driver misbehaves. Each such step is written to disk before it starts and
removed when it ends. If the app dies inside one, the next launch turns that feature off on that phone
(**Safe mode**, with a button to turn it back on) and shows a card saying what happened, with the crash's stack
(from Android's exit records and the tombstone) to **Copy** or **Share**. The photo being worked on is kept and comes
back after a crash. Photos taken with the app's camera are also saved to the Gallery, in `Pictures/Reality3D`.

Phone models that reports show always crash in ML Kit (so far the Galaxy S26 Ultra, `SM-S948B`) skip it from the
start, even on a fresh install or after **Turn on**. A scan has its own **Copy report** button (while scanning, when
it fails and on the result): how long each step took, the warnings shown and for how long, the camera and depth
ARCore gave (raw or smoothed depth maps), what the box anchor cost and how the build went.

When ML Kit's cut-out is off, the app offers **Segment Anything 2.1** (Meta, Apache-2.0, tiny, through LiteRT on the
GPU or CPU; 97 MB, downloaded once and checked with SHA-256). It finds the photo's objects with a grid of prompts,
starts from the one in the middle, and a tap on anything else cuts that out too.

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

The photo builder is tested end to end on made-up photos with a known answer (size, upright pose, table removed).
JVM tests cover the mesh builder and simplifier, depth input and refinement, exporters and the GLB reader,
segmentation, the mask editor and Segment Anything's prompt and mask handling, the viewer's picking and framing, the
Gemini Nano parser, scan fusion (synthetic scans must fuse into a closed surface within about 1 mm, also from sparse
raw depth with outliers), the scan coach and quality, photo quality, saved projects, the server client, crash
diagnostics (tombstones, freezes, turning features off) and Robolectric runs of every screen with fake engines,
including a real camera JPEG. The server has its own pytest suite; CI runs both.

## Neon Drift

`app/` contains the Neon Drift Android game.

## CI

Every pull request and every push to main builds, tests and lints Reality3D and tests the server. Main-branch
builds publish `Reality3D.apk` to the `latest-build` GitHub Release.
