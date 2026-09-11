# SIGHTLINE — Project Overview

> **"Don't just see. Understand."**
> A phone-first AI assistant that helps visually impaired users accomplish real-world tasks by understanding what they're trying to *do* and giving short, actionable spoken guidance.

**Built for:** iQOO Hackathon — Open Innovation
**Status:** Tier 1 complete, on-device, fully functional
**Repository:** https://github.com/akhilamalakota/sightline

---

## 1. Problem Statement

Over 2.2 billion people globally have vision impairments. Existing assistive apps describe *everything* the camera sees — producing overwhelming walls of spoken text. SIGHTLINE takes the opposite approach: it understands the user's **intent** and speaks only what matters.

A blind user shouldn't hear *"I see a table, a chair, a lamp, a window, and three people"* — they should hear *"There are two chairs. One is a few steps ahead, slightly left."*

---

## 2. Core Architecture

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│  CameraX     │────▶│  YOLOv8n     │────▶│  Spatial    │
│  (frames)    │     │  TFLite      │     │  Engine     │
└─────────────┘     └──────────────┘     └──────┬──────┘
                                                 │
┌─────────────┐     ┌──────────────┐     ┌──────▼──────┐
│  Voice       │────▶│  GoalParser  │────▶│  Response   │
│  (STT/TTS)   │     │  (keywords)  │     │  Builder    │
└─────────────┘     └──────────────┘     └──────┬──────┘
                                                 │
                                          ┌──────▼──────┐
                                          │  Spoken     │
                                          │  Guidance   │
                                          └─────────────┘
