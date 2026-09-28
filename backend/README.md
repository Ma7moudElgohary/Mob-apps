# Reality3D GPU backend

The Android app's **AI 3D** mode talks to this small HTTP service. Heavy model environments stay on a GPU machine while Quick Local and Scan 360 remain on-device.

## Start

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn reality3d_server:app --host 0.0.0.0 --port 8000
```

Configure one or both model adapters as command templates. `{input}` is the uploaded JPG and `{output}` must become a GLB file.

```bash
export REALITY3D_SF3D_COMMAND='python /opt/stable-fast-3d/reality3d_adapter.py --input {input} --output {output}'
export REALITY3D_HUNYUAN_COMMAND='python /opt/hunyuan3d/reality3d_adapter.py --input {input} --output {output}'
export REALITY3D_PUBLIC_BASE_URL='https://your-gpu-host.example.com'
```

The wrapper intentionally does not bake model-specific CLI assumptions into Reality3D. Install the upstream model version you want and provide a tiny adapter that accepts `--input` and `--output`. This keeps API keys and GPU dependencies off the phone.

Endpoints: `GET /health`, `POST /v1/generate`, `GET /v1/files/{job}`.
