"""Finds, downloads and runs OpenMVS, the free program that turns photos with known camera positions into a
textured 3D mesh (https://github.com/cdcseacave/openMVS, AGPL-3.0). The server downloads the official build for
this computer once, checks it against a known checksum and keeps it in the user's Reality3D folder."""

import hashlib
import os
import platform
import shutil
import stat
import subprocess
import tempfile
import threading
import time
import urllib.request
import zipfile
from collections import deque
from pathlib import Path
from typing import Callable, Optional

OPENMVS_VERSION = "2.4.0"
RELEASE_URL = f"https://github.com/cdcseacave/openMVS/releases/download/v{OPENMVS_VERSION}/"

# The official builds of the release above: (file, sha256, size in bytes).
PACKAGES = {
    "windows-x86_64": ("OpenMVS_Windows_x64.zip", "0c31660c15c9ebc4c106873cf67564d9570d404aef7a6403451da1b6178b2167", 22363227),
    "linux-x86_64": ("OpenMVS_Ubuntu_x64.zip", "7104ae1ddd6ca38fbca9e0e4a70b20af59e21e0b497eb7181c864fbf38ca8d00", 165398013),
    "macos-arm64": ("OpenMVS_macOS_arm64.zip", "3d4c616c97031b1ab6e2eecb0ddd5614fb99513c0782a32c9350602faf38799b", 68347008),
}

REQUIRED = ("InterfaceCOLMAP", "DensifyPointCloud", "ReconstructMesh", "TextureMesh")

WINDOWS = os.name == "nt"
# What Windows reports when a program can't find a DLL it needs (here: the Visual C++ runtime).
MISSING_DLL = 0xC0000135

Progress = Callable[[Optional[float], str], None]


class ToolError(Exception):
    """A problem to show to the user as it is."""


def platform_key(system: Optional[str] = None, machine: Optional[str] = None) -> Optional[str]:
    system = (system or platform.system()).lower()
    machine = (machine or platform.machine()).lower()
    arch = "arm64" if machine in ("arm64", "aarch64") else "x86_64" if machine in ("x86_64", "amd64") else machine
    key = {"windows": "windows", "linux": "linux", "darwin": "macos"}.get(system, system) + "-" + arch
    return key if key in PACKAGES else None


def home_folder() -> Path:
    return Path(os.environ.get("R3D_HOME") or Path.home() / ".reality3d")


def install_folder() -> Path:
    return home_folder() / f"openmvs-{OPENMVS_VERSION}"


def exe(name: str) -> str:
    return name + (".exe" if WINDOWS else "")


def has_tools(folder: Path) -> bool:
    return folder.is_dir() and all((folder / exe(name)).is_file() for name in REQUIRED)


def find_tools(extra: Optional[list] = None) -> Optional[Path]:
    """The folder holding OpenMVS: R3D_OPENMVS, a `tools/openmvs` folder next to the server, the downloaded copy,
    or wherever the programs are on the PATH."""
    candidates = [Path(p) for p in extra or []]
    if os.environ.get("R3D_OPENMVS"):
        candidates.append(Path(os.environ["R3D_OPENMVS"]))
    candidates += [Path(__file__).with_name("tools") / "openmvs", install_folder()]
    for folder in candidates:
        if has_tools(folder):
            return folder
    found = shutil.which(exe(REQUIRED[1]))
    if found and has_tools(Path(found).parent):
        return Path(found).parent
    return None


def can_download() -> bool:
    return platform_key() is not None and not os.environ.get("R3D_NO_DOWNLOAD")


