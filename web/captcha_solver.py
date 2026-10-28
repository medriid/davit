"""
VTOP text-captcha solver.

A single-layer softmax classifier in numpy. The weights live in
model/captcha_model.json, which isn't in the repository (see
docs/05-captcha-solver.md).

Pipeline:
  1. The captcha JPEG is drawn onto a 200x40 surface (VTOP serves it at 200x40,
     so no resampling is needed in practice).
  2. Per pixel, compute HSV *saturation* scaled to 0..255. This drops the grey
     speckle noise and keeps the coloured glyphs.
  3. Slice out the 6 fixed character cells (each ~24x22 px).
  4. Binarize each cell against its own mean, flatten row-major, then
     logits = bits . W + b  ->  softmax  ->  argmax over the 31-char alphabet.
"""
import base64
import io
import json
import os

import numpy as np
from PIL import Image

_MODEL_PATH = os.path.join(os.path.dirname(__file__), "model", "captcha_model.json")

with open(_MODEL_PATH) as _f:
    _M = json.load(_f)

_W = np.asarray(_M["weights"], dtype=np.float64)   # (528, 32)
_B = np.asarray(_M["biases"], dtype=np.float64)    # (32,)
_ALPHA = _M["alphabet"]                             # 31 chars; argmax may hit idx 31 (unused)

# Per-character cell geometry, matching the original saturate() slicing.
_CELL_ROWS = [(8, 30) if i % 2 == 0 else (13, 35) for i in range(6)]
_CELL_COLS = [((i + 1) * 25 + 2, (i + 2) * 25 + 1) for i in range(6)]


def _to_200x40_rgb(img: Image.Image) -> np.ndarray:
    img = img.convert("RGB")
    if img.size != (200, 40):
        img = img.resize((200, 40), Image.BILINEAR)
    return np.asarray(img, dtype=np.int32)  # (40, 200, 3)


def _saturation(px: np.ndarray) -> np.ndarray:
    mx = px.max(axis=2)
    mn = px.min(axis=2)
    # mx==0 -> 0, else round((mx-mn)*255/mx)
    return np.where(mx == 0, 0, np.round((mx - mn) * 255 / np.maximum(mx, 1))).astype(np.int32)


def solve_image(img: Image.Image) -> str:
    """Return the 6-char captcha string for a PIL image (may contain '?' if a
    cell classifies to the unused 32nd output)."""
    sat = _saturation(_to_200x40_rgb(img)).astype(np.float64)  # (40, 200)
    out = []
    for (r0, r1), (c0, c1) in zip(_CELL_ROWS, _CELL_COLS):
        block = sat[r0:r1, c0:c1]
        bits = (block > block.mean()).astype(np.float64).flatten()  # row-major
        logits = bits @ _W + _B
        e = np.exp(logits - logits.max())
        idx = int((e / e.sum()).argmax())
        out.append(_ALPHA[idx] if idx < len(_ALPHA) else "?")
    return "".join(out)


def solve_data_uri(data_uri: str) -> str:
    """Solve a `data:image/...;base64,....` captcha string from the login page."""
    b64 = data_uri.split(",", 1)[1]
    return solve_image(Image.open(io.BytesIO(base64.b64decode(b64))))


def solve_bytes(raw: bytes) -> str:
    return solve_image(Image.open(io.BytesIO(raw)))