```

**Processing is 100% on-device, offline, with zero cloud dependency.**

---

## 3. Feature Modes

| Mode | Trigger Phrases | What It Does |
|---|---|---|
| **FIND** | "find a chair", "where is the door" | Detects objects, reports direction (left/center/right) + distance (near/mid/far), spoken as natural language |
| **GUIDE** | "guide me", "take me to the exit" | Step-by-step spoken navigation toward a target with obstacle warnings |
| **UNDERSTAND** | "what's around me", "describe the scene" | Contextual summary of everything the camera sees |
| **REMEMBER** | "remember where I put my phone" | Stores object location relative to nearby landmarks; recalls later |
| **READ** | "read this", "read the sign" | ML Kit OCR extracts text from camera view |
| **MONEY** | "how much is this", "count money" | Detects Indian banknotes (₹10, ₹20, ₹50, ₹100, ₹200, ₹500, ₹2000) with a dedicated YOLOv8n model |
| **BARCODE** | "scan this", "barcode" | ML Kit reads QR codes, EAN/UPC product barcodes; speaks format + value |
| **COLOR** | "what color is this", "is the light on" | HSV pixel sampling of center frame; answers color name + brightness |
| **Control** | "stop", "repeat", "help" | Voice controls — stop speaking, repeat last response, or read the help menu |

---

## 4. System Modules (14 Kotlin files, ~130 KB)

### 4.1 Entry Point
**`MainActivity.kt`** — Permission requests (CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS), overlay permission prompt, Compose setContent, init sequence (voice → detector → telemetry → ready state). Includes ADB debug hook (`--es cmd "..."`) for automated testing.

### 4.2 Data Types
**`Models.kt`** — All shared enums and data classes:
- `GoalMode`: IDLE, FIND, GUIDE, UNDERSTAND, REMEMBER, READ, MONEY, BARCODE, COLOR
- `Direction`: LEFT, CENTER, RIGHT
- `DistanceZone`: NEAR, MID, FAR
- `PathStatus`: CLEAR, BLOCKED
- `ApproachState`: STATIC, APPROACHING, RECEDING
- `TrafficLightColor`: RED, YELLOW, GREEN, UNKNOWN
- `DetectedObject`: label, bbox, confidence, distanceMeters, distanceZone, direction, approach, trafficLightColor
- `UserGoal`: mode, target, rawUtterance
- `SpatialWorldModel`: timestamp, objects, targetLock, obstaclesInPath, pathStatus
- `MemoryEntry`: label, locationDescription, nearbyObjects, timestampMs

### 4.3 Camera Pipeline
**`FrameConverter.kt`** — Bridges CameraX `ImageProxy` (YUV_420_888) → RGB `Bitmap` via NV21 intermediate. Handles interleaved/separate UV planes and rotation correction for the detection pipeline.

### 4.4 Detection Layer

**`ObjectDetector.kt`** — General-purpose YOLOv8n TFLite detector:
- Input: 640×640 letterbox with auto-detection of normalized vs pixel coordinates
- Confidence threshold: 0.30
- Non-maximum suppression (IoU 0.5)
- Falls back: GPU → NNAPI → CPU
- Labels: COCO 80-class vocabulary
- Normalization: auto-adapts to model output format (normalized 0-1 or raw pixel coordinates)

**`MoneyDetector.kt`** — Indian banknote detector (wraps `ObjectDetector`):
- Model: `currency.tflite` (12.1 MB, exported from `yolov8n_currency_best.pt` via HuggingFace `Rathnavelu/indian-currency-cnn-yolo`)
- Input: 320×320
- Confidence threshold: 0.35
- Labels: `["10", "100", "20", "200", "2000", "50", "500"]` (exact order from model class IDs)

**`BarcodeReader.kt`** — ML Kit barcode/QR scanner (one-shot):
- Formats: `FORMAT_ALL_FORMATS` (QR, EAN-13, EAN-8, UPC-A, UPC-E, Code 128, etc.)
- Returns: format name ("QR", "Product", or "Code") + raw value string
- Runs on `Dispatchers.IO` via `Tasks.await()`

**`ColorLightDetector.kt`** — HSV pixel sampling (pure math, no ML):
- Samples the center 50% of the frame (what user is pointing at)
- 6 hue bands: ORANGE, YELLOW, GREEN, BLUE, PURPLE, PINK
- Non-saturated pixels classified as: white (bright), black (dark), gray
- Mean brightness → isBright (>0.45) / isDark (<0.22)
- Answers both "what color is this?" and "is the light on?" in one pass

**`TrafficLightClassifier.kt`** — Classifies traffic-light bounding box crops by counting bright saturated pixels in RED/YELLOW/GREEN hue bands (stride=2, min 8 qualifying pixels).

### 4.5 Spatial Intelligence
**`SpatialEngine.kt`** — Converts raw detections into a structured world model:
- **Temporal stabilization**: per-track EMA smoothing (α=0.5), confirmation after 2+ frames, sticky window (~1.6s) so momentary gaps don't drop objects
- **Metric distance**: `knownHeight × focalPx / bboxHeightPx` → meters; 75 COCO-class known heights in lookup table; falls back to zone (NEAR/MID/FAR) when unknown
- **Direction**: center third → CENTER; left third → LEFT; right third → RIGHT
- **Approach detection**: tracks bbox height growth over 4-8 frames; classifies APPROACHING / RECEDING / STATIC
- **Path status**: objects in center-NEAR zone labeled as obstacles (person, chair, car, etc.) → BLOCKED; otherwise CLEAR
- **Target lock**: for FIND/GUIDE, selects closest matching object (meters-aware closeness scoring)
- **Track management**: max 12 tracks, LRU eviction of unconfirmed tracks, confidence decay while unseen

### 4.6 Goal Parsing
**`GoalParser.kt`** — Deterministic keyword matcher (no LLM, no ML, no network):
- Longest-trigger-wins matching across 8 intent patterns (FIND, GUIDE, UNDERSTAND, REMEMBER, READ, MONEY, BARCODE, COLOR)
- ~80 known object targets for FIND mode
- Special cases: "remember where I put X" extracts target after "put"; UNDERSTAND defaults to "surroundings"; MONEY/BARCODE/COLOR are one-shot modes with self-targeting
- Returns `UserGoal(mode, target, rawUtterance)` or null

### 4.7 Memory
**`MemoryStore.kt`** — Android DataStore persistence:
- `rememberObject()`: finds target in current detections, records position + up to 3 nearest landmarks as natural-language description, persists to DataStore
- `recallObject()`: retrieves last stored location + nearby objects for a label
- `hasMemory()`: checks if a memory exists

### 4.8 Output Layer

**`ResponseBuilder.kt`** — Templated spoken responses + SpatialEngine integration:
- Mode-specific templates: FIND reports count + direction + distance; GUIDE gives step-by-step with obstacle warnings; UNDERSTAND summarizes all objects; REMEMBER confirms storage or recalls location; READ returns OCR text; MONEY states denomination; BARCODE states format + value; COLOR states color + brightness
- Natural language via `SpatialEngine.directionWord()`, `distanceWord()`, `metersWord()`
- Speech rate: 1.15× (slightly faster for information density)

**`VoiceManager.kt`** — Full STT + TTS lifecycle:
- **TTS**: Android `TextToSpeech` engine, QUEUE_FLUSH (latest message replaces pending), `isSpeaking` StateFlow for UI binding, 1.15× speech rate
- **STT**: Android `SpeechRecognizer`, free-form language model, offline-preferred, auto-retry on BUSY errors (3 attempts with 2s/4s backoff)
- **Mic exclusivity**: stops TTS, waits 1s for audio release, then arms STT (OPPO/iQOO audio conflict fix)
- `processTextCommand()`: text-input fallback that feeds directly to the result callback

### 4.9 Wake Word
**`WakeWordService.kt`** — Always-on "Hey Sightline" foreground service:
- **Engine**: Vosk (open-source, offline, no API key, no account needed)
- **Model**: `vosk-model-small-en-us-0.15` bundled in assets (`model-en/`)
- **Grammar mode**: restricted to `["hey sight line", "hey sight", "sight line", "sight"]` — makes detection near-instant and false-positive resistant
- **Mic handover**: captures only when app is backgrounded; stops capture when app comes to foreground so in-app SpeechRecognizer gets exclusive mic access
- **Wake behavior**: vibration (400ms) → beep → launch MainActivity → stop capture
- **Foreground service**: microphone-type, low-importance notification ("Listening for 'Hey Sightline'")
- **Restart**: `START_STICKY` + persisted capture-state in SharedPreferences survives process kill

**`BootReceiver.kt`** — `BOOT_COMPLETED` receiver that restarts `WakeWordService` on device boot.

### 4.10 ViewModel (Orchestrator)
**`SightlineViewModel.kt`** (33.9 KB) — The central state machine:
- Owns all subsystems: detectors, SpatialEngine, GoalParser, VoiceManager, MemoryStore, TelemetryClient
- `processTextCommand()` → `handleVoiceInput()` → GoalParser → mode-specific execution
- Continuous camera loop: 1000ms (IDLE) / 200ms (active mode) frame throttle
- One-shot mode pattern: `@Volatile colorFired/barcodeFired/moneyFired` + `bitmapOwnedByMode`; 12-second speak cap → `returnToIdle()`
- Mode lifecycle: IDLE → (trigger) → active mode → speak → 12s timeout → IDLE
- Telemetry integration: world model JSON → WebSocket → Command Center

### 4.11 UI
**`SightlineApp.kt`** (20.4 KB) — Jetpack Compose Material3 interface:
- 2 screens: Camera View (detection overlay with bounding boxes + labels) and Memory Recall
- Dark theme (Black background) for accessibility
- TalkBack-compatible accessibility semantics on all interactive elements
- Status indicators: listening, speaking, mode badge, battery level

### 4.12 Telemetry
**`TelemetryClient.kt`** — Fire-and-forget WebSocket to optional Command Center:
- OkHttp WebSocket to `ws://192.168.1.100:3001/telemetry`
- 3s connect/read/write timeouts, no retry storm
- Payload: goal, target, confidence, pathStatus, objects, lastResponse, timestamp
- **Never blocks, crashes, or impacts the app if unreachable**

