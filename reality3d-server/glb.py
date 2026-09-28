"""A minimal binary glTF (GLB) writer: one textured, indexed triangle mesh."""

import json
import struct

import numpy as np


def _pad(data: bytes, fill: bytes = b"\0") -> bytes:
    return data + fill * (-len(data) % 4)


def write_glb(positions: np.ndarray, normals: np.ndarray, uvs: np.ndarray, indices: np.ndarray, png: bytes) -> bytes:
    """positions/normals: (n, 3) float32, uvs: (n, 2) float32 with (0, 0) at the image's top-left, indices: (m,) uint32."""
    positions = np.ascontiguousarray(positions, dtype="<f4")
    normals = np.ascontiguousarray(normals, dtype="<f4")
    uvs = np.ascontiguousarray(uvs, dtype="<f4")
    indices = np.ascontiguousarray(indices, dtype="<u4")
    chunks = [positions.tobytes(), normals.tobytes(), uvs.tobytes(), indices.tobytes(), png]
    views, offset, binary = [], 0, b""
    for i, chunk in enumerate(chunks):
        view = {"buffer": 0, "byteOffset": offset, "byteLength": len(chunk)}
        if i < 3:
            view["target"] = 34962
        elif i == 3:
            view["target"] = 34963
        views.append(view)
        padded = _pad(chunk)
        binary += padded
        offset += len(padded)
    gltf = {
        "asset": {"version": "2.0", "generator": "Reality3D server"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "model"}],
        "meshes": [{"primitives": [{
            "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2},
            "indices": 3,
            "material": 0,
        }]}],
        "materials": [{"pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0.0, "roughnessFactor": 0.9}, "doubleSided": True}],
        "textures": [{"source": 0, "sampler": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987}],
        "images": [{"bufferView": 4, "mimeType": "image/png"}],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(positions), "type": "VEC3",
             "min": positions.min(axis=0).tolist(), "max": positions.max(axis=0).tolist()},
            {"bufferView": 1, "componentType": 5126, "count": len(normals), "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": len(uvs), "type": "VEC2"},
            {"bufferView": 3, "componentType": 5125, "count": len(indices), "type": "SCALAR"},
        ],
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
    }
    json_chunk = _pad(json.dumps(gltf, separators=(",", ":")).encode(), b" ")
    total = 12 + 8 + len(json_chunk) + 8 + len(binary)
    return (
        struct.pack("<4sII", b"glTF", 2, total)
        + struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
        + struct.pack("<I4s", len(binary), b"BIN\0") + binary
    )
