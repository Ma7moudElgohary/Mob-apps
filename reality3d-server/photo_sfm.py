"""Step one of photos → 3D, run as its own program so a crash or a long calculation can't take the server with it:
COLMAP (through pycolmap) works out where each photo was taken, the model is stood upright and scaled (see
alignment.py), cut down to the object, and the photos are undistorted for OpenMVS.

    python photo_sfm.py <work folder> <options.json>

The work folder holds images/ (JPEGs). It talks through its output: lines `PROGRESS <0..1> <text>`, then either
`RESULT <json>` or `ERROR <text>`.
"""

import json
import os
import sys
import traceback
from pathlib import Path

import numpy as np

import alignment


class Failure(Exception):
    """A problem to show to the user as it is."""


def say(fraction: float, message: str) -> None:
    print(f"PROGRESS {fraction:.3f} {message}", flush=True)


def load_poses(path: str) -> dict:
    """{photo name: 4×4 camera-to-world} from poses.json, where each pose is 16 numbers, column-major (ARCore)."""
    with open(path) as f:
        raw = json.load(f)
    return {name: np.array(values, dtype=float).reshape(4, 4).T for name, values in raw.items() if len(values) == 16}


def views_of(reconstruction) -> dict:
    views = {}
    for image in reconstruction.images.values():
        if image.has_pose:
            views[image.name] = alignment.CameraView(image.cam_from_world().rotation.matrix(), np.array(image.projection_center()))
    return views


def looked_at(views: dict) -> tuple:
    """The point the cameras' viewing directions come closest to together, and the typical distance to it;
    None when they don't converge (every camera looking the same way)."""
    lhs, rhs = np.zeros((3, 3)), np.zeros(3)
    for view in views.values():
        direction = view.rotation[2]  # the camera's forward (+Z) axis in world coordinates
        project = np.eye(3) - np.outer(direction, direction)
        lhs += project
        rhs += project @ view.center
    if np.linalg.cond(lhs) > 1e6:
        return None
    point = np.linalg.solve(lhs, rhs)
    distances = [float(np.linalg.norm(v.center - point)) for v in views.values()]
    return point, float(np.median(distances))