def download(url: str, target: Path, progress: Progress, label: str) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "Reality3D-server"})
    with urllib.request.urlopen(request, timeout=60) as response, open(target, "wb") as out:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        while chunk := response.read(1 << 20):
            out.write(chunk)
            done += len(chunk)
            progress(done / total if total else None, f"{label} ({done // (1 << 20)} of {max(total // (1 << 20), 1)} MB)")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def install(progress: Progress, key: Optional[str] = None, fetch: Callable = download, base_url: str = RELEASE_URL, packages: Optional[dict] = None) -> Path:
    """Downloads OpenMVS for this computer into the Reality3D folder and returns where it is."""
    packages = packages or PACKAGES
    key = key or platform_key()
    if key is None or key not in packages:
        raise ToolError(
            f"There is no ready-made OpenMVS for {platform.system()} {platform.machine()}. Build it from "
            "https://github.com/cdcseacave/openMVS and set R3D_OPENMVS to the folder with DensifyPointCloud in it."
        )
    if os.environ.get("R3D_NO_DOWNLOAD"):
        raise ToolError("OpenMVS isn't installed and R3D_NO_DOWNLOAD is set. Install it and point R3D_OPENMVS at it.")
    name, expected, size = packages[key]
    target = install_folder()
    scratch = Path(tempfile.mkdtemp(prefix="reality3d_openmvs_"))
    try:
        archive = scratch / name
        try:
            fetch(base_url + name, archive, progress, f"Downloading the 3D builder (OpenMVS {OPENMVS_VERSION})")
        except Exception as e:
            raise ToolError(f"Couldn't download OpenMVS ({e}). Is this computer online? You can also download {name} from {RELEASE_URL} yourself.") from e
        if sha256(archive) != expected:
            raise ToolError("The OpenMVS download doesn't match its known checksum, so it was thrown away. Try again.")
        progress(None, "Unpacking the 3D builder")
        unpacked = scratch / "unpacked"
        with zipfile.ZipFile(archive) as zf:
            root = unpacked.resolve()
            for member in zf.infolist():
                destination = (unpacked / member.filename).resolve()
                if root != destination and root not in destination.parents:
                    raise ToolError("The OpenMVS download has an unsafe file name, so it was thrown away.")
            zf.extractall(unpacked)
        source = next((p.parent for p in unpacked.rglob(exe(REQUIRED[1]))), None)
        if source is None:
            raise ToolError("The OpenMVS download doesn't contain the programs it should.")
        if target.exists():
            shutil.rmtree(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(source, target)
        for program in target.iterdir():
            if program.is_file() and program.suffix.lower() in ("", ".exe"):
                program.chmod(program.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        check(target)
        return target
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


def check(folder: Path) -> None:
    """Runs one program to see that it starts; explains the usual Windows problem. (OpenMVS's --help prints its
    banner and exits with 1, so a good start is judged by the banner, not by the exit code.)"""
    try:
        # OpenMVS writes a log file wherever it runs: not in the server's folder.
        with tempfile.TemporaryDirectory(prefix="reality3d_check_") as elsewhere:
            done = subprocess.run([str(folder / exe(REQUIRED[1])), "--help"], capture_output=True, timeout=60, cwd=elsewhere, **hidden_window())
    except (OSError, subprocess.SubprocessError) as e:
        raise ToolError(f"OpenMVS won't start on this computer: {e}") from e
    if done.returncode == MISSING_DLL or done.returncode == MISSING_DLL - (1 << 32):
        raise ToolError(
            "OpenMVS needs the Microsoft Visual C++ Redistributable. Install it from https://aka.ms/vs/17/release/vc_redist.x64.exe "
            "and try again."
        )
    if b"OpenMVS" not in done.stdout + done.stderr:
        raise ToolError(f"OpenMVS won't start on this computer (exit code {done.returncode}).")


def hidden_window() -> dict:
    """subprocess options that keep a console window from flashing up on Windows."""
    return {"creationflags": subprocess.CREATE_NO_WINDOW} if WINDOWS else {}


def run(
    command: list,
    cwd: Path,
    on_line: Callable[[str], None] = lambda line: None,
    cancel: Optional[threading.Event] = None,
    timeout: Optional[float] = None,
    env: Optional[dict] = None,
) -> tuple[int, list]:
    """Runs a program, handing its output to [on_line] as it appears (progress uses carriage returns too).
    Returns its exit code and the last lines it printed. Raises ToolError when cancelled or out of time."""
    process = subprocess.Popen(
        [str(c) for c in command], cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0, env=env, **hidden_window()
    )
    tail: deque = deque(maxlen=40)

    def read():
        pending = b""
        stream = process.stdout
        while True:
            chunk = stream.read(4096)
            if not chunk:
                break
            pending += chunk
            while True:
                cut = min((i for i in (pending.find(b"\n"), pending.find(b"\r")) if i >= 0), default=-1)
                if cut < 0:
                    break
                line, pending = pending[:cut].decode("utf-8", "replace").strip(), pending[cut + 1:]
                if line:
                    tail.append(line)
                    on_line(line)
        if pending.strip():
            tail.append(pending.decode("utf-8", "replace").strip())
            on_line(tail[-1])

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    deadline = None if timeout is None else time.monotonic() + timeout
    try:
        while reader.is_alive():
            reader.join(0.25)
            if cancel is not None and cancel.is_set():
                process.kill()
                reader.join(5)
                raise ToolError("Cancelled")
            if deadline is not None and time.monotonic() > deadline:
                process.kill()
                reader.join(5)
                raise ToolError(f"{Path(str(command[0])).stem} took too long and was stopped")
        return process.wait(), list(tail)
    finally:
        if process.poll() is None:
            process.kill()
