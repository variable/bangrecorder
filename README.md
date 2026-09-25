# Bang Recorder (Android)

Listens all day and saves a 10-second clip around every loud bang, with the exact time it happened.

## How it works
- Keeps the last ~11 s of audio in memory (nothing is written while it's quiet).
- A bang = peak above the trigger level **and** a sudden jump of 15 dB+ over the recent background,
  so steady noise (vacuum, music, drill) doesn't keep re-triggering.
- On a bang it saves 5 s before + 5 s after as a WAV, named by time, e.g. `bang_2026-09-25_14-32-07.wav`.
- Every clip is also logged in `events.csv` (time to the millisecond, peak level, where in the clip the bang is).
- Files live in `Android/data/com.example.bangrecorder/files/bangs/`. Use "Share all" to send them anywhere.

## Build
**Android Studio:** File → Open → this folder → let Gradle sync → Run (phone connected with USB debugging) or
Build → Build APK(s).

**No Android Studio:** push the folder to a GitHub repo. The included workflow builds `app-debug.apk`;
download it from the Actions tab → latest run → Artifacts, then open it on the phone to install.

## Tuning (top of RecorderService.kt)
- `PRE_SEC` / `POST_SEC` — clip length around the bang
- `DEFAULT_THRESHOLD` — starting trigger level (also adjustable with the slider)
- `IMPULSE_DB` — how sudden a noise must be; lower = catches more, higher = fewer false alarms

Storage: ~1.9 MB per clip at 16 kHz.
