# YOLO26 Depth MVP Verification

- Verification date: 2026-08-28
- Device: HONOR REP-AN00
- Android version: 15
- Model: `yolo26n-depth_w8a32.tflite`
- LiteRT accelerator: GPU
- Model input: 640 x 640
- Depth output: 640 x 640
- Average displayed FPS after 60 seconds: approximately 7.0 FPS
- Average displayed inference time after 60 seconds: approximately 32.1 ms
- Five-minute run: PASS
- Background/foreground recovery: PASS
- Lock/unlock recovery: PASS
- Rotation recovery (portrait -> landscape -> portrait): PASS
- Permission settings-return recovery: PASS
- Instrumentation tests: PASS, 3 tests

## GPU Evidence

Logcat reported:

```text
LiteRT accelerator=GPU input=640x640 output=DepthTensorShape(width=640, height=640)
```

The on-screen accelerator label matched Logcat. The final observed screen sample was:

```text
GPU | 7.9 FPS | 26.6 ms
```

## Functional And Stability Results

- The pseudo-color depth image changed continuously as the phone moved.
- The image remained upright in portrait orientation.
- The app did not freeze or crash during the five-minute run.
- FPS and inference time remained visible and valid.
- Observed PSS samples were approximately 607 MB, 708 MB, 822 MB, 775 MB,
  566 MB, 725 MB, and 456 MB. The pattern was consistent with garbage
  collection rather than continuous unbounded growth.
- Graphics memory stabilized at approximately 257 MB.
- Explicit fixed denial of camera permission displayed an understandable
  permission message and an action to grant permission.

## Lifecycle Results

- Pressing Home, waiting 10 seconds, and returning: PASS.
- Locking and unlocking the phone: PASS.
- Opening Settings and returning: PASS.
- Rotating portrait -> landscape -> portrait: PASS.
- The application process remained alive during these checks.

## Automated Verification

The following Gradle verification completed successfully:

```text
clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
connectedDebugAndroidTest
```

The instrumentation run completed all three tests successfully.
This run packaged and installed the permission-resume fix currently in the
working tree. The Android test runner removed its temporary app and test APKs
after completion, as expected.

## Performance Note

The measured average throughput of approximately 7.0 FPS is below the
suggested 10 FPS target. This does not block the functional MVP, but it is the
first optimization target for the next iteration.

## Final Permission-Resume Fix

The screen now rechecks camera permission whenever the Activity resumes. This
addresses returning directly from system settings after granting permission.
The change passes unit tests, APK builds, and all three device instrumentation
tests.

The focused device scenario also passed on the installed Task 9 build:

1. Camera permission was revoked and marked as a fixed denial.
2. The existing Activity displayed the permission explanation and grant action.
3. System app settings were opened while the Activity remained in the back stack.
4. Camera permission was granted while the app was in the background.
5. Returning to the same Activity automatically started the camera and depth
   inference without force-stopping or relaunching the app.

The resumed screen reported GPU inference. Observed samples included
`GPU | 9.5 FPS | 20.8 ms` and `GPU | 10.0 FPS | 21.4 ms`. Camera permission was
confirmed as granted after the test.

## Unified Depth-To-Audio Verification Update

- Verification date: 2026-08-30
- Device: HONOR REP-AN00, Android 15
- Local commit baseline: `385ef48`
- JVM tests: PASS, 67 tests
- Instrumentation tests: PASS, 40 tests, 0 failed, 0 skipped
- Debug APK, AndroidTest APK, Release APK and Release lint vital: PASS

The expanded suite covers metric-depth and ground-filter contracts, Python/C++
golden parity, successful and failed ground fits, full-frame obstacle
classification, 64x64 occupancy, smoothing and hysteresis, immediate-alert
deduplication, latest-only frame processing, native lifecycle, HRTF rendering,
real AudioTrack playback, and visual-to-audio coordination.

Task 15 added two device tests:

1. `DepthCameraLifecycleInstrumentedTest` backgrounds and resumes the real
   `MainActivity`, then rotates landscape and portrait. The live GPU depth page
   recovered after every transition.
2. `Glasses64AudioLifecycleTest` starts a real AudioTrack, calls stop, and
   verifies that the playback callback completes, worker and track references
   are cleared, and the captured track is released.

The permission settings-return scenario was repeated with the unified build.
A fixed CAMERA denial showed the permission page; permission was granted while
the same Activity task was in the background, and returning to that task
automatically restarted camera inference. A real five-second lock/wake cycle
also returned to the same MainActivity and resumed the live page. The observed
post-unlock sample was `GPU | 4.1 FPS | 28.0 ms` with MLE `97.4 ms`. No error
was present in the application PID log.

The complete pipeline remains below the sustained 10 FPS target. Performance
profiling and optimization are intentionally deferred to Task 16.
