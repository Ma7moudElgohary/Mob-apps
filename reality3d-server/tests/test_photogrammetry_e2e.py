"""The whole photos → 3D pipeline on made-up photos with a known answer (see synthetic.py).

Slow (a few minutes) and needs pycolmap and OpenMVS, so it only runs when asked:  R3D_E2E=1 python -m pytest tests/test_photogrammetry_e2e.py
(OpenMVS is found through R3D_OPENMVS, or downloaded the first time.)
"""

import os
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import photogrammetry as pg  # noqa: E402
import synthetic  # noqa: E402

pytestmark = pytest.mark.skipif(not os.environ.get("R3D_E2E"), reason="slow: set R3D_E2E=1 to run it")


def build(tmp_path, poses: bool):
    truth = synthetic.make_photos(tmp_path / "photos.zip", count_per_ring=(10, 10, 6), size=(640, 480), poses=poses)
    engine = pg.PhotogrammetryEngine()
    if not engine.available():
        pytest.skip(engine.why_not() or "the photo builder isn't available here")
    steps = []
    out = engine.run(tmp_path / "photos.zip", tmp_path / "work", {"quality": "fast", "mode": "object"}, lambda f, m: steps.append((f, m)))
    gltf, binary = pg.read_glb(out.read_bytes())
    primitive = gltf["meshes"][0]["primitives"][0]
    positions = pg.accessor_array(gltf, binary, primitive["attributes"]["POSITION"])
    return truth, gltf, positions, steps


def test_a_scan_with_poses_comes_back_upright_in_meters_and_without_the_table(tmp_path):
    truth, gltf, positions, steps = build(tmp_path, poses=True)
    info = gltf["asset"]["extras"]["reality3d"]
    assert info["scaleKnown"] is True and info["method"] == "arcore" and info["placed"] >= 0.9 * info["photos"]
    size = positions.max(axis=0) - positions.min(axis=0)
    assert np.allclose(size, truth["size"], rtol=0.10), (size, truth["size"])
    assert np.isclose(positions[:, 1].min(), 0.0, atol=1e-6)
    assert abs((positions[:, 0].max() + positions[:, 0].min()) / 2) < 0.02
    # Progress ran up to the end and every step said what it was doing.
    fractions = [f for f, _ in steps if f is not None]
    assert fractions == sorted(fractions) and fractions[-1] >= 0.97 and all(m for _, m in steps)


def test_photos_alone_make_an_upright_model_one_unit_long(tmp_path):
    truth, gltf, positions, _ = build(tmp_path, poses=False)
    info = gltf["asset"]["extras"]["reality3d"]
    assert info["scaleKnown"] is False and info["method"] == "gravity"
    size = positions.max(axis=0) - positions.min(axis=0)
    assert np.isclose(size.max(), 1.0, atol=1e-3) and np.isclose(positions[:, 1].min(), 0.0, atol=1e-6)
    # Standing up: the height is what it should be relative to the footprint's diagonal (the model's yaw is arbitrary).
    footprint = np.hypot(truth["size"][0], truth["size"][2])
    assert 0.6 < size[1] / size.max() < 1.0 and truth["size"][1] / footprint > 0.5
