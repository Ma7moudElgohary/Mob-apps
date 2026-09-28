"""Reality3D GPU backend.

This service deliberately keeps model installations outside the Android app. It can wrap
Stable Fast 3D or Hunyuan3D through configurable command templates so model revisions do
not require changing the mobile protocol.
"""
from __future__ import annotations

import base64
import os
import shlex
import subprocess
import tempfile
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

app = FastAPI(title="Reality3D GPU Backend", version="1.0")
ROOT = Path(os.getenv("REALITY3D_OUTPUT_DIR", "./outputs")).resolve()
ROOT.mkdir(parents=True, exist_ok=True)

class Request(BaseModel):
    engine: str = "sf3d"
    imageBase64: str


def command_for(engine: str, input_path: Path, output_path: Path) -> list[str]:
    env_name = {"sf3d": "REALITY3D_SF3D_COMMAND", "hunyuan": "REALITY3D_HUNYUAN_COMMAND"}.get(engine)
    if not env_name:
        raise HTTPException(400, f"Unsupported engine: {engine}")
    template = os.getenv(env_name, "").strip()
    if not template:
        raise HTTPException(503, f"{env_name} is not configured on this GPU host")
    return [part.format(input=str(input_path), output=str(output_path)) for part in shlex.split(template)]

@app.get("/health")
def health():
    return {"ok": True, "engines": {"sf3d": bool(os.getenv("REALITY3D_SF3D_COMMAND")), "hunyuan": bool(os.getenv("REALITY3D_HUNYUAN_COMMAND"))}}

@app.post("/v1/generate")
def generate(req: Request):
    try:
        image = base64.b64decode(req.imageBase64, validate=True)
    except Exception as exc:
        raise HTTPException(400, "Invalid imageBase64") from exc
    if len(image) > 25 * 1024 * 1024:
        raise HTTPException(413, "Image is too large")
    job = uuid.uuid4().hex
    work = ROOT / job
    work.mkdir(parents=True, exist_ok=False)
    input_path = work / "input.jpg"
    output_path = work / "result.glb"
    input_path.write_bytes(image)
    command = command_for(req.engine, input_path, output_path)
    try:
        completed = subprocess.run(command, cwd=work, capture_output=True, text=True, timeout=int(os.getenv("REALITY3D_TIMEOUT", "600")))
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(504, "3D generation timed out") from exc
    if completed.returncode != 0:
        raise HTTPException(500, f"Generator failed: {completed.stderr[-1500:]}")
    if not output_path.exists() or output_path.stat().st_size < 1024:
        raise HTTPException(500, "Generator did not create a valid GLB")
    public = os.getenv("REALITY3D_PUBLIC_BASE_URL", "http://localhost:8000").rstrip("/")
    return {"engine": req.engine, "downloadUrl": f"{public}/v1/files/{job}", "message": "Full 3D generation complete"}

@app.get("/v1/files/{job}")
def download(job: str):
    if not job.isalnum():
        raise HTTPException(400, "Invalid job")
    file = ROOT / job / "result.glb"
    if not file.exists():
        raise HTTPException(404, "Result not found")
    return FileResponse(file, media_type="model/gltf-binary", filename="Reality3D.glb")
