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
tests. A persistent overwrite-install check of this exact build was blocked by
the phone package installer with
`INSTALL_FAILED_ABORTED: User rejected permissions`; therefore this one focused
device scenario remains pending until USB installation is allowed on the phone.
