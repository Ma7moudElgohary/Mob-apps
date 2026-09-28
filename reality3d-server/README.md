# Reality3D server

One photo can't show the back of an object. This small server lets the Reality3D app send its cut-out
photo to an **image-to-3D AI on your own computer** and get back a complete, textured 3D model (GLB),
back included. Nothing goes to the internet: the phone talks to your computer over your Wi-Fi.

Supported AIs (install the ones you want; the server finds them through `engines.toml`):

| Engine | What it is | GPU memory |
|---|---|---|
| **Stable Fast 3D** (`sf3d`) | Stability AI, a textured model in about a second | about 6 GB |
| **TripoSR** (`triposr`) | VAST / Stability, fast and light | about 6 GB |
| **Hunyuan3D-2** (`hunyuan3d`) | Tencent, the most detailed shape and texture | 16 GB or more |
| Quick preview (`preview`) | Built in, no AI: inflates the outline. For testing the connection | none |

## Setup

1. Python 3.10 or newer, then in this folder:

   ```
   python -m venv .venv
   .venv\Scripts\activate          (Windows)   or   source .venv/bin/activate   (macOS/Linux)
   pip install -r requirements.txt
   ```

2. Install one or more AIs by following their own instructions, e.g.
   [Stable Fast 3D](https://github.com/Stability-AI/stable-fast-3d),
   [TripoSR](https://github.com/VAST-AI-Research/TripoSR) or
   [Hunyuan3D-2](https://github.com/Tencent/Hunyuan3D-2) (install its `hy3dgen` package into this same environment).

3. Copy `engines.example.toml` to `engines.toml` and point each command at your install.

4. Start the server:

   ```
   uvicorn server:app --host 0.0.0.0 --port 8765
   ```

   To require an access code, set it first: `set R3D_TOKEN=pick-a-code` (Windows) or `export R3D_TOKEN=pick-a-code`.

5. Find the computer's address on your network (`ipconfig` on Windows, `ip addr` or System Settings on
   macOS/Linux), for example `192.168.1.20`. In the app, open a photo, then **AI Full 3D on your computer →
   Connect to your computer**, type `192.168.1.20:8765` (and the access code if you set one).
   If it can't connect, allow port 8765 through the computer's firewall.

## API

- `GET /v1/health` → the server's engines and whether an access code is needed
- `POST /v1/jobs` (multipart: `image` PNG with a transparent background, `engine`, `texture_size`, `faces`) → `{"id"}`
- `GET /v1/jobs/{id}` → `status` (`queued`, `running`, `done`, `failed`), `progress`, `message`
- `GET /v1/jobs/{id}/result` → the model as `model/gltf-binary`

Jobs run one at a time, since these models fill the GPU. With `R3D_TOKEN` set, every call except
`/v1/health` needs `Authorization: Bearer <code>`.

## Tests

```
pip install pytest httpx
python -m pytest tests
```
