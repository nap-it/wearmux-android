#!/usr/bin/env python3
"""
watch_test.py — inject watch notifications into PECI WearableService via ADB.

Usage:
    python scripts/watch_test.py              # interactive menu
    python scripts/watch_test.py --sequence   # auto-run all scenarios in order
    python scripts/watch_test.py -d <serial>  # target a specific device

Requires: adb on PATH, PECI app running on device (service must be started).
"""

import subprocess
import sys
import time
import argparse

PACKAGE = "com.example.peciwearables"
SERVICE = f"{PACKAGE}/.integration.WearableService"

# ── Intent action strings (must match WearableService companion object) ──────
ACTION_WATCH_NOTIFY  = f"{PACKAGE}.WATCH_NOTIFY"
ACTION_WATCH_VIBRATE = f"{PACKAGE}.WATCH_VIBRATE"
ACTION_WATCH_BEEP    = f"{PACKAGE}.WATCH_BEEP"

# ── notify_type values (WatchProtocol.NotifyType) ───────────────────────────
NOTIFY_WARNING = 0
NOTIFY_DANGER  = 1
NOTIFY_SAFE    = 2

# ── VibratePattern values (WatchProtocol.VibratePattern) ────────────────────
PAT_SHORT     = 0
PAT_DOUBLE    = 1
PAT_LONG      = 2
PAT_TRIPLE    = 3
PAT_HEARTBEAT = 4
PAT_ALARM     = 5


def adb_available() -> bool:
    """Return True if adb is reachable on PATH."""
    try:
        r = subprocess.run(["adb", "version"], capture_output=True, timeout=5)
        return r.returncode == 0
    except FileNotFoundError:
        return False


def adb_devices() -> list[str]:
    """Return list of connected device serials (online only)."""
    r = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=10)
    serials = []
    for line in r.stdout.splitlines()[1:]:
        parts = line.strip().split()
        if len(parts) == 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


RECEIVER = "com.example.peciwearables/.DebugBroadcastReceiver"

def adb_startservice(action: str, extras: list[str], device: str | None = None) -> bool:
    """
    Run: adb [-s device] shell am broadcast -a <action> -n <RECEIVER> [extras...]
    Uses broadcast via DebugBroadcastReceiver (exported=true) which forwards
    to WearableService internally. The explicit -n component is required on
    Android 14+ — implicit broadcasts to exported receivers are not delivered.
    extras format: ["-e", "key", "value", "--ei", "key2", "42", ...]
    """
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    cmd += [
        "shell", "am", "broadcast",
        "-a", action,
        "-n", RECEIVER,
    ] + extras

    r = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    ok = r.returncode == 0 and "Error" not in r.stdout
    if not ok:
        stderr = (r.stderr or r.stdout).strip()
        print(f"  ⚠  ADB error: {stderr or 'unknown'}")
    return ok


# ── Safety scenario dispatchers ───────────────────────────────────────────────

def dispatch_approach(device: str | None, distance_m: int = 8) -> None:
    """UC1.1 pre-alert: pedestrian approaching crosswalk. WARNING (orange)."""
    _log("📍 approach", f"Crosswalk in {distance_m} m")
    adb_startservice(ACTION_WATCH_VIBRATE, ["--ei", "vibrate_pattern", str(PAT_SHORT)], device)
    time.sleep(0.3)
    adb_startservice(ACTION_WATCH_BEEP, [
        "--ei", "tone_freq_hz", "660",
        "--ei", "tone_duration_ms", "120",
    ], device)
    time.sleep(0.3)
    adb_startservice(ACTION_WATCH_NOTIFY, [
        "--ei", "notify_type", str(NOTIFY_WARNING),
        "-e",  "watch_notify_title", f"Crosswalk in {distance_m} m",
        "-e",  "watch_notify_body",  "Look both ways before crossing.",
    ], device)