---

## 5. Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Kotlin | JVM target 17 |
| UI | Jetpack Compose + Material3 | BOM 2024.06.00 |
| Camera | CameraX | 1.3.4 |
| Object Detection | TensorFlow Lite | 2.16.1 (GPU delegate) |
| Currency Detection | TFLite (YOLOv8n custom) | currency.tflite (12.1 MB) |
| Barcode/QR | ML Kit Barcode Scanning | 17.3.0 |
| OCR | ML Kit Text Recognition | 16.0.0 |
| Wake Word | Vosk (offline, open-source) | 0.3.75 |
| Persistence | Android DataStore | 1.1.1 |
| Networking | OkHttp WebSocket | 4.12.0 |
| Coroutines | kotlinx-coroutines-android | 1.8.1 |
| Android SDK | compileSdk/targetSdk 34, minSdk 26 | — |
| Gradle | 8.9 | — |

---

## 6. ML Models

### 6.1 General Object Detection
- **Model**: YOLOv8n (nano) → TFLite
- **Input**: 640×640 letterboxed RGB
- **Output**: 80 COCO classes
- **Confidence**: 0.30 threshold + NMS (IoU 0.5)
- **Runtime**: GPU delegate → NNAPI fallback → CPU fallback
- **Size**: ~6 MB (bundled in assets)

### 6.2 Indian Currency Detection
- **Source**: HuggingFace `Rathnavelu/indian-currency-cnn-yolo`
- **Model**: YOLOv8n trained on Indian banknotes
- **Export**: `models/export_currency.py` (ultralytics, imgsz=320, float32)
- **Input**: 320×320 letterboxed
- **Output**: 7 classes — ₹10, ₹20, ₹50, ₹100, ₹200, ₹500, ₹2000
- **Confidence**: 0.35 threshold
- **Size**: 12,153,914 bytes (12.1 MB)

