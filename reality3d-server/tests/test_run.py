import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import run  # noqa: E402
from engines import CommandEngine, split_command  # noqa: E402


def test_the_addresses_a_phone_could_use():
    found = run.usable_addresses(["127.0.0.1", "169.254.3.4", "10.0.0.7", "8.8.8.8", "192.168.1.20", "172.20.1.1", "172.40.1.1", "100.101.102.103", "::1", "nonsense", "192.168.1.20"])
    # Home networks first, then other private ranges, then VPNs; no duplicates, nothing public or loopback.
    assert found == ["192.168.1.20", "10.0.0.7", "172.20.1.1", "100.101.102.103"]
    assert run.usable_addresses([]) == []


def test_this_computer_reports_only_addresses_others_could_use():
    for address in run.local_addresses():
        assert not address.startswith(("127.", "169.254."))


def test_the_banner_shows_what_to_type_and_what_to_allow():
    text = run.banner(["192.168.1.20"], 8765, needs_code=False)
    assert "192.168.1.20:8765" in text
    assert "same Wi-Fi" in text and "firewall" in text and "Allow" in text
    assert "No access code is set" in text
    assert "An access code is needed" in run.banner(["192.168.1.20"], 8765, needs_code=True)
    several = run.banner(["192.168.1.20", "10.0.0.7"], 9000, needs_code=False)
    assert "192.168.1.20:9000" in several and "10.0.0.7:9000" in several and "Several addresses" in several
    assert "ipconfig" in run.banner([], 8765, needs_code=False)
    assert "3D from many photos is ready" in run.banner(["192.168.1.20"], 8765, needs_code=False, photos="3D from many photos is ready (...)")
    assert "many photos" not in run.banner(["192.168.1.20"], 8765, needs_code=False).split("In the app")[1].split("type:")[1]


def test_the_banner_tells_whether_photos_can_be_turned_into_models():
    note = run.photo_builder_note()
    assert note.startswith("3D from many photos")


def test_windows_paths_keep_their_backslashes_and_spaces():
    template = r'"C:\Program Files\Python312\python.exe" C:\tools\sf3d\run.py {input} --output-dir "{output}" --n 3'
    assert split_command(template, windows=True) == [
        r"C:\Program Files\Python312\python.exe", r"C:\tools\sf3d\run.py", "{input}", "--output-dir", "{output}", "--n", "3",
    ]
    assert split_command("python '/opt/my tool/run.py' {input}", windows=False) == ["python", "/opt/my tool/run.py", "{input}"]


def test_a_tool_is_given_paths_with_spaces_as_single_arguments(tmp_path):
    folder = tmp_path / "Ana Maria's files"
    folder.mkdir()
    script = tmp_path / "show.py"
    script.write_text(
        "import pathlib, sys\n"
        "out = pathlib.Path(sys.argv[2])\n"
        "out.mkdir(exist_ok=True)\n"
        "(out / 'model.glb').write_bytes(b'glTF' + pathlib.Path(sys.argv[1]).name.encode())\n"
    )
    engine = CommandEngine("spaces", "Spaces", "", f'"{sys.executable}" "{script}" {{input}} {{output}}', cwd=str(folder))
    image = folder / "input.png"
    image.write_bytes(b"png")
    result = engine.run(image, folder, {}, lambda fraction, message: None)
    assert result.read_bytes() == b"glTFinput.png"
    assert os.getcwd() != str(folder)
