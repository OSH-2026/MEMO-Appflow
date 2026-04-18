# AppFlow Reproduction Plan For MEMO-OS

## Goal

Bring the scheduling ideas from the AppFlow paper into this repository in a way that is honest about platform limits and still useful for a runnable Android prototype.

This plan treats the paper as a scheduler redesign, not as a predictor swap. The current repository already has a usable chain:

`collection/replay -> predictor -> policy -> MemoSystemServiceFacade -> SystemBridge`

The main change is to replace the current coarse periodic execution model with an AppFlow-style event-driven scheduler that coordinates:

- selective file preloading
- memory-pressure-aware reclamation policy
- context-aware process kill planning

## What The Paper Actually Does

The paper is not just an app-side optimization. It explicitly spans:

- Android framework changes for preloading and kill control
- Linux kernel changes for reclamation behavior
- `/proc` communication between the preloader and reclaimer

Important paper details that matter for reproduction:

- Three modules: Selective File Preloader, Adaptive Memory Reclaimer, Context-Aware Process Killer
- The reclaimer samples allocation activity every 100 ms
- The paper uses a recent-use window of 30 minutes for kill deferment
- The before-launch preload budget is capped at 100 MB
- The memory-sensitive trigger is based on both free memory and allocation rate

That means a normal APK cannot fully reproduce the kernel/framework behavior. In this repository we should target:

1. an app-side and replay-driven reproduction that captures the scheduler logic
2. an optional rooted or AOSP-backed extension path for real system hooks later

## Current Gaps In This Repository

The current codebase already has the right high-level separation, but the runtime behavior is much simpler than AppFlow:

- `app/src/main/java/com/memoos/worker/WorkScheduler.kt` runs coarse periodic work at 15, 30, and 60 minute intervals
- `app/src/main/java/com/memoos/ui/main/MainActivity.kt` adds a 6 second refresh loop, but only while the UI is visible
- `app/src/main/java/com/memoos/policy/engine/ThresholdPolicyEngine.kt` outputs only keep, prewarm, and hint lists
- `app/src/main/java/com/memoos/system/prewarm/PrewarmController.kt` is only a placeholder
- `app/src/main/java/com/memoos/system/memory/RetentionController.kt` is only a placeholder
- `app/src/main/java/com/memoos/core/model/ResourceDecision.kt` is too shallow for file-level preload plans, pressure modes, and kill reasoning

In other words, the repo already has the right seams, but not the scheduler state or decision richness required by the paper.

## Recommended Architecture

### 1. Keep The Existing Predictor, Replace The Runtime Scheduler

Do not start by rewriting the predictor. The paper's contribution is mostly below that layer.

Recommended split:

- predictor stays responsible for `which app is likely next`
- a new AppFlow scheduler becomes responsible for `what to preload`, `when to protect memory`, and `what to avoid killing`

The cleanest way to do that in this repo is to keep `PredictPipeline` and replace the current policy/runtime side with a richer coordinator.

### 2. Add A Dedicated AppFlow Layer

Add a new package tree, for example:

- `app/src/main/java/com/memoos/appflow/model/`
- `app/src/main/java/com/memoos/appflow/profile/`
- `app/src/main/java/com/memoos/appflow/scheduler/`
- `app/src/main/java/com/memoos/appflow/service/`

This avoids overloading the existing threshold policy classes with paper-specific logic.

Suggested new core models:

- `AppLaunchProfile`
- `PreloadPlan`
- `MemoryPressureSnapshot`
- `KillCandidate`
- `ProtectedAssetSet`
- `AppFlowDecision`

Suggested fields:

- `AppLaunchProfile`: package name, small-file bytes, large-file bytes, hot segments, cutoff size, baseline memory, relaunch memory, expected cold-launch ms
- `PreloadPlan`: before-launch files, during-launch files, budget bytes, predicted benefit ms
- `MemoryPressureSnapshot`: available memory, threshold memory, headroom, pressure mode, allocation-rate proxy
- `KillCandidate`: package name, current memory estimate, relaunch baseline, recent-use timestamp, delta-memory score
- `AppFlowDecision`: preload plan, protected set, reclaim mode, keep list, defer-kill list, kill candidates, rationale

## Mapping Paper Modules To This Repository

