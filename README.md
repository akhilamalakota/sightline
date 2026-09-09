# SIGHTLINE

**"Don't just see. Understand."**

A phone-first AI assistant that helps visually impaired users accomplish real-world tasks — not by describing everything the camera sees, but by understanding what they're trying to *do* and giving short, actionable spoken guidance.

Built for: **iQOO Hackathon — Open Innovation**

---

## Quick Start

### Prerequisites

- Android Studio (latest stable)
- Node.js 18+ (for Command Center)
- A physical Android device (emulator won't give real camera/detection performance)
- The YOLOv8n TFLite model (see below)

### 1. Export the Model

```bash
cd models/
pip install ultralytics
python export_model.py
# Drops yolov8n.tflite into models/
```

Then copy `yolov8n.tflite` into `sightline-app/app/src/main/assets/`.

### 2. Build & Run the Android App

```bash
cd sightline-app/
# Open in Android Studio, or:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Run the Command Center (optional)

```bash
cd dashboard/
npm install
cd client/
npm install
cd ..
npm run dev
# Dashboard: http://localhost:5173
# Server: ws://localhost:3001/telemetry
```

---

## Project Structure

```
sightline-freebuff/
├── sightline-app/           # Native Android app (Kotlin + Compose)
│   └── app/src/main/java/com/sightline/app/
│       ├── Models.kt           # Core data types
│       ├── MainActivity.kt     # Permissions + entry point
│       ├── camera/
│       │   └── FrameConverter.kt   # ImageProxy → Bitmap
│       ├── detection/
│       │   └── ObjectDetector.kt   # YOLOv8n TFLite + NMS
│       ├── spatial/
│       │   └── SpatialEngine.kt    # Direction/distance estimation
│       ├── goal/
│       │   └── GoalParser.kt       # Keyword intent matcher
│       ├── memory/
│       │   └── MemoryStore.kt      # DataStore persistence
│       ├── output/
│       │   ├── ResponseBuilder.kt  # Scripted response templates
│       │   └── VoiceManager.kt     # STT + TTS
│       ├── telemetry/
│       │   └── TelemetryClient.kt  # WebSocket → Command Center
│       └── ui/
│           ├── SightlineViewModel.kt  # State machine orchestrator
│           └── SightlineApp.kt        # Compose UI (2 screens)
├── dashboard/               # Command Center (Node + React)
│   ├── server.js               # Socket.io server
│   └── client/                 # Vite + React dashboard
├── models/
│   └── export_model.py      # YOLOv8n → TFLite export script
└── README.md
```

---

## How It Works

1. **Camera → Detection**: CameraX delivers frames to TFLite YOLOv8n with GPU delegate
2. **Detections → World Model**: SpatialEngine maps bboxes to direction (left/center/right) + distance (near/mid/far)
3. **Voice → Goal**: GoalParser matches speech to intent (FIND/GUIDE/UNDERSTAND/REMEMBER) + target
4. **Goal + World → Response**: ResponseBuilder picks the right template
5. **Response → Speech**: VoiceManager speaks it out loud
6. **Telemetry → Dashboard**: WebSocket streams world state to Command Center

All processing is **on-device, offline, zero cloud dependency**.

---

## Core Capabilities

| Mode | What it does |
|---|---|
| **FIND** | "Find a chair" → *"There are two chairs. One is a few steps ahead, slightly left."* |
| **GUIDE** | Step-by-step spoken navigation, obstacle warnings |
| **UNDERSTAND** | "What's around me?" → Contextual summary of scene |
| **REMEMBER** | "Remember where I put my phone" → recalls later |

---

## Design Principles

- **Boring, working code** over clever code — this has to survive a live demo
- **Zero silent failures** — every async operation has a visible/audible fallback
- **Command Center can't break the app** — it's a bonus layer, not a dependency
- **TalkBack-compatible** — every element has proper accessibility semantics
- **Fewer features, better execution** — two screens, four modes, no settings

---

## Tech Stack

- **App**: Kotlin, Jetpack Compose, CameraX, TFLite, ML Kit
- **Model**: YOLOv8n → TFLite (GPU delegate, fallback to NNAPI/CPU)
- **Voice**: Android SpeechRecognizer (offline) + TextToSpeech (native)
- **Dashboard**: Node.js + Socket.io + React (Vite)

---

## License

Built for iQOO Hackathon. Use however you want.
