import time
import subprocess
from collections import deque
from pathlib import Path

INPUT_LOG = Path("/tmp/input_log.tsv")
EXEC_LOG = Path("/tmp/exec_log.tsv")

WINDOW_NS = 300_000_000  # 300 ms

EXCLUDE_EXECS = {
    "launchd",
    "xpcproxy",
}

def get_command_for_pid(pid: int) -> str:
    """
    Best-effort full command lookup using ps.
    Returns '<unknown>' if the process already exited or lookup failed.
    """
    try:
        result = subprocess.run(
            ["ps", "-p", str(pid), "-o", "command="],
            capture_output=True,
            text=True,
            timeout=1.0,
            check=False,
        )
        cmd = result.stdout.strip()
        if cmd:
            return cmd
    except Exception:
        pass
    return "<unknown>"

def main():
    recent_inputs = deque()
    seen_pids = set()

    input_file = open(INPUT_LOG, "r")
    exec_file = open(EXEC_LOG, "r")
    input_file.seek(0, 2)
    exec_file.seek(0, 2)

    try:
        while True:
            got_any = False

            # Read new input events
            while True:
                pos = input_file.tell()
                line = input_file.readline()
                if not line:
                    input_file.seek(pos)
                    break
                got_any = True
                line = line.strip()
                if not line:
                    continue

                parts = line.split("\t")
                if len(parts) < 2:
                    continue

                try:
                    ts_ns = int(parts[0])
                except ValueError:
                    continue

                recent_inputs.append(ts_ns)

            # Read new exec events
            while True:
                pos = exec_file.tell()
                line = exec_file.readline()
                if not line:
                    exec_file.seek(pos)
                    break
                got_any = True
                line = line.strip()
                if not line:
                    continue

                parts = line.split("\t", 3)
                if len(parts) != 4:
                    continue

                ts_str, pid_str, ppid_str, execname = parts

                try:
                    ts_ns = int(ts_str)
                    pid = int(pid_str)
                    ppid = int(ppid_str)
                except ValueError:
                    continue

                if pid in seen_pids:
                    continue
                seen_pids.add(pid)

                if execname in EXCLUDE_EXECS:
                    continue

                # Discard stale input events
                while recent_inputs and recent_inputs[0] < ts_ns - WINDOW_NS:
                    recent_inputs.popleft()

                # Check whether this exec followed a recent input
                user_driven = False
                if recent_inputs:
                    dt = ts_ns - recent_inputs[-1]
                    if 0 <= dt <= WINDOW_NS:
                        user_driven = True

                if not user_driven:
                    continue

                command = get_command_for_pid(pid)

                print("PID  PPID COMMAND")
                print(f"{pid:<4} {ppid:<4} {command}")
                print()

            if not got_any:
                time.sleep(0.05)

    finally:
        input_file.close()
        exec_file.close()

if __name__ == "__main__":
    main()