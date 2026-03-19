# Architecture

MEMO-OS keeps a responsibility-based architecture that already reads like a system project even though the current runnable prototype is still inside the Android app sandbox.

## Central Execution Chain

The repository is organized around this chain:

`AppEvent history -> PredictionBatch -> ResourceDecision -> MemoSystemServiceFacade -> SystemBridge -> observable system status`

This matters because the research contribution is not the widget and not the predictor in isolation. The contribution is prediction-guided Android resource management.

## Layer Roles

- `core`: normalized models, config, helpers, and time abstractions
- `data`: collection, ingestion, dataset registry, normalization, replay, Room, and repositories
- `predictor`: transition trainer, predictor contracts, and predictor service
- `policy`: policy contracts and resource-decision engines
- `system`: app-level and native system-facing control points and execution bridges
- `evaluation`: experiment recording, metrics, analysis, and export
- `orchestration`: end-to-end pipelines
- `worker`: scheduled entry points
- `ui`: output and control surfaces only

## Boundary Rules

- UI does not talk to DAOs directly
- dataset-specific parsing never leaks into predictor or policy code
- predictors see only normalized `AppEvent`
- policies see only `PredictionBatch` and config
- system execution happens only through `MemoSystemServiceFacade` and `SystemBridge`
- widget and dashboard read repository/service state after execution

## Current Runtime Center

The most important current classes are:

- `ui/main/MemoGraph.kt`
- `orchestration/PredictPipeline.kt`
- `orchestration/PolicyPipeline.kt`
- `orchestration/ReplayPipeline.kt`
- `system/service/MemoSystemServiceFacade.kt`
- `system/bridge/AppLevelSystemBridge.kt`
- `system/bridge/NativeSystemBridge.kt`
- `data/repository/SystemStatusRepository.kt`

`PolicyPipeline` is the point where predictions stop being model output and become system-facing intent. It converts policy results into actual bridge execution and records observable system status.

## Current Execution Semantics

### Replay mode

`DatasetRegistry -> PublicDatasetManager -> ReplayEventStream -> PredictPipeline -> PolicyPipeline -> EvaluationPipeline -> export`

### Online mode

`UsageStatsCollector -> CollectPipeline -> PredictPipeline -> PolicyPipeline -> EvaluationPipeline -> widget/dashboard state`

## Why The Widget Is Not The Core

The widget exists to expose:

- latest predicted top-3 apps
- latest system execution summary
- a launch surface for predicted apps

It reads from `WidgetStateRepository`, which in turn reads from the prediction repository and the system status repository. It does not own model training, system decisions, or raw system calls.

## Current Native Role

JNI/C++ is used where native/system flavor is meaningful:

- score normalization
- threshold merging
- native strategy bridge scaffolding for future system-close execution

This keeps native code small, purposeful, and future-ready without collapsing the whole stack into C++.

## Emulator-First Presentation

Android Emulator is now the official demo target. To support that:

- bundled sample datasets are seeded into app internal storage
- `MainActivity` exposes explicit replay and online-cycle entry points
- `DashboardActivity` exposes mode selection, dataset preparation, replay trigger, metrics, exports, and system status
- workers stay mode-aware and preserve the same architecture used on real devices

## Current Consolidation Decisions

This pass removed or consolidated overlapping presentation entry points:

- one dashboard activity: `DashboardActivity`
- one widget provider/updater pair: `RecommendationWidgetProvider` and `RecommendationWidgetUpdater`

That keeps the repository aligned with its final architecture rather than accumulating parallel demo surfaces.
