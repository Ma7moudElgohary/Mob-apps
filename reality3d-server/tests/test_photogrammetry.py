import io
import json
import struct
import sys
import zipfile
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import photogrammetry as pg  # noqa: E402
from engines import EngineError  # noqa: E402


def jpeg(size=(400, 300), orientation=None, color=(120, 90, 60)) -> bytes:
    image = Image.new("RGB", size, color)
    exif = Image.Exif()
    if orientation:
        exif[0x0112] = orientation
    buffer = io.BytesIO()
    image.save(buffer, "JPEG", exif=exif)
    return buffer.getvalue()


def zipped(tmp_path: Path, files: dict) -> Path:
    path = tmp_path / "photos.zip"
    with zipfile.ZipFile(path, "w") as zf:
        for name, data in files.items():
            zf.writestr(name, data)
    return path


def photos(count=8, **kwargs) -> dict:
    return {f"images/frame_{i:03d}.jpg": jpeg(**kwargs) for i in range(count)}


def test_photos_are_unpacked_upright_and_no_bigger_than_needed(tmp_path):
    files = photos(7)
    files["images/frame_003.jpg"] = jpeg((400, 300), orientation=6)  # a portrait photo stored sideways
    files["README.txt"] = b"not a photo"
    files["colmap/cameras.txt"] = b"# nothing"
    prepared = pg.prepare_photos(zipped(tmp_path, files), tmp_path / "work", 200)
    assert prepared.names == [f"frame_{i:03d}.jpg" for i in range(7)]
    assert sorted(p.name for p in (tmp_path / "work" / "images").iterdir()) == prepared.names
    sizes = {n: Image.open(tmp_path / "work" / "images" / n).size for n in prepared.names}
    assert sizes["frame_000.jpg"] == (200, 150)
    assert sizes["frame_003.jpg"] == (150, 200)  # turned upright (300 × 400) first, then shrunk
    assert prepared.poses == {} and prepared.box is None and prepared.camera_params is None


def test_small_photos_are_not_blown_up_and_other_formats_are_converted(tmp_path):
    png = io.BytesIO()
    Image.new("RGBA", (120, 90), (10, 200, 30, 255)).save(png, "PNG")
    files = photos(6, size=(100, 80))
    files["extra/holiday.png"] = png.getvalue()
    prepared = pg.prepare_photos(zipped(tmp_path, files), tmp_path / "work", 1600)
    assert "holiday.jpg" in prepared.names
    assert Image.open(tmp_path / "work" / "images" / "holiday.jpg").size == (120, 90)
    assert Image.open(tmp_path / "work" / "images" / "frame_000.jpg").size == (100, 80)


def test_photos_with_the_same_name_from_different_folders_stay_apart(tmp_path):
    files = {f"a/{i}.jpg": jpeg() for i in range(3)} | {f"b/{i}.jpg": jpeg() for i in range(3)}
    prepared = pg.prepare_photos(zipped(tmp_path, files), tmp_path / "work", 400)
    assert len(set(prepared.names)) == 6


def test_problems_with_the_photos_are_explained(tmp_path, monkeypatch):
    with pytest.raises(EngineError, match="at least 6"):
        pg.prepare_photos(zipped(tmp_path, photos(3)), tmp_path / "w1", 400)
    (tmp_path / "junk.zip").write_bytes(b"this is not a zip")
    with pytest.raises(EngineError, match="proper zip"):
        pg.prepare_photos(tmp_path / "junk.zip", tmp_path / "w2", 400)
    broken = photos(6)
    broken["images/frame_002.jpg"] = b"not a picture"
    with pytest.raises(EngineError, match="frame_002.jpg"):
        pg.prepare_photos(zipped(tmp_path, broken), tmp_path / "w3", 400)
    monkeypatch.setattr(pg, "MAX_PHOTOS", 8)
    with pytest.raises(EngineError, match="limit is 8"):
        pg.prepare_photos(zipped(tmp_path, photos(9)), tmp_path / "w4", 400)


def scan_files(count=8, size=(640, 480), fx=500.0) -> dict:
    files = photos(count, size=size)
    frames = []
    for i in range(count):
        m = np.eye(4)
        m[:3, 3] = [i * 0.1, 0.2, 0.5]
        frames.append({"file": f"images/frame_{i:03d}.jpg", "width": size[0], "height": size[1], "fx": fx, "fy": fx + 2, "cx": size[0] / 2, "cy": size[1] / 2,
                       "cameraToWorld": m.T.reshape(-1).tolist()})
    files["cameras.json"] = json.dumps({"frames": frames, "box": {"center": [0.1, 0.2, 0.3], "size": 0.25}})
    return files


