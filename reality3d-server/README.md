# Reality3D server

One photo can't show the back of an object. This small server lets the Reality3D app send its cut-out
photo to an **image-to-3D AI on your own computer** and get back a complete, textured 3D model (GLB),
back included. Nothing goes to the internet: the phone talks to your computer over your Wi-Fi.

## Quick start

You need a computer on the **same Wi-Fi as the phone**, and Python 3.10 or newer
([python.org/downloads](https://www.python.org/downloads/); on Windows tick **Add python.exe to PATH** in the installer).

1. Put this folder on the computer (on GitHub: the branch's **Code → Download ZIP**, then open the
   `reality3d-server` folder inside it).
2. Start it:
   - **Windows:** double-click `start.bat`.
   - **macOS / Linux:** run `./start.sh` in a terminal.

   The first time it sets itself up (about a minute, needs the internet). Every time it prints the address to
   type in the app, like `192.168.1.20:8765`. If Windows asks about the firewall, choose **Allow** (Private networks).
3. In the app, open a photo, then **AI Full 3D on your computer → Connect to your computer**, type the address
   and tap **Save and connect**. It says *Connected to …* and lists the engines.
4. Leave the window open while you use it. Press Ctrl+C (or close the window) to stop.

With nothing else installed, the only engine is **Quick preview (CPU)**: it inflates the outline into a rounded
shape, with no AI. That is enough to check that the phone and the computer are talking. For a real full 3D model
install one of the AIs below.

To ask the phone for an access code, set `R3D_TOKEN` before starting (`set R3D_TOKEN=pick-a-code` on Windows,
`export R3D_TOKEN=pick-a-code` elsewhere). `R3D_PORT` changes the port (8765).

## The AIs

| Engine | What it is | GPU memory | Weights |
|---|---|---|---|
| **Stable Fast 3D** (`sf3d`) | Stability AI, a textured model in about a second | about 6 GB | free, but accept its terms on Hugging Face and log in first |
| **TripoSR** (`triposr`) | VAST / Stability, fast and light | about 6 GB | free (MIT) |
| **Hunyuan3D-2** (`hunyuan3d`) | Tencent, the most detailed shape and texture | 16 GB or more | free |
| Quick preview (`preview`) | Built in, no AI: inflates the outline. For testing the connection | none | none |

All of them want an NVIDIA graphics card. Install the ones you want by following their own instructions:
[Stable Fast 3D](https://github.com/Stability-AI/stable-fast-3d),
[TripoSR](https://github.com/VAST-AI-Research/TripoSR) or
[Hunyuan3D-2](https://github.com/Tencent/Hunyuan3D-2) (install its `hy3dgen` package into this server's own
environment, `.venv`).

Then copy `engines.example.toml` to `engines.toml` (next to `server.py`) and point each command at your install.
`{input}` and `{output}` are filled in for each photo. Quote paths with spaces, and if the tool must run from its
own folder give it as `cwd`:

```toml
[triposr]
command = '"C:/tools/TripoSR/.venv/Scripts/python.exe" run.py {input} --output-dir {output} --model-save-format glb --bake-texture'
cwd = "C:/tools/TripoSR"
```

Restart the server. Engines that are set up appear in the app; tap one and **Make full 3D model**.
The console shows each job as it starts and finishes.

## If it doesn't connect

- The phone and the computer must be on the same Wi-Fi (not the phone's mobile data, and not a guest network).
- Several addresses are shown when the computer has more than one network (Wi-Fi, VPN, virtual adapters): use the one that
  starts like your phone's Wi-Fi address, usually `192.168…`.
- Allow the server through the firewall: on Windows, *Windows Security → Firewall → Allow an app* and tick Python
  for **Private** networks (or run `start.bat` again and choose Allow when asked).
- `http://<address>:8765/v1/health` opened in the phone's browser should show the server's engines as text.

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
