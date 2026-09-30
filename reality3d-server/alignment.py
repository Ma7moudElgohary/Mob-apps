"""Puts a photogrammetry model into the phone's world: upright, and in meters when the phone knew where it was.

Photogrammetry works out where every photo was taken but not which way is up or how big anything is. When the
photos come from a Reality3D scan, ARCore recorded each camera's pose in meters with gravity pointing down, and
this module turns that into the missing scale and orientation. Otherwise it settles for making the model
stand upright, assuming the photos were taken with the phone held the right way up.
"""

from dataclasses import dataclass, field
from typing import Optional

import numpy as np

# ARCore's camera looks down -Z with +Y up; COLMAP's looks down +Z with +Y down.
GL_TO_CV = np.diag([1.0, -1.0, -1.0])

UP = np.array([0.0, 1.0, 0.0])


@dataclass
class CameraView:
    """A registered photo as COLMAP sees it: world-to-camera rotation (OpenCV axes) and the camera's position."""

    rotation: np.ndarray  # (3, 3)
    center: np.ndarray  # (3,)


@dataclass
class Transform:
    """x' = scale · R x + t, mapping the COLMAP model into the final world (Y up)."""

    scale: float = 1.0
    rotation: np.ndarray = field(default_factory=lambda: np.eye(3))
    translation: np.ndarray = field(default_factory=lambda: np.zeros(3))
    scale_known: bool = False
    method: str = "none"  # "arcore", "gravity" or "none"
    detail: str = ""

    def apply(self, points: np.ndarray) -> np.ndarray:
        return self.scale * (np.asarray(points, dtype=float) @ self.rotation.T) + self.translation


def rotation_angle(rotation: np.ndarray) -> float:
    """The angle of a rotation matrix, in degrees."""
    cosine = (np.trace(rotation) - 1.0) / 2.0
    return float(np.degrees(np.arccos(np.clip(cosine, -1.0, 1.0))))


def nearest_rotation(matrix: np.ndarray) -> np.ndarray:
    """The rotation closest to [matrix] (Frobenius norm)."""
    u, _, vt = np.linalg.svd(matrix)
    d = np.sign(np.linalg.det(u @ vt)) or 1.0
    return u @ np.diag([1.0, 1.0, d]) @ vt


