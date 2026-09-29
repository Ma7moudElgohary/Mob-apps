import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import meshtools  # noqa: E402


def quad_grid(cells: int, origin, size: float, axis: int, sign: int = 1):
    """A flat square split into cells × cells squares (two triangles each) at right angles to [axis], facing [sign].
    Every triangle has its own three vertices, the way a textured model has them at its seams."""
    corners, faces = [], []
    u, v = [a for a in range(3) if a != axis]
    step = size / cells
    for i in range(cells):
        for j in range(cells):
            def point(a, b):
                p = np.array(origin, dtype=float)
                p[u] += a * step
                p[v] += b * step
                return p
            quad = [point(i, j), point(i + 1, j), point(i + 1, j + 1), point(i, j + 1)]
            for tri in ((0, 1, 2), (0, 2, 3)):
                base = len(corners)
                corners.extend(quad[k] for k in tri)
                faces.append([base, base + 1, base + 2])
    positions = np.array(corners)
    faces = np.array(faces)
    normal = meshtools.face_normals(positions, faces)[0][axis]
    if np.sign(normal) != sign:
        faces = faces[:, ::-1]
    return positions, faces


def merge(*parts):
    positions, faces, offset = [], [], 0
    for p, f in parts:
        positions.append(p)
        faces.append(f + offset)
        offset += len(p)
    return np.vstack(positions), np.vstack(faces)


def cube(cells, low, size):
    low = np.array(low, dtype=float)
    sides = [
        quad_grid(cells, low, size, 1, -1), quad_grid(cells, low + [0, size, 0], size, 1, 1),
        quad_grid(cells, low, size, 0, -1), quad_grid(cells, low + [size, 0, 0], size, 0, 1),
        quad_grid(cells, low, size, 2, -1), quad_grid(cells, low + [0, 0, size], size, 2, 1),
    ]
    return merge(*sides)


def scene():
    table = quad_grid(20, [-0.3, 0.0, -0.3], 0.6, 1, 1)
    thing = cube(6, [-0.05, 0.0, -0.05], 0.1)
    stray = cube(1, [0.2, 0.3, 0.2], 0.03)
    return merge(table, thing, stray), len(table[1]), len(thing[1]), len(stray[1])


BOTTOM = 6 * 6 * 2  # the cube's underside: flat and on the table, so it goes with it


def test_the_flat_surface_the_object_stands_on_is_dropped():
    (positions, faces), table, thing, stray = scene()
    left = meshtools.remove_table(positions, faces, floor=0.0)
    assert len(left) == len(faces) - table - BOTTOM
    low = positions[left][:, :, 1].max(axis=1) <= 0.012
    assert not (np.abs(meshtools.face_normals(positions, left)[:, 1]) > 0.8)[low].any()


def test_only_the_biggest_piece_is_kept_even_when_its_triangles_dont_share_vertices():
    (positions, faces), table, thing, stray = scene()
    piece = meshtools.largest_piece(positions, meshtools.remove_table(positions, faces, floor=0.0))
    assert len(piece) == thing - BOTTOM
    inside = positions[piece]
    assert inside.max() <= 0.1 + 1e-9 and inside.min() >= -0.05 - 1e-9  # the cube, not the stray one at 0.2
    assert len(meshtools.largest_piece(positions, faces)) == table + thing  # the table and the cube touch: one piece
    assert len(meshtools.largest_piece(np.zeros((0, 3)), np.zeros((0, 3), dtype=int))) == 0


def test_a_chain_of_triangles_is_one_piece():
    n = 400
    positions = np.array([[i * 0.5, 0.0, 0.0] if i % 2 == 0 else [i * 0.5 - 0.5, 1.0, 0.0] for i in range(n + 2)])
    faces = np.array([[i, i + 1, i + 2] for i in range(n)])
    assert len(meshtools.largest_piece(positions, faces)) == n


def test_unused_vertices_are_dropped_and_the_rest_renumbered():
    positions = np.arange(30, dtype=float).reshape(10, 3)
    uvs = np.arange(20, dtype=float).reshape(10, 2)
    faces = np.array([[2, 5, 7], [5, 7, 9]])
    p, u, f = meshtools.compact(positions, uvs, faces)
    assert len(p) == 4 and f.tolist() == [[0, 1, 2], [1, 2, 3]]
    assert np.allclose(p[f[0]], positions[[2, 5, 7]]) and np.allclose(u[f[1]], uvs[[5, 7, 9]])


def test_only_what_lies_inside_the_scan_box_is_kept():
    (positions, faces), table, thing, stray = scene()
    low, high = np.array([-0.06, 0.005, -0.06]), np.array([0.06, 0.12, 0.06])
    inside = meshtools.crop_to_box(positions, faces, low, high)
    assert 0 < len(inside) < len(faces)
    middles = positions[inside].mean(axis=1)
    assert (middles >= low).all() and (middles <= high).all()
    assert positions[inside][:, :, 0].max() < 0.1  # the far tabletop and the stray cube (x from 0.2) are gone
    assert len(meshtools.crop_to_box(positions, faces, [5, 5, 5], [6, 6, 6])) == 0
