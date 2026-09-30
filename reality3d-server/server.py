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

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse
from PIL import Image, UnidentifiedImageError

from engines import Engine, EngineError, load_engines

VERSION = "1.1"
MAX_UPLOAD = 20 * 1024 * 1024
MAX_PHOTOS_UPLOAD = int(os.environ.get("R3D_MAX_UPLOAD_MB", 2048)) * 1024 * 1024
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
    status: str = "queued"  # queued, running, done, failed, cancelled
    progress: Optional[float] = None
    message: Optional[str] = None
    result: Optional[Path] = None
    input: Optional[Path] = None
    cancel: threading.Event = field(default_factory=threading.Event)
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

    def create(self, engine: Engine, options: dict) -> Job:
        """A job with its own folder, not started yet: the caller puts the input there and calls start()."""
        self._forget_old()
        job = Job(uuid.uuid4().hex, engine.id, self.root / uuid.uuid4().hex, options)
        job.folder.mkdir()
        with self.lock:
            self.jobs[job.id] = job
        return job

    def start(self, job: Job) -> None:
        self.queue.put(job.id)

    def submit(self, engine: Engine, image: Image.Image, options: dict) -> Job:
        job = self.create(engine, options)
        job.input = job.folder / "input.png"
        image.save(job.input)
        self.start(job)
        return job

    def discard(self, job: Job) -> None:
        with self.lock:
            self.jobs.pop(job.id, None)
        shutil.rmtree(job.folder, ignore_errors=True)

    def cancel(self, job: Job) -> None:
        """Stops a job: at once when it is waiting, as soon as the engine notices when it is running."""
        job.cancel.set()
        if job.status == "queued":
            job.status, job.message = "cancelled", "Cancelled"

    def get(self, job_id: str) -> Optional[Job]:
        with self.lock:
            return self.jobs.get(job_id)

    def _loop(self):
        while True:
            job = self.get(self.queue.get())
            if job is None or job.status == "cancelled":
                continue
            job.status = "running"
            engine = self.engines[job.engine]
            started = time.time()
            log(f"{engine.name}: started")

            def progress(fraction: Optional[float], message: str):
                job.progress = fraction
                job.message = message

            try:
                job.result = engine.run(job.input, job.folder, {**job.options, "_cancel": job.cancel}, progress)
                job.status, job.progress, job.message = "done", 1.0, None
                log(f"{engine.name}: done in {time.time() - started:.0f} s")
            except EngineError as e:
                if job.cancel.is_set():
                    job.status, job.message = "cancelled", "Cancelled"
                    log(f"{engine.name}: cancelled")
                else:
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
            "engines": [_describe(e) for e in engines.values()],
        }

    def _describe(engine: Engine) -> dict:
        available = engine.available()
        described = {"id": engine.id, "name": engine.name, "note": engine.note, "available": available, "kind": engine.kind}
        if not available and engine.why_not():
            described["why"] = engine.why_not()
        return described

    @app.post("/v1/jobs", status_code=202, dependencies=[Depends(authorized)])
    async def create_job(
        image: UploadFile = File(...),
        engine: str = Form("sf3d"),
        texture_size: int = Form(1024),
        faces: int = Form(20000),
    ):
        chosen = engines.get(engine)
        if chosen is None or chosen.kind != "image":
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

    @app.post("/v1/photo-jobs", status_code=202, dependencies=[Depends(authorized)])
    async def create_photo_job(request: Request, engine: str = "photogrammetry", quality: str = "standard", mode: str = "object"):
        """The body is a zip of photos (and, from a Reality3D scan, cameras.json); the options are in the query."""
        chosen = engines.get(engine)
        if chosen is None or chosen.kind != "photos":
            raise HTTPException(400, f"Unknown engine '{engine}'")
        if not chosen.available():
            raise HTTPException(409, chosen.why_not() or f"{chosen.name} isn't set up on this computer")
        job = worker.create(chosen, {"quality": quality, "mode": "scene" if mode == "scene" else "object"})
        target = job.folder / "photos.zip"
        received = 0
        try:
            with open(target, "wb") as out:
                async for chunk in request.stream():
                    received += len(chunk)
                    if received > MAX_PHOTOS_UPLOAD:
                        raise HTTPException(413, "The photos are too big; send fewer or smaller ones")
                    out.write(chunk)
        except HTTPException:
            worker.discard(job)
            raise
        except Exception:  # The phone gave up half way.
            worker.discard(job)
            raise HTTPException(400, "The photos didn't arrive completely") from None
        if received == 0:
            worker.discard(job)
            raise HTTPException(400, "No photos were sent")
        job.input = target
        log(f"{chosen.name}: {received / 1e6:.0f} MB of photos received")
        worker.start(job)
        return {"id": job.id, "status": job.status}

    @app.delete("/v1/jobs/{job_id}", dependencies=[Depends(authorized)])
    def cancel_job(job_id: str):
        job = worker.get(job_id)
        if job is None:
            raise HTTPException(404, "No such job (the server may have restarted)")
        worker.cancel(job)
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