def test_a_scans_poses_box_and_lens_are_read(tmp_path):
    prepared = pg.prepare_photos(zipped(tmp_path, scan_files()), tmp_path / "work", 320)
    assert sorted(prepared.poses) == prepared.names and len(prepared.poses["frame_003.jpg"]) == 16
    assert prepared.poses["frame_003.jpg"][12:15] == [pytest.approx(0.3), pytest.approx(0.2), pytest.approx(0.5)]  # column-major: the last column is the position
    assert prepared.box == {"center": [0.1, 0.2, 0.3], "size": 0.25}
    # The photos were shrunk to half size, so the lens numbers are halved with them.
    assert prepared.camera_params == [pytest.approx(0.5 * 501.0), pytest.approx(160.0), pytest.approx(120.0), 0.0]


def test_poses_for_missing_photos_or_a_broken_file_are_ignored(tmp_path):
    files = scan_files()
    meta = json.loads(files["cameras.json"])
    meta["frames"] = [f for f in meta["frames"] if not f["file"].endswith("frame_001.jpg")]
    meta["frames"].append({"file": "images/ghost.jpg", "cameraToWorld": [0.0] * 16})
    meta["frames"].append({"file": "images/frame_001.jpg", "cameraToWorld": [1.0, 2.0]})
    meta["box"] = {"center": [1, 2], "size": 1}
    files["cameras.json"] = json.dumps(meta)
    prepared = pg.prepare_photos(zipped(tmp_path, files), tmp_path / "w1", 640)
    assert "ghost.jpg" not in prepared.poses and len(prepared.poses) == 7 and prepared.box is None
    files["cameras.json"] = b"{not json"
    prepared = pg.prepare_photos(zipped(tmp_path, files), tmp_path / "w2", 640)
    assert prepared.poses == {} and prepared.camera_params is None


def test_progress_lines_from_the_programs_are_understood():
    assert pg.parse_progress("PROGRESS 0.250 Matching the photos with each other") == (0.25, "Matching the photos with each other")
    assert pg.parse_progress("PROGRESS 7 Too far") == (1.0, "Too far")
    assert pg.parse_progress("PROGRESS nonsense text") is None
    assert pg.parse_progress("RESULT {}") is None
    assert pg.depth_fraction("Estimated depth-maps 3 (27.27%, 53s, ETA 2m)...") == pytest.approx(0.9 * 0.2727)
    assert pg.depth_fraction("Dense fused depth-maps 5 (50.00%, 4s, ETA 7s)") == pytest.approx(0.95)
    assert pg.depth_fraction("Selecting images for dense reconstruction completed") is None


def test_a_meshs_size_is_read_from_its_ply_header(tmp_path):
    path = tmp_path / "m.ply"
    path.write_bytes(b"ply\nformat binary_little_endian 1.0\nelement vertex 1234\nproperty float x\nelement face 5678\nproperty list uchar int vertex_indices\nend_header\n\x00\x01")
    assert pg.ply_counts(path) == (1234, 5678)


def openmvs_glb(positions, uvs, indices, image_uri="scene_textured_0.png") -> bytes:
    """A GLB the way OpenMVS writes one: positions, texture coordinates and triangles, the picture left outside."""
    chunks = [np.asarray(positions, "<f4").tobytes(), np.asarray(indices, "<u4").tobytes(), np.asarray(uvs, "<f4").tobytes()]
    binary, views, offset = b"", [], 0
    for chunk in chunks:
        views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(chunk)})
        binary += chunk + b"\0" * (-len(chunk) % 4)
        offset = len(binary)
    gltf = {
        "asset": {"generator": "OpenMVS", "version": "2.0"},
        "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "TEXCOORD_0": 2}, "indices": 1, "material": 0, "mode": 4}]}],
        "materials": [{"pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}, "extensions": {"KHR_materials_unlit": {}}}],
        "textures": [{"source": 0}], "images": [{"uri": image_uri}],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(positions), "type": "VEC3"},
            {"bufferView": 1, "componentType": 5125, "count": len(indices), "type": "SCALAR"},
            {"bufferView": 2, "componentType": 5126, "count": len(uvs), "type": "VEC2"},
        ],
        "bufferViews": views, "buffers": [{"byteLength": len(binary)}],
    }
    text = json.dumps(gltf).encode()
    text += b" " * (-len(text) % 4)
    total = 12 + 8 + len(text) + 8 + len(binary)
    return struct.pack("<4sII", b"glTF", 2, total) + struct.pack("<I4s", len(text), b"JSON") + text + struct.pack("<I4s", len(binary), b"BIN\0") + binary


