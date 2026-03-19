# Metrics

MEMO-OS records both prediction quality and system-facing decision statistics.

## Prediction Metrics

- `Hit@1`
- `Hit@3`
- `MRR`

These come from `ExperimentRecord` entries produced by replay or online execution.

## System-Facing Decision Metrics

- `keepAliveCount`
- `prewarmCount`
- `hintCount`
- optional launch latency
- optional memory snapshot reference
- optional battery snapshot reference

These matter because MEMO-OS is not just scoring predictions. It is turning predictions into system-facing resource intent.

## Export Columns

Exports are stable enough for later analysis scripts and ablation studies. Current CSV and JSON exports include:

- `mode`
- `datasetName`
- `predictorName`
- `policyName`
- `predictedTop3`
- `actualNextApp`
- `hitAt1`
- `hitAt3`
- `predictionTimestamp`
- `observationTimestamp`
- `keepAliveCount`
- `prewarmCount`
- `hintCount`
- `launchLatencyMs`
- `memorySnapshotRef`
- `batterySnapshotRef`

## Export Paths

Replay-mode exports:

- `files/dataset_cache/exports/*.csv`
- `files/dataset_cache/exports/*.json`

Dashboard-triggered aggregate exports:

- `files/exports/experiments.csv`
- `files/exports/experiments.json`

## Interpretation

Prediction metrics tell us how well the next-app model is doing.

Decision metrics tell us how aggressively the current policy is trying to influence the system-facing layer.

Both are needed. A systems project is not complete if it only reports recommendation accuracy without showing what those predictions caused the system to attempt.
