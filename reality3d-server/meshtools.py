"""Small mesh clean-ups for photogrammetry results (numpy only). Meshes here are vertex positions (n, 3), texture
coordinates (n, 2) and triangles as indices (m, 3); vertices at texture seams appear once per seam side."""

import numpy as np


def face_normals(positions: np.ndarray, faces: np.ndarray) -> np.ndarray:
    a, b, c = positions[faces[:, 0]], positions[faces[:, 1]], positions[faces[:, 2]]
    normal = np.cross(b - a, c - a)
    length = np.linalg.norm(normal, axis=1, keepdims=True)
    return normal / np.maximum(length, 1e-20)


def remove_table(positions: np.ndarray, faces: np.ndarray, floor: float, tolerance: float = 0.012) -> np.ndarray:
    """The triangles that aren't part of the flat surface the object stands on: everything that lies within
    [tolerance] of the height [floor] and is flat (facing up or down) is dropped, so the object is left on its own."""
    heights = positions[faces][:, :, 1]
    flat = (heights.max(axis=1) <= floor + tolerance) & (np.abs(face_normals(positions, faces)[:, 1]) > 0.35)
    return faces[~flat]


def largest_piece(positions: np.ndarray, faces: np.ndarray) -> np.ndarray:
    """The triangles of the biggest connected piece (by triangle count). Vertices that are only copies of one
    another for the sake of texture seams count as one, so a textured model isn't cut up along its seams."""
    if len(faces) == 0:
        return faces
    _, welded = np.unique(positions, axis=0, return_inverse=True)
    corners = welded.reshape(-1)[faces]
    label = np.arange(int(welded.max()) + 1)
    while True:
        smallest = label[corners].min(axis=1)
        before = label.copy()
        for k in range(3):
            np.minimum.at(label, corners[:, k], smallest)
        label = label[label]  # jump to each label's own label: far fewer rounds on long chains
        if (label == before).all():
            break
    pieces = label[corners[:, 0]]
    biggest = np.bincount(pieces).argmax()
    return faces[pieces == biggest]


def compact(positions: np.ndarray, uvs: np.ndarray, faces: np.ndarray) -> tuple:
    """Drops the vertices no triangle uses; returns (positions, uvs, faces) renumbered."""
    used = np.unique(faces)
    renumber = np.full(len(positions), -1, dtype=np.int64)
    renumber[used] = np.arange(len(used))
    return positions[used], uvs[used], renumber[faces]


def estimate_floor(positions: np.ndarray, faces: np.ndarray):
    """(height, tolerance) of the flat surface an object probably stands on, or None: the biggest upward-facing
    plane in the lower part of the model (a table or floor is the widest flat thing there), when it is a real share
    of the surface. The tolerance is how far from that height a triangle may be and still count as part of it."""
    if len(faces) < 200:
        return None
    triangles = positions[faces]
    cross = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
    areas = 0.5 * np.linalg.norm(cross, axis=1)
    normal_y = cross[:, 1] / np.maximum(2 * areas, 1e-20)
    heights = triangles[:, :, 1].mean(axis=1)
    low, high = np.percentile(positions[np.unique(faces), 1], [1, 99])
    span = float(high - low)
    up = normal_y > 0.9
    if span <= 0 or not up.any():
        return None
    bins = 120
    histogram, edges = np.histogram(heights[up], bins=bins, range=(low, high), weights=areas[up])
    histogram[int(0.4 * bins):] = 0.0  # a table is at the bottom: a big flat top higher up is the object itself
    peak = int(histogram.argmax())
    if histogram[peak] < 0.12 * areas.sum():
        return None
    in_peak = up & (heights >= edges[peak]) & (heights <= edges[peak + 1])
    height, tolerance = float(np.median(heights[in_peak])), 0.04 * span
    # A table has nothing under it; the flat top of a low box has the box's sides.
    if areas[heights < height - tolerance].sum() > 0.05 * areas.sum():
        return None
    return height, tolerance


def crop_to_box(positions: np.ndarray, faces: np.ndarray, low, high) -> np.ndarray:
    """The triangles whose middle lies inside the box [low, high] (corner coordinates)."""
    middles = positions[faces].mean(axis=1)
    inside = ((middles >= np.asarray(low)) & (middles <= np.asarray(high))).all(axis=1)
    return faces[inside]
