"""A made-up scene photographed from all around, for testing photos → 3D without a real object.

A textured ground plane with two boxes on it (a small one on a bigger one), lit from one side, seen by a camera
that walks three rings around it. The photos come with the poses a Reality3D scan would have recorded (ARCore's
convention: +X right, +Y up, looking down -Z, meters), so the whole pipeline, size and orientation included,
can be checked against the truth.
"""

import io
import json
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

# The object: a wide box with a smaller one on top (x, y, z ranges in meters); the ground is y = 0.
BOXES = [
    (np.array([-0.10, 0.0, -0.07]), np.array([0.10, 0.12, 0.07])),
    (np.array([-0.03, 0.12, -0.03]), np.array([0.05, 0.18, 0.04])),
]
TARGET = np.array([0.0, 0.07, 0.0])
LIGHT = np.array([0.4, 1.0, 0.3]) / np.linalg.norm([0.4, 1.0, 0.3])


def noise_texture(seed: int, size: int = 1024) -> np.ndarray:
    """Rich, blotchy colour with detail at every scale: lots of features for the matcher to find."""
    rng = np.random.default_rng(seed)
    total = np.zeros((size, size, 3))
    for octave, weight in ((8, 1.0), (32, 0.8), (128, 0.6), (512, 0.5)):
        layer = rng.random((octave, octave, 3))
        layer = np.asarray(Image.fromarray((layer * 255).astype(np.uint8)).resize((size, size), Image.BICUBIC), dtype=float) / 255
        total += weight * layer
    total /= total.max()
    return (60 + 190 * total).clip(0, 255).astype(np.uint8)


def sample(texture: np.ndarray, u: np.ndarray, v: np.ndarray) -> np.ndarray:
    h, w = texture.shape[:2]
    x, y = (u % 1.0) * (w - 1), (v % 1.0) * (h - 1)
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    x1, y1 = np.minimum(x0 + 1, w - 1), np.minimum(y0 + 1, h - 1)
    fx, fy = (x - x0)[..., None], (y - y0)[..., None]
    top = texture[y0, x0] * (1 - fx) + texture[y0, x1] * fx
    bottom = texture[y1, x0] * (1 - fx) + texture[y1, x1] * fx
    return top * (1 - fy) + bottom * fy


def render(position: np.ndarray, forward: np.ndarray, size: tuple, focal: float, textures: list) -> np.ndarray:
    width, height = size
    right = np.cross(forward, [0.0, 1.0, 0.0])
    right /= np.linalg.norm(right)
    up = np.cross(right, forward)
    xs = (np.arange(width) + 0.5 - width / 2) / focal
    ys = (np.arange(height) + 0.5 - height / 2) / focal
    gx, gy = np.meshgrid(xs, ys)
    directions = gx[..., None] * right - gy[..., None] * up + forward
    directions /= np.linalg.norm(directions, axis=2, keepdims=True)
    best = np.full((height, width), np.inf)
    color = np.zeros((height, width, 3))
    sky = np.array([190.0, 205.0, 225.0])
    color[:] = sky

    def hit(t, point, normal, uv, texture):
        nearer = (t > 1e-6) & (t < best)
        if not nearer.any():
            return
        shade = 0.45 + 0.55 * np.clip((normal * LIGHT).sum(axis=-1), 0, 1)
        picked = sample(texture, uv[0], uv[1]) * shade[..., None]
        best[nearer] = t[nearer]
        color[nearer] = picked[nearer]

    # Ground plane y = 0.
    with np.errstate(divide="ignore", invalid="ignore"):
        t = -position[1] / directions[..., 1]
    point = position + directions * t[..., None]
    inside = np.abs(point[..., 0]) < 2.0
    inside &= np.abs(point[..., 2]) < 2.0
    hit(np.where(inside, t, np.inf), point, np.broadcast_to([0.0, 1.0, 0.0], point.shape), (point[..., 0] / 0.6, point[..., 2] / 0.6), textures[0])

    for index, (low, high) in enumerate(BOXES):
        with np.errstate(divide="ignore", invalid="ignore"):
            t1, t2 = (low - position) / directions, (high - position) / directions
        near, far = np.minimum(t1, t2).max(axis=-1), np.maximum(t1, t2).min(axis=-1)
        t = np.where((near <= far) & (far > 0), near, np.inf)
        point = position + directions * t[..., None]
        finite = np.isfinite(t)
        safe = np.where(finite[..., None], point, 0.0)
        # Which face: the coordinate that sits on a box side.
        centre, half = (low + high) / 2, (high - low) / 2
        rel = (safe - centre) / half
        axis = np.abs(rel).argmax(axis=-1)
        normal = np.zeros_like(safe)
        np.put_along_axis(normal, axis[..., None], np.sign(np.take_along_axis(rel, axis[..., None], axis=-1)), axis=-1)
        u = np.where(axis == 0, safe[..., 2], safe[..., 0]) / 0.09
        v = np.where(axis == 1, safe[..., 2], safe[..., 1]) / 0.09
        hit(t, point, normal, (u, v), textures[1 + index])
    return color.clip(0, 255).astype(np.uint8)


def make_photos(archive: Path, count_per_ring=(12, 12, 8), elevations=(22, 45, 65), radius: float = 0.75,
                size: tuple = (800, 600), poses: bool = True, drift: float = 0.0, seed: int = 0, jpeg: bool = True) -> dict:
    """Writes a zip like a Reality3D scan's photo export. Returns the truth: the object's size and where it stands."""
    focal = 0.95 * size[0]
    textures = [noise_texture(1), noise_texture(2), noise_texture(3)]
    rng = np.random.default_rng(seed)
    frames = []
    index = 0
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_STORED) as zf:
        for ring, (count, elevation) in enumerate(zip(count_per_ring, elevations)):
            for k in range(count):
                azimuth = np.radians(360.0 * k / count + 17 * ring)
                lift = np.radians(elevation)
                position = TARGET + radius * np.array([np.sin(azimuth) * np.cos(lift), np.sin(lift), np.cos(azimuth) * np.cos(lift)])
                forward = (TARGET - position) / np.linalg.norm(TARGET - position)
                picture = render(position, forward, size, focal, textures)
                buffer = io.BytesIO()
                Image.fromarray(picture).save(buffer, "JPEG" if jpeg else "PNG", quality=93)
                name = f"frame_{index:03d}.{'jpg' if jpeg else 'png'}"
                zf.writestr(f"images/{name}", buffer.getvalue())
                right = np.cross(forward, [0.0, 1.0, 0.0])
                right /= np.linalg.norm(right)
                up = np.cross(right, forward)
                shown = position + rng.normal(0, drift, 3) if drift else position
                matrix = np.eye(4)
                matrix[:3, 0], matrix[:3, 1], matrix[:3, 2], matrix[:3, 3] = right, up, -forward, shown
                frames.append({"file": f"images/{name}", "width": size[0], "height": size[1], "fx": focal, "fy": focal,
                               "cx": size[0] / 2, "cy": size[1] / 2, "cameraToWorld": matrix.T.reshape(-1).tolist()})
                index += 1
        if poses:
            meta = {"generator": "synthetic", "units": "meters", "frames": frames,
                    "box": {"center": [0.0, 0.09, 0.0], "size": 0.2}}
            zf.writestr("cameras.json", json.dumps(meta))
    low = np.minimum.reduce([b[0] for b in BOXES])
    high = np.maximum.reduce([b[1] for b in BOXES])
    return {"size": high - low, "low": low, "high": high, "photos": index}