def test_the_builders_glb_becomes_a_self_contained_model_standing_on_the_ground(tmp_path):
    positions = np.array([[5, 5, 5], [7, 5, 5], [5, 8, 5], [5, 5, 9]], dtype=np.float32)
    uvs = np.array([[0, 0], [1, 0], [0, 1], [1, 1]], dtype=np.float32)
    indices = np.array([0, 1, 2, 0, 2, 3, 0, 3, 1, 1, 3, 2], dtype=np.uint32)
    (tmp_path / "in.glb").write_bytes(openmvs_glb(positions, uvs, indices))
    Image.new("RGB", (64, 64), (200, 100, 50)).save(tmp_path / "atlas.png")
    data, vertices, faces = pg.finish_glb(tmp_path / "in.glb", tmp_path / "atlas.png", scale_known=True, details={"photos": 40, "placed": 38}, object_only=False)
    assert (vertices, faces) == (4, 4)
    gltf, binary = pg.read_glb(data)
    assert gltf["images"][0]["mimeType"] == "image/jpeg" and "bufferView" in gltf["images"][0] and "uri" not in gltf["images"][0]
    view = gltf["bufferViews"][gltf["images"][0]["bufferView"]]
    assert binary[view["byteOffset"]: view["byteOffset"] + 2] == b"\xff\xd8"  # a JPEG really is in there
    assert gltf["asset"]["extras"]["reality3d"] == {"source": "photogrammetry", "scaleKnown": True, "photos": 40, "placed": 38}
    out = pg.accessor_array(gltf, binary, gltf["meshes"][0]["primitives"][0]["attributes"]["POSITION"])
    # Real size kept (meters), lowest point on the ground, middle over the origin.
    assert np.allclose(out.max(axis=0) - out.min(axis=0), [2, 3, 4]) and np.isclose(out[:, 1].min(), 0.0)
    assert np.isclose((out[:, 0].max() + out[:, 0].min()) / 2, 0.0) and np.isclose((out[:, 2].max() + out[:, 2].min()) / 2, 0.0)
    normals = pg.accessor_array(gltf, binary, gltf["meshes"][0]["primitives"][0]["attributes"]["NORMAL"])
    assert np.allclose(np.linalg.norm(normals, axis=1), 1.0, atol=1e-5)
    unknown, _, _ = pg.finish_glb(tmp_path / "in.glb", tmp_path / "atlas.png", scale_known=False, details={}, object_only=False)
    gltf, binary = pg.read_glb(unknown)
    out = pg.accessor_array(gltf, binary, gltf["meshes"][0]["primitives"][0]["attributes"]["POSITION"])
    assert np.isclose((out.max(axis=0) - out.min(axis=0)).max(), 1.0) and not gltf["asset"]["extras"]["reality3d"]["scaleKnown"]


def test_an_empty_or_broken_glb_is_reported(tmp_path):
    (tmp_path / "bad.glb").write_bytes(b"not a glb at all, really not")
    Image.new("RGB", (8, 8)).save(tmp_path / "a.png")
    with pytest.raises(EngineError, match="isn't a GLB"):
        pg.finish_glb(tmp_path / "bad.glb", tmp_path / "a.png", True, {})
    (tmp_path / "empty.glb").write_bytes(openmvs_glb(np.zeros((0, 3)), np.zeros((0, 2)), np.zeros(0, dtype=np.uint32)))
    with pytest.raises(EngineError, match="empty"):
        pg.finish_glb(tmp_path / "empty.glb", tmp_path / "a.png", True, {})
    # Only specks left after the clean-up: say so instead of handing over nothing.
    positions = np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0]], dtype=np.float32)
    (tmp_path / "speck.glb").write_bytes(openmvs_glb(positions, np.zeros((3, 2)), np.array([0, 1, 2], dtype=np.uint32)))
    with pytest.raises(EngineError, match="Nothing solid"):
        pg.finish_glb(tmp_path / "speck.glb", tmp_path / "a.png", True, {})


def glb_of(tmp_path, positions, faces, name="scene.glb"):
    path = tmp_path / name
    path.write_bytes(openmvs_glb(positions.astype(np.float32), np.zeros((len(positions), 2)), faces.reshape(-1).astype(np.uint32)))
    return path


def read_back(data):
    gltf, binary = pg.read_glb(data)
    primitive = gltf["meshes"][0]["primitives"][0]
    return gltf, pg.accessor_array(gltf, binary, primitive["attributes"]["POSITION"]), pg.accessor_array(gltf, binary, primitive["indices"]).reshape(-1, 3)