def dispatch_danger(device: str | None) -> None:
    """UC1.1 danger: pedestrian in zone, didn't look. DANGER (red) + ALARM vibration."""
    _log("🔴 danger", "Warning!")
    adb_startservice(ACTION_WATCH_NOTIFY, [
        "--ei", "notify_type", str(NOTIFY_DANGER),
        "-e",  "watch_notify_title", "Warning!",
        "-e",  "watch_notify_body",  "Approaching the crosswalk and you did not look — cross carefully.",
    ], device)


def dispatch_safe_to_cross(device: str | None) -> None:
    """UC1.1/1.3 safe: pedestrian looked both ways. SAFE (green 👍)."""
    _log("✅ safe_to_cross", "Safe to cross")
    adb_startservice(ACTION_WATCH_BEEP, [
        "--ei", "tone_freq_hz", "880",
        "--ei", "tone_duration_ms", "160",
    ], device)
    time.sleep(0.3)
    adb_startservice(ACTION_WATCH_VIBRATE, ["--ei", "vibrate_pattern", str(PAT_DOUBLE)], device)
    time.sleep(0.3)
    adb_startservice(ACTION_WATCH_NOTIFY, [
        "--ei", "notify_type", str(NOTIFY_SAFE),
        "-e",  "watch_notify_title", "Safe to cross",
        "-e",  "watch_notify_body",  "You looked both ways. You can cross carefully.",
    ], device)


def dispatch_crossing_wait(device: str | None) -> None:
    """UC1.3 blocked: vehicle detected. WARNING (orange) + LONG vibration."""
    _log("⏳ crossing_wait", "Vehicle detected")
    adb_startservice(ACTION_WATCH_BEEP, [
        "--ei", "tone_freq_hz", "420",
        "--ei", "tone_duration_ms", "600",
    ], device)
    time.sleep(0.3)
    adb_startservice(ACTION_WATCH_VIBRATE, ["--ei", "vibrate_pattern", str(PAT_LONG)], device)
    time.sleep(0.3)
    adb_startservice(ACTION_WATCH_NOTIFY, [
        "--ei", "notify_type", str(NOTIFY_WARNING),
        "-e",  "watch_notify_title", "Wait",
        "-e",  "watch_notify_body",  "Vehicle detected on the crosswalk.",
    ], device)


# ── Isolated hardware dispatchers ─────────────────────────────────────────────

def dispatch_vibrate(device: str | None, pattern: int) -> None:
    pattern_names = {
        PAT_SHORT: "SHORT", PAT_DOUBLE: "DOUBLE", PAT_LONG: "LONG",
        PAT_TRIPLE: "TRIPLE", PAT_HEARTBEAT: "HEARTBEAT", PAT_ALARM: "ALARM",
    }
    _log("📳 vibrate", pattern_names.get(pattern, str(pattern)))
    adb_startservice(ACTION_WATCH_VIBRATE, ["--ei", "vibrate_pattern", str(pattern)], device)


def dispatch_beep(device: str | None, freq_hz: int = 880, duration_ms: int = 250) -> None:
    _log("🔔 beep", f"{freq_hz} Hz / {duration_ms} ms")
    adb_startservice(ACTION_WATCH_BEEP, [
        "--ei", "tone_freq_hz", str(freq_hz),
        "--ei", "tone_duration_ms", str(duration_ms),
    ], device)


# ── Helpers ───────────────────────────────────────────────────────────────────

def _log(label: str, detail: str) -> None:
    ts = time.strftime("%H:%M:%S")
    print(f"  [{ts}] {label}  —  {detail}")


# ── Menu ──────────────────────────────────────────────────────────────────────

MENU = """
╔══════════════════════════════════════════╗
║   PECI Watch Notification Tester         ║
╠══════════════════════════════════════════╣
║  Scenarios (match SafetyOutputs):        ║
║   1  approach        (⚠️  WARNING orange) ║
║   2  danger          (🔴 DANGER  red)    ║
║   3  safe_to_cross   (✅ SAFE    green)  ║
║   4  crossing_wait   (⏳ WARNING orange) ║
╠══════════════════════════════════════════╣
║  Isolated hardware:                      ║
║   5  vibrate         (choose pattern)    ║
║   6  beep            (880 Hz / 420 Hz)   ║
╠══════════════════════════════════════════╣
║   s  run sequence    (1→2→3→4 auto)      ║
║   q  quit                                ║
╚══════════════════════════════════════════╝
"""

