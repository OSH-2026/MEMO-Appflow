# LSApp

Title: `LSApp: Large dataset of Sequential mobile App usage`

Original repository:
- https://github.com/aliannejadi/LSApp

What it contains:
- A compressed dataset file `lsapp.tsv.gz`
- Main table file `lsapp.tsv`
- Sequential mobile app usage events from a user study

Reported dataset statistics:
- `599,635` app usage records
- `76,247` sessions
- `87` unique apps
- `292` users
- Mean duration per user: `15 days`

Schema reported by the source repository:
- `user_id`
- `session_id`
- `timestamp`
- `app_name`
- `event_type`

Event types reported by the source repository:
- `Opened`
- `Closed`
- `User Interaction`
- `Broken`

Why it matters for MEMO-Appflow:
- LSApp is a strong fit for our offline replay and benchmark track.
- It provides real sequential app usage events suitable for prediction evaluation.
- It can be normalized into our internal `AppEvent` schema before entering replay, predictor, policy, and evaluation pipelines.

Compatibility note:
- LSApp uses `app_name`, not Android package names.
- That makes it ideal for offline public-dataset replay.
- It is not a direct replacement for online device-mode execution that expects real launchable Android packages.

Suggested mapping into MEMO-Appflow:
- `timestamp` -> `AppEvent.timestamp`
- `app_name` -> normalized internal app identifier
- `user_id` -> metadata
- `session_id` -> metadata
- `event_type` -> filter or metadata, with `Opened` as the primary event for next-app prediction

Project note:
- This repository stores the reference and integration note only.
- Use the original LSApp repository for the upstream dataset file and documentation.

Sources:
- Repository: https://github.com/aliannejadi/LSApp
