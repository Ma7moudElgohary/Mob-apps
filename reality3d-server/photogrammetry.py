"""Photos → a textured 3D model on this computer, the way photogrammetry apps do it (COLMAP + OpenMVS).

The phone sends the photos of one object taken from all around (a zip; a Reality3D scan adds where ARCore knew
each photo was taken). Then:

  1. photo_sfm.py (COLMAP through pycolmap) works out where every photo was taken, stands the model upright, gives
     it real-world size when ARCore's positions are there, and keeps the part around the object;
  2. OpenMVS makes a dense point cloud, a mesh and a texture from the photos;
  3. this file tidies the result into one self-contained GLB the phone can open.
"""

import io
import json
import os
import re
import shutil
import struct
import sys
import threading
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Optional

import numpy as np
from PIL import Image, ImageOps

import alignment
import meshtools
import mvs_tools
from engines import Engine, EngineError, Progress
from glb import write_glb

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}
MIN_PHOTOS = 6
MAX_PHOTOS = 400
MAX_UNPACKED = 6 << 30
EMPTY_COLOR = 0xA0A0A0  # faces no photo saw


@dataclass(frozen=True)
class Preset:
    size: int  # longest side of the photos the builder works with, in pixels
    views: int  # neighbouring photos each depth map is made from
    geometric: int  # extra passes that keep depth maps consistent with each other
    faces: int  # triangles in the finished mesh
    texture: int  # texture size in pixels


PRESETS = {
    "fast": Preset(size=1000, views=3, geometric=1, faces=100_000, texture=2048),
    "standard": Preset(size=1600, views=4, geometric=1, faces=250_000, texture=4096),
    "high": Preset(size=2400, views=5, geometric=2, faces=500_000, texture=4096),  # phones cope with 4096 at most
}


@dataclass
class Prepared:
    names: list
    poses: dict  # photo name → 16 numbers, ARCore's column-major camera-to-world matrix
    box: Optional[dict]
    camera_params: Optional[list]  # focal length and principal point in pixels of the prepared photos


def prepare_photos(archive: Path, folder: Path, size: int) -> Prepared:
    """Unpacks the photos into folder/images as upright JPEGs no larger than [size]; reads cameras.json if present."""
    images = folder / "images"
    images.mkdir(parents=True, exist_ok=True)
    try:
        zf = zipfile.ZipFile(archive)
    except zipfile.BadZipFile as e:
        raise EngineError("The photos didn't arrive as a proper zip file. Send them again.") from e
    with zf:
        infos = [i for i in zf.infolist() if not i.is_dir()]
        if sum(i.file_size for i in infos) > MAX_UNPACKED:
            raise EngineError("The photos are too big to process here (over 6 GB unpacked).")
        cameras = {}
        for info in infos:
            if PurePosixPath(info.filename).name.lower() == "cameras.json":
                try:
                    cameras = json.loads(zf.read(info))
                except ValueError:
                    cameras = {}
        photos = sorted(
            (i for i in infos if PurePosixPath(i.filename).suffix.lower() in IMAGE_EXTENSIONS and not PurePosixPath(i.filename).name.startswith(".")),
            key=lambda i: i.filename,
        )
        if len(photos) < MIN_PHOTOS:
            raise EngineError(f"Need at least {MIN_PHOTOS} photos of the object from different sides, got {len(photos)}.")
        if len(photos) > MAX_PHOTOS:
            raise EngineError(f"That's {len(photos)} photos; the limit is {MAX_PHOTOS}. Send fewer, taken about 10° apart.")
        names, by_stem, scales = [], {}, {}
        for info in photos:
            stem = re.sub(r"[^A-Za-z0-9_.-]", "_", PurePosixPath(info.filename).stem) or "photo"
            name, n = stem + ".jpg", 1
            while name in names:
                n += 1
                name = f"{stem}_{n}.jpg"
            try:
                picture = Image.open(io.BytesIO(zf.read(info)))
                picture.load()
            except (OSError, ValueError, Image.DecompressionBombError) as e:
                raise EngineError(f"{PurePosixPath(info.filename).name} isn't a picture I can read.") from e
            original = picture.size
            picture = ImageOps.exif_transpose(picture).convert("RGB")
            if max(picture.size) > size:
                factor = size / max(picture.size)
                picture = picture.resize((max(1, round(picture.width * factor)), max(1, round(picture.height * factor))), Image.LANCZOS)
            exif = picture.getexif()
            picture.save(images / name, "JPEG", quality=95, exif=exif if len(exif) else b"")
            names.append(name)
            by_stem[PurePosixPath(info.filename).name.lower()] = name
            scales[name] = (picture.width / original[0], original, picture.size)
    poses, prior = {}, None
    frames = cameras.get("frames") if isinstance(cameras, dict) else None
    if isinstance(frames, list):
        focals = []
        for frame in frames:
            name = by_stem.get(PurePosixPath(str(frame.get("file", ""))).name.lower())
            matrix = frame.get("cameraToWorld")
            if name and isinstance(matrix, list) and len(matrix) == 16:
                poses[name] = [float(v) for v in matrix]
                scale = scales[name][0]
                focals.append((0.5 * (frame.get("fx", 0) + frame.get("fy", 0)) * scale, frame.get("cx", 0) * scale, frame.get("cy", 0) * scale))
        if focals and len(poses) >= MIN_PHOTOS:
            f, cx, cy = np.median(np.array(focals), axis=0)
            if f > 0:
                prior = [float(f), float(cx), float(cy), 0.0]
    box = cameras.get("box") if isinstance(cameras, dict) else None
    if not (isinstance(box, dict) and isinstance(box.get("center"), list) and len(box["center"]) == 3 and box.get("size")):
        box = None
    return Prepared(names, poses, box, prior)


