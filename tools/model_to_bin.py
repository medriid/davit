"""
Convert the web build's captcha model (JSON) into the binary the Android core bundles.

    py tools/model_to_bin.py web/model/captcha_model.json

Writes android/core/src/main/resources/dev/medriid/davit/vtop/captcha_model.bin:
the alphabet, a newline, then 528x32 weights and 32 biases as big-endian doubles.
"""
import json
import os
import struct
import sys

OUT = os.path.join(os.path.dirname(__file__), "..", "android", "core", "src", "main",
                   "resources", "dev", "medriid", "davit", "vtop", "captcha_model.bin")


def main(path: str) -> None:
    with open(path, encoding="utf-8") as f:
        model = json.load(f)
    weights, biases, alphabet = model["weights"], model["biases"], model["alphabet"]
    if len(weights) != 528 or any(len(row) != 32 for row in weights) or len(biases) != 32:
        sys.exit("expected 528x32 weights and 32 biases")
    with open(OUT, "wb") as f:
        f.write(alphabet.encode("ascii") + b"\n")
        for row in weights:
            f.write(struct.pack(">32d", *row))
        f.write(struct.pack(">32d", *biases))
    print("wrote", os.path.normpath(OUT))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