### 6.3 Barcode/QR Scanning
- **Engine**: Google ML Kit (bundled, offline)
- **Formats**: All formats (QR, EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Data Matrix, etc.)
- **Execution**: One-shot per command (not continuous)

### 6.4 Color + Light Detection
- **Method**: HSV pixel sampling of center 50% of frame
- **No ML** — pure math, ~4-pixel stride
- **Output**: 6 hue bands (ORANGE, YELLOW, GREEN, BLUE, PURPLE, PINK) + white/gray/black for non-saturated + brightness classification

### 6.5 Traffic Light Classification
- **Method**: HSV hue band counting (stride=2)
- **Output**: RED / YELLOW / GREEN / UNKNOWN
- **Used within**: ObjectDetector when "traffic light" is detected in COCO

### 6.6 Wake Word
- **Engine**: Vosk (open-source, fully offline)
- **Model**: vosk-model-small-en-us-0.15 (~50 MB, bundled in assets)
- **Grammar**: restricted token set for instant, low-false-positive detection

---

## 7. Key Design Decisions

| Decision | Rationale |
|---|---|
| **Deterministic keyword parser (no LLM)** | Zero hallucination risk, zero latency, zero network dependency — reliability wins for a live demo |
| **One-shot camera modes** | MONEY/BARCODE/COLOR capture one frame, speak result, return to IDLE — simple UX, avoids camera contention |
| **EMA temporal stabilization** | Prevents "jittery" object reports; objects must be seen 2+ frames before speaking, persist ~1.6s after last sighting |
| **Metric distance from known object heights** | Monocular distance estimation without depth sensors; 75-class lookup table with real-world heights in meters |
| **Vosk grammar mode (no [unk])** | "Sightline" isn't in Vosk's vocabulary — grammar forces mapping to "sight line"/"hey sight" → substring match fires |
| **Telemetry never blocks** | WebSocket is fire-and-forget with 3s timeouts; dashboard is a bonus, not a dependency |
| **DataStore for memory** | Survives app restart; keyed by object label; stores natural-language location + nearby landmarks |
| **ADB debug hook** | `--es cmd "..."` for automated testing without manual speech input; no production impact |

---

## 8. Project Structure

```
sightline-freebuff/
├── app/
│   └── src/main/
│       ├── java/com/sightline/app/
│       │   ├── MainActivity.kt          (194 lines)  Entry point + permissions + ADB hook
│       │   ├── Models.kt                (shared)     Enums + data classes
│       │   ├── SightlineApp.kt          (20.4 KB)    Compose UI (2 screens)
│       │   ├── SightlineViewModel.kt    (33.9 KB)    State machine orchestrator
│       │   ├── camera/
│       │   │   └── FrameConverter.kt    (101 lines)  YUV → Bitmap
│       │   ├── detection/
│       │   │   ├── ObjectDetector.kt    (13.2 KB)    YOLOv8n TFLite + NMS
│       │   │   ├── MoneyDetector.kt     (28 lines)   INR banknote detector
│       │   │   ├── BarcodeReader.kt     (45 lines)   ML Kit barcode/QR
│       │   │   ├── ColorLightDetector.kt(90 lines)   HSV color + brightness
│       │   │   └── TrafficLightClassifier.kt         Traffic light color
│       │   ├── spatial/
│       │   │   └── SpatialEngine.kt     (377 lines)  Tracking + distance + world model
│       │   ├── goal/
│       │   │   └── GoalParser.kt        (164 lines)  Keyword intent matcher
│       │   ├── memory/
│       │   │   └── MemoryStore.kt       (119 lines)  DataStore persistence
│       │   ├── output/
│       │   │   ├── ResponseBuilder.kt   (10.9 KB)    Template responses + spatial language
│       │   │   └── VoiceManager.kt      (248 lines)  TTS + STT lifecycle
│       │   ├── telemetry/
│       │   │   └── TelemetryClient.kt   (109 lines)  WebSocket → Command Center
│       │   └── wake/
│       │       ├── WakeWordService.kt   (376 lines)  "Hey Sightline" foreground service
│       │       └── BootReceiver.kt      (24 lines)   Restart on boot
│       └── assets/
│           ├── yolov8n.tflite                       General object model
│           ├── currency.tflite                      Indian currency model
│           └── model-en/                            Vosk wake word model
├── dashboard/                          Optional Command Center
│   ├── server.js                       Socket.io telemetry server
│   └── client/                         Vite + React dashboard
├── models/
│   ├── yolov8n_currency_best_saved_model/   Training source (HuggingFace)
│   ├── yolov8n_savedmodel/                  YOLOv8n base
│   ├── export_currency.py                   Currency model export script
│   └── export_yolo_v8.py                    Base model export script
├── download/
│   └── Sightline.apk                   Pre-built APK (33 MB)
├── screen.png                          Screenshot
└── README.md
```

