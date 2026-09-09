"""
SIGHTLINE — Alternative Model Export
Exports YOLOv8n to TFLite via ONNX → SavedModel → TFLite path.
Falls back to different conversion strategies.
"""

from ultralytics import YOLO
import os

def export_via_onnx():
    """Export YOLOv8n as ONNX, then convert to TFLite manually."""
    print("Loading YOLOv8n...")
    model = YOLO("yolov8n.pt")

    print("Step 1: Exporting to ONNX...")
    model.export(format="onnx", imgsz=320, simplify=True)
    print("✅ ONNX exported: yolov8n.onnx")

    print("Step 2: Converting ONNX to TFLite via onnx2tf...")
    try:
        import onnx2tf
        onnx2tf.convert(
            input_onnx_file_path="yolov8n.onnx",
            output_folder_path="yolov8n_savedmodel",
            non_verbose=True,
            output_signaturedefs=True,
            disable_group_convolution=True,
        )
        print("✅ SavedModel exported")
    except Exception as e:
        print(f"⚠️ onnx2tf failed: {e}")
        print("Trying alternative: onnx2tf with different settings...")
        try:
            import onnx2tf
            onnx2tf.convert(
                input_onnx_file_path="yolov8n.onnx",
                output_folder_path="yolov8n_savedmodel",
                non_verbose=True,
                output_signaturedefs=True,
                disable_group_convolution=True,
                copy_onnx_input_output_names_to_tflite=True,
            )
        except Exception as e2:
            print(f"❌ onnx2tf alternative also failed: {e2}")
            return False

    print("Step 3: Converting SavedModel to TFLite...")
    try:
        import tensorflow as tf

        converter = tf.lite.TFLiteConverter.from_saved_model("yolov8n_savedmodel")
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        tflite_model = converter.convert()

        with open("yolov8n.tflite", "wb") as f:
            f.write(tflite_model)
        print(f"✅ TFLite exported: yolov8n.tflite ({len(tflite_model) / 1024 / 1024:.1f} MB)")
        return True
    except Exception as e:
        print(f"❌ TFLite conversion failed: {e}")
        return False

if __name__ == "__main__":
    print("=" * 60)
    print("SIGHTLINE Model Export (Alternative Path)")
    print("=" * 60)

    if not os.path.exists("yolov8n.pt"):
        print("Downloading YOLOv8n weights...")
        YOLO("yolov8n.pt")

    success = export_via_onnx()

    if success:
        print("\n" + "=" * 60)
        print("Export complete!")
        print("Copy yolov8n.tflite to sightline-app/app/src/main/assets/")
        print("=" * 60)
    else:
        print("\n❌ Export failed. Try installing: pip install onnx2tf tensorflow")
