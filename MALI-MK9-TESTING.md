# Mortal Kombat (PS Vita) / Mali-G610 — experimental test builds

Hardware: Kinhank X5 Pro, RK3588S, Mali-G610 (Android / Vulkan).

## Observed baseline (V1, 2026-11-07)

- The game enters a fight and gameplay responds to input.
- Severe stretched / exploding polygon geometry remains with the optional
  `Experimental Mali geometry fix` switched both **on** and **off**.
- Visual corruption also affects special moves and X-ray sequences.
- This is not a verified fix; the first precision hypothesis was inconclusive.

## V2 changes to investigate

- Merge upstream Vita3K+ `all-enhancements` (2026-11-06) into a separate branch.
- Include upstream vertex-store emulation and other recent Mali-specific changes.
- Retain the optional full-precision switch for controlled A/B comparisons.
- Keep shader-cache keys separate for vertex-store and precision variants.

The code has not yet been validated as a fix for MK9 on Mali-G610.

## V2 findings on X5 Pro (reported 2026-11-07)

- With **Page Table** mapping and `Experimental Mali geometry fix` **off**,
  fighting characters still had severely stretched, exploding meshes.
- With the precision experiment **on**, a Cyrax close-up looked more
  recognizable, but Scorpion looked worse; fight geometry was still broken.
  Cinematics and fights are not an equivalent A/B comparison.
- With **Double Buffer** mapping and the precision experiment **off**,
  fighter geometry was still badly distorted (observed Cyrax/Mileena fight).
- With **Memory Mapping = Disabled** and the precision experiment **off**,
  the long screen-crossing polygon strips were noticeably reduced. Character
  body parts were more recognizable, particularly Cyrax. Persistent fan-like
  vertex spikes remained on Cyrax, Kratos, Kitana, Raiden and other fighters.
- User reported combat with memory mapping disabled was **very fluid, like the
  original game**. No instrumented FPS measurement was taken.

**Current best observed configuration:** Vulkan, memory mapping disabled,
precision experiment off. This is a partial visual improvement, not a fix.

**Next engineering focus:** investigate the vertex-buffer upload / GPU memory
mapping and shader-store paths. `enable_memory_mapping` changes more than
buffer-copy behavior (including the vertex-store emulation path), so the A/B
result does not by itself identify a root cause. Preserve the playable V2
configuration while investigating. Tests across different fighters and arenas
are useful observations but not controlled same-scene comparisons.

## Device test procedure

1. Preserve the V1 APK for comparison. Keep game data and firmware backups.
2. Install the V2 APK created by GitHub Actions on `mali-v2-upstream`.
3. Test the same fight with the experimental switch **off** first.
4. Restart the game after switching the option **on** and repeat the fight.
5. Compare character meshes, backgrounds, special moves, X-rays, and FPS.
6. Record screenshots / video and a Vita3K log from both runs.

Changing between APKs or changing shader options without a fresh cache may
confound results. Preserve saves before clearing application data or caches.

## V3 hypothesis: snapshot guest vertex/index data early

V2's best result used `Memory Mapping = Disabled`. In this mode, the Vulkan
renderer normally copies vertex/index bytes when the queued draw executes.
If MK9 reuses those guest buffers on the CPU before the host renderer reaches
the queued draw, the copied data may already belong to a later draw.

V3 adds a **separate, default-OFF** `Experimental Mali vertex snapshots (V3)`
switch. When enabled on Mali **and only with memory mapping disabled**, it
copies small vertex and index streams when the guest kicks the command list,
and binds those snapshots during rendering. The general code normally
disables snapshots when a game registers GPU buffer-store shaders, because
an early copy can miss data produced later by the GPU. V3 deliberately
overrides that guard when the tester opts in, so **missing geometry or worse
visual corruption is possible**. This is a diagnostic hypothesis, not a fix.

Test with precision experiment OFF and memory mapping Disabled. Compare the
same fighters, same arena, one toggle at a time. If the result deteriorates,
disable the V3 snapshot switch and restart. Leave V2 as the fallback APK.

## V3 result (reported 2026-11-07)

- The tester confirmed that the Scorpion-vs-Jade combat photograph was captured
  **after enabling** `Experimental Mali vertex snapshots (V3)`.
- Both fighter meshes still exploded into fan-shaped spikes, while the arena
  rendered normally. The V3 early-snapshot hypothesis has **not corrected**
  the observable vertex geometry issue.
- A rigorously controlled V3-on versus V3-off comparison in the same arena is
  still outstanding, so do not claim V3 is worse or identical in every scene.
- Recommended fallback: V3 switch OFF; memory mapping Disabled, full precision
  OFF, with an application/game restart after changing shader settings.

## Historical upstream comparison lead (unverified for this X5 Pro)

- Official Vita3K-Android issue #769 reports Mortal Kombat Komplete Edition
  apparently ran at 60 FPS without polygon artifacts on a different Mali GPU
  (G615 MC6) at upstream build **3961**, but had white/purple lines in build
  **3962**. This is a user's report, **not an independently verified fix**.
  https://github.com/Vita3K/Vita3K-Android/issues/769
- Official build 3961 for Android exists at:
  https://github.com/Vita3K/Vita3K-builds/releases/download/3961/vita3k-3961-2f50abb5-android.apk
- The official upstream 3961/3962 source comparison shows only one intervening
  commit (0e9e3a2), changing **GUI/config reading files**, not renderer code.
  Hence the issue alone is **not evidence** that an identified renderer change
  caused the regression. Other causes (build variability, settings, shader
  cache, drivers, user error) remain possible.
- An isolated test of official 3961 on the RK3588S Mali-G610, after protecting
  game saves and firmware, can cheaply falsify/confirm the report's relevance.
  Installing it may require uninstalling the + fork due package-signature
  conflicts. Preserve the installed APK and save data before swapping builds.
