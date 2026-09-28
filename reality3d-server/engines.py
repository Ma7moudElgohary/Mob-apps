"""Image-to-3D engines. Each turns an RGBA cut-out (transparent background) into a GLB file."""

import importlib.util
import io
import os
import shlex
import subprocess
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

import numpy as np
from PIL import Image

from glb import write_glb

Progress = Callable[[Optional[float], str], None]


class EngineError(Exception):
    """A failure to show to the user as it is."""


@dataclass
class Engine:
    id: str
    name: str
    note: str

    def available(self) -> bool:
        return True

    def run(self, image: Path, work: Path, options: dict, progress: Progress) -> Path:
        raise NotImplementedError


class PreviewEngine(Engine):
    """No GPU needed: inflates the silhouette into a rounded, closed shape textured with the photo, front and back.
    It is there to test the connection from the phone; the AI engines give real 3D shapes."""

    def __init__(self):
        super().__init__("preview", "Quick preview (CPU)", "Inflated silhouette, no AI. For testing the connection.")

    def run(self, image: Path, work: Path, options: dict, progress: Progress) -> Path:
        progress(0.1, "Reading the cut-out")
        rgba = Image.open(image).convert("RGBA")
        grid = 96
        scale = grid / max(rgba.size)
        w, h = max(2, round(rgba.width * scale)), max(2, round(rgba.height * scale))
        alpha = np.asarray(rgba.resize((w, h), Image.BILINEAR))[:, :, 3] / 255.0
        inside = alpha >= 0.5
        if inside.sum() < 16:
            raise EngineError("the picture has no object: send a cut-out with a transparent background")
        progress(0.4, "Inflating the outline")
        distance = _distance_inside(inside)
        height = np.sqrt(distance / max(distance.max(), 1e-6)) * 0.35 * max(w, h) / grid
        # Grid points of the front surface: x right, y up, z towards the viewer; the longest side is 1.
        ys, xs = np.nonzero(inside)
        index = -np.ones((h, w), dtype=np.int64)
        index[ys, xs] = np.arange(len(xs))
        s = 1.0 / max(w, h)
        front = np.stack([(xs - (w - 1) / 2) * s, ((h - 1) / 2 - ys) * s, height[ys, xs] * s * grid / 2], axis=1)
        uv = np.stack([(xs + 0.5) / w, (ys + 0.5) / h], axis=1)
        tris = []
        for y in range(h - 1):
            for x in range(w - 1):
                a, b, c, d = index[y, x], index[y, x + 1], index[y + 1, x], index[y + 1, x + 1]
                if min(a, b, c, d) >= 0:
                    tris += [(a, c, b), (b, c, d)]
        tris = np.array(tris, dtype=np.int64)
        if len(tris) == 0:
            raise EngineError("the object is too thin to model")
        n = len(front)
        back = front * np.array([1, 1, -1])
        positions = np.vstack([front, back])
        uvs = np.vstack([uv, uv])
        faces = [tris, tris[:, ::-1] + n]
        # Walls along the outline join the front to the back, so the shape is closed.
        edges = {}
        for t in tris:
            for i in range(3):
                e = (t[i], t[(i + 1) % 3])
                edges[e] = edges.get(e, 0) + 1
        walls = [((a, b + n, b), (a, a + n, b + n)) for (a, b) in edges if (b, a) not in edges]
        if walls:
            faces.append(np.array([face for pair in walls for face in pair], dtype=np.int64))
        indices = np.vstack(faces).reshape(-1)
        # Keep only points some triangle uses: a lone point has no normal.
        used = np.unique(indices)
        remap = -np.ones(len(positions), dtype=np.int64)
        remap[used] = np.arange(len(used))
        positions, uvs, indices = positions[used], uvs[used], remap[indices]
        progress(0.8, "Writing the model")
        normals = _vertex_normals(positions, indices.reshape(-1, 3))
        png = io.BytesIO()
        rgba.convert("RGB").save(png, "PNG")
        out = work / "model.glb"
        out.write_bytes(write_glb(positions.astype(np.float32), normals, uvs.astype(np.float32), indices.astype(np.uint32), png.getvalue()))
        return out


class CommandEngine(Engine):
    """Runs an installed image-to-3D project through a command line from the config, e.g. Stable Fast 3D's run.py,
    then picks up the GLB it wrote. Placeholders: {input} {output} {texture_size} {faces}."""

    def __init__(self, id: str, name: str, note: str, command: Optional[str], timeout: int = 1800):
        super().__init__(id, name, note)
        self.command = command
        self.timeout = timeout

    def available(self) -> bool:
        return bool(self.command)

    def run(self, image: Path, work: Path, options: dict, progress: Progress) -> Path:
        if not self.command:
            raise EngineError(f"{self.name} isn't set up on this computer; see engines.example.toml")
        output = work / "output"
        output.mkdir(exist_ok=True)
        values = {"input": str(image), "output": str(output), "texture_size": str(options.get("texture_size", 1024)),
                  "faces": str(options.get("faces", 20000))}
        args = shlex.split(self.command.format(**values))
        progress(None, f"Running {self.name}")
        try:
            done = subprocess.run(args, capture_output=True, text=True, timeout=self.timeout, cwd=work)
        except FileNotFoundError as e:
            raise EngineError(f"couldn't start {self.name}: {e}") from e
        except subprocess.TimeoutExpired as e:
            raise EngineError(f"{self.name} took longer than {self.timeout // 60} minutes") from e
        if done.returncode != 0:
            tail = (done.stderr or done.stdout or "").strip().splitlines()[-3:]
            raise EngineError(f"{self.name} failed: " + (" / ".join(tail) or f"exit code {done.returncode}"))
        found = sorted(output.rglob("*.glb"), key=lambda p: p.stat().st_mtime)
        if not found:
            raise EngineError(f"{self.name} finished without writing a .glb file")
        return found[-1]


