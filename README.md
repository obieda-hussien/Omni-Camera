# Omni Camera

An independent Android camera built with Kotlin, Jetpack Compose and CameraX. Local portrait lighting, exposure fusion and inline controls; no Omni Studio dependency.

## Working features

- Photo, Video, Night and Pro modes; tap focus/metering, long-press lock, pinch zoom, lens switch and torch.
- Bundled ML Kit face detection and selfie segmentation. The first portrait does not require a model download or an account.
- Native C++17 imaging engine: guided illumination mapping, face/person masks, linear-light colour-preserving shadow recovery, measured-noise adaptive denoising and bounded detail enhancement. Small lighting maps guide full-resolution pixels; transcendental functions are precomputed in lookup tables. Two background workers preserve deterministic output.
- Manufacturer Auto/HDR/Night extensions when the selected camera supports them, with a native fallback. OEM-developed photos receive reduced additional enhancement.
- A bounded two-photo queue releases the shutter after acquisition while development and saving continue in the background. Review opens from the gallery button; it no longer interrupts every capture.
- Optional three-exposure JPEG HDR fusion: coarse-to-fine translation alignment against an immutable anchor, accumulated per-pixel linear-light weights, exposure-normalized motion rejection, and first-frame fallback in unreliable areas. Available in Auto Photo, subject to EV support.
- Processing quality and memory budget; EXIF orientation normalization, optional mirrored selfie output, optional original file and before/after review.
- Natural SDR, an actual GPU Flat SDR effect on both preview and recording, and HLG 10-bit when CameraX reports camera/encoder support. Resolution, requested frame rate, target bitrate and optional microphone.
- Capability-gated ISO/shutter/manual focus and white-balance presets. RAW support is reported, but RAW capture is **not** implemented.
- OLED camera UI with a stable 3:4 portrait / 4:3 landscape frame, overlay controls, shutter feedback, restrained transitions and a new adaptive/monochrome aperture icon. Composition grid, histogram, reorderable quick controls and debounced preferences. Labels use Android string resources.
- MediaStore publication with pending-file cleanup; no broad media-reading permission. The camera is released on screen disposal and recordings stop on backgrounding.

## Colour and hardware limits

Flat SDR is a low-contrast 8-bit **look**, not sensor Log, RAW video or extra sensor dynamic range. HLG is HDR, not a vendor Log profile. This pipeline does not expose native sensor Log; the interface says so. HDR availability is checked for each camera. CameraX may negotiate frame rates or resolutions below a requested configuration; recorded output must be verified on real devices.

Photo sliders affect the saved image, not a simulated real-time portrait preview. JPEG fusion cannot undo the manufacturer's ISP or recover detail absent from every source frame. Translation alignment handles small handheld shifts, not parallax, large rotation or complex motion; rejected frames fall back to the base. Skin texture/identity are not generated or replaced.

This is a JPEG computational-photography pipeline, not Google Camera HDR+ or a promise of matching its quality. Comparable saved-image tests on the same physical phone remain necessary. No unbenchmarked neural enhancement weights are bundled.

Memory pressure limits photo size. `High` requests up to 12 MP, not an unconditional 12 MP output. Multi-frame fusion uses more time and memory than one-frame processing. Selfie segmentation is a beta SDK and can fail on difficult edges; processing remains available without a mask.

## Build

JDK 17, Android SDK 36, Build Tools 35.0.0, NDK 27.0.12077973 and CMake 3.22.1. GitHub Actions installs these and builds the release:

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease bundleRelease
```

Release enables R8, resource shrinking and consumer rules from CameraX/ML Kit. Release includes ARM64/ARMv7 native libraries; debug retains Intel emulator support. No keystore is committed. Local builds without signing configuration produce unsigned release artifacts.

For production signing, supply `OMNI_KEYSTORE_PATH`, `OMNI_STORE_PASSWORD`, `OMNI_KEY_ALIAS`, `OMNI_KEY_PASSWORD` as environment variables. GitHub Actions accepts `OMNI_KEYSTORE_B64` and the same three password/alias secrets, builds separately zipped APK/AAB artifacts and preserves mapping/reports. Without secrets, CI explicitly labels artifacts **development** and generates a temporary test certificate. Those builds are installable for testing, but certificates change between runs: do not use them for a published app or expect in-place updates.

## Validation

Twelve native regression groups cover selective face lighting, noise reduction, neutral colour/alpha/stride preservation, thread determinism, translation registration, rejected alignment, clipped-highlight fusion, order-independent burst accumulation and temporal noise reduction. `scripts/verify-native.sh` also prints a 3 MP host-only comparison against the previous per-pixel scalar maths; this is not a phone latency claim.

Kotlin unit tests cover capture queue ownership, recovery LUTs, sRGB round-trips, neutral colour/alpha preservation, face versus background metering, monotonic tone mapping, heap budgets, exposure alignment, clipped-highlight fusion and fallback boundaries. Build and lint validate integration. A separate Android 11 emulator job loads JNI, captures/develops/saves a photo, verifies that processing does not open a modal, opens review, rotates to landscape and uploads UI screenshots. Physical-device tests remain necessary for camera HAL behavior, autofocus, manual controls, ML edges, HDR colour tagging, recording orientation and thermal performance.

Test backlit portraits, low light, different skin tones, glasses/hair edges, moving people, rapid mode/lens switching, portrait/landscape, Arabic RTL, permission denial, low storage and backgrounding. Compare the **saved** output and metadata, not just the viewfinder.

## Next stages

RAW/DNG capture, calibrated vendor Log paths where accessible, stronger multi-scale motion/rotation alignment, specialist denoising models after mobile benchmarking, calibrated LUT monitoring, zebra/focus peaking and optional OmniLink integration. These are not claimed as completed features.

## Model sources

- [ML Kit selfie segmentation](https://developers.google.com/ml-kit/vision/selfie-segmentation/android): bundled `segmentation-selfie:16.0.0-beta6`.
- [ML Kit face detection](https://developers.google.com/ml-kit/vision/face-detection/android): bundled `face-detection:16.1.7`.

Dependencies and pretrained models remain under their respective upstream licenses/terms; they are not re-licensed by this repository. No separately scraped or unverified model weights are shipped.