def parse_progress(line: str) -> Optional[tuple]:
    """(fraction, text) from a `PROGRESS 0.25 Matching photos` line of photo_sfm.py, else None."""
    if not line.startswith("PROGRESS "):
        return None
    parts = line.split(" ", 2)
    try:
        return min(max(float(parts[1]), 0.0), 1.0), parts[2] if len(parts) > 2 else ""
    except (ValueError, IndexError):
        return None


DEPTH_PROGRESS = re.compile(r"(Estimated|Dense fused|Fused|Filtered) depth-maps \d+ \(([\d.]+)%")


def depth_fraction(line: str) -> Optional[float]:
    """How far the densifying is, 0..1, from one of OpenMVS's progress lines: estimating takes most of the time."""
    found = DEPTH_PROGRESS.search(line)
    if not found:
        return None
    percent = float(found.group(2)) / 100.0
    return 0.9 * percent if found.group(1) == "Estimated" else 0.9 + 0.1 * percent


def ply_counts(path: Path) -> tuple:
    """(vertices, faces) from a PLY file's header."""
    vertices = faces = 0
    with open(path, "rb") as f:
        for raw in f:
            line = raw.decode("ascii", "replace").strip()
            if line.startswith("element vertex"):
                vertices = int(line.split()[-1])
            elif line.startswith("element face"):
                faces = int(line.split()[-1])
            elif line == "end_header":
                break
    return vertices, faces


def read_glb(data: bytes) -> tuple:
    """(json, binary chunk) of a GLB file."""
    magic, version, _ = struct.unpack_from("<4sII", data, 0)
    if magic != b"glTF" or version != 2:
        raise EngineError("The 3D builder wrote something that isn't a GLB file.")
    offset, gltf, binary = 12, None, b""
    while offset + 8 <= len(data):
        length, kind = struct.unpack_from("<I4s", data, offset)
        chunk = data[offset + 8: offset + 8 + length]
        if kind == b"JSON":
            gltf = json.loads(chunk.decode("utf-8"))
        elif kind == b"BIN\0":
            binary = chunk
        offset += 8 + length
    if gltf is None:
        raise EngineError("The 3D builder wrote a GLB file without a model in it.")
    return gltf, binary


def accessor_array(gltf: dict, binary: bytes, index: int) -> np.ndarray:
    accessor = gltf["accessors"][index]
    view = gltf["bufferViews"][accessor["bufferView"]]
    dtype = {5126: "<f4", 5125: "<u4", 5123: "<u2", 5121: "u1"}[accessor["componentType"]]
    width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}[accessor["type"]]
    start = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
    return np.frombuffer(binary, dtype=dtype, count=accessor["count"] * width, offset=start).reshape(accessor["count"], width)


