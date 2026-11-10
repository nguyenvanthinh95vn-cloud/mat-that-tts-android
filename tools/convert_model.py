#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Doi giong doc cho app Mat That TTS (Android).

Chuyen 1 model Piper (.onnx + .onnx.json) thanh bo file ma app can:
  - app/src/main/assets/piper/ngochuyen.onnx  (them metadata sherpa-onnx)
  - app/src/main/assets/piper/tokens.txt      (sinh tu phoneme_id_map)

Dung khi train xong model moi (vd: epoch 500 thay cho epoch 209):
    python3 tools/convert_model.py \
        --onnx ~/workspace/voice-clone-test/ngochuyen-onnx/ngochuyen-epoch500.onnx \
        --config ~/workspace/voice-clone-test/ngochuyen-onnx/ngochuyen-epoch500.onnx.json

Can cai: pip install onnx
Ghi chu: espeak-ng-data KHONG can doi (dung chung cho moi giong Piper).
"""
import argparse
import json
import os
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", required=True, help="Duong dan file model .onnx")
    ap.add_argument("--config", required=True, help="Duong dan file .onnx.json cua model")
    ap.add_argument(
        "--out-dir",
        default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "..", "app", "src", "main", "assets", "piper"),
        help="Thu muc assets/piper cua app",
    )
    args = ap.parse_args()

    import onnx

    with open(args.config, encoding="utf-8") as f:
        cfg = json.load(f)

    os.makedirs(args.out_dir, exist_ok=True)

    # 1) tokens.txt tu phoneme_id_map (theo doc sherpa-onnx, muc Piper)
    id_map = cfg["phoneme_id_map"]
    tokens_path = os.path.join(args.out_dir, "tokens.txt")
    with open(tokens_path, "w", encoding="utf-8") as f:
        for s, i in sorted(id_map.items(), key=lambda kv: kv[1][0]):
            f.write(f"{s} {i[0]}\n")
    print(f"tokens.txt: {len(id_map)} dong -> {tokens_path}")

    # 2) Them metadata sherpa-onnx vao ban copy cua model
    model = onnx.load(args.onnx)
    meta = {
        "model_type": "vits",
        "comment": "piper",
        "language": cfg.get("language", {}).get("name_english", "Vietnamese"),
        "voice": cfg.get("espeak", {}).get("voice", "vi"),
        "has_espeak": 1,
        "n_speakers": cfg.get("num_speakers", 1),
        "sample_rate": cfg.get("audio", {}).get("sample_rate", 22050),
    }
    # Xoa metadata cu (neu co) roi them moi
    del model.metadata_props[:]
    for k, v in meta.items():
        m = model.metadata_props.add()
        m.key = k
        m.value = str(v)
    out_path = os.path.join(args.out_dir, "ngochuyen.onnx")
    onnx.save(model, out_path)
    print(f"model: {out_path} ({os.path.getsize(out_path)} bytes)")
    print("Xong. Giu nguyen ten file ngochuyen.onnx de app khong can sua code.")


if __name__ == "__main__":
    main()
