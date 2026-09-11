"""
SIGHTLINE — Indian Currency Model Export

Downloads yolov8n_currency_best.pt (Rathnavelu/indian-currency-cnn-yolo on
HuggingFace — 7 INR denominations: 10/20/50/100/200/500/2000, YOLOv8-nano,
mobile-optimized) and exports it to float32 TFLite at imgsz=320 for on-device
inference in MONEY mode.

Usage:
    python export_currency.py

Output:
    yolov8n_currency_best_float32.tflite  (copy to app/src/main/assets/currency.tflite)
"""

import os
import sys
import urllib.request

MODEL_URL = "https://huggingface.co/Rathnavelu/indian-currency-cnn-yolo/resolve/main/yolov8n_currency_best.pt"
PT = "yolov8n_currency_best.pt"


def main() -> int:
    if not os.path.exists(PT):
        print("Downloading currency model from HuggingFace...", flush=True)
        try:
            urllib.request.urlretrieve(MODEL_URL, PT)
        except Exception as e:
            print(f"DOWNLOAD FAILED: {e}", flush=True)
            return 1
        print(f"Downloaded: {os.path.getsize(PT)} bytes", flush=True)

    from ultralytics import YOLO

    model = YOLO(PT)
    print("Model names:", model.names, flush=True)
    print("Exporting to TFLite float32 imgsz=320...", flush=True)
    model.export(format="tflite", imgsz=320, half=False, int8=False)
    print("DONE", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())