# DNYTUBE Android project — final source package

DNYTUBE is a new Android implementation of the requested audio-player/equalizer workflow. It does not include an activation-code mechanism.

## Included
- Local audio picker and MediaPlayer playback
- Play/pause/stop, seek position updates
- Android audio focus handling
- Native Equalizer, BassBoost and Virtualizer effects where supported by the device
- 31-band UI with nearest-band mapping to the device's native Equalizer
- Compressor and crossover parameter/state UI with preset persistence
- Channel A/B mute/phase/filter/gain/limit state controls
- JSON preset import and real JSON preset export through Android's document picker
- YouTube WebView entry point
- Persistent settings

## Build
Requires Android Studio with Android SDK Platform 35 and Gradle/Android Gradle Plugin 8.5.2.

Open this directory in Android Studio and build the `app` module. This environment did not contain a usable Android SDK/Gradle installation, so an APK could not be compiled and runtime-tested here.

## Important implementation boundary
The Android platform `Equalizer` is device-dependent and normally exposes fewer than 31 native bands. The 31-band screen therefore maps requested bands to the nearest available native band. The compressor, crossover, matrix and visualizer controls persist their parameters, but they are not a custom PCM DSP engine equivalent to a professional multiband processor.

This package is a clean-room implementation, not a modified copy of another APK and does not remove or bypass another application's licensing mechanism.
