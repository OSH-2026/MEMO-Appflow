# System Integration Plan

MEMO-OS already separates prediction from system-facing execution so the current app-level prototype can evolve toward deeper Android integration without rewriting the whole project.

## Current Runnable Path

Today the project runs in a normal Android app sandbox:

- `AppLaunchController` launches apps via package launch intents
- `PrewarmController` records explicit prewarm intent and is ready for privileged replacement
- `RetentionController` records explicit keep-alive intent and is ready for privileged replacement
- `AppLevelSystemBridge` performs the current app-level execution path
- `NativeSystemBridge` provides a native/system-close strategy seam
- `MemoSystemServiceFacade` coordinates the system-facing step used by policy execution

The current bridge result is stored in `SystemStatusRepository`, which makes system execution visible to the dashboard and widget.

## Why This Matters

The system-facing layer is the core OS contribution:

- prediction quality matters because it influences resource decisions
- resource decisions matter because they influence execution intent
- execution intent is what will eventually map to privileged framework or system-service hooks

## Native Role Today

JNI/C++ is not decorative:

- Markov prediction can normalize scores in native code
- threshold policy can merge thresholds in native code
- `NativeSystemBridge` is a direct extension point for later native strategy logic

This gives the project a real native/system flavor now while keeping most coordination logic in Kotlin.

## Planned Replacement Path

The current app-level bridge can later be replaced or augmented by:

- Binder-backed system service APIs
- privileged process or daemon coordination
- framework launch hinting
- memory-management hooks
- process retention / LMK-adj related control points
- deeper AOSP scheduler and resource-management integration

## Stable Contracts To Preserve

These contracts should remain stable as integration deepens:

- `Predictor`
- `PolicyEngine`
- `ResourceDecision`
- `SystemBridge`
- `MemoSystemServiceFacade`
- `ExperimentRecorder`

That allows system internals to become more privileged without forcing the research stack to be redesigned.

## App-Level vs Future Privileged Hooks

Current app-level behavior:

- visible, runnable, demonstrable on emulator
- structured logs and execution reports
- output to widget and dashboard

Future privileged behavior:

- real prewarm hooks
- real retention control
- binder or native service execution
- deeper system telemetry

The code intentionally uses `TODO` only where privileged future hooks genuinely depend on system-level access.
