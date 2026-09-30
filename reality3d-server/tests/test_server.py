import io
import struct
import sys
import time
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import server  # noqa: E402
from engines import CommandEngine, Engine, EngineError, PreviewEngine  # noqa: E402
from server import create_app  # noqa: E402


def cutout(size=(200, 150)) -> bytes:
    """A red disc on a transparent background, like the phone sends."""
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(image).ellipse((40, 20, 160, 130), fill=(210, 40, 30, 255))
    data = io.BytesIO()
    image.save(data, "PNG")
    return data.getvalue()


def wait(client, job_id, headers=None):
    for _ in range(200):
        status = client.get(f"/v1/jobs/{job_id}", headers=headers).json()
        if status["status"] in ("done", "failed", "cancelled"):
            return status
        time.sleep(0.05)
    raise AssertionError("the job never finished")


def parse_glb(data: bytes):
    magic, version, length = struct.unpack_from("<4sII", data)
    assert magic == b"glTF" and version == 2 and length == len(data)
    json_length, kind = struct.unpack_from("<I4s", data, 12)
    assert kind == b"JSON"
    import json
    return json.loads(data[20:20 + json_length])


def test_preview_engine_turns_a_cutout_into_a_closed_textured_model():
    client = TestClient(create_app({"preview": PreviewEngine()}, token=""))
    health = client.get("/v1/health").json()
    assert health["engines"][0]["id"] == "preview" and health["engines"][0]["available"]
    assert health["authRequired"] is False
    job = client.post("/v1/jobs", files={"image": ("cutout.png", cutout(), "image/png")}, data={"engine": "preview"})
    assert job.status_code == 202
    status = wait(client, job.json()["id"])
    assert status["status"] == "done", status
    result = client.get(f"/v1/jobs/{job.json()['id']}/result")
    assert result.headers["content-type"] == "model/gltf-binary"
    gltf = parse_glb(result.content)
    primitive = gltf["meshes"][0]["primitives"][0]
    assert set(primitive["attributes"]) == {"POSITION", "NORMAL", "TEXCOORD_0"}
    assert gltf["images"][0]["mimeType"] == "image/png"
    # Closed: every edge is shared by exactly two triangles, wound opposite ways.
    view = gltf["bufferViews"][gltf["accessors"][primitive["indices"]]["bufferView"]]
    binary = result.content[20 + struct.unpack_from("<I", result.content, 12)[0] + 8:]
    indices = np.frombuffer(binary, dtype="<u4", count=view["byteLength"] // 4, offset=view["byteOffset"]).reshape(-1, 3)
    edges = {}
    for a, b, c in indices:
        for e in ((a, b), (b, c), (c, a)):
            edges[e] = edges.get(e, 0) + 1
    assert all(count == 1 and edges.get((b, a)) == 1 for (a, b), count in edges.items())


def test_access_code_is_required_when_set():
    client = TestClient(create_app({"preview": PreviewEngine()}, token="s3cret"))
    assert client.get("/v1/health").json()["authRequired"] is True
    files = {"image": ("cutout.png", cutout(), "image/png")}
    assert client.post("/v1/jobs", files=files, data={"engine": "preview"}).status_code == 401
    assert client.post("/v1/jobs", files=files, data={"engine": "preview"}, headers={"Authorization": "Bearer nope"}).status_code == 401
    ok = client.post("/v1/jobs", files=files, data={"engine": "preview"}, headers={"Authorization": "Bearer s3cret"})
    assert ok.status_code == 202
    assert client.get(f"/v1/jobs/{ok.json()['id']}").status_code == 401


def test_bad_requests_are_refused():
    client = TestClient(create_app({"preview": PreviewEngine(), "sf3d": CommandEngine("sf3d", "Stable Fast 3D", "", None)}, token=""))
    files = {"image": ("cutout.png", cutout(), "image/png")}
    assert client.post("/v1/jobs", files=files, data={"engine": "nope"}).status_code == 400
    assert client.post("/v1/jobs", files=files, data={"engine": "sf3d"}).status_code == 409
    assert client.post("/v1/jobs", files={"image": ("x.png", b"not an image", "image/png")}, data={"engine": "preview"}).status_code == 400
    assert client.get("/v1/jobs/unknown").status_code == 404
    empty = Image.new("RGBA", (50, 50), (0, 0, 0, 0))
    data = io.BytesIO()
    empty.save(data, "PNG")
    job = client.post("/v1/jobs", files={"image": ("empty.png", data.getvalue(), "image/png")}, data={"engine": "preview"})
    status = wait(client, job.json()["id"])
    assert status["status"] == "failed" and "no object" in status["message"]
    assert client.get(f"/v1/jobs/{job.json()['id']}/result").status_code == 409


def test_command_engines_run_the_configured_tool(tmp_path):
    # A stand-in "AI" that copies a GLB into the output folder, the way SF3D's run.py writes its result.
    template = tmp_path / "model.glb"
    template.write_bytes(b"glTF" + b"\0" * 16)
    script = tmp_path / "fake_ai.py"
    script.write_text(
        "import shutil, sys, pathlib\n"
        "out = pathlib.Path(sys.argv[2]) / '0'\n"
        "out.mkdir(parents=True)\n"
        f"shutil.copy({str(template)!r}, out / 'mesh.glb')\n"
    )
    engine = CommandEngine("fake", "Fake AI", "", f"{sys.executable} {script} {{input}} {{output}}")
    client = TestClient(create_app({"fake": engine}, token=""))
    job = client.post("/v1/jobs", files={"image": ("cutout.png", cutout(), "image/png")}, data={"engine": "fake"})
    assert wait(client, job.json()["id"])["status"] == "done"
    assert client.get(f"/v1/jobs/{job.json()['id']}/result").content == template.read_bytes()

    broken = CommandEngine("broken", "Broken AI", "", f"{sys.executable} -c \"import sys; sys.exit('out of GPU memory')\"")
    client = TestClient(create_app({"broken": broken}, token=""))
    job = client.post("/v1/jobs", files={"image": ("cutout.png", cutout(), "image/png")}, data={"engine": "broken"})
    status = wait(client, job.json()["id"])
    assert status["status"] == "failed" and "out of GPU memory" in status["message"]


class FakePhotos(Engine):
    """Stands in for photogrammetry: counts the photos in the zip and writes a GLB-looking file."""

    def __init__(self, work_seconds: float = 0.0, ready: bool = True):
        super().__init__("photogrammetry", "Photos → 3D", "Needs photos.", kind="photos")
        self.work_seconds, self.ready, self.seen = work_seconds, ready, None

    def available(self) -> bool:
        return self.ready

    def why_not(self):
        return None if self.ready else "Run  pip install pycolmap"

    def run(self, photos, work, options, progress):
        import zipfile

        self.seen = {k: v for k, v in options.items() if k != "_cancel"}
        cancel = options["_cancel"]
        progress(0.3, "Working on the photos")
        deadline = time.monotonic() + self.work_seconds
        while time.monotonic() < deadline:
            if cancel.is_set():
                raise EngineError("Cancelled")
            time.sleep(0.02)
        with zipfile.ZipFile(photos) as zf:
            count = len(zf.namelist())
        out = work / "model.glb"
        out.write_bytes(b"glTF" + str(count).encode())
        return out


def photo_zip(count=3) -> bytes:
    import zipfile

    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as zf:
        for i in range(count):
            zf.writestr(f"images/{i}.jpg", b"jpeg")
    return data.getvalue()


def test_photo_jobs_take_a_zip_and_bring_back_the_model():
    engine = FakePhotos()
    client = TestClient(create_app({"photogrammetry": engine, "preview": PreviewEngine()}, token=""))
    health = {e["id"]: e for e in client.get("/v1/health").json()["engines"]}
    assert health["photogrammetry"]["kind"] == "photos" and health["preview"]["kind"] == "image"
    job = client.post("/v1/photo-jobs?quality=high&mode=scene", content=photo_zip(5), headers={"Content-Type": "application/zip"})
    assert job.status_code == 202
    assert wait(client, job.json()["id"])["status"] == "done"
    assert client.get(f"/v1/jobs/{job.json()['id']}/result").content == b"glTF5"
    assert engine.seen == {"quality": "high", "mode": "scene"}
    other = client.post("/v1/photo-jobs", content=photo_zip(), headers={"Content-Type": "application/zip"})
    assert wait(client, other.json()["id"])["status"] == "done"
    assert engine.seen == {"quality": "standard", "mode": "object"}


def test_an_engine_that_isnt_ready_says_what_to_do():
    client = TestClient(create_app({"photogrammetry": FakePhotos(ready=False)}, token=""))
    health = client.get("/v1/health").json()["engines"][0]
    assert health["available"] is False and "pip install pycolmap" in health["why"]
    refused = client.post("/v1/photo-jobs", content=photo_zip(), headers={"Content-Type": "application/zip"})
    assert refused.status_code == 409 and "pip install pycolmap" in refused.json()["detail"]


def test_photo_engines_and_cutout_engines_are_not_mixed_up():
    client = TestClient(create_app({"photogrammetry": FakePhotos(), "preview": PreviewEngine()}, token=""))
    assert client.post("/v1/photo-jobs?engine=preview", content=photo_zip()).status_code == 400
    assert client.post("/v1/photo-jobs?engine=nope", content=photo_zip()).status_code == 400
    files = {"image": ("cutout.png", cutout(), "image/png")}
    assert client.post("/v1/jobs", files=files, data={"engine": "photogrammetry"}).status_code == 400


def test_empty_and_oversized_photo_uploads_are_refused(monkeypatch):
    client = TestClient(create_app({"photogrammetry": FakePhotos()}, token=""))
    assert client.post("/v1/photo-jobs", content=b"").status_code == 400
    monkeypatch.setattr(server, "MAX_PHOTOS_UPLOAD", 100)
    assert client.post("/v1/photo-jobs", content=b"x" * 500).status_code == 413


def test_the_access_code_guards_photo_jobs_and_cancelling():
    client = TestClient(create_app({"photogrammetry": FakePhotos(work_seconds=5)}, token="s3cret"))
    assert client.post("/v1/photo-jobs", content=photo_zip()).status_code == 401
    ok = client.post("/v1/photo-jobs", content=photo_zip(), headers={"Authorization": "Bearer s3cret"})
    assert ok.status_code == 202
    assert client.delete(f"/v1/jobs/{ok.json()['id']}").status_code == 401
    assert client.delete(f"/v1/jobs/{ok.json()['id']}", headers={"Authorization": "Bearer s3cret"}).status_code == 200


def test_a_running_photo_job_and_a_waiting_one_can_be_cancelled():
    client = TestClient(create_app({"photogrammetry": FakePhotos(work_seconds=30)}, token=""))
    first = client.post("/v1/photo-jobs", content=photo_zip(), headers={"Content-Type": "application/zip"}).json()["id"]
    second = client.post("/v1/photo-jobs", content=photo_zip(), headers={"Content-Type": "application/zip"}).json()["id"]
    for _ in range(100):
        if client.get(f"/v1/jobs/{first}").json()["status"] == "running":
            break
        time.sleep(0.02)
    assert client.get(f"/v1/jobs/{second}").json()["status"] == "queued"
    assert client.delete(f"/v1/jobs/{second}").json()["status"] == "cancelled"
    assert client.delete(f"/v1/jobs/{first}").status_code == 200
    status = wait(client, first)
    assert status["status"] == "cancelled"
    assert client.get(f"/v1/jobs/{first}/result").status_code == 409
    assert client.delete("/v1/jobs/unknown").status_code == 404
