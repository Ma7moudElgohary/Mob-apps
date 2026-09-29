import hashlib
import io
import os
import stat
import sys
import threading
import time
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import mvs_tools  # noqa: E402


def fake_package(layout: str = "", exit_code: int = 0, banner: bool = True) -> bytes:
    """A zip with the four programs OpenMVS needs, each a tiny script, the way the release zips hold them. Like the
    real ones they print a banner and exit with 1 when asked for help."""
    say = "echo OpenMVS x64 v0\n" if banner else ""
    script = f"#!/bin/sh\n{say}exit {exit_code}\n" if os.name != "nt" else f"@echo OpenMVS x64 v0\r\n@exit {exit_code}\r\n" if banner else f"@exit {exit_code}\r\n"
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        for name in (*mvs_tools.REQUIRED, "RefineMesh"):
            zf.writestr(f"{layout}{mvs_tools.exe(name)}", script)
        zf.writestr(f"{layout}readme.txt", "hello")
    return buffer.getvalue()


def table(payload: bytes, key: str = "linux-x86_64") -> dict:
    return {key: ("OpenMVS_test.zip", hashlib.sha256(payload).hexdigest(), len(payload))}


def serving(payload: bytes):
    def fetch(url, target, progress, label):
        assert url.endswith("OpenMVS_test.zip")
        progress(0.5, label)
        Path(target).write_bytes(payload)
        progress(1.0, label)

    return fetch


@pytest.fixture(autouse=True)
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("R3D_HOME", str(tmp_path / "home"))
    monkeypatch.delenv("R3D_OPENMVS", raising=False)
    monkeypatch.delenv("R3D_NO_DOWNLOAD", raising=False)
    return tmp_path / "home"


def test_the_computers_that_have_a_ready_made_build():
    assert mvs_tools.platform_key("Windows", "AMD64") == "windows-x86_64"
    assert mvs_tools.platform_key("Linux", "x86_64") == "linux-x86_64"
    assert mvs_tools.platform_key("Darwin", "arm64") == "macos-arm64"
    assert mvs_tools.platform_key("Linux", "aarch64") is None
    assert mvs_tools.platform_key("Windows", "ARM64") is None
    for name, checksum, size in mvs_tools.PACKAGES.values():
        assert name.startswith("OpenMVS_") and len(checksum) == 64 and size > 1_000_000


def test_the_tools_are_found_where_the_user_pointed(tmp_path, monkeypatch):
    assert mvs_tools.find_tools() is None or mvs_tools.find_tools().is_dir()
    folder = tmp_path / "mine"
    folder.mkdir()
    for name in mvs_tools.REQUIRED[:-1]:
        (folder / mvs_tools.exe(name)).write_text("x")
    monkeypatch.setenv("R3D_OPENMVS", str(folder))
    assert not mvs_tools.has_tools(folder)  # one program is missing
    (folder / mvs_tools.exe(mvs_tools.REQUIRED[-1])).write_text("x")
    assert mvs_tools.has_tools(folder)
    assert mvs_tools.find_tools() == folder


def test_downloading_unpacks_checks_and_installs(home):
    payload = fake_package(layout="vc17/x64/Release/", exit_code=1)  # the real programs exit with 1 after --help
    steps = []
    folder = mvs_tools.install(lambda f, m: steps.append(m), key="linux-x86_64", fetch=serving(payload), packages=table(payload))
    assert folder == home / f"openmvs-{mvs_tools.OPENMVS_VERSION}"
    assert mvs_tools.has_tools(folder)
    assert (folder / mvs_tools.exe("DensifyPointCloud")).stat().st_mode & stat.S_IXUSR
    assert any("Downloading" in m for m in steps) and any("Unpacking" in m for m in steps)
    assert mvs_tools.find_tools() == folder
    # Installing again replaces the old copy.
    assert mvs_tools.install(lambda f, m: None, key="linux-x86_64", fetch=serving(payload), packages=table(payload)) == folder


