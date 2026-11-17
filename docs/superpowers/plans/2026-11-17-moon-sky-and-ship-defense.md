# Moon in the Sky + Ship Defence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The game's moon follows the real moon across a south-facing sky with seas, crater rims, a shimmering halo and a sun-facing lit side; the ship decides per threat whether to shoot a rock or dodge it.

**Architecture:** Pure maths in `core/` (`MoonSky` ephemeris + screen mapping, `MoonFace` dot classification, `MoonTrack` once-a-minute samples interpolated per frame), fed by `GameHub.tick1s` from the cached location; `DotRenderer` draws it allocation-free. Ship defence is a new decision inside `GameWorld.pilot`, reusing `shoot()` and today's dodge.

**Tech Stack:** Java 8, Android framework only, no Gradle. JVM tests via `./test.sh` (harness `T`, `AllTests.SUITES`, `public static void testX()`), APK via `./build.sh` (warning-free).

**Spec:** `docs/superpowers/specs/2026-11-17-moon-sky-and-ship-defense-design.md` (and `docs/request-moon-and-defense.md`).

## Global Constraints

- `core/` has no android imports; `./test.sh` all green; `./build.sh` warning-free.
- Frame loop allocation-free; effects use the fx Rng stream, never the game Rng; determinism (same seed, same flight) holds.
- Moon stays on the 4 dp lattice, 40 dp disc (`cfg.moonRadiusDp = 20`), phase from `MoonPhase`.
- Moon tones: lit L65, seas L30, crater rims L100 (L65 in lock stills), shadow L10, halo sparse L10 dots at 1.25–1.35 R.
- Halo dots shimmer on their own 3–6 s cycles; one crater rim glints every 8–15 s in the stars' glint style (not in stills).
- View faces south: azimuth 60°→300° maps to x 8%→92% of width; altitude 0°→75° maps to y from the band's bottom to top. Main band 64%→30% of height.
- By day the moon is drawn at half alpha; over its last 2° above the horizon it fades out.
- Recompute once a minute (not per frame); move smoothly between updates.
- Unknown location: today's fixed corner (`GameConfig.moonCentreX/Y`); unknown sun times: hidden, as today.
- Ship: threat = soonest rock on a collision course within 2 s. Shot estimate = RCS turn to lead-aim bearing + remaining fire cooldown + bolt flight time; shoot when estimate + 0.15 s < time to impact, else today's dodge (horizons 1 s autopilot, 0.5 s manual). Manual mode shoots only when the turn is ≤ 40°. Works in every fire mode. Fragments are re-judged next step. Engines, retros, RCS limits, zero-g feel and the tap waypoint unchanged.
- Commits on `phase2` only, trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Moon in the north / outside az 60–300 or above 75°: clamped to the band edges, never off-screen or NaN. (Task 1 test `testScreenMappingClamps`.)
2. Location known but weather (sun times) unknown: moon still placed from location; day factor from computed sun altitude. (Task 3 test `testDayFactorFallsBackToSunAltitude`.)
3. Interpolation across the azimuth wrap or limb-angle wrap (±π): no spin through the long way. (Task 2 test `testTrackInterpolatesAnglesTheShortWay`.)
4. Ship firing repeatedly at the same rock every 0.12 s while the first bolt is still flying. (Task 4 test `testOneBoltPerEngagement`.)
5. A shot rock that splits: fragments on a collision course are judged fresh (shoot, dodge or bump), never ignored by a stale engagement. (Task 4 test `testFragmentsAreJudgedAgain`.)

---

### Task 1: MoonSky — moon and sun positions, sky mapping

**Files:**
- Create: `app/src/main/java/dev/gawel/dothome/core/MoonSky.java`
- Test: `tests/dev/gawel/dothome/core/MoonSkyTest.java` (register in `AllTests.SUITES`)

