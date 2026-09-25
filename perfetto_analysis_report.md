# Mobile idle CPU investigation

## Question and reproduction

Investigated why the Android app keeps using CPU while the online game board is
idle, and whether that workload can explain slow selection and moves.

The connected device was a Samsung SM-S908E (Galaxy S22 Ultra) on Android 16.
The foreground activity was `com.chesstree.app/.MainActivity`; the app showed an
active online game at revision 27. No taps were made during the idle capture.

## Measurements

Before the trace, three `adb shell top` samples for PID 2219 reported 122%,
73.6%, and 123% CPU. The Perfetto CPU profile sampled for 15 seconds and
contained 1,350 samples over 14.98 seconds:

- Main/UI thread (TID 2219): 1,040 samples, about 10.4 sampled CPU-seconds.
- Worker thread (TID 2229): 292 samples, about 2.92 sampled CPU-seconds.
- All app threads: about 13.5 sampled CPU-seconds, or roughly 90% of one core
  over the capture window. This is a sampling estimate, not a wall-clock timer.

The raw trace is `/tmp/chesstree-perf/android-idle/raw-trace`.

## Finding

The dominant sampled path on the main thread was:

`MultiplayerController.refreshGame` → `withRemoteState` →
`GameStateResponse.toSession` → `GameSession.replay` → `GameReducer.reduce` →
`LegalMoveGenerator.legalMoves`.

`GameSession.replay` appeared on 1,017 of 1,040 main-thread samples, and
`refreshGame` on 1,018. The trace also shows repeated work in
`ThreePlayerBoardTopology.diagonalRays` and `orthogonalNeighbours` during legal
move generation, plus ART allocation/garbage-collection activity.

The online screen polls active games every five seconds. Each successful remote
state is passed to `withRemoteState`, which rebuilds the whole local session by
replaying its move history. It rejects older revisions, but it does not skip a
remote state with the same revision. The profiler therefore captures full game
replays while the user is idle. That repeated synchronous rules work and its
allocations are the best-supported cause of the sustained CPU use and likely
also make taps and moves wait behind computation.

This evidence is for the active online game path. It does not establish that the
same code is responsible for delays in a local/offline game, or by itself
quantify the iOS slowdown.

## Reproduction commands

```sh
adb shell pidof com.chesstree.app
adb shell top -b -n 3 -p 2219
/tmp/chesstree-perf/cpu_profile -n com.chesstree.app -d 15000 \
  -o /tmp/chesstree-perf/android-idle
```

The helper's automatic profile conversion reported “No profiles generated”,
but it saved a valid raw Perfetto trace. The trace was loaded with
`trace_processor`; the following queries validated the sample count and hot
path:

```sql
INCLUDE PERFETTO MODULE stacks.cpu_profiling;
SELECT COUNT(*) AS app_profile_samples
FROM cpu_profiling_samples s
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.chesstree.app';

SELECT t.tid, t.is_main_thread, COUNT(*) AS samples
FROM perf_sample s
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.chesstree.app'
GROUP BY t.tid, t.is_main_thread
ORDER BY samples DESC;

INCLUDE PERFETTO MODULE stacks.cpu_profiling;
WITH RECURSIVE ancestors(sample_id, callsite_id, frame_id) AS (
  SELECT s.id, s.callsite_id, c.frame_id
  FROM cpu_profiling_samples s
  JOIN thread t USING (utid)
  JOIN process p USING (upid)
  JOIN stack_profile_callsite c ON c.id = s.callsite_id
  WHERE p.name = 'com.chesstree.app' AND t.is_main_thread = 1
  UNION ALL
  SELECT a.sample_id, parent.id, parent.frame_id
  FROM ancestors a
  JOIN stack_profile_callsite current ON current.id = a.callsite_id
  JOIN stack_profile_callsite parent ON parent.id = current.parent_id
)
SELECT f.name, COUNT(DISTINCT a.sample_id) AS samples
FROM ancestors a
JOIN stack_profile_frame f ON f.id = a.frame_id
WHERE f.name GLOB '*GameSession*replay*'
   OR f.name GLOB '*refreshGame*'
   OR f.name GLOB '*withRemoteState*'
   OR f.name GLOB '*toSession*'
   OR f.name GLOB '*LegalMoveGenerator*'
   OR f.name GLOB '*ThreePlayerBoardTopology*'
GROUP BY f.name
ORDER BY samples DESC;
```

## iOS and limits

The iPhone 16 Pro was connected. `xctrace` could not attach directly by app
name or PID, so an all-process 16.14-second Time Profiler capture was used as a
fallback. It contained only one sample for ChessTree, which is not enough to
attribute CPU time. No iOS performance conclusion is drawn from that capture.

The follow-up optimization is implemented in
`composeApp/src/commonMain/kotlin/com/chesstree/multiplayer/presentation/MultiplayerController.kt`:
unchanged move histories reuse the current session, appended history applies
only the new moves, and a full replay remains as the fallback for shortened or
divergent histories (such as a completed undo). This profile predates the
optimization; a new capture is needed to quantify its effect.