def test_a_download_that_isnt_the_official_one_is_thrown_away(home):
    payload = fake_package()
    wrong = {"linux-x86_64": ("OpenMVS_test.zip", "0" * 64, len(payload))}
    with pytest.raises(mvs_tools.ToolError, match="checksum"):
        mvs_tools.install(lambda f, m: None, key="linux-x86_64", fetch=serving(payload), packages=wrong)
    assert not (home / f"openmvs-{mvs_tools.OPENMVS_VERSION}").exists()


def test_a_download_with_unsafe_names_is_refused(home):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        zf.writestr("../../evil.txt", "no")
        zf.writestr(mvs_tools.exe("DensifyPointCloud"), "x")
    payload = buffer.getvalue()
    with pytest.raises(mvs_tools.ToolError, match="unsafe"):
        mvs_tools.install(lambda f, m: None, key="linux-x86_64", fetch=serving(payload), packages=table(payload))


def test_problems_are_explained(home, monkeypatch):
    with pytest.raises(mvs_tools.ToolError, match="no ready-made"):
        mvs_tools.install(lambda f, m: None, key="plan9-mips")
    payload = fake_package()

    def offline(url, target, progress, label):
        raise OSError("no route to host")

    with pytest.raises(mvs_tools.ToolError, match="online"):
        mvs_tools.install(lambda f, m: None, key="linux-x86_64", fetch=offline, packages=table(payload))
    empty = io.BytesIO()
    with zipfile.ZipFile(empty, "w") as zf:
        zf.writestr("readme.txt", "nothing here")
    with pytest.raises(mvs_tools.ToolError, match="doesn't contain"):
        mvs_tools.install(lambda f, m: None, key="linux-x86_64", fetch=serving(empty.getvalue()), packages=table(empty.getvalue()))
    monkeypatch.setenv("R3D_NO_DOWNLOAD", "1")
    assert not mvs_tools.can_download()
    with pytest.raises(mvs_tools.ToolError, match="R3D_NO_DOWNLOAD"):
        mvs_tools.install(lambda f, m: None, key="linux-x86_64", fetch=serving(payload), packages=table(payload))


@pytest.mark.skipif(os.name == "nt", reason="uses a shell script")
def test_a_program_that_wont_start_is_reported(home):
    payload = fake_package(exit_code=3, banner=False)
    with pytest.raises(mvs_tools.ToolError, match="exit code 3"):
        mvs_tools.install(lambda f, m: None, key="linux-x86_64", fetch=serving(payload), packages=table(payload))


def python(code: str) -> list:
    return [sys.executable, "-c", code]


def test_progress_that_uses_carriage_returns_is_read_as_it_comes(tmp_path):
    lines = []
    code, tail = mvs_tools.run(
        python("import sys,time\nfor i in range(3):\n    sys.stdout.write('step %d\\r' % i); sys.stdout.flush(); time.sleep(0.05)\nprint('all done')"),
        tmp_path, lines.append,
    )
    assert code == 0 and lines == ["step 0", "step 1", "step 2", "all done"] and tail[-1] == "all done"


def test_only_the_last_lines_are_kept_and_the_exit_code_is_returned(tmp_path):
    code, tail = mvs_tools.run(python("print('\\n'.join(str(i) for i in range(100))); raise SystemExit(4)"), tmp_path)
    assert code == 4 and tail[-1] == "99" and len(tail) == 40


def test_a_running_program_can_be_cancelled(tmp_path):
    cancel = threading.Event()
    threading.Timer(0.5, cancel.set).start()
    started = time.monotonic()
    with pytest.raises(mvs_tools.ToolError, match="Cancelled"):
        mvs_tools.run(python("import time; time.sleep(60)"), tmp_path, cancel=cancel)
    assert time.monotonic() - started < 10


def test_a_program_that_takes_too_long_is_stopped(tmp_path):
    started = time.monotonic()
    with pytest.raises(mvs_tools.ToolError, match="took too long"):
        mvs_tools.run(python("import time; time.sleep(60)"), tmp_path, timeout=0.6)
    assert time.monotonic() - started < 10