def rotation_between(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """The smallest rotation taking the direction [a] onto [b]."""
    a = np.asarray(a, dtype=float) / np.linalg.norm(a)
    b = np.asarray(b, dtype=float) / np.linalg.norm(b)
    cross = np.cross(a, b)
    dot = float(np.dot(a, b))
    if dot < -1.0 + 1e-9:
        # Opposite directions: half a turn about any axis at right angles to [a].
        axis = np.cross(a, [1.0, 0.0, 0.0])
        if np.linalg.norm(axis) < 1e-6:
            axis = np.cross(a, [0.0, 1.0, 0.0])
        axis /= np.linalg.norm(axis)
        return 2.0 * np.outer(axis, axis) - np.eye(3)
    skew = np.array([[0.0, -cross[2], cross[1]], [cross[2], 0.0, -cross[0]], [-cross[1], cross[0], 0.0]])
    return np.eye(3) + skew + skew @ skew / (1.0 + dot)


def fit_scale_and_shift(source: np.ndarray, target: np.ndarray) -> tuple[float, np.ndarray, float]:
    """The scale s and shift t with s·source + t ≈ target, ignoring outliers; also the fit's typical error."""
    keep = np.ones(len(source), dtype=bool)
    scale, shift, residual = 1.0, np.zeros(3), np.inf
    for _ in range(6):
        source_mean, target_mean = source[keep].mean(axis=0), target[keep].mean(axis=0)
        ds, dt = source[keep] - source_mean, target[keep] - target_mean
        spread = float((ds * ds).sum())
        if spread < 1e-18:
            break
        scale = float((ds * dt).sum() / spread)
        shift = target_mean - scale * source_mean
        errors = np.linalg.norm(scale * source + shift - target, axis=1)
        residual = float(np.sqrt(np.mean(errors[keep] ** 2)))
        limit = max(3.0 * float(np.median(errors[keep])), 1e-6)
        better = errors <= limit
        if better.sum() < max(4, len(source) // 2) or (better == keep).all():
            break
        keep = better
    return scale, shift, residual


def from_arcore(views: dict[str, CameraView], arcore: dict[str, np.ndarray]) -> Optional[Transform]:
    """Scale, orientation and position from ARCore's camera poses (4×4 camera-to-world, OpenGL axes, meters).

    Each photo's viewing direction gives the rotation between the two worlds; the camera positions then give
    the scale and shift. Returns None when there is too little to go on or the two disagree."""
    names = [name for name in views if name in arcore]
    if len(names) < 4:
        return None
    each = np.array([arcore[n][:3, :3] @ GL_TO_CV @ views[n].rotation for n in names])
    keep = np.ones(len(names), dtype=bool)
    rotation = nearest_rotation(each.mean(axis=0))
    for _ in range(4):
        rotation = nearest_rotation(each[keep].mean(axis=0))
        angles = np.array([rotation_angle(rotation.T @ r) for r in each])
        limit = max(4.0, 3.0 * float(np.median(angles[keep])))
        better = angles <= limit
        if better.sum() < 4 or (better == keep).all():
            break
        keep = better
    angles = np.array([rotation_angle(rotation.T @ r) for r in each])
    typical = float(np.median(angles[keep]))
    if typical > 15.0:
        return None
    # The ARCore world's up must come out as +Y: it is, since ARCore's world has Y up already.
    colmap_centers = np.array([views[n].center for n in names])[keep]
    arcore_centers = np.array([arcore[n][:3, 3] for n in names])[keep]
    scale, shift, residual = fit_scale_and_shift(colmap_centers @ rotation.T, arcore_centers)
    extent = float(np.linalg.norm(arcore_centers.std(axis=0)))
    reliable = scale > 0 and extent > 0.05 and residual <= 0.15 * extent
    detail = f"{int(keep.sum())} of {len(names)} photos agreed with ARCore (rotation off by {typical:.1f}°, position off by {residual * 100:.1f} cm)"
    if not reliable:
        return Transform(1.0, rotation, np.zeros(3), False, "arcore", detail + "; the positions didn't fit, so the size isn't known")
    return Transform(scale, rotation, shift, True, "arcore", detail)


def up_direction(views: dict[str, CameraView]) -> Optional[np.ndarray]:
    """Which way is up in the model: the average of the cameras' own up directions (photos are upright)."""
    if not views:
        return None
    total = np.zeros(3)
    for view in views.values():
        total += -view.rotation[1]  # camera -Y (up) in world coordinates: the second row of world-to-camera, negated
    length = float(np.linalg.norm(total))
    if length < 0.3 * len(views):
        return None  # The phones pointed every which way: no reliable idea of up.
    return total / length


def from_gravity(views: dict[str, CameraView]) -> Transform:
    """Stands the model upright from the way the photos were held; the size stays unknown."""
    up = up_direction(views)
    if up is None:
        return Transform(detail="couldn't tell which way is up from the photos")
    return Transform(1.0, rotation_between(up, UP), np.zeros(3), False, "gravity", "stood upright from how the photos were held")


def settle_on_ground(points: np.ndarray, scale_known: bool, target: float = 1.0) -> tuple[float, np.ndarray]:
    """Scale and shift that put the model's lowest point on y = 0 and its middle over the origin; when the
    real size isn't known the longest side is made [target] long. Returns (scale factor, shift after scaling)."""
    low, high = points.min(axis=0), points.max(axis=0)
    factor = 1.0
    if not scale_known:
        longest = float((high - low).max())
        factor = target / longest if longest > 1e-12 else 1.0
    middle = (low + high) / 2.0
    shift = np.array([-middle[0], -low[1], -middle[2]]) * factor
    return factor, shift
