# Real eBPF Ablation Result Interpretation

Date: 2026-05-07

Experiment track owner: Jingyi Guo.

Main result file:

```text
docs/real_device_experiments/real_ebpf_ablation/latest_real_ablation.json
```

This report was generated on the Android emulator from the latest real eBPF camera/photo scenario. It is not a synthetic dataset run and not a host Python prediction benchmark.

## What The Ablation Tests

The full configuration uses the whole real eBPF scenario:

```text
full_real_ebpf
```

Each ablation removes one evidence family and reruns MAPLE:

| Config | Removed or changed evidence |
| --- | --- |
| `no_network` | UDP `sendto` / `recvfrom` and Network IO evidence |
| `no_camera_media` | Camera Service and Media Codec evidence |
| `no_display_ui` | SurfaceFlinger, RenderThread, input/display evidence |
| `no_binder_service` | Binder and Android service evidence |
| `no_memory` | Memory pressure, reclaim, LMKD and thermal-memory hints |
| `counters_only` | Keeps aggregate counters but removes target/workflow hints |
| `app_sequence_baseline` | Removes eBPF/system evidence and keeps only category/app sequence context |

The goal is to measure whether eBPF evidence changes MAPLE's prediction and the downstream scheduling plan.

## Metrics

| Metric | Meaning |
| --- | --- |
| task-domain hit rate | For this camera/photo run, whether predicted domains cover the expected `camera_media`, `display_ui`, and `network` resource needs |
| predicted app id | MAPLE Stage 2 app id |
| Stage 1 category | MAPLE's resource/app category reasoning result |
| Top-1 app | First real Android launchable app after adaptive mapping |
| Top-3 overlap | Jaccard overlap with `full_real_ebpf` Top-3 |
| scheduler domain alignment | Whether predicted resource domains match planned action domains |
| MAPLE latency | Device-side MAPLE inference time |
| end-to-end latency | MAPLE + app mapping + non-intrusive action planning |

## Does eBPF Make It Better?

For this camera/photo real-user run, yes for prediction quality and scheduling relevance, and also faster than the app-sequence baseline. The claim should stay precise:

- eBPF changes the result from "current camera-like app" toward follow-up resource demand: `Photos -> Chrome -> Camera`.
- eBPF keeps display/network/camera/service evidence available, so scheduling actions cover UI, network, camera/media, Binder/service and memory policy.
- Full eBPF is faster than the sequence-only baseline in this run, but it is not the fastest of every ablation variant. Some reduced prompts are faster because they remove information.

## Full eBPF vs App-Sequence Baseline

| Metric | App-sequence baseline | Full real eBPF | Change |
| --- | ---: | ---: | ---: |
| MAPLE latency | 69.104s | 36.468s | -32.636s, 47.2% faster |
| End-to-end latency | 74.343s | 41.991s | -32.352s, 43.5% faster |
| Stage 1 category | Camera Service | Display Composition | changed |
| Predicted app id | 25 | 115 | changed |
| Top-1 real app | `com.android.camera2` | `com.google.android.apps.photos` | changed |
| Task-domain hit rate | 100.0% | 100.0% | tied |
| Full-eBPF prediction stability | 0.0% Stage 1 overlap | 100.0% Stage 1 overlap | full eBPF reference |

Interpretation:

The baseline sees the camera usage sequence and predicts Camera again. Full eBPF sees the deeper system context: Display Composition is dominant while camera/media and network are also present. After adaptive app mapping, the product recommends Photos first, then Chrome, then Camera. That is more useful for a camera/photo workflow because the next action after taking or viewing media is often viewing/sharing/browsing, not simply reopening the same camera app.

There is no manually labeled "next app" ground truth for this real-user run, so we should not report a fake supervised accuracy number. The closest meaningful accuracy-style metric is task-domain hit rate: whether a configuration predicts the expected resource domains for a real camera/photo interaction. Under that metric, several configurations tie at 100.0%. To separate them, we also report stability against the full real-eBPF result, because full eBPF is the most complete evidence condition.

## Per-Variant Results

| Config | Predicted id | Stage 1 | Top-1 app | Task-domain hit | MAPLE latency | End-to-end | Top-3 overlap | Scheduler-domain overlap |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| `full_real_ebpf` | 115 | Display Composition | `com.google.android.apps.photos` | 100.0% | 36.468s | 41.991s | 1.00 | 1.00 |
| `no_network` | 115 | Display Composition | `com.google.android.apps.photos` | 66.7% | 41.698s | 45.174s | 0.50 | 0.89 |
| `no_camera_media` | 115 | Display Composition | `com.google.android.apps.photos` | 66.7% | 27.747s | 30.135s | 0.50 | 0.89 |
| `no_display_ui` | 25 | Camera Service | `com.android.camera2` | 66.7% | 67.407s | 72.949s | 0.50 | 0.89 |
| `no_binder_service` | 25 | Camera Service | `com.android.camera2` | 100.0% | 55.533s | 59.676s | 1.00 | 0.89 |
| `no_memory` | 115 | Display Composition | `com.google.android.apps.photos` | 100.0% | 30.234s | 32.530s | 1.00 | 1.00 |
| `counters_only` | 115 | Display Composition | `com.google.android.apps.photos` | 100.0% | 36.416s | 41.324s | 1.00 | 1.00 |
| `app_sequence_baseline` | 25 | Camera Service | `com.android.camera2` | 100.0% | 69.104s | 74.343s | 1.00 | 1.00 |

