# Chaos / monkey harness

Hunts **crashes and hangs**, not assertion failures. The regular `flows/` suite
checks that features *work*; this checks that the app *survives* abuse — rapid
tab/sheet churn, background/foreground mid-animation, random taps, rotation,
network flaps, and deep-link cold/warm cycling.

## Run it

```bash
# standalone (auto-detects booted iOS sim, or pass android)
.maestro/scripts/chaos.sh ios
.maestro/scripts/chaos.sh android --passes 3 --monkey 8000 --seed 42

# as part of the full hands-off run
.maestro/scripts/run_all.sh ios   --chaos
.maestro/scripts/run_all.sh android --chaos --soak 5   # also re-run flows 5x
```

`--passes N` cycles the whole Maestro battery N times (default 1). One pass =
40 nav + 30 lifecycle + 25 random-tap inner rounds. Intensity is scaled by the
runner, not by Maestro env vars — in Maestro 2.6 a flow's `env:` default beats
CLI `-e`, so the round counts are fixed in the flows and chaos.sh outer-loops.

Exit `0` = survived clean. Exit `1` = a crash/hang/ANR/new crash-report was
detected; the signature and log path are printed.

## What owns the verdict

**The device log + OS crash-report dirs — never a Maestro flow's pass/fail.**
The flows tap with `optional: true` everywhere, so a missed tap is a no-op. The
only things treated as findings:

| Platform | Signals scraped |
|----------|-----------------|
| Android  | `FATAL EXCEPTION`, `E AndroidRuntime`, `ANR in <pkg>`, tombstones, `signal N (SIG…)`, native crash; `adb monkey` CRASH/ANR; PSS growth >2.5× |
| iOS sim  | new `Local-*.ips` crash reports, `Fatal App Hang`, `EXC_BAD_ACCESS`, `SIGABRT/SIGSEGV`, `semaphore_wait_trap`, watchdog termination; app-died liveness check |

These map directly to the app's real crash history: main-dispatcher Fatal App
Hangs, the UIPasteboard watchdog hang, the deep-link `singleTask` warm-delivery
race, and SpringBoard churn.

## Platform coverage

| Phase | Android | iOS sim |
|-------|:-------:|:-------:|
| adb monkey (true random fuzz) | ✅ | — (no equivalent; `random_taps.yaml` approximates) |
| nav/sheet churn | ✅ | ✅ |
| lifecycle background/foreground | ✅ | ✅ |
| random taps / swipes | ✅ | ✅ |
| network flaps | ✅ | — (sim shares host network) |
| rotation thrash | ✅ | — (no reliable simctl hook) |
| deep-link cold + warm | ✅ | ✅ |

Network flaps and rotation are real-device / emulator-only — run on Android for
full coverage of those two classes.

## Flows

- `nav_churn.yaml` — tab + chat-sheet thrash (carousel-reload-on-Main hang class)
- `lifecycle_churn.yaml` — background/foreground mid-animation (warm-start + deep-link race)
- `random_taps.yaml` — pseudo-random taps/swipes/long-press ("monkey" for iOS)

`CHAOS_ROUNDS` controls repeat count per flow (default per-flow; overridden by
`chaos.sh --rounds` or `maestro test -e CHAOS_ROUNDS=N`).
