import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import alignment  # noqa: E402
from alignment import GL_TO_CV, CameraView  # noqa: E402


def random_rotation(rng) -> np.ndarray:
    """A uniformly random rotation."""
    q, r = np.linalg.qr(rng.normal(size=(3, 3)))
    q = q * np.sign(np.diag(r))
    return q * np.sign(np.linalg.det(q))


def look_at(position, target):
    """ARCore's camera-to-world matrix (OpenGL axes) for a camera at [position] looking at [target], world Y up."""
    forward = (target - position) / np.linalg.norm(target - position)
    right = np.cross(forward, [0.0, 1.0, 0.0])
    right /= np.linalg.norm(right)
    up = np.cross(right, forward)
    m = np.eye(4)
    m[:3, 0], m[:3, 1], m[:3, 2], m[:3, 3] = right, up, -forward, position
    return m


def ring_of_cameras(count=24, radius=0.6, heights=(0.15, 0.4)):
    poses = {}
    for i in range(count):
        a = 2 * np.pi * i / count
        h = heights[i % len(heights)]
        poses[f"p{i}.jpg"] = look_at(np.array([radius * np.sin(a), h, radius * np.cos(a)]), np.array([0.0, 0.05, 0.0]))
    return poses


def colmap_views(arcore: dict, rotation, scale, shift) -> dict:
    """The same cameras as COLMAP would see them in its own arbitrary world, where [rotation], [scale] and [shift]
    take that world to ARCore's: x_arcore = scale · rotation · x_colmap + shift."""
    views = {}
    for name, m in arcore.items():
        cam_to_world_cv = rotation.T @ (m[:3, :3] @ GL_TO_CV)
        center = rotation.T @ (m[:3, 3] - shift) / scale
        views[name] = CameraView(cam_to_world_cv.T, center)
    return views


def test_a_rotation_takes_one_direction_onto_another():
    rng = np.random.default_rng(1)
    for _ in range(20):
        a, b = rng.normal(size=3), rng.normal(size=3)
        r = alignment.rotation_between(a, b)
        assert np.allclose(r @ r.T, np.eye(3), atol=1e-9) and np.isclose(np.linalg.det(r), 1.0)
        assert np.allclose(r @ (a / np.linalg.norm(a)), b / np.linalg.norm(b), atol=1e-9)
    flip = alignment.rotation_between([0, 1, 0], [0, -1, 0])
    assert np.allclose(flip @ [0, 1, 0], [0, -1, 0]) and np.isclose(np.linalg.det(flip), 1.0)
    assert np.allclose(alignment.rotation_between([1, 2, 3], [1, 2, 3]), np.eye(3))


def test_the_size_and_orientation_come_back_from_arcores_poses():
    rng = np.random.default_rng(2)
    arcore = ring_of_cameras()
    rotation, scale, shift = random_rotation(rng), 0.37, np.array([1.5, -2.0, 0.7])
    found = alignment.from_arcore(colmap_views(arcore, rotation, scale, shift), arcore)
    assert found is not None and found.scale_known and found.method == "arcore"
    assert np.isclose(found.scale, scale, rtol=1e-6)
    assert np.allclose(found.rotation, rotation, atol=1e-6)
    assert np.allclose(found.translation, shift, atol=1e-6)
    # A point of the model lands where the same point is in ARCore's world.
    point = np.array([0.3, -0.2, 1.1])
    assert np.allclose(found.apply(point[None])[0], scale * rotation @ point + shift, atol=1e-6)


def test_noisy_poses_and_a_few_wrong_ones_still_give_the_right_size():
    rng = np.random.default_rng(3)
    arcore = ring_of_cameras(40)
    rotation, scale, shift = random_rotation(rng), 2.5, np.array([0.1, 0.2, 0.3])
    views = colmap_views(arcore, rotation, scale, shift)
    for name in list(arcore)[:3]:  # ARCore lost track for a moment: those poses are far off.
        arcore[name] = look_at(np.array([3.0, 1.0, -2.0]), np.zeros(3))
    for name, m in arcore.items():  # And the rest drift by a centimetre or so.
        m[:3, 3] += rng.normal(0, 0.008, 3)
    found = alignment.from_arcore(views, arcore)
    assert found is not None and found.scale_known
    assert np.isclose(found.scale, scale, rtol=0.03)
    assert alignment.rotation_angle(found.rotation.T @ rotation) < 2.0


def test_poses_that_dont_fit_the_photos_are_not_believed():
    rng = np.random.default_rng(4)
    arcore = ring_of_cameras()
    views = colmap_views(arcore, np.eye(3), 1.0, np.zeros(3))
    scrambled = dict(zip(arcore, rng.permutation(list(arcore.values()))))
    found = alignment.from_arcore(views, {n: np.array(m) for n, m in scrambled.items()})
    assert found is None or not found.scale_known
    assert alignment.from_arcore(views, {}) is None
    assert alignment.from_arcore(views, dict(list(arcore.items())[:3])) is None


def test_without_poses_the_model_is_stood_upright_from_how_the_photos_were_held():
    rng = np.random.default_rng(5)
    arcore = ring_of_cameras()
    rotation = random_rotation(rng)
    found = alignment.from_gravity(colmap_views(arcore, rotation, 1.0, np.zeros(3)))
    assert found.method == "gravity" and not found.scale_known
    # ARCore's up (+Y) is where the model's up must end up: in COLMAP's arbitrary world that direction is rotation.T · Y.
    up_in_colmap = rotation.T @ np.array([0.0, 1.0, 0.0])
    assert np.allclose(found.rotation @ up_in_colmap, [0, 1, 0], atol=0.12)


def test_photos_taken_every_which_way_give_no_idea_of_up():
    rng = np.random.default_rng(6)
    views = {f"p{i}": CameraView(random_rotation(rng), rng.normal(size=3)) for i in range(30)}
    found = alignment.from_gravity(views)
    assert found.method == "none" and np.allclose(found.rotation, np.eye(3))


def test_the_model_settles_on_the_ground_at_its_real_size_or_one_unit_long():
    points = np.array([[1.0, 2.0, 3.0], [1.4, 2.1, 3.2], [1.2, 2.8, 3.1]])
    factor, shift = alignment.settle_on_ground(points, scale_known=True)
    placed = points * factor + shift
    assert factor == 1.0 and np.isclose(placed[:, 1].min(), 0.0)
    assert np.isclose((placed[:, 0].min() + placed[:, 0].max()) / 2, 0.0) and np.isclose((placed[:, 2].min() + placed[:, 2].max()) / 2, 0.0)
    factor, shift = alignment.settle_on_ground(points, scale_known=False)
    placed = points * factor + shift
    assert np.isclose((placed.max(axis=0) - placed.min(axis=0)).max(), 1.0) and np.isclose(placed[:, 1].min(), 0.0)


def test_a_fit_ignores_points_that_are_way_off():
    rng = np.random.default_rng(7)
    source = rng.normal(size=(30, 3))
    target = 1.8 * source + np.array([0.5, 0.1, -0.3])
    target[:4] += 5.0
    scale, shift, residual = alignment.fit_scale_and_shift(source, target)
    assert np.isclose(scale, 1.8, rtol=1e-3) and np.allclose(shift, [0.5, 0.1, -0.3], atol=1e-3) and residual < 1e-3
