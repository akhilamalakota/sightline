# Models Directory

## YOLOv8n → TFLite Export

Run `python export_model.py` to download and export the model.

### Expected Tensor Shapes

| | Shape | Dtype | Notes |
|---|---|---|---|
| **Input** | `[1, 320, 320, 3]` | float32 | Normalised 0-1, RGB |
| **Output** | `[1, 85, 8400]` | float32 | 5 (x,y,w,h,obj) + 80 (COCO classes) |

### Files After Export

- `yolov8n.tflite` → copy to `sightline-app/app/src/main/assets/`
- `yolov8n_int8.tflite` → optional, faster but may need GPU delegate support check

### COCO Classes (80)

person, bicycle, car, motorcycle, airplane, bus, train, truck, boat, traffic light,
fire hydrant, stop sign, parking meter, bench, bird, cat, dog, horse, sheep, cow,
elephant, bear, zebra, giraffe, backpack, umbrella, handbag, tie, suitcase, frisbee,
skis, snowboard, sports ball, kite, baseball bat, baseball glove, skateboard, surfboard,
tennis racket, bottle, wine glass, cup, fork, knife, spoon, bowl, banana, apple,
sandwich, orange, broccoli, carrot, hot dog, pizza, donut, cake, chair, couch,
potted plant, bed, dining table, toilet, tv, laptop, mouse, remote, keyboard,
cell phone, microwave, oven, toaster, sink, refrigerator, book, clock, vase,
scissors, teddy bear, hair drier, toothbrush