def vertex_normals(positions: np.ndarray, faces: np.ndarray) -> np.ndarray:
    normals = np.zeros_like(positions, dtype=np.float64)
    a, b, c = positions[faces[:, 0]], positions[faces[:, 1]], positions[faces[:, 2]]
    face = np.cross(b - a, c - a)
    for k in range(3):
        np.add.at(normals, faces[:, k], face)
    length = np.linalg.norm(normals, axis=1, keepdims=True)
    return (normals / np.maximum(length, 1e-20)).astype(np.float32)


def finish_glb(opened: Path, texture: Path, scale_known: bool, details: dict, floor: Optional[float] = None, object_only: bool = True, region: Optional[tuple] = None) -> tuple:
    """The 3D builder's GLB with its texture inside and normals, standing on y = 0 with its middle over the origin,
    in meters when the size is known (else the longest side is 1). For a single object, the table it stood on
    ([floor]: its height in the model's world, when known) and any stray bits are cut away; [region] is the box
    (low corner, high corner) the object was scanned in, when known, and what lies outside it goes too.
    Returns (glb bytes, vertices, faces)."""
    gltf, binary = read_glb(opened.read_bytes())
    primitive = gltf["meshes"][0]["primitives"][0]
    positions = accessor_array(gltf, binary, primitive["attributes"]["POSITION"]).astype(np.float64)
    uvs = accessor_array(gltf, binary, primitive["attributes"]["TEXCOORD_0"]).astype(np.float32)
    faces = accessor_array(gltf, binary, primitive["indices"]).reshape(-1, 3).astype(np.int64)
    if len(faces) < 1 or len(positions) < 3:
        raise EngineError("The 3D builder made an empty model.")
    details = dict(details)
    if object_only:
        if region is not None:
            inside = meshtools.crop_to_box(positions, faces, region[0], region[1])
            if len(inside) >= 0.05 * len(faces):  # a box in the wrong place mustn't take the whole model away
                faces = inside
        piece = meshtools.largest_piece(positions, faces)
        tolerance = 0.012
        found = meshtools.estimate_floor(positions, piece)
        if found is not None and (floor is None or abs(found[0] - floor) < 0.06):
            floor, tolerance = found  # what the model shows beats ARCore's idea of the table's height
        if floor is not None:
            without_table = meshtools.largest_piece(positions, meshtools.remove_table(positions, faces, floor, tolerance))
            # A thin object (a coin, a sheet of paper) is all "table" by this rule: keep the table then.
            if len(without_table) >= 0.15 * len(faces):
                piece = without_table
                details["tableRemoved"] = True
        if len(piece) < 20:
            raise EngineError("Nothing solid was left of the model. Take the photos closer to the object and from more sides.")
        positions, uvs, faces = meshtools.compact(positions, uvs, piece)
    factor, shift = alignment.settle_on_ground(positions, scale_known)
    positions = (positions * factor + shift).astype(np.float32)
    normals = vertex_normals(positions, faces)
    with Image.open(texture) as atlas:
        jpeg = io.BytesIO()
        atlas.convert("RGB").save(jpeg, "JPEG", quality=90, subsampling=0)
    extras = {"reality3d": {"source": "photogrammetry", "scaleKnown": bool(scale_known), **details}}
    glb = write_glb(positions, normals, uvs, faces.reshape(-1).astype(np.uint32), jpeg.getvalue(), mime="image/jpeg", extras=extras)
    return glb, len(positions), len(faces)