VIBRATE_SUBMENU = """
  Vibration patterns:
    0  SHORT      1  DOUBLE    2  LONG
    3  TRIPLE     4  HEARTBEAT 5  ALARM
  Choice: """


def run_menu(device: str | None) -> None:
    print(MENU)
    while True:
        try:
            choice = input("Choice: ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("\nBye.")
            break

        if choice == "q":
            print("Bye.")
            break
        elif choice == "1":
            dispatch_approach(device)
        elif choice == "2":
            dispatch_danger(device)
        elif choice == "3":
            dispatch_safe_to_cross(device)
        elif choice == "4":
            dispatch_crossing_wait(device)
        elif choice == "5":
            try:
                pat = int(input(VIBRATE_SUBMENU).strip())
                dispatch_vibrate(device, pat)
            except ValueError:
                print("  Invalid pattern.")
        elif choice == "6":
            print("  Beep: 1=880Hz(safe)  2=660Hz(approach)  3=420Hz(wait)")
            freq_map = {"1": 880, "2": 660, "3": 420}
            f = freq_map.get(input("  Choice: ").strip(), 880)
            dispatch_beep(device, f)
        elif choice == "s":
            run_sequence(device)
        else:
            print("  Unknown choice — try 1-6, s, or q.")


def run_sequence(device: str | None, delay: float = 4.0) -> None:
    """Fire all 4 scenarios in order with `delay` seconds between them."""
    print(f"\n▶  Running full sequence (delay={delay}s between steps)…\n")
    steps = [
        ("approach",       lambda: dispatch_approach(device)),
        ("danger",         lambda: dispatch_danger(device)),
        ("safe_to_cross",  lambda: dispatch_safe_to_cross(device)),
        ("crossing_wait",  lambda: dispatch_crossing_wait(device)),
    ]
    for i, (name, fn) in enumerate(steps, 1):
        print(f"  Step {i}/{len(steps)}: {name}")
        fn()
        if i < len(steps):
            print(f"  … waiting {delay}s …")
            time.sleep(delay)
    print("\n✓  Sequence complete.\n")


# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Inject PECI watch notifications via ADB for manual testing.",
    )
    parser.add_argument(
        "-d", "--device",
        metavar="SERIAL",
        help="ADB device serial (default: auto-detect single connected device)",
    )
    parser.add_argument(
        "--sequence",
        action="store_true",
        help="Non-interactive: fire all 4 scenarios in order and exit.",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=4.0,
        metavar="SECS",
        help="Seconds between steps in --sequence mode (default: 4.0)",
    )
    args = parser.parse_args()

    # ── Preflight ─────────────────────────────────────────────────────────────
    if not adb_available():
        print("ERROR: 'adb' not found on PATH. Install Android SDK platform-tools.")
        sys.exit(1)

    device = args.device
    if device is None:
        devices = adb_devices()
        if len(devices) == 0:
            print("ERROR: No ADB devices connected. Connect a device or start an emulator.")
            sys.exit(1)
        if len(devices) > 1:
            print(f"Multiple devices found: {devices}")
            print("Use -d <serial> to specify one.")
            sys.exit(1)
        device = devices[0]

    print(f"  Using device: {device}")
    print("  ⚠  Make sure the PECI app is open and WearableService is running.")
    print("  ⚠  Galaxy Watch must be paired and nearby.\n")

    # ── Dispatch ──────────────────────────────────────────────────────────────
    if args.sequence:
        run_sequence(device, delay=args.delay)
    else:
        run_menu(device)


if __name__ == "__main__":
    main()
