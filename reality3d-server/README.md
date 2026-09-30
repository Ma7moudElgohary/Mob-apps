# Reality3D server

This small server lets the Reality3D app hand the heavy work to **your own computer**, over your Wi-Fi. Nothing
goes to the internet. It does two things:

- **3D from many photos** (photogrammetry): the app sends photos taken from all around an object, and the computer
  works out where each was taken and builds a detailed, textured 3D model, in real size when the photos come from
  a Reality3D scan. This is what RealityScan, KIRI Engine and Polycam do, and it is the most accurate way. No graphics
  card is needed; it takes minutes. See [3D from many photos](#3d-from-many-photos).
- **AI Full 3D from one photo**: an image-to-3D AI on the computer's graphics card guesses the back of an object
  from a single cut-out photo. See [The AIs](#the-ais).

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
3. In the app, tap **Connect to your computer** in **3D from many photos** (or, with a photo open, in **AI Full 3D on
   your computer**), type the address and tap **Save and connect**. It says *Connected to …*.
4. Leave the window open while you use it. Press Ctrl+C (or close the window) to stop.

Out of the box you get **3D from many photos** and **Quick preview (CPU)**, which inflates a photo's outline into a
rounded shape, with no AI, just to check that the phone and the computer are talking. For AI models from a single
photo, install one of the AIs below.

To ask the phone for an access code, set `R3D_TOKEN` before starting (`set R3D_TOKEN=pick-a-code` on Windows,
`export R3D_TOKEN=pick-a-code` elsewhere). `R3D_PORT` changes the port (8765).

## 3D from many photos

**In the app:** *Scan* a 360° walk around the object, then tap **Build on my computer · best quality** (the photos and
where ARCore knew the camera was go to the computer). Or take 40 to 80 photos with the normal camera app and pick
them in **3D from many photos** on the home screen. The model comes back to *My models*.

**Taking the photos:** go around the object two or three times, at different heights, about every 10° (every photo
should share most of its view with the next), in even light, without zooming or moving the object, on a surface with
some texture. Shiny, see-through and plain white or black surfaces are hard for any photogrammetry.

**What runs on the computer:** [COLMAP](https://colmap.github.io/) (through the `pycolmap` package, BSD license) finds where every photo
was taken; [OpenMVS](https://github.com/cdcseacave/openMVS) (AGPL-3.0, run as a separate program) builds the dense points, the mesh
and the texture. Both are free.
`start.bat` / `start.sh` install `pycolmap` (from `requirements-photos.txt`; the server still starts if that fails).
The first photo job downloads OpenMVS for your computer (22 MB on Windows), checks it against a known checksum and keeps
it in `.reality3d` in your home folder. It runs on the processor; more cores are faster.

| Setting | Photos are worked at | Mesh | Roughly, for 40 photos |
|---|---|---|---|
| Fast | 1000 px | 100 000 triangles | a few minutes |
| Standard | 1600 px | 250 000 triangles | 5 to 20 minutes |
| High | 2400 px | 500 000 triangles | 15 to 60 minutes |

(Times depend a lot on the computer: they are what a 4-core test machine took, scaled up.)

With **One object** the model is cut down to the object in the middle and the table it stands on is removed; with
**Whole scene** everything the photos show is kept. A scan's photos give the model its real size and put it upright. Photos
from the gallery give an upright model of unknown size: open it in *My models* and set a real length.

Options through the environment: `R3D_OPENMVS` (a folder with OpenMVS's programs, to use your own build, for example
the CUDA one), `R3D_HOME` (where the download goes), `R3D_NO_DOWNLOAD=1` (never download), `R3D_PHOTO_TIMEOUT` (seconds
before a job is stopped, default 3 hours) and `R3D_MAX_UPLOAD_MB` (default 2048).

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

- `GET /v1/health` → the server's engines (`kind` is `image` for the AIs and `photos` for the photo builder; `why` says
  what to do when one isn't available) and whether an access code is needed
- `POST /v1/jobs` (multipart: `image` PNG with a transparent background, `engine`, `texture_size`, `faces`) → `{"id"}`
- `POST /v1/photo-jobs?quality=fast|standard|high&mode=object|scene` (the body is a zip of photos, and for a scan
  `cameras.json`) → `{"id"}`
- `GET /v1/jobs/{id}` → `status` (`queued`, `running`, `done`, `failed`, `cancelled`), `progress`, `message`
- `DELETE /v1/jobs/{id}` → stops the job (the photo builder stops at once)
- `GET /v1/jobs/{id}/result` → the model as `model/gltf-binary` (photo models say in the file whether they are in real
  meters: `asset.extras.reality3d.scaleKnown`)

Jobs run one at a time, since these models fill the GPU (or the processor). With `R3D_TOKEN` set, every call except
`/v1/health` needs `Authorization: Bearer <code>`.

## Tests

```
pip install pytest httpx pycolmap
python -m pytest tests
```
`R3D_E2E=1 python -m pytest tests/test_photogrammetry_e2e.py` also runs the whole photos → 3D pipeline on made-up photos
of a known object and checks the size, the upright pose and that the table is gone (a few minutes; needs OpenMVS).