| Paper module | Current seam in repo | App-side reproduction target | Real system target later |
| --- | --- | --- | --- |
| Selective File Preloader | `system/prewarm/PrewarmController.kt` | Build per-app launch profiles and run before-launch plus during-launch preload simulation | Low-priority framework preloader with real file page control |
| Adaptive Memory Reclaimer | `system/memory/RetentionController.kt`, `system/monitor/MemoryMonitor.kt` | Build pressure modes and retention/protection policies from memory snapshots and proxies | Page-type-aware reclaim in kernel or privileged daemon |
| Context-Aware Process Killer | `policy/`, `system/service/`, `data/repository/` | Rank kill candidates and defer recent apps, even if actual kill is only simulated | Replace LMK ordering or issue privileged force-stop decisions |
| System-wide scheduler | `worker/WorkScheduler.kt`, `MainActivity.kt`, `MemoGraph.kt` | Replace periodic worker chain with an event-driven coordinator service | Binder service or framework scheduler |

## Scheduling Redesign

This is the most important repository-specific change.

The current scheduling model is split between:

- WorkManager periodic jobs for background behavior
- a foreground-only UI loop for near-real-time refresh

That is exactly the kind of coarse and fragmented runtime behavior that will make an AppFlow port feel weak.

Replace it with three scheduling tiers:

### Tier A: Foreground Or Active-Live Coordinator

Add a long-lived coordinator, ideally a foreground service when live mode is enabled:

- `AppFlowCoordinatorService`
- `AppFlowCoordinator`

Responsibilities:

- poll lightweight memory state every 500 ms to 1 s when device is interactive
- pull recent usage deltas
- trigger prediction only when history changed or pressure changed
- compute preload, protection, reclaim mode, and kill-plan state together
- emit telemetry to `SystemStatusRepository`

### Tier B: Idle Maintenance Loop

Run a slower maintenance pass every 10 to 30 seconds when:

- screen is on but app is not foreground
- recent-use probability is high
- a protected preload window is still active

Responsibilities:

- keep short-lived protected preload sets alive
- decay stale predictions
- recompute kill deferment windows

### Tier C: WorkManager Fallback Only

Keep WorkManager, but demote it to:

- recovery after reboot
- daily compaction or export
- low-frequency heartbeat

It should no longer be the main decision loop.

## Phase 1: Trace-Driven Reproduction

This is the fastest path to something publishable and demoable in the current app.

### Data Plane

Add a launch-profile repository backed by JSON assets or local files:

- package-level file-size histogram
- hot small-file bytes
- hot large-file segments
- cutoff size candidates
- baseline memory after fresh launch
- current memory estimate after prolonged background residency

At first these values can come from:

- hand-built profiles for a small set of demo apps
- replay datasets
- offline analysis scripts

### Decision Plane

Implement `AppFlowPolicyEngine` or `AppFlowCoordinator`:

- take `PredictionBatch`
- read latest `MemorySnapshot`
- read app recency and last-seen times
- compute a preload budget and protected set
- compute a reclaim mode
- compute kill candidate ranking by `deltaMemory = currentMemory - relaunchBaseline`

### Execution Plane

In unprivileged mode, execution should be explicit about what is real versus simulated:

- real: collect usage, track recency, track memory headroom, record decisions, run replay, show dashboard state
- simulated: cross-app file preloading, file-page protection, cross-app process killing

This still has strong value because the paper's scheduler can be evaluated in replay and compared against the current threshold baseline.

## Phase 2: Selective File Preloader In This App

Implement the paper's two-phase idea even if the first version is profile-driven.

### Before-Launch Preload

For the top predicted app:

- preload all hot small files below an app-specific cutoff
- optionally preload hot segments of large files
- respect a global preload budget, starting with 100 MB to match the paper

Decision method:

- precompute candidate cutoff sizes per app
- store estimated memory cost `P(s_i)`
- store estimated throughput gain `V(s_i)`
- solve a small multiple-choice knapsack when choosing which profiles to preload

### During-Launch Streaming

When the predicted app is actually launched:

- stream remaining large segments with a low-priority worker
- treat this as a separate execution stage from prelaunch preload

In app sandbox mode, this may be a replay-time latency model or profile simulation. If you later move to root or AOSP, the same interface can drive real file prefetch.

## Phase 3: Adaptive Memory Reclaimer In This App

The paper uses kernel page-allocation rate plus free memory. We cannot read the true kernel signal from a normal app, so the app version should use a proxy.

Suggested app-side proxy:

- `availableMb` and `thresholdMb` from `MemoryMonitor`
- recent drop in available memory over the last few coordinator ticks
- whether a predicted launch window is active
- whether a preload window is active

