# Tomorrow 🎙️✨

An AI-powered native Android utility that captures unstructured voice notes, spoken thoughts, or typed brain dumps and transforms them into structured, actionable tasks. Powered by the **Gemini 1.5 Flash API** and an offline-first **Room Database** persistence layer.

---

## 🛠️ Tech Stack & Highlights

* **Language:** Kotlin
* **UI Framework:** Jetpack Compose & Material 3
* **AI Integration:** Google Generative AI SDK (`com.google.ai.client.generativeai`) using **Gemini 1.5 Flash**
* **Local Persistence:** Room Database (SQLite) with Kotlin Coroutines & StateFlow
* **Audio & Speech:** Native Android `SpeechRecognizer` API
* **Architecture:** MVVM + Clean Architecture + Repository Pattern
* **Target SDK:** Android 14+ (API 34+)

---

## ✨ Key Features

* **Natural Voice-to-Task Pipeline:** Records unstructured speech or text and extracts actionable parameters (task title, due dates, priority level, categories, and subtasks).
* **Strict JSON Extraction Schema:** Leverages Gemini 1.5 Flash with structured system prompting to guarantee valid, type-safe JSON output.
* **Offline-First Persistence:** Automatically caches and manages extracted tasks locally via Room for instant access and zero network latency on review.
* **Declarative Material 3 UI:** Built using pure Jetpack Compose with reactive state hoisting and smooth task status transitions.

---

## 🏗️ Architecture & Data Flow

```text
[ SpeechRecognizer / Microphone ]
               │
               ▼
   [ Compose UI Presentation ]
               ▲
               │ (UiState / User