---

## 9. Build & Run

### Prerequisites
- Android Studio (latest stable) or Gradle 8.9
- Physical Android device (emulator won't provide real camera/detection performance)
- Node.js 18+ (for optional Command Center)

### Export the Currency Model
```bash
cd models/
pip install ultralytics
python export_currency.py
# Produces currency.tflite → copy to app/src/main/assets/
```

### Build the App
```bash
cd sightline-freebuff/
gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Run Command Center (Optional)
```bash
cd dashboard/
npm install && cd client/ && npm install && cd ..
npm run dev
# Dashboard: http://localhost:5173
# Telemetry: ws://localhost:3001/telemetry
```

---

## 10. Automated Testing (ADB Debug Hook)

Any command can be injected without speech input:

```bash
adb shell am start --activity-single-top \
  -n com.sightline.freebuff/com.sightline.app.MainActivity \
  --es cmd "find a chair"
```

**Verified commands:**
| Command | Mode | Behavior |
|---|---|---|
| `help` | Control | Reads full help menu listing all capabilities |
| `stop` | Control | Silences TTS immediately |
| `repeat` | Control | Repeats last spoken response |
| `what color is this` | COLOR | Captures one frame, speaks dominant color + brightness |
| `is the light on` | COLOR | Captures one frame, speaks "bright" or "dark" |
| `scan this` | BARCODE | Reads one barcode/QR, speaks format + value |
| `how much is this` | MONEY | Reads one banknote, speaks denomination |
| `find a chair` | FIND | Detects chairs, speaks count + direction + distance |
| `guide me to the exit` | GUIDE | Continuously guides with spatial directions |
| `what's around me` | UNDERSTAND | Summarizes all visible objects |
| `remember where I put my phone` | REMEMBER | Stores current location of target |
| `read this` | READ | OCR reads text from camera view |

---

## 11. Git History (9 commits)

```
f34e321 Tier 1: voice controls + color/barcode/money modes, battery throttle
a34c29c Fix wake word: mic exclusivity handover, grammar without [unk], substring firing
067ef2e Swap wake word engine Picovoice -> Vosk (open source, offline, no API key)
cb013bb Add hands-free 'Hey Sightline' wake word, auto-launch, and zero-tap mic
ee8c5ea Add approach radar, traffic light reading, offline OCR (read this), and haptic locating
6f7936e Update project title in README
50ff34b Fix overlapping object tags on camera overlay
16332be Add stabilized detection, 640px letterboxed inference, metric distance, and arrival guidance
f2b3e73 Sightline Freebuff - AI assistant for visually impaired users
```

**Branch:** `master`
**Remote:** `https://github.com/akhilamalakota/sightline.git`

---

## 12. What Makes This Different

1. **Intent-first, not description-first** — doesn't flood the user with everything it sees; only speaks what's relevant to what they asked
2. **100% offline** — no API keys, no subscriptions, no cloud; works without internet
3. **Open-source engine** — Vosk wake word (no Picovoice licensing), ML Kit (on-device), TFLite
4. **Metric distance without depth sensors** — monocular distance estimation from known object heights
5. **Temporal stabilization** — EMA smoothing + sticky tracking prevents jittery spoken output
6. **9 modes in one app** — FIND, GUIDE, UNDERSTAND, REMEMBER, READ, MONEY, BARCODE, COLOR, plus control commands
7. **Production-quality failure handling** — every async path has try/catch + fallback; telemetry never crashes the app
8. **TalkBack-compatible** — accessibility semantics on all UI elements

---

## 13. Future Scope

- **Continuous mode** — cycle through all detection modes automatically (scan everything continuously)
- **Depth estimation** — integrate MiDaS or similar monocular depth model for better distance accuracy
- **Indoor navigation** — SLAM-based mapping for hallway/room navigation
- **Multi-language** — Hindi/regional language voice commands and responses
- **Scene understanding** — integrate a vision-language model for richer scene descriptions
- **Community features** — crowd-sourced object labels and navigation routes

---

*Built with ❤️ for iQOO Hackathon — Open Innovation*