def test_a_single_object_is_left_without_its_table_or_stray_bits(tmp_path):
    from test_meshtools import BOTTOM, scene

    (positions, faces), table, thing, stray = scene()
    Image.new("RGB", (16, 16), (90, 90, 90)).save(tmp_path / "atlas.png")
    path = glb_of(tmp_path, positions, faces)
    data, vertices, count = pg.finish_glb(path, tmp_path / "atlas.png", True, {}, floor=0.0, object_only=True)
    gltf, out, triangles = read_back(data)
    assert count == thing - BOTTOM and gltf["asset"]["extras"]["reality3d"]["tableRemoved"] is True
    assert np.allclose(out.max(axis=0) - out.min(axis=0), [0.1, 0.1, 0.1], atol=1e-6) and np.isclose(out[:, 1].min(), 0.0)
    # Without ARCore's idea of the table's height the model itself shows where the table is.
    data, _, count = pg.finish_glb(path, tmp_path / "atlas.png", True, {}, floor=None, object_only=True)
    assert count == thing - BOTTOM and read_back(data)[0]["asset"]["extras"]["reality3d"]["tableRemoved"] is True
    # ARCore's height being a few centimeters off doesn't matter either.
    _, _, count = pg.finish_glb(path, tmp_path / "atlas.png", True, {}, floor=0.03, object_only=True)
    assert count == thing - BOTTOM
    # No table to be seen (the cube alone): only stray bits go.
    alone = glb_of(tmp_path, positions[table * 3:], faces[table:] - table * 3, "alone.glb")
    data, _, count = pg.finish_glb(alone, tmp_path / "atlas.png", False, {}, floor=None, object_only=True)
    assert count == thing and "tableRemoved" not in read_back(data)[0]["asset"]["extras"]["reality3d"]
    # The scan box: what lies outside it goes (the cube stands at the origin, the stray bit is far off anyway).
    box = (np.array([-0.06, -0.02, -0.06]), np.array([0.06, 0.14, 0.06]))
    data, _, count = pg.finish_glb(path, tmp_path / "atlas.png", True, {}, floor=0.0, object_only=True, region=box)
    assert count == thing - BOTTOM
    # ...but a box that is nowhere near the object doesn't take the model away.
    far = (np.array([5.0, 5.0, 5.0]), np.array([6.0, 6.0, 6.0]))
    _, _, count = pg.finish_glb(path, tmp_path / "atlas.png", True, {}, floor=0.0, object_only=True, region=far)
    assert count == thing - BOTTOM
    # A scene keeps everything.
    data, _, count = pg.finish_glb(path, tmp_path / "atlas.png", True, {}, floor=0.0, object_only=False)
    assert count == len(faces)


def test_a_thin_object_is_not_mistaken_for_the_table(tmp_path):
    from test_meshtools import quad_grid

    positions, faces = quad_grid(10, [0.0, 0.004, 0.0], 0.2, 1, 1)  # a sheet of paper lying on the table
    Image.new("RGB", (16, 16), (200, 200, 200)).save(tmp_path / "atlas.png")
    data, _, count = pg.finish_glb(glb_of(tmp_path, positions, faces), tmp_path / "atlas.png", True, {}, floor=0.0, object_only=True)
    assert count == len(faces) and "tableRemoved" not in read_back(data)[0]["asset"]["extras"]["reality3d"]


def test_a_texture_full_of_black_patches_is_spotted(tmp_path):
    rng = np.random.default_rng(0)
    good = rng.integers(60, 230, (256, 256, 3), dtype=np.uint8)
    good[128:] = [0xA0, 0xA0, 0xA0]  # the empty colour: nothing there, not a fault
    Image.fromarray(good).save(tmp_path / "good.png")
    assert not pg.texture_is_spoiled(tmp_path / "good.png")
    bad = good.copy()
    bad[:128, :80] = 0
    Image.fromarray(bad).save(tmp_path / "bad.png")
    assert pg.texture_is_spoiled(tmp_path / "bad.png")
    Image.new("RGB", (64, 64), (0xA0, 0xA0, 0xA0)).save(tmp_path / "blank.png")
    assert not pg.texture_is_spoiled(tmp_path / "blank.png")


def test_the_quality_settings_trade_time_for_detail():
    fast, standard, high = (pg.PRESETS[k] for k in ("fast", "standard", "high"))
    assert fast.size < standard.size < high.size
    assert fast.faces < standard.faces < high.faces
    assert fast.texture < standard.texture <= high.texture and high.texture <= 4096  # the phone has to hold it


def test_a_finished_build_leaves_only_the_model_and_the_log(tmp_path):
    (tmp_path / "images").mkdir()
    (tmp_path / "images" / "a.jpg").write_bytes(b"x")
    for name in ("model.glb", "log.txt", "depth0001.dmap", "scene.mvs", "database.db"):
        (tmp_path / name).write_bytes(b"x")
    pg.tidy(tmp_path)
    assert sorted(p.name for p in tmp_path.iterdir()) == ["log.txt", "model.glb"]