**Interfaces:**
- Produces:
  - `static void moon(long epochMs, double latDeg, double lonDeg, double[] out)` — out[0] topocentric altitude deg (no refraction), out[1] azimuth deg from north, clockwise (180 = south).
  - `static void sun(long epochMs, double latDeg, double lonDeg, double[] out)` — same layout, geocentric is fine.
  - `static void moonEcliptic(double jde, double[] out)` — geocentric λ deg, β deg, Δ km (for the Meeus check).
  - `static float limbAngle(double moonAz, double moonAlt, double sunAz, double sunAlt)` — screen radians (x right, y down) of the direction from the moon toward the sun along the great circle, with screen +x = increasing azimuth and screen up = increasing altitude:
    `dx = cos h2·sin(A2−A1)`, `dUp = cos h1·sin h2 − sin h1·cos h2·cos(A2−A1)`, return `atan2(−dUp, dx)`.
  - `static float screenX(double azDeg, float w)` → `w·(0.08 + 0.84·(az−60)/240)`, az clamped to 60..300.
  - `static float bandTop(float w, float h)`, `bandBottom(float w, float h)` — main 0.30h / 0.64h; cover (`Zones.isCover`) 0.30h / 0.67h.
  - `static float screenY(double altDeg, float w, float h)` → bottom + (top − bottom)·clamp(alt/75, 0, 1).
- Allocation-free (callers pass `out`); no android.

Algorithm: Meeus ch. 47 lunar series (enough terms of tables 47.A/47.B to meet the tolerances below) with D, M, M′, F, E; nutation may be ignored; obliquity from Meeus 22.2; GMST from Meeus 12.4; topocentric parallax (Meeus ch. 40 or the simple `alt −= asin(6378.14/Δ)·cos(alt)` correction). Sun: Meeus ch. 25 low precision. ΔT = 69 s.

- [ ] **Step 1: Write the failing tests** (`MoonSkyTest`). Each published number carries a comment with its source.
  - `testMeeus47a`: `moonEcliptic(2448724.5)` (1992-04-12 0h TD, Meeus *Astronomical Algorithms* example 47.a): λ = 133.162655 ± 0.1, β = −3.229126 ± 0.1, Δ = 368409.7 ± 300 km.
  - `testChicagoMoonTimesUsno`: Chicago 41.88, −87.63. Source: USNO API `https://aa.usno.navy.mil/api/rstt/oneday?date=<d>&coords=41.88,-87.63&tz=<tz>`, fetched 2026-11-17. For each event, scan minute by minute over ±60 min of the published time and find the event in `moon(...)`: rise/set = topocentric altitude crossing −0.833°; transit = azimuth crossing 180° (altitude maximum). Assert |found − published| ≤ 10 min.
    - 2026-11-17 (UTC−6): rise 12:49, transit 18:16, set 23:51
    - 2026-11-24 (UTC−6): set 07:07, rise 16:08
    - 2026-12-01 (UTC−6): transit 06:02, set 12:26
    - 2026-06-15 (UTC−5): rise 05:18, transit 13:32, set 21:42
    - 2026-03-10 (UTC−5): rise 01:46, transit 06:06, set 10:24
  - `testChicagoSunTimesUsno`: same source; sun altitude crosses −0.833° within 5 min of: 2026-11-17 rise 06:43 set 16:28 (UTC−6); 2026-06-15 rise 05:15 set 20:27 (UTC−5).
  - `testLimbPointsTowardTheSun`: 2026-11-17 18:16 CST (waxing, sun set in the west-southwest): compute both, `limbAngle` has cos > 0.5 (points right/west). 2026-12-01 06:02 CST (waning, sun below the east): cos < −0.5. And a synthetic check: sun straight above the moon (same az, higher alt) → angle ≈ −π/2 ± 1e-3.
  - `testScreenMappingClamps`: `screenX(60,1000)=80`, `screenX(300,1000)=920`, `screenX(10,1000)=80`, `screenX(350,1000)=920`; `screenY(0,…)=bandBottom`, `screenY(75)=screenY(89)=bandTop`, `screenY(-5)=bandBottom`; cover band for w/h = 0.45.
- [ ] **Step 2:** `./test.sh` → fails (no MoonSky).
- [ ] **Step 3:** Implement `MoonSky`.
- [ ] **Step 4:** `./test.sh` → all pass.
- [ ] **Step 5:** Commit `feat(core): MoonSky ephemeris and south-facing sky mapping`.

### Task 2: MoonFace and MoonTrack — the look and the smoothing (core)

**Files:**
- Create: `core/MoonFace.java`, `core/MoonTrack.java`
- Test: `tests/dev/gawel/dothome/core/MoonFaceTest.java` (register)

