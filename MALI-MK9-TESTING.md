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

## Device test procedure

1. Preserve the V1 APK for comparison. Keep game data and firmware backups.
2. Install the V2 APK created by GitHub Actions on `mali-v2-upstream`.
3. Test the same fight with the experimental switch **off** first.
4. Restart the game after switching the option **on** and repeat the fight.
5. Compare character meshes, backgrounds, special moves, X-rays, and FPS.
6. Record screenshots / video and a Vita3K log from both runs.

Changing between APKs or changing shader options without a fresh cache may
confound results. Preserve saves before clearing application data or caches.
