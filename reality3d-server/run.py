"""Starts the Reality3D server and shows the address to type into the phone app.

    python run.py            (or double-click start.bat on Windows, run ./start.sh on macOS and Linux)

R3D_PORT changes the port (8765) and R3D_TOKEN asks the phone for an access code.
"""

import ipaddress
import os
import socket

DEFAULT_PORT = 8765


def usable_addresses(candidates) -> list:
    """The addresses a phone could reach this computer on: home and office networks (192.168.x.x first), then
    other private ranges and VPNs like Tailscale (100.x.x.x). Loopback and link-local addresses are left out."""
    vpn = ipaddress.ip_network("100.64.0.0/10")
    found = []
    for text in candidates:
        try:
            ip = ipaddress.ip_address(text)
        except ValueError:
            continue
        if ip.version != 4 or ip.is_loopback or ip.is_link_local:
            continue
        if (ip.is_private or ip in vpn) and str(ip) not in found:
            found.append(str(ip))
    return sorted(found, key=lambda a: (not a.startswith("192.168."), a.startswith("100."), a))


def local_addresses() -> list:
    """This computer's addresses on its networks, best first."""
    candidates = []
    try:
        # Asks the system which network a packet to elsewhere would leave by. Nothing is sent.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            candidates.append(probe.getsockname()[0])
    except OSError:
        pass
    try:
        candidates += socket.gethostbyname_ex(socket.gethostname())[2]
    except OSError:
        pass
    return usable_addresses(candidates)


def banner(addresses, port: int, needs_code: bool) -> str:
    lines = ["", "Reality3D server is running.", "", "In the app: open a photo, then", "  AI Full 3D on your computer > Connect to your computer, and type:", ""]
    if addresses:
        lines += [f"      {address}:{port}" for address in addresses]
        if len(addresses) > 1:
            lines += ["", "  (Several addresses: use the one that starts like your phone's Wi-Fi, usually 192.168...)"]
    else:
        lines += [f"      <this computer's address>:{port}", "", "  (Couldn't find it: run ipconfig, and use the IPv4 address of your Wi-Fi.)"]
    lines += ["", "The phone and this computer must be on the same Wi-Fi."]
    lines += ["If Windows asks about the firewall, choose Allow (Private networks)."]
    lines += ["An access code is needed in the app." if needs_code else "No access code is set (set R3D_TOKEN to ask for one).", "", "Press Ctrl+C to stop.", ""]
    return "\n".join(lines)


def main():
    import uvicorn

    from server import app

    port = int(os.environ.get("R3D_PORT", DEFAULT_PORT))
    print(banner(local_addresses(), port, bool(os.environ.get("R3D_TOKEN"))), flush=True)
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="warning")


if __name__ == "__main__":
    main()
