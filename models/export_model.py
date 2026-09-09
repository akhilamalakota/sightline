"""
SIGHTLINE — Model Export Script

Exports YOLOv8n (nano) to TensorFlow Lite format for on-device inference.

Usage:
    pip install ultralytics
    python export_model.py

This produces:
    - yolov8n.tflite  (standard float32 — recommended first pass)
    - yolov8n_int8.tflite  (int8 quantized — smaller, faster, use if GPU delegate supports it)

After export, copy the .tflite file to:
    sightline-app/app/src/main/assets/yolov8n.tflite
"""

from ultralytics import YOLO
import os

def export_float32():
    """Export YOLOv8n as float32 TFLite — safest first pass."""
    print("Loading YOLOv8n...")
    model = YOLO("yolov8n.pt")

    print("Exporting to TFLite (float32)...")
    model.export(
        format="tflite",
        imgsz=320,          # Match the input size in ObjectDetector.kt
        half=False,          # Float32 for maximum compatibility
        int8=False,
    )
    print("✅ Exported: yolov8n_float32.tflite")

def export_int8():
    """Export YOLOv8n as int8 quantized TFLite — faster, smaller."""
    print("Loading YOLOv8n...")
    model = YOLO("yolov8n.pt")

    print("Exporting to TFLite (int8 quantized)...")
    model.export(
        format="tflite",
        imgsz=320,
        half=False,
        int8=True,           # INT8 quantization for speed
    )
    print("✅ Exported: yolov8n_int8.tflite")

if __name__ == "__main__":
    print("=" * 60)
    print("SIGHTLINE Model Export")
    print("=" * 60)

    # Check if model exists
    if not os.path.exists("yolov8n.pt"):
        print("Downloading YOLOv8n weights...")
        YOLO("yolov8n.pt")  # Downloads automatically

    export_float32()
    export_int8()

    print("\n" + "=" * 60)
    print("Export complete!")
    print("\nNext steps:")
    print("  1. Copy the .tflite file to sightline-app/app/src/main/assets/")
    print("  2. Update ObjectDetector.kt inputSize if you changed imgsz")
    print("  3. Expected input:  [1, 320, 320, 3]  float32")
    print("  4. Expected output: [1, 85, 8400]     (5 + 80 COCO classes)")
    print("=" * 60)