Define three modes:

- `NORMAL`
- `EFFICIENCY_FIRST`
- `REBALANCE`

Suggested behavior:

- `NORMAL`: no aggressive preload protection
- `EFFICIENCY_FIRST`: suspend large preloads, keep only hot small-file preload sets protected, avoid adding new background work
- `REBALANCE`: let protected sets expire and reduce residency budgets

This does not reclaim pages directly, but it gives the repository the same control logic shape as the paper and prepares the code for privileged hooks later.

## Phase 4: Context-Aware Process Killer

This module should not start with actual killing. Start with ranking and evaluation.

For each background app tracked in replay or live mode:

- estimate current memory
- estimate relaunch baseline memory
- compute `deltaMemory = currentMemory - relaunchBaseline`
- track recency window, starting with 30 minutes from the paper

Decision rule:

- defer recently used apps
- rank older memory-bloated apps by descending `deltaMemory`
- expose both `killCandidates` and `deferredRecentApps` in telemetry

Execution options:

- unprivileged mode: recommendation only
- adb or rooted mode: optional force-stop for controlled experiments
- AOSP mode: replace or augment LMK ordering

## Concrete Repository Changes

The first real patch series should touch these areas.

### Modify

- `app/src/main/java/com/memoos/core/model/ResourceDecision.kt`
- `app/src/main/java/com/memoos/core/config/MemoConfig.kt`
- `app/src/main/java/com/memoos/data/repository/ConfigRepository.kt`
- `app/src/main/java/com/memoos/data/repository/SystemStatusRepository.kt`
- `app/src/main/java/com/memoos/orchestration/PolicyPipeline.kt`
- `app/src/main/java/com/memoos/ui/main/MemoGraph.kt`
- `app/src/main/java/com/memoos/worker/WorkScheduler.kt`
- `app/src/main/java/com/memoos/ui/main/MainActivity.kt`
- `app/src/main/java/com/memoos/system/prewarm/PrewarmController.kt`
- `app/src/main/java/com/memoos/system/memory/RetentionController.kt`

### Add

- `app/src/main/java/com/memoos/appflow/model/AppLaunchProfile.kt`
- `app/src/main/java/com/memoos/appflow/model/AppFlowDecision.kt`
- `app/src/main/java/com/memoos/appflow/model/PreloadPlan.kt`
- `app/src/main/java/com/memoos/appflow/model/MemoryPressureSnapshot.kt`
- `app/src/main/java/com/memoos/appflow/model/KillCandidate.kt`
- `app/src/main/java/com/memoos/appflow/profile/LaunchProfileRepository.kt`
- `app/src/main/java/com/memoos/appflow/scheduler/AppFlowCoordinator.kt`
- `app/src/main/java/com/memoos/appflow/service/AppFlowCoordinatorService.kt`
- `app/src/main/java/com/memoos/appflow/policy/AppFlowPolicyEngine.kt`
- `app/src/main/java/com/memoos/appflow/execution/AppFlowExecutionAdapter.kt`

## Evaluation Plan

To keep the repository aligned with the paper, compare three stages:

1. current threshold baseline
2. AppFlow logic in replay or simulated mode
3. privileged or rooted mode if available

Track at least:

- estimated cold-launch latency
- cold relaunch count
- protected preload hit rate
- preload waste ratio
- pressure-mode transitions
- deferred-kill count
- net freed memory score distribution

The replay path is especially important because it lets this repo show the scheduler logic even before privileged hooks exist.

## Practical Recommendation

If the goal is a strong course or demo project, stop at:

- event-driven coordinator
- profile-driven two-phase preload planning
- pressure-mode state machine
- kill-candidate ranking
- replay evaluation against the current baseline

If the goal is a close reproduction of the paper itself, the app alone is not enough. You will need:

- root or custom ROM access
- Android framework hooks for preloading and kill control
- kernel support for page-type-aware reclaim and protected-page checks

## Best Next Step

The best immediate patch order for this repository is:

1. replace `WorkScheduler` as the main runtime brain with an event-driven AppFlow coordinator
2. add richer AppFlow models and telemetry
3. implement profile-driven selective preloading in replay mode
4. add pressure modes and kill-candidate ranking
5. only then decide whether to invest in rooted or AOSP-level reproduction

This order improves the current app even before any privileged integration, and it fixes the weakest current point: background scheduling that is too coarse for a system-style runtime optimizer.