class PhotogrammetryEngine(Engine):
    """Photos of an object from all around → textured, real-size 3D model."""

    def __init__(self):
        super().__init__(
            "photogrammetry",
            "Photos → 3D (photogrammetry)",
            "Best quality: many photos from all around the object, worked out on this computer. Takes minutes, not seconds.",
            kind="photos",
        )
        self.timeout = float(os.environ.get("R3D_PHOTO_TIMEOUT", 3 * 3600))

    def available(self) -> bool:
        import importlib.util

        return importlib.util.find_spec("pycolmap") is not None and (mvs_tools.find_tools() is not None or mvs_tools.can_download())

    def why_not(self) -> Optional[str]:
        import importlib.util

        if importlib.util.find_spec("pycolmap") is None:
            return "Run  pip install pycolmap  on the computer, then start the server again."
        if mvs_tools.find_tools() is None and not mvs_tools.can_download():
            return "OpenMVS isn't installed and can't be downloaded here; set R3D_OPENMVS to its folder."
        return None

    def run(self, photos: Path, work: Path, options: dict, progress: Progress) -> Path:
        preset = PRESETS.get(options.get("quality", "standard"), PRESETS["standard"])
        scene = options.get("mode") == "scene"
        cancel: threading.Event = options.get("_cancel") or threading.Event()
        started = time.monotonic()

        def remaining() -> float:
            return max(60.0, self.timeout - (time.monotonic() - started))

        progress(0.01, "Unpacking the photos")
        prepared = prepare_photos(photos, work, preset.size)

        tools = mvs_tools.find_tools()
        if tools is None:
            try:
                tools = mvs_tools.install(lambda f, m: progress(0.02 + 0.06 * f if f is not None else None, m))
            except mvs_tools.ToolError as e:
                raise EngineError(str(e)) from e
        if cancel.is_set():
            raise EngineError("Cancelled")

        # 1. Where were the photos taken?
        poses_file = None
        if prepared.poses:
            poses_file = work / "poses.json"
            poses_file.write_text(json.dumps(prepared.poses))
        sfm_options = {
            "size": preset.size,
            "mode": "scene" if scene else "object",
            "poses": str(poses_file) if poses_file else None,
            "box": prepared.box,
            "camera": "single" if prepared.camera_params else "auto",
            "camera_params": prepared.camera_params,
        }
        (work / "sfm_options.json").write_text(json.dumps(sfm_options))
        result, failure = {}, []

        def on_sfm(line: str):
            step = parse_progress(line)
            if step is not None:
                progress(0.08 + 0.37 * step[0], step[1])
            elif line.startswith("RESULT "):
                result.update(json.loads(line[7:]))
            elif line.startswith("ERROR "):
                failure.append(line[6:])

        code, tail = self._tool([sys.executable, str(Path(__file__).with_name("photo_sfm.py")), str(work), str(work / "sfm_options.json")], work, on_sfm, cancel, remaining())
        if code != 0 or not result:
            raise EngineError(failure[0] if failure else "The photos couldn't be processed: " + " / ".join(tail[-3:]))
        details = {"photos": result["photos"], "placed": result["placed"], "method": result["method"]}

        # 2. Dense points, mesh, texture.
        def densify(line: str):
            fraction = depth_fraction(line)
            if fraction is not None:
                progress(0.46 + 0.39 * fraction, "Building the surface from the photos")

        progress(0.45, "Handing the photos to the 3D builder")
        self._mvs(tools, "InterfaceCOLMAP", ["-i", work / "dense", "-o", work / "scene.mvs", "--image-folder", work / "dense" / "images"], work, cancel, remaining())
        self._mvs(
            tools, "DensifyPointCloud",
            [work / "scene.mvs", "--resolution-level", 0, "--max-resolution", preset.size, "--number-views", preset.views,
             "--geometric-iters", preset.geometric, "--estimate-roi", 0 if scene else 1.1, "--crop-to-roi", 0 if scene else 1,
             "--remove-dmaps", 1],
            work, cancel, remaining(), densify, "The photos didn't show enough detail to build a surface. Shiny, see-through and plain surfaces are hard; try light without reflections.",
        )
        progress(0.86, "Making the mesh")
        self._mvs(
            tools, "ReconstructMesh",
            [work / "scene_dense.mvs", "-p", work / "scene_dense.ply", "--target-face-num", preset.faces, "--remove-spurious", 20, "--smooth", 2,
             "--crop-to-roi", 0 if scene else 1],
            work, cancel, remaining(),
        )
        mesh = work / "scene_dense_mesh.ply"
        if not mesh.exists() or ply_counts(mesh)[1] < 500:
            raise EngineError("The 3D builder found too little surface. Take more photos from all around, closer to the object, in even light.")
        progress(0.92, "Painting the mesh with the photos")
        atlas = self._texture(tools, work, preset, cancel, remaining)

        progress(0.98, "Packing the model")
        floor = None
        if result["scaleKnown"] and prepared.box and isinstance(prepared.box.get("floorY"), (int, float)):
            floor = float(prepared.box["floorY"])
        region = None
        if result["scaleKnown"] and prepared.box:
            centre, size = np.array(prepared.box["center"], dtype=float), float(prepared.box["size"])
            region = (centre - [0.6 * size, 0.6 * size, 0.6 * size], centre + [0.6 * size, 0.7 * size, 0.6 * size])
        glb, vertices, faces = finish_glb(work / "scene_textured.glb", atlas, bool(result["scaleKnown"]), details, floor, object_only=not scene, region=region)
        out = work / "model.glb"
        out.write_bytes(glb)
        tidy(work)
        return out

    def _texture(self, tools: Path, work: Path, preset: Preset, cancel, remaining) -> Path:
        """Textures the mesh; if OpenMVS's seam smoothing spoils the picture (it does on some builds), once more without."""
        atlas = None
        for smoothing in (1, 0):
            for stale in work.glob("scene_textured*"):
                stale.unlink()
            self._mvs(
                tools, "TextureMesh",
                [work / "scene_dense.mvs", "-m", work / "scene_dense_mesh.ply", "-o", work / "scene_textured.mvs", "--export-type", "glb",
                 "--max-texture-size", preset.texture, "--global-seam-leveling", smoothing, "--local-seam-leveling", smoothing,
                 "--empty-color", EMPTY_COLOR],
                work, cancel, remaining(),
            )
            atlases = sorted(work.glob("scene_textured_*.png")) + sorted(work.glob("scene_textured_*.jpg"))
            if not atlases:
                raise EngineError("The 3D builder didn't write a texture.")
            if len(atlases) > 1:
                raise EngineError("The model needs more than one texture; try the fast quality setting.")
            atlas = atlases[0]
            if smoothing == 0 or not texture_is_spoiled(atlas):
                break
        return atlas

    def _mvs(self, tools: Path, name: str, args: list, work: Path, cancel, timeout: float, on_line=lambda line: None, empty_message: str = "") -> None:
        code, tail = self._tool([tools / mvs_tools.exe(name)] + [str(a) for a in args], work, on_line, cancel, timeout)
        if code == mvs_tools.MISSING_DLL or code == mvs_tools.MISSING_DLL - (1 << 32):
            raise EngineError("OpenMVS needs the Microsoft Visual C++ Redistributable: install it from https://aka.ms/vs/17/release/vc_redist.x64.exe and try again.")
        if code != 0:
            raise EngineError(empty_message or f"{name} failed: " + (" / ".join(tail[-3:]) or f"exit code {code}"))

    def _tool(self, command: list, work: Path, on_line, cancel, timeout: float) -> tuple:
        try:
            code, tail = mvs_tools.run(command, work, on_line, cancel, timeout)
        except mvs_tools.ToolError as e:
            raise EngineError(str(e)) from e
        with open(work / "log.txt", "a", encoding="utf-8") as log:
            log.write(f"$ {' '.join(str(c) for c in command)}\n" + "\n".join(tail) + "\n")
        return code, tail


def tidy(work: Path) -> None:
    """After a good build, drops everything but the model and the log: the depth maps and copies of the photos take
    gigabytes, and the folder stays around for an hour."""
    for item in work.iterdir():
        if item.name in ("model.glb", "log.txt"):
            continue
        try:
            shutil.rmtree(item) if item.is_dir() else item.unlink()
        except OSError:
            pass


def texture_is_spoiled(path: Path) -> bool:
    """OpenMVS's seam smoothing can fill patches with black. A finished atlas has hardly any pure black in it."""
    with Image.open(path) as atlas:
        pixels = np.asarray(atlas.convert("RGB").resize((512, 512), Image.BOX))
    covered = ~(np.abs(pixels.astype(int) - np.array([(EMPTY_COLOR >> 16) & 255, (EMPTY_COLOR >> 8) & 255, EMPTY_COLOR & 255])).sum(axis=2) < 12)
    if covered.sum() < 500:
        return False
    dark = (pixels.max(axis=2) < 12) & covered
    return dark.sum() / covered.sum() > 0.06
