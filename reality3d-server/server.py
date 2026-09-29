"""Reality3D server: turns a phone's cut-out photo into a full 3D model with an image-to-3D AI on this computer's GPU.

Run:  python run.py   (it shows the address to type in the app; or: uvicorn server:app --host 0.0.0.0 --port 8765)
"""

import io
import os
import queue
import secrets
import shutil
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from PIL import Image, UnidentifiedImageError

from engines import Engine, EngineError, load_engines

VERSION = "1.0"
MAX_UPLOAD = 20 * 1024 * 1024
MAX_SIDE = 2048
KEEP_SECONDS = 3600
MAX_JOBS = 50


def log(message: str) -> None:
    """A line on the console, so the person at the computer can see the phone's jobs arrive."""
    print(f"{time.strftime('%H:%M:%S')}  {message}", flush=True)


@dataclass
class Job:
    id: str
    engine: str
    folder: Path
    options: dict
    status: str = "queued"  # queued, running, done, failed
    progress: Optional[float] = None
    message: Optional[str] = None
    result: Optional[Path] = None
    created: float = field(default_factory=time.time)


class Worker:
    """Runs one job at a time: image-to-3D models fill the GPU."""

    def __init__(self, engines: dict):
        self.engines = engines
        self.jobs: dict[str, Job] = {}
        self.lock = threading.Lock()
        self.queue: "queue.Queue[str]" = queue.Queue()
        self.root = Path(tempfile.mkdtemp(prefix="reality3d_"))
        threading.Thread(target=self._loop, daemon=True).start()

    def submit(self, engine: Engine, image: Image.Image, options: dict) -> Job:
        self._forget_old()
        job = Job(uuid.uuid4().hex, engine.id, self.root / uuid.uuid4().hex, options)
        job.folder.mkdir()
        image.save(job.folder / "input.png")
        with self.lock:
            self.jobs[job.id] = job
        self.queue.put(job.id)
        return job

    def get(self, job_id: str) -> Optional[Job]:
        with self.lock:
            return self.jobs.get(job_id)

    def _loop(self):
        while True:
            job = self.get(self.queue.get())
            if job is None:
                continue
            job.status = "running"
            engine = self.engines[job.engine]
            started = time.time()
            log(f"{engine.name}: started")

            def progress(fraction: Optional[float], message: str):
                job.progress = fraction
                job.message = message

            try:
                job.result = engine.run(job.folder / "input.png", job.folder, job.options, progress)
                job.status, job.progress, job.message = "done", 1.0, None
                log(f"{engine.name}: done in {time.time() - started:.0f} s")
            except EngineError as e:
                job.status, job.message = "failed", str(e)
                log(f"{engine.name}: failed: {e}")
            except Exception as e:  # An engine's own error: report it rather than dying.
                job.status, job.message = "failed", f"{engine.name} failed: {e.__class__.__name__}: {e}"
                log(f"{engine.name}: failed: {e.__class__.__name__}: {e}")

    def _forget_old(self):
        now = time.time()
        with self.lock:
            old = [j for j in self.jobs.values() if now - j.created > KEEP_SECONDS and j.status in ("done", "failed")]
            extra = sorted((j for j in self.jobs.values() if j.status in ("done", "failed")), key=lambda j: j.created)
            old += extra[: max(0, len(self.jobs) - MAX_JOBS)]
            for job in old:
                self.jobs.pop(job.id, None)
                shutil.rmtree(job.folder, ignore_errors=True)


def create_app(engines: Optional[dict] = None, token: Optional[str] = None) -> FastAPI:
    engines = engines if engines is not None else load_engines()
    token = token if token is not None else os.environ.get("R3D_TOKEN") or None
    worker = Worker(engines)
    app = FastAPI(title="Reality3D server", version=VERSION)

    def authorized(authorization: Optional[str] = Header(default=None)):
        if token and not (authorization and secrets.compare_digest(authorization, f"Bearer {token}")):
            raise HTTPException(401, "Wrong or missing access code")

    @app.get("/v1/health")
    def health():
        return {
            "name": "Reality3D server",
            "version": VERSION,
            "authRequired": bool(token),
            "engines": [{"id": e.id, "name": e.name, "note": e.note, "available": e.available()} for e in engines.values()],
        }

    @app.post("/v1/jobs", status_code=202, dependencies=[Depends(authorized)])
    async def create_job(
        image: UploadFile = File(...),
        engine: str = Form("sf3d"),
        texture_size: int = Form(1024),
        faces: int = Form(20000),
    ):
        chosen = engines.get(engine)
        if chosen is None:
            raise HTTPException(400, f"Unknown engine '{engine}'")
        if not chosen.available():
            raise HTTPException(409, f"{chosen.name} isn't set up on this computer")
        data = await image.read(MAX_UPLOAD + 1)
        if len(data) > MAX_UPLOAD:
            raise HTTPException(413, "The picture is too large")
        try:
            picture = Image.open(io.BytesIO(data))
            picture.load()
        except (UnidentifiedImageError, OSError) as e:
            raise HTTPException(400, "That isn't a picture") from e
        picture = picture.convert("RGBA")
        picture.thumbnail((MAX_SIDE, MAX_SIDE))
        options = {"texture_size": min(max(texture_size, 256), 4096), "faces": min(max(faces, 1000), 500000)}
        job = worker.submit(chosen, picture, options)
        return {"id": job.id, "status": job.status}

    @app.get("/v1/jobs/{job_id}", dependencies=[Depends(authorized)])
    def job_status(job_id: str):
        job = worker.get(job_id)
        if job is None:
            raise HTTPException(404, "No such job (the server may have restarted)")
        return {"id": job.id, "status": job.status, "progress": job.progress, "message": job.message}

    @app.get("/v1/jobs/{job_id}/result", dependencies=[Depends(authorized)])
    def job_result(job_id: str):
        job = worker.get(job_id)
        if job is None:
            raise HTTPException(404, "No such job (the server may have restarted)")
        if job.status != "done" or job.result is None:
            raise HTTPException(409, "The model isn't ready yet")
        return FileResponse(job.result, media_type="model/gltf-binary", filename="model.glb")

    return app


app = create_app()