Best configurations by metric:

| Metric | Best config(s) | Value |
| --- | --- | ---: |
| Task-domain hit rate | `full_real_ebpf`, `no_binder_service`, `no_memory`, `counters_only`, `app_sequence_baseline` | 100.0% |
| Stage1 stability vs full eBPF | `full_real_ebpf`, `no_network`, `no_camera_media`, `no_memory`, `counters_only` | 100.0% overlap |
| Top-1 stability vs full eBPF | `full_real_ebpf`, `no_network`, `no_camera_media`, `no_memory`, `counters_only` | same Top-1 |
| Top-3 overlap vs full eBPF | `full_real_ebpf`, `no_binder_service`, `no_memory`, `counters_only`, `app_sequence_baseline` | 1.00 |
| Lowest MAPLE latency | `no_camera_media` | 27.747s |
| Lowest end-to-end latency | `no_camera_media` | 30.135s |

Accuracy interpretation: if we only ask "did it cover the broad expected camera/photo resource domains", several configs tie. If we ask "did it preserve the full-eBPF product prediction", `full_real_ebpf`, `no_memory`, and `counters_only` are strongest overall; `no_network` and `no_camera_media` keep the same Top-1 but lose Top-3 coverage. `no_display_ui`, `no_binder_service`, and `app_sequence_baseline` are weaker because they change Stage 1 and Top-1 away from the full-eBPF result.

## Important Deltas Against Full eBPF

| Removed evidence | Prediction impact | Latency impact |
| --- | --- | --- |
| Network | prediction id unchanged; Top-3 overlap drops from 1.00 to 0.50 | MAPLE +5.230s (+14.3%); end-to-end +3.183s (+7.6%) |
| Camera/media | prediction id unchanged; Top-3 overlap drops from 1.00 to 0.50 | MAPLE -8.721s (-23.9%); end-to-end -11.856s (-28.2%) |
| Display/UI | prediction changes from id 115 to 25; Stage 1 overlap drops from 1.00 to 0.00 | MAPLE +30.939s (+84.8%); end-to-end +30.958s (+73.7%) |
| Binder/service | prediction changes from id 115 to 25; Stage 1 overlap drops from 1.00 to 0.00 | MAPLE +19.065s (+52.3%); end-to-end +17.685s (+42.1%) |
| Memory | prediction unchanged; scheduler-domain overlap remains 1.00 | MAPLE -6.234s (-17.1%); end-to-end -9.461s (-22.5%) |
| Counters only | prediction unchanged in this case | MAPLE -0.052s (-0.1%); end-to-end -0.667s (-1.6%) |
| App-sequence baseline | prediction changes from id 115 to 25; Stage 1 overlap drops from 1.00 to 0.00 | MAPLE +32.636s (+89.5%); end-to-end +32.352s (+77.0%) |

## What The Results Mean

Display/UI evidence and Binder/service evidence are the most important for this specific run. Removing either one changes the predicted category from Display Composition to Camera Service, and changes the first user-facing app from Photos to Camera.

Network and camera/media evidence do not change the predicted id in this run, but they change the Top-3 composition and the scheduling domain profile. They matter for the product because they influence whether the system plans network, camera/media, and UI-related actions.

Memory evidence does not change the prediction in this run. Its role is mostly scheduling safety: it controls how aggressive warm launch, trim-memory, idle maintenance, and cache actions should be.

The sequence-only baseline is not enough for our goal. It predicts Camera, which is plausible as a next-app classifier, but it loses the deeper system context that tells the product to prepare photo/display/network follow-up resources.

## Bottom Line

Adding real eBPF evidence improves the product signal in this run:

- prediction changes from `Camera Service / com.android.camera2` to `Display Composition / com.google.android.apps.photos`;
- end-to-end latency improves from 74.343s to 41.991s, a 43.5% reduction;
- MAPLE latency improves from 69.104s to 36.468s, a 47.2% reduction;
- removing Display/UI or Binder/service evidence breaks the full-eBPF prediction, which shows those eBPF families are carrying important information.

The result should not be overstated as "eBPF always makes every metric better." The correct conclusion is: in this real Android camera/photo scenario, eBPF gives better resource-aware prediction and scheduling context than the app-sequence baseline, while also reducing the measured MAPLE/end-to-end latency versus that baseline.
