# Dataset

Public dataset support is a first-class feature in MEMO-Appflow, not an optional add-on.

## Supported Sources

- public datasets described by manifest
- local replay logs
- online device or emulator usage traces

All three sources must normalize into the same internal schema before reaching predictor, policy, or evaluation code:

- `AppEvent`

## Registry And Manifest Flow

`DatasetRegistry` stores known manifests such as:

- `sample_local`
- `sample_public_json`
- `lsapp_placeholder`

Each manifest specifies:

- dataset name
- source type
- URL and/or local path
- cache file name
- format
- parser key
- normalizer key

The downloader and parser are therefore selected by manifest metadata rather than predictor-specific logic.

## Download / Cache / Normalize Flow

The formal dataset path is:

`manifest lookup -> cache resolution -> local copy or download -> parser -> normalizer -> AppEvent stream`

Key classes:

- `data/dataset/DatasetRegistry.kt`
- `data/dataset/PublicDatasetManager.kt`
- `data/ingestion/PublicDatasetDownloader.kt`
- `data/ingestion/PublicDatasetParser.kt`
- `data/ingestion/PublicDatasetNormalizer.kt`

## Supported Formats

- CSV via `SimpleUsageCsvParser` and `SimpleUsageCsvNormalizer`
- JSON via `SimpleUsageJsonParser` and `SimpleUsageJsonNormalizer`

Dataset-specific raw fields remain isolated in ingestion classes. Predictor and policy code never depend on raw external columns.

## Emulator / Device Reality

Desktop-side sample files under the repository root are useful during development, but the emulator cannot read those workspace-relative paths directly. To keep replay mode actually usable on Android:

- sample datasets are bundled in `app/src/main/assets/dataset_cache/`
- `MemoGraph.create(...)` seeds them into `files/dataset_cache/`
- the registry overrides `sample_local` and `sample_public_json` to point at those seeded files on emulator or device

This means replay mode works in the actual Android runtime environment instead of only in the desktop workspace.

## Replay Consistency

Offline replay uses the same:

- `Predictor`
- `PolicyEngine`
- `EvaluationPipeline`

as online mode. The benchmark task and live task therefore share the same normalized schema and decision semantics.

## Splits

`DatasetSplitManager` creates:

- train
- validation
- test

These splits make offline benchmarking reproducible and ready for later ablation studies.

## Storage Locations

Desktop development samples:

- `dataset_cache/sample_usage.csv`
- `dataset_cache/sample_usage.json`

Runtime sample copies on emulator or device:

- `files/dataset_cache/sample_usage.csv`
- `files/dataset_cache/sample_usage.json`

Replay exports:

- `files/dataset_cache/exports/*.csv`
- `files/dataset_cache/exports/*.json`
