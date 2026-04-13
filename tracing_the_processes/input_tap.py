import time
from Quartz import (
    CGEventTapCreate,
    CGEventTapEnable,
    kCGSessionEventTap,
    kCGHeadInsertEventTap,
    kCGEventTapOptionListenOnly,
    CGEventMaskBit,
    kCGEventKeyDown,
    kCGEventLeftMouseDown,
    kCGEventRightMouseDown,
    kCGEventOtherMouseDown,
)
from CoreFoundation import (
    CFMachPortCreateRunLoopSource,
    CFRunLoopAddSource,
    CFRunLoopGetCurrent,
    kCFRunLoopCommonModes,
    CFRunLoopRun,
)

LOGFILE = "/tmp/input_log.tsv"

def callback(proxy, event_type, event, refcon):
    ts_ns = time.time_ns()
    with open(LOGFILE, "a") as f:
        f.write(f"{ts_ns}\t{event_type}\n")
    return event

def main():
    mask = (
        CGEventMaskBit(kCGEventKeyDown)
        | CGEventMaskBit(kCGEventLeftMouseDown)
        | CGEventMaskBit(kCGEventRightMouseDown)
        | CGEventMaskBit(kCGEventOtherMouseDown)
    )

    tap = CGEventTapCreate(
        kCGSessionEventTap,
        kCGHeadInsertEventTap,
        kCGEventTapOptionListenOnly,
        mask,
        callback,
        None,
    )

    if tap is None:
        raise RuntimeError(
            "CGEventTapCreate failed. Grant Input Monitoring permission in "
            "System Settings > Privacy & Security > Input Monitoring."
        )

    source = CFMachPortCreateRunLoopSource(None, tap, 0)
    CFRunLoopAddSource(CFRunLoopGetCurrent(), source, kCFRunLoopCommonModes)
    CGEventTapEnable(tap, True)
    CFRunLoopRun()

if __name__ == "__main__":
    main()