**Interfaces:**
- Consumes: `MoonPhase.phase(long)`.
- Produces:
  - `MoonFace` constants `OUTSIDE=0, SHADOW=1, LIT=2, SEA=3, CRATER=4, HALO=5`; `static final int CRATERS = 3` (0 Tycho, 1 Copernicus, 2 Kepler); `static float craterX(int i)`, `craterY(int i)` (unit face coords, y down, north up).
  - `static int cell(float ux, float uy, float phase, float limbAngle)` — ux, uy in units of R from the centre (screen axes). Halo for 1.25 ≤ r ≤ 1.35 *and* a sparse lattice selection (`static boolean haloDot(int i, int j)`, a fixed hash on lattice indices picking ~1 in 3) — `cell` returns HALO only through `haloCell(int i, int j, float r)`; keep `cell` for r ≤ 1. Lit test in the sun frame: rotate (ux, uy) by −limbAngle to (x′, y′); p = phase < 0.5 ? phase : 1 − phase; lit iff `x′ > sqrt(max(0,1−y′²))·cos(2πp)`. Lit cells inside a sea ellipse → SEA; lit cells within 0.12 R of a crater centre → CRATER; unlit → SHADOW (seas and craters not drawn in shadow).
  - Seas (face fixed upright — ruling: no parallactic rotation of the features), ellipses {cx, cy, rx, ry} in units of R, builder may tune for a readable 5-cell-radius disc: Imbrium (−0.30,−0.40,0.28,0.24), Serenitatis (0.22,−0.38,0.18,0.18), Tranquillitatis (0.40,−0.05,0.22,0.20), Procellarum (−0.62,0.00,0.25,0.45), Nubium (−0.15,0.40,0.18,0.15). Craters: Tycho (−0.10,0.75), Copernicus (−0.32,−0.08), Kepler (−0.58,−0.05).
  - `static float haloPeriod(int i, int j)` in [3, 6] s and `static float haloPhase(int i, int j)` in [0, 2π), deterministic hashes.
  - `MoonTrack`: `void set(long t0Ms, float az0, float alt0, float limb0, long t1Ms, float az1, float alt1, float limb1)`, `boolean valid()`, `void clear()`, and `float az(long nowMs)`, `alt(long)`, `limb(long)` linearly interpolated (clamped to [t0, t1]; angles the short way for limb and azimuth across 0/360). No allocation.
- [ ] **Step 1: Failing tests** in `MoonFaceTest`:
  - `testFullMoonShowsSeasAndCraters`: phase 0.5, limb 0: sampling a 41×41 grid over the unit disc finds LIT, SEA and CRATER cells, and SHADOW none; the point at Tycho's centre is CRATER, Imbrium's centre is SEA.
  - `testNewMoonIsAllShadow`: phase 0.0 → no LIT/SEA/CRATER inside the disc.
  - `testLitSideFacesLimbAngle`: phase 0.25 (half), limb 0 → (0.6,0) lit, (−0.6,0) SHADOW; limb π/2 → (0,0.6) lit (screen down), (0,−0.6) SHADOW; phase 0.75 with limb π → (−0.6,0) lit.
  - `testOutsideDisc`: `cell(1.1f,0,…)` = OUTSIDE.
  - `testHaloSparseAndTimed`: over lattice indices −8..8 with r from (i,j)/5 in [1.25,1.35]: between 20% and 50% are halo dots; every `haloPeriod` in [3,6].
  - `testTrackInterpolatesAnglesTheShortWay`: az 350 → 10 over 60 s at mid = 0 (±1e-3, as 0 or 360); limb 3.0 → −3.0 at mid ≈ ±π; before t0 returns t0 values, after t1 returns t1 values.
- [ ] **Step 2:** `./test.sh` → fails.
- [ ] **Step 3:** Implement both classes.
- [ ] **Step 4:** `./test.sh` → pass.
- [ ] **Step 5:** Commit `feat(core): MoonFace seas, craters and halo; MoonTrack smoothing`.

### Task 3: Moon in the sky — world state, hub feed, renderer

**Files:**
- Modify: `core/GameWorld.java` (moon track + crater glint), `core/GameConfig.java` (constants), `GameHub.java`, `DotRenderer.java`
- Test: `tests/dev/gawel/dothome/core/MoonSkyTest.java` or a new `MoonWorldTest.java` (register)

