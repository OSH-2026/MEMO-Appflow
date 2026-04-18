## About Platforms
Because `place-bot` uses `Microsoft Windows` but `Java_Herobrine` uses `Kali Linux`, there are some tips for the scripts:

### Linux only

- trace_exec.bt (it's a `bpftrace` script, which can only be run in Linux)

### Unix-like only

- trace_exec.d (it's a `dtrace` script, only unix-like systems like macOS & Linux support `dtrace`)

### Cross-platform scripts

Oh my god, powershell is an open-source and cross-platform shell!

- analyze\_replay\_results.ps1
- seed\_sample\_dataset.ps1
