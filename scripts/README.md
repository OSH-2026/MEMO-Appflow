# Scripts

The scripts directory now only keeps build/deploy helpers that support the
Android-device product path. Runtime collection, parsing, MAPLE inference,
Top-3 mapping, scheduling, and widget updates run inside the Android app.

Primary entry points:

- `build_memo_libbpf.sh`

The old host-side adb/Python collectors and bpftrace programs were removed.
The host should install the APK, push model/native artifacts, and pull logs;
it should not own product logic.