**Interfaces:**
- Consumes: Task 1 `MoonSky.*`, Task 2 `MoonFace.*`, `MoonTrack`; `LocationSource.cached(Context)` → `Place` or null (android side).
- Produces:
  - `GameWorld.moonTrack()` → the world's `MoonTrack`; `GameWorld.moonGlintCrater()` (−1 none) and `moonGlint()` seconds left (0..`GLINT_SECONDS`), run in `stepStars` on the fx stream: next glint after `fx.range(8, 15)` s, crater `fx.nextInt(3)`.
  - `static float MoonSky.dayFactor(float dayness, double sunAltDeg)` — `dayness` when not NaN, else `clamp((sunAlt + 6)/12, 0, 1)`; `static float MoonSky.alpha(double moonAlt, float day)` = `clamp(moonAlt/2, 0, 1)·(1 − 0.5·day)`.
- GameHub: once a minute (first call immediately), on `tick1s`: `Place p = LocationSource.cached(appContext)` (cache the Place in a field, refreshed with the weather refresh, not a prefs read every minute is fine either way); if p null → `world.moonTrack().clear()`; else compute moon/sun now and now+60 s and `set(...)` with limb from `limbAngle`; also store the current sun altitude for `dayFactor`. Reuse `double[]` fields — no per-minute allocation required but allowed (not frame loop).
- DotRenderer:
  - Track valid: centre = (`screenX(az)`, `screenY(alt)`) from the track at `System.currentTimeMillis()`; alpha = `MoonSky.alpha(alt, day)·fog·fade`; limb from the track. Track invalid: today's corner and today's `moonAlpha` with limb 0 for waxing / π for waning (same look as `MoonPhase.cell`).
  - Each lattice dot: SHADOW `dot10`, LIT `dot65`, SEA a new L30 SoftDot (`dot30`), CRATER `dot100` (or `dot65` when `still`), HALO `dot10` with alpha × (0.35 + 0.65·(0.5+0.5·sin(2π t/period + phase))) (constant 0.6 in stills). The glinting crater draws the stars' glint (flare + four small dots one step out) when not still.
  - The stars' moon exclusion radius becomes 1.35 R + step and uses the moon's current centre.
  - Clip to `clearTopY..clearBottomY` as today. No allocation per frame.
- [ ] **Step 1: Failing tests:**
  - `testDayFactorFallsBackToSunAltitude`: `dayFactor(NaN, -10)=0`, `dayFactor(NaN, 10)=1`, `dayFactor(0.3f, 50)=0.3`.
  - `testAlphaFadesOverLast2Degrees`: `alpha(-0.1,0)=0`, `alpha(1,0)=0.5`, `alpha(30,0)=1`, `alpha(30,1)=0.5`.
  - `testCraterGlintCadence`: a live world stepped 120 s at 0.016 — glints start 8–15 s apart (track gaps via `moonGlintCrater()` turning ≥0), crater index in 0..2; and same seed twice gives identical game-Rng-driven ship/rock state (glints use fx only).
- [ ] **Step 2:** `./test.sh` fails. **Step 3:** implement. **Step 4:** `./test.sh` pass and `./build.sh` warning-free.
- [ ] **Step 5:** Render check: extend `tools/WidgetPreview.java` only if trivial; otherwise skip (device check owed). Commit `feat(game): the real moon crosses the sky with seas, craters and a shimmering halo`.

### Task 4: Ship defence — shoot or dodge

**Files:**
- Modify: `core/GameWorld.java` (pilot, new `defend` logic, constants), tests `tests/dev/gawel/dothome/core/DefenceTest.java` (new, register); update `GameWorldTest.testAutopilotDodgesIncomingRock`, `ManualModeTest.testDodgesOnlyARockOnACollisionCourseWithinHalfASecond` only as far as the new rule requires.

**Interfaces:**
- Consumes: `shoot(tx, ty, toeIn)`, `fireT`, `cfg.shotSpeed`, `cfg.rcsAccel`, `cfg.maxSpin`, `cfg.gunCone`, `cfg.fireCooldown`, `hitRadius(Rock)`, `DODGE_MARGIN`, `collisionCourse(horizon)`.
- Produces (package-private for tests): `boolean defending()` (turning to/firing at a threat this step), `int defenceShots()` (count of bolts fired by defence), `static float turnTime(float angle, float rcsAccel, float maxSpin)` (bang-bang at 0.8·rcsAccel, capped spin: `|a| < maxSpin²/(0.8·rcsAccel)` → `2·sqrt(|a|/(0.8·rcsAccel))`, else `|a|/maxSpin + maxSpin/(0.8·rcsAccel)`).
- Constants: `DEFEND_HORIZON = 2f`, `DEFEND_MARGIN_S = 0.15f`, `DEFEND_MANUAL_TURN = 40°`.