def run(work: Path, options: dict) -> dict:
    try:
        import pycolmap
    except ImportError as e:
        raise Failure("The photo builder needs pycolmap: run  pip install pycolmap  and start the server again.") from e

    pycolmap.logging.minloglevel = 2  # errors only: the server's console isn't the place for COLMAP's chatter
    images = work / "images"
    names = sorted(p.name for p in images.iterdir() if p.suffix.lower() == ".jpg")
    count = len(names)
    threads = int(options.get("threads") or os.cpu_count() or 4)
    database = work / "database.db"
    if database.exists():
        database.unlink()
    size = int(options["size"])

    say(0.02, f"Finding features in {count} photos")
    extraction = pycolmap.FeatureExtractionOptions()
    extraction.max_image_size = size
    extraction.num_threads = threads
    reader = pycolmap.ImageReaderOptions()
    prior = options.get("camera_params")
    if prior:
        reader.camera_model = "SIMPLE_RADIAL"
        reader.camera_params = ",".join(f"{v:.4f}" for v in prior)
    mode = pycolmap.CameraMode.SINGLE if options.get("camera") == "single" else pycolmap.CameraMode.AUTO
    pycolmap.extract_features(database, images, camera_mode=mode, reader_options=reader, extraction_options=extraction, device=pycolmap.Device.auto)

    say(0.22, "Matching the photos with each other")
    matching = pycolmap.FeatureMatchingOptions()
    matching.num_threads = threads
    if count <= 200:
        pycolmap.match_exhaustive(database, matching_options=matching, device=pycolmap.Device.auto)
    else:
        pairing = pycolmap.SequentialPairingOptions()
        pairing.overlap = 20
        pycolmap.match_sequential(database, matching_options=matching, pairing_options=pairing, device=pycolmap.Device.auto)

    say(0.42, "Working out where each photo was taken")
    sparse = work / "sparse"
    sparse.mkdir(exist_ok=True)
    mapping = pycolmap.IncrementalPipelineOptions()
    mapping.num_threads = threads
    mapping.min_model_size = min(10, max(3, count // 2))
    ticks = [0]

    def tick():
        ticks[0] += 1
        say(0.42 + 0.4 * min(1.0, ticks[0] / max(count, 1)), f"Placed {min(ticks[0], count)} of {count} photos")

    models = pycolmap.incremental_mapping(database, images, sparse, options=mapping, next_image_callback=tick)
    if not models:
        raise Failure(
            "Couldn't work out where the photos were taken. They need to overlap a lot: keep every part of the object in at "
            "least three photos, keep them sharp, and avoid plain, shiny or see-through surfaces."
        )
    best = max(models.values(), key=lambda m: (m.num_reg_images(), m.num_points3D()))
    placed = best.num_reg_images()
    if placed < max(4, int(0.35 * count)):
        raise Failure(
            f"Only {placed} of {count} photos could be placed. Move a little between photos (about 10°), keep the object in "
            "view, don't change the zoom, and use light without strong reflections."
        )

    say(0.85, "Standing the model upright")
    views = views_of(best)
    poses = load_poses(options["poses"]) if options.get("poses") else {}
    transform = alignment.from_arcore(views, poses) if poses else None
    if transform is None:
        transform = alignment.from_gravity(views)
    best.transform(pycolmap.Sim3d(transform.scale, pycolmap.Rotation3d(transform.rotation), transform.translation))
    aligned_views = {name: alignment.CameraView(v.rotation @ transform.rotation.T, transform.apply(v.center[None])[0]) for name, v in views.items()}

    roi = "none"
    points = best.num_points3D()
    if options.get("mode", "object") == "object":
        best, roi, points = crop_to_object(best, transform, aligned_views, options.get("box"))

    say(0.92, "Preparing the photos for the 3D builder")
    aligned = work / "aligned"
    aligned.mkdir(exist_ok=True)
    best.write(str(aligned))
    dense = work / "dense"
    pycolmap.undistort_images(dense, aligned, images, output_type="COLMAP")

    return {
        "photos": count,
        "placed": placed,
        "points": points,
        "scaleKnown": transform.scale_known,
        "method": transform.method,
        "detail": transform.detail,
        "roi": roi,
    }


def crop_to_object(reconstruction, transform, views, box):
    """Keeps the sparse points around the object being photographed, so the 3D builder works on it and not on the
    room: the scan's box when the phone knew where it was, else where the cameras are looking. [reconstruction] and
    [views] are already in the final world. Returns the cropped reconstruction (or the original when there is
    nothing sensible to crop to), how it was cropped, and how many points are left."""
    import pycolmap

    low = high = None
    how = "none"
    if box and transform.method == "arcore" and transform.scale_known:
        center, size = np.array(box["center"], dtype=float), float(box["size"])
        low = center - np.array([0.65 * size, 0.55 * size, 0.65 * size])
        high = center + np.array([0.65 * size, 0.7 * size, 0.65 * size])
        how = "box"
    else:
        found = looked_at(views)
        if found is not None:
            point, distance = found
            low, high = point - 0.6 * distance, point + 0.6 * distance
            how = "auto"
    total = reconstruction.num_points3D()
    if low is None:
        return reconstruction, "none", total
    cropped = reconstruction.crop(pycolmap.AlignedBox3d(low, high))
    kept = cropped.num_points3D()
    # A small object on a big textured floor owns few of the points; the scan's own box is trusted with fewer.
    if kept < 300 or (how == "auto" and kept < 0.02 * total):
        return reconstruction, "none", total
    return cropped, how, kept


def main(argv: list) -> int:
    work = Path(argv[1])
    with open(argv[2]) as f:
        options = json.load(f)
    try:
        result = run(work, options)
    except Failure as e:
        print(f"ERROR {e}", flush=True)
        return 2
    except Exception as e:  # Anything else: say what it was, the details go to the console.
        traceback.print_exc()
        print(f"ERROR The photos couldn't be processed ({e.__class__.__name__}: {e})", flush=True)
        return 3
    print("RESULT " + json.dumps(result), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