class HunyuanEngine(Engine):
    """Tencent Hunyuan3D-2 in this process, through its hy3dgen package: shape, then (unless turned off) texture."""

    def __init__(self, model: str = "tencent/Hunyuan3D-2", texture: bool = True):
        super().__init__("hunyuan3d", "Hunyuan3D-2", "High-quality shape and texture; needs a large GPU.")
        self.model = model
        self.texture = texture
        self._shape = None
        self._paint = None

    def available(self) -> bool:
        return importlib.util.find_spec("hy3dgen") is not None

    def run(self, image: Path, work: Path, options: dict, progress: Progress) -> Path:
        if not self.available():
            raise EngineError("Hunyuan3D-2 isn't installed here (pip package hy3dgen)")
        from hy3dgen.shapegen import Hunyuan3DDiTFlowMatchingPipeline  # noqa: PLC0415 (optional dependency)

        if self._shape is None:
            progress(0.05, "Loading Hunyuan3D-2 (first time takes a while)")
            self._shape = Hunyuan3DDiTFlowMatchingPipeline.from_pretrained(self.model)
        progress(0.2, "Generating the shape")
        mesh = self._shape(image=str(image))[0]
        if self.texture:
            from hy3dgen.texgen import Hunyuan3DPaintPipeline  # noqa: PLC0415

            if self._paint is None:
                progress(0.6, "Loading the texture model")
                self._paint = Hunyuan3DPaintPipeline.from_pretrained(self.model)
            progress(0.7, "Painting the texture")
            mesh = self._paint(mesh, image=str(image))
        out = work / "model.glb"
        mesh.export(str(out))
        return out


def load_engines(config_path: Optional[Path] = None) -> dict:
    """The preview engine, plus the AI engines from the TOML config (R3D_ENGINES, or engines.toml here)."""
    path = config_path or Path(os.environ.get("R3D_ENGINES", Path(__file__).with_name("engines.toml")))
    config = {}
    if path.exists():
        with open(path, "rb") as f:
            config = tomllib.load(f)
    sf3d, triposr, hunyuan = config.get("sf3d", {}), config.get("triposr", {}), config.get("hunyuan3d", {})
    engines = [
        PreviewEngine(),
        CommandEngine("sf3d", "Stable Fast 3D", "Fast (about a second on a big GPU), textured, needs about 6 GB of GPU memory.",
                      sf3d.get("command"), int(sf3d.get("timeout", 1800))),
        CommandEngine("triposr", "TripoSR", "Fast and light, works on smaller GPUs.",
                      triposr.get("command"), int(triposr.get("timeout", 1800))),
        HunyuanEngine(hunyuan.get("model", "tencent/Hunyuan3D-2"), bool(hunyuan.get("texture", True))),
    ]
    for id, section in config.items():
        if id not in ("sf3d", "triposr", "hunyuan3d") and isinstance(section, dict) and section.get("command"):
            engines.append(CommandEngine(id, section.get("name", id), section.get("note", ""), section["command"],
                                         int(section.get("timeout", 1800))))
    return {e.id: e for e in engines}


def _distance_inside(inside: np.ndarray) -> np.ndarray:
    """Chamfer distance (in cells) from each inside cell to the outside."""
    h, w = inside.shape
    big = float(h + w)
    d = np.where(inside, big, 0.0)
    for y in range(h):
        for x in range(w):
            if d[y, x]:
                up = d[y - 1, x] if y else 0.0
                left = d[y, x - 1] if x else 0.0
                d[y, x] = min(d[y, x], up + 1, left + 1)
    for y in range(h - 1, -1, -1):
        for x in range(w - 1, -1, -1):
            if d[y, x]:
                down = d[y + 1, x] if y < h - 1 else 0.0
                right = d[y, x + 1] if x < w - 1 else 0.0
                d[y, x] = min(d[y, x], down + 1, right + 1)
    return d


def _vertex_normals(positions: np.ndarray, faces: np.ndarray) -> np.ndarray:
    normals = np.zeros_like(positions)
    a, b, c = positions[faces[:, 0]], positions[faces[:, 1]], positions[faces[:, 2]]
    face = np.cross(b - a, c - a)
    for k in range(3):
        np.add.at(normals, faces[:, k], face)
    length = np.linalg.norm(normals, axis=1, keepdims=True)
    return (normals / np.maximum(length, 1e-12)).astype(np.float32)