Rule, each `pilot` step before the dodge check:
1. Threat: the rock with the earliest contact time t_hit ≤ 2 s, where contact = first root of |r + u·t| = hitRadius + DODGE_MARGIN·k (r, u relative to the ship, both holding velocity); skip a rock that is the current engagement (see 4).
2. Lead aim: solve |p + v_r·t| = shotSpeed·t for the earliest positive t (p = rock − ship, v_r = rock's world velocity; bolts do not inherit the ship's velocity); none → dodge path.
3. Estimate = `turnTime(wrap(bearing − angle))` (0 if within gunCone) + max(0, fireT) + t_flight. Shoot iff estimate + 0.15 < t_hit, and in manual mode also |turn| ≤ 40°.
4. Shooting: want = lead bearing, engines and retros off (as when facing); when |wrap(bearing − angle)| ≤ gunCone and fireT ≤ 0: `shoot(interceptX, interceptY, true)`, defenceShots++, record the engagement (rock, expires after t_flight + 0.1 s). While engaged, keep that rock out of the threat scan; when it expires (miss) the rock is judged again. A rock removed (killed/split) ends the engagement; fragments are new rocks and are judged next step.
5. Not shooting: today's `collisionCourse(horizon)`/dodge unchanged; while defending, `collisionCourse` ignores the engaged rock and the threat being shot; a different rock may still trigger a dodge, which wins (abandons the defence aim that step).
6. Defence takes priority over the auto-fire facing hold for heading; it fires regardless of FireMode. No Rng use.
7. Cleanup (request): remove a duplicated `retroCmd = ...` line at the end of `pilot` if present (Ruling at plan time: HEAD 656015e has only one; the implementer confirms with `grep -c` and notes it).

- [ ] **Step 1: Failing tests** in `DefenceTest` (helper like `ShipFlightTest.live`, 620×722, k=1, rocks cleared, `skipIntro`):
  - `testRockDeadAheadIsShot`: ship at rest at (310, 390), angle 0; size-1 rock at (310+300, 390) moving −120 px/s. Step ≤ 4 s: `defenceShots() ≥ 1`, the rock is gone before any `onShipHit`, `evading()` never true.
  - `testFastCloseRockFromBehindIsDodged`: ship angle 0 at rest; size-2 rock at (310 − 120, 390) moving +260 px/s: `evading()` becomes true, `defenceShots() == 0` before the dodge starts.
  - `testSlowFarRockFromBehindIsTurnedOnAndShot`: rock at (310 − 300, 390) moving +160 px/s (t_hit ≈ 1.6 s): `defending()` true early, heading turns > 120°, `defenceShots() ≥ 1`, rock gone, no hit, never evading.
  - `testManualRockAt90IsDodged`: `aim(560, 390)`, then a rock at (310, 390 − 180) moving +250 px/s (straight down at it, 90° off the heading): `evading()` true, `defenceShots()==0`, and |wrap(angle − wpBearing)| ≤ 60° throughout (the heading stays with the waypoint, today's dodge cone).
  - `testOneBoltPerEngagement`: the dead-ahead case with the rock made far (t_flight 0.35 s): defenceShots stays 1 until the first bolt's flight time + 0.1 s has passed.
  - `testFragmentsAreJudgedAgain`: size-3 rock dead ahead; after it splits, a fragment still on a collision course is shot or dodged (defenceShots ≥ 2 or evading), never ignored until contact.
  - `testDefenceDeterministic`: two worlds same seed, same scenario, 6 s: identical ship x, y, angle and shot counts.
- [ ] **Step 2:** `./test.sh` fails.
- [ ] **Step 3:** Implement. Then run the whole suite: existing dodge tests built around a rock dead ahead now see a shot instead. Change their scenario minimally so they still pin the dodge path (e.g. a rock that cannot be shot in time, or from the side in manual mode) and explain each change in the report; never delete a dodge test or weaken its dodge assertions.
- [ ] **Step 4:** `./test.sh` all pass; `./build.sh` warning-free.
- [ ] **Step 5:** Commit `feat(game): the ship shoots a threat it can hit in time, dodges the rest`.
