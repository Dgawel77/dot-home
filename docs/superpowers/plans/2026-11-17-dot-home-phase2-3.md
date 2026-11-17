# Dot Home Phase 2/3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Dot Home game wallpaper to life (weather, day/night and the moon, charging, milestones, the lock still, the unfold burst) and add the Bears score widget, the Blue Line arrivals widget and the beat pulse.

**Architecture:** Every new behaviour is pure logic in `core/` (no android imports), JVM-tested through the `T` harness. Thin Android glue sits in `GameHub`, `DotWallpaperService`, `DotRenderer`, new widget providers and `SetupActivity`. The game's own `Rng` sequence is never consumed by effects; effects use their own seeded streams and fixed pools, so the frame loop stays allocation-free.

**Tech Stack:** Java 8 source, Android framework APIs only (minSdk 26, targetSdk 34), no Gradle or libraries; `./test.sh` (JVM tests) and `./build.sh` (aapt2/javac/d8 APK).

**Spec:** `docs/specs/2026-11-15-fold6-home-screen-design.md` (sections "Phase 2" and "Phase 3"), `docs/specs/2026-11-16-fold6-dot-design-rules.md` (visual rules and Decisions), and the rulings in `docs/HANDOFF-phase2-3.md` ("Rulings I've already made"), which fix every value the spec leaves open. Executors read all three.

## Global Constraints

- Java 8 source, framework APIs only, no libraries, no Gradle. minSdk 26, targetSdk 34.
- `core/` has no `android.*` references (`test.sh` greps). Each new core class gets a test class registered in `tests/dev/gawel/dothome/test/AllTests.SUITES`. Test methods are `public static void testX()`, using `T.eq`, `T.near` and `T.check`.
- `./build.sh` stays warning-free. The only exception is javac's two-line deprecation note, which you fix with `@SuppressWarnings` plus a version check.
- Palette: GROUND #000000, L100 #DDDDDD, L65 #A6A6A6, L46 #757575, L10 #1A1A1A, all from `DotStyle`. LASER #FF3B30 is for the ship's lasers only. Every new effect is greyscale.
- Lattice positions sit on 4 dp, with steps S4/S8/S12/S16. Dot weights come from `Lattice.Weight`. Text sizes are 11, 13, 15 or 20 dp. Labels are JetBrains Mono caps (`Fonts.mono`). Numbers use `DotMatrixFont`. There are no words in dot type.
- Tempos: breath 4 s, signal 2 s, event 0.6–0.8 s. Arrivals use cubic ease-out and departures cubic ease-in. Overshoot is allowed only on the unfold burst.
- Frame loop: nothing is allocated per frame. Pools are fixed arrays sized at construction. Effects never draw from `GameWorld`'s game `Rng`; use the `fx` stream or a new `Rng` seeded from it.
- Every tunable value lives in `GameConfig` as a named field (scaled by `k` where it is a length or speed).
- RemoteViews layouts never contain a plain `<View>` (use FrameLayout). All widget text is drawn into bitmaps. The corner radius is `max(semShapeRadius, 40dp)` via `WidgetUpdater.shapeRadiusDp`. Each widget has a picker `previewImage` and a "Dot …" label. A widget with nothing to show is blank (a single-FrameLayout empty layout, as `widget_music_empty.xml`).
- Network: keep the single Open-Meteo request (you may add fields). New feeds are ESPN (no key) and CTA (key in prefs `cta.key`). The CTA key is never committed, logged or included in an exception message.
- Implementers never touch the phone. The controller installs and checks the device.
- Commits are on `phase2` and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Missing, stale or garbled weather cache** (first boot, offline, Open-Meteo shape change): no precipitation, no wind lean, no fog, full night stars and no moon. Never a crash. Tests: Task 1 `testNullAndStaleGiveNone`, Task 3 `testUnknownSunGivesNaN`.
2. **Fold or unfold while an effect is mid-flight** (snow, charge stream, a milestone shape forming, the burst itself): `GameWorld.resize` rescales every pooled effect position and target, like rocks, so nothing is stranded off-screen. Tests: Task 2 `testResizeScalesPrecip`, Task 4 `testResizeScalesStream`, Task 5 `testResizeScalesShapeTargets`.
3. **Feed failures** (no network, ESPN JSON reshaped, CTA error code, HTML error page): the parsers return null or empty without throwing, and the widget keeps its last good data or goes blank. It never shows "Couldn't add widget". Tests: Task 8 `testGarbageIsNull`, Task 10 `testGarbageAndBadKey`.
4. **Local-midnight and time-zone edges:** an evening Bears kickoff (01:20Z) belongs to the previous *local* day. Milestones re-arm on the next local day. Tests: Task 8 `testEveningKickoffIsLocalDay`, Task 5 `testMilestonesOncePerDay`.
5. **Caps under load** (heavy snow plus a barrage plus a milestone at once): precipitation, the charge stream and shape dots each stay under their caps, and total particles stay ≤ `particleCap`. Tests: Task 2 `testPrecipCap`, Task 4 `testStreamCap`, Task 5 `testShapeRespectsParticleCap`.

## Rulings (controller, recorded in the ledger)

- The execution method is subagent-driven, and the plan is not waiting on approval: David approved the scope and asked for an autonomous build.
- The lock still reuses `renderStill`'s existing clear-top band (`LockStills.CLEAR_TOP` 0.42) and adds a clear-bottom band of 0.18 for the Now Bar.
- Widget picker previews are drawn by a JVM tool (`tools/WidgetPreview.java`, java.awt). It draws the same core layout models the widgets use, so it works with the phone offline.
- Bears and Blue Line refreshes at 60 s ride the wallpaper service's screen-on `TIME_TICK` (once a minute). A periodic `FeedJob` (6 h) refreshes the Bears schedule. There are no foreground services and no alarms.
- Bears digits use the same grey level as the clock widget's weather temperature digits in `WidgetArt.weatherBlock`. Labels are L65.
- If the device is more than 10 km from every Blue Line station and no station is pinned, the Blue Line widget is blank.

---

### Task 1: Weather data for the game (WeatherFx)

**Files:**
- Modify: `app/src/main/java/dev/gawel/dothome/core/Weather.java` (add `precipMm`, `visibilityM`)
- Modify: `app/src/main/java/dev/gawel/dothome/core/OpenMeteoParser.java`, `core/WeatherUrls.java`
- Create: `app/src/main/java/dev/gawel/dothome/core/WeatherFx.java`
- Modify: `tests/fixtures/open-meteo.json` (add `"visibility":24140.0` to `current` and `current_units`)
- Test: `tests/dev/gawel/dothome/core/WeatherFxTest.java`, plus additions to `tests/dev/gawel/dothome/test/WeatherTest.java`

**Interfaces:**
- Produces:
  - `Weather.precipMm` (double, 0 if absent) and `Weather.visibilityM` (double, `NaN` if absent). Add a 14-arg constructor with these two last. The existing 12-arg constructor delegates with `0, Double.NaN`. `withAlert` and `withPlace` keep both fields.
  - `WeatherFx` (immutable):
    - `enum Kind { NONE, RAIN, SNOW }`
    - `final Kind kind`
    - `final float intensity` (0..1)
    - `final float windX, windY` (the direction the wind blows *toward* on a portrait screen, east = +x and north = −y, magnitude `min(1, mph/25)`)
    - `final boolean fog`
    - `static final WeatherFx NONE`
    - `static WeatherFx from(Weather w, long nowMs)`
    - `static final long STALE_MS = 3h`

- [ ] **Step 1: Write failing tests.**
  - In `WeatherFxTest`:
    - `testSnowCodes`: code 73 → SNOW 0.6. Code 71 → SNOW 0.3. Code 86 → SNOW 1.0.
    - `testRainCodesAndPrecip`: code 61 with precip 0 → RAIN 0.3. Code 61 with precip 3.0 → RAIN 1.0, since precip intensity is `clamp(mm/2.5)` for rain and `clamp(mm/1.0)` for snow, and the final intensity is the max of the code base and the precip intensity. Code 95 → RAIN 1.0.
    - `testClearIgnoresPrecip`: code 0 with precip 1.0 → NONE.
    - `testWindVector`: from 270° at 25 mph → windX ≈ +1, windY ≈ 0. From 0° at 12.5 mph → windY ≈ +0.5 (a north wind blows south). 50 mph caps at magnitude 1.
    - `testFog`: code 45 → fog. Code 48 → fog. Visibility 800 m → fog. Visibility NaN → no fog.
    - `testNullAndStaleGiveNone`: `from(null, t)` → `NONE`. `fetchedAtMs = t - STALE_MS - 1` → `NONE`, with no wind and no fog. Exactly `STALE_MS` old is still live.
  - In `WeatherTest`:
    - `testParsesVisibilityAndPrecip`: from the fixture, visibility is 24140 and precip is 0.
    - `testMissingVisibilityIsNaN`: from JSON without `visibility`, the value is NaN.
    - `testUrlRequestsVisibility`: `WeatherUrls.openMeteo` contains `visibility` and still makes exactly one request.
- [ ] **Step 2: Run** `./test.sh`. Expected: compile FAIL (`WeatherFx` missing).
- [ ] **Step 3: Implement.**
  - Code bases:
    - light {51, 56, 61, 66, 71, 77, 80, 85} = 0.3
    - moderate {53, 63, 73, 81} = 0.6
    - heavy {55, 57, 65, 67, 75, 82, 86, 95, 96, 99} = 1.0
  - Snow codes are {71, 73, 75, 77, 85, 86}. The rest of those sets are RAIN.
  - Add `visibility` to the `current=` list in `WeatherUrls.openMeteo`.
- [ ] **Step 4: Run** `./test.sh`. Expected: all pass (249 + new).
- [ ] **Step 5: Commit** with the message `feat(weather): WeatherFx from cached Open-Meteo (precip, wind, fog, visibility)`.

### Task 2: Weather in the game (snow, rain, wind lean, fog)

**Files:**
- Create: `app/src/main/java/dev/gawel/dothome/core/Precip.java`
- Modify: `core/GameConfig.java`, `core/GameWorld.java`, `DotRenderer.java`, `GameHub.java`
- Test: `tests/dev/gawel/dothome/core/PrecipTest.java`

**Interfaces:**
- Consumes: `WeatherFx` (Task 1).
- Produces:
  - `Precip` (a fixed pool):
    - `static final int CAP = 240`
    - `final float[] x, y, vx, vy, size`
    - `final boolean[] light` (true = L65, false = L46; snow only)
    - `int n`
    - `boolean snow` (the kind of the live drops)
    - `void step(float dt, WeatherFx fx, float gravX, float gravY, float w, float h, GameConfig cfg, Rng rng)`
    - `void scale(float sx, float sy)`
  - `GameWorld`:
    - `void setWeather(WeatherFx fx)` (null → NONE)
    - `WeatherFx weather()`
    - `Precip precip()`
  - `GameConfig` fields:
    - `precipMax` (= `starCount(w,h)`, capped at `Precip.CAP`)
    - `snowFall` = 40k px/s, `snowDrift` = 30k px/s at wind 1
    - `rainFall` = 520k px/s, `rainSlant` = 160k px/s at wind 1
    - `windRockBias` = 6k px/s at wind 1
    - `fogStarDim` = 0.5f

- [ ] **Step 1: Write failing tests** in `PrecipTest`, on a world of 1856×2160 with seeded `Rng(7)`:
  - `testNoneSpawnsNothing`: after 10 s of steps at dt 1/60 with `NONE`, `n == 0`.
  - `testPrecipCap`: SNOW 1.0 for 20 s → `n <= cfg.precipMax`, `n <= Precip.CAP`, and `n >= 0.8 * precipMax`. SNOW 0.3 → `n <= round(0.3 * precipMax) + 1`.
  - `testSnowFallsTowardGravity`: with grav (0,1), the mean `vy > 0`. With grav (1,0), the mean `vx > 0`. With grav magnitude < 0.2, it falls toward screen-down (0,1).
  - `testRainSlantsWithWind`: RAIN with windX +1 → every live drop has `vx > 0`. `snow == false`.
  - `testStopsSpawningAfterClear`: SNOW for 5 s, then NONE for 30 s → `n == 0`. Drops fall out; there is no instant clear.
  - `testResizeScalesPrecip`: `scale(0.5f, 1f)` halves every x.
  - `testWeatherDoesNotTouchGameRng`: two `GameWorld(new Rng(3))` worlds, one with SNOW 1.0 and wind 0. After 600 steps, rock positions are identical.
  - `testWindLeansRocks`: two worlds with the same seed, one with windX = 1. After 10 s, the mean rock x difference ≈ `windRockBias * 10` (tol 15%), comparing only rocks present in both (size-3 originals, by index, before any wrap: use a 4000×4000 world).
- [ ] **Step 2: Run** `./test.sh`. Expected: FAIL to compile.
- [ ] **Step 3: Implement.**
  - **Precip:**
    - The target count is `round(cfg.precipMax * fx.intensity)`. Spawn up to the target per step at the upwind or up edge, opposite the fall direction, at a random position along that edge.
    - A drop is recycled once it is more than 8k px past any edge, and not respawned beyond the target.
    - The fall direction `g` is the normalized (gravX, gravY), or (0,1) when the magnitude is under 0.2.
    - Snow velocity is `g * snowFall * rand(0.7..1.3)` plus the wind drift (windX, windY) times `snowDrift`, with a slow sideways wobble: a sine of period 4 s on its own phase.
    - Rain velocity is `g * rainFall` plus `wind * rainSlant`.
    - Snow `size` is `rand(0.6..1.0)` of the light radius, and `light` is true for about 40% of flakes.
  - **GameWorld:**
    - It holds `Precip` and an `Rng precipRng` seeded from `fx`.
    - `step` calls `precip.step` with `tiltX`/`tiltY`.
    - Rocks move by `(r.vx + fx.windX*cfg.windRockBias) * dt`, and the same for y.
    - `resize` calls `precip.scale(sx, sy)`.
  - **DotRenderer `drawScene`:**
    - Precip draws after the stars and before the rocks.
    - Snow is soft dots, `dot65` if `light`, otherwise `dot46`, radius `rLight*size`.
    - Rain is three `dot46` dots at `rLight`, spaced one lattice `step` back along `-(vx,vy)` normalized.
    - On stills it is capped as for everything else (it already is ≤ L65), and it obeys `clearTopY`.
    - When `world.weather().fog` is true, star alpha is multiplied by `cfg.fogStarDim`.
  - **GameHub:** in `onVisible`, call `world.setWeather(WeatherFx.from(WeatherRepo.cached(ctx), now))`. Recompute every 10 min from `DotEngine.doFrame`'s existing 1 s refresh block, via a new `GameHub.tick1s(long nowMs)` that this task introduces and later tasks extend.
- [ ] **Step 4: Run** `./test.sh` and `./build.sh`. Expected: all pass, and the APK builds with no warnings.
- [ ] **Step 5: Commit** with the message `feat(game): real weather (snow, rain, wind lean, fog dims stars)`.

### Task 3: Day and night, and the moon

**Files:**
- Create: `core/Sky.java`, `core/MoonPhase.java`
- Modify: `core/GameConfig.java`, `core/GameWorld.java`, `DotRenderer.java`, `GameHub.java`
- Test: `tests/dev/gawel/dothome/core/SkyTest.java`

**Interfaces:**
- Produces:
  - `Sky.dayness(long nowMs, long sunriseMs, long sunsetMs)` → float 0 (night) .. 1 (day), or `NaN` when either input is ≤ 0.
  - `Sky.RAMP_MS = 45 min`
  - `MoonPhase.SYNODIC_DAYS = 29.530588853`
  - `MoonPhase.EPOCH_MS` = 2000-01-06T18:14Z
  - `MoonPhase.phase(long nowMs)` → 0..1 (0 new, 0.5 full)
  - `MoonPhase.cell(float x, float y, float phase)` → 0 outside the unit disc, 1 unlit, 2 lit (x, y in −1..1, screen y down)
  - `GameConfig`:
    - `dayStarCount = 0.6f`, `dayStarBright = 0.75f`
    - `moonRadiusDp = 20f` (a 40 dp disc)
    - `static float[] moonCentre(float w, float h)` → {x, y} in px: main (0.84w, 0.33h), cover (0.80w, 0.33h), chosen by `Zones.isCover`
  - `GameWorld`: `void setDayness(float d)`, `float dayness()` (NaN = unknown)

- [ ] **Step 1: Write failing tests** in `SkyTest`, using sunrise 12:40Z and sunset 22:29Z on 2026-11-15 (the fixture values):
  - `testNoonIsDayMidnightIsNight`: 18:00Z → 1. 06:00Z → 0.
  - `testRampsAroundSunset`: at sunset → 0.5. Sunset + 45 min → 0. Sunset − 45 min → 1. Sunset + 22.5 min → 0.25 (tol 0.01).
  - `testRampsAroundSunrise`: at sunrise → 0.5. Sunrise − 45 min → 0.
  - `testOldSunTimesShiftByDays`: the same sunrise and sunset with `now` 3 days later at 18:00Z → 1, and at 12:40Z → 0.5.
  - `testUnknownSunGivesNaN`: sunrise 0 → NaN.
  - `testMoonPhaseKnownDates`: 2000-01-06T18:14Z → 0 (tol 0.001). +14.765 d → 0.5 (tol 0.001). 2026-11-24T14:53Z (a full moon) → 0.5 (tol 0.03).
  - `testMoonCells`: phase 0 → no lit cells over a 21×21 sample. Phase 0.5 → every in-disc cell lit. Phase 0.25 → (0.5,0) lit and (−0.5,0) unlit. Phase 0.75 → (−0.5,0) lit and (0.5,0) unlit. (1,1) → 0.
  - `testMoonClearOfWidgetZones`: for 1856×2160 and 968×2376, the box `moonCentre ± 0.06*w` does not intersect any rect in `Zones.defaults(w,h)`.
- [ ] **Step 2: Run** `./test.sh`. Expected: compile FAIL.
- [ ] **Step 3: Implement `Sky.dayness`.**

```
k = floorDiv(now - sunrise, DAY); sr = sunrise + k*DAY; ss = sunset + k*DAY; if (ss < sr) ss += DAY;
up   = clamp((now - (sr - R)) / (2R));  down = clamp(((ss + R) - now) / (2R));
next = clamp((now - (sr + DAY - R)) / (2R));
return max(min(up, down), next);
```

  - **Terminator:** let `xt = sqrt(1-y²)*cos(2π·phase)`. While waxing (phase < 0.5), a cell is lit if `x > xt`. While waning, a cell is lit if `x < -xt`.
  - **Renderer stars:** draw only stars with index `< round(n * lerp(1, dayStarCount, d))`, and multiply alpha by `lerp(1, dayStarBright, d)`. NaN means d = 0 for stars.
  - **Renderer moon:**
    - Draw only when d is not NaN, at alpha `clamp(1 - 2d)`.
    - It sits on lattice cells within `moonRadiusDp` of `moonCentre`, snapped with `Lattice.snap`. Each cell is a light-weight dot: lit is `dot65`, unlit is a `SoftDot(DotStyle.L10)`.
    - It is drawn after the stars and before the precipitation, and it is still-safe.
  - **GameHub:** cache `sunriseMs` and `sunsetMs` from the weather in `onVisible` and the 10-min refresh. Call `world.setDayness(Sky.dayness(...))` in `tick1s`.
- [ ] **Step 4: Run** `./test.sh` and `./build.sh`. Expected: pass and a clean build.
- [ ] **Step 5: Commit** with the message `feat(game): day/night star ramp and a dot moon with the real phase`.

### Task 4: Charging stream and low-battery dim

**Files:**
- Create: `core/ChargeStream.java`
- Modify: `core/GameConfig.java`, `core/GameWorld.java`, `DotRenderer.java`, `GameHub.java`
- Test: `tests/dev/gawel/dothome/core/ChargeStreamTest.java`

**Interfaces:**
- Produces:
  - `ChargeStream` (a fixed pool):
    - `static final int CAP = 64`
    - `final float[] x, y, vx, vy, glow` (glow 0..1 → level L46..L65)
    - `int n`
    - `long spawned, absorbed`
    - `void step(float dt, boolean on, float shipX, float shipY, float w, float h, GameConfig cfg, Rng rng)`
    - `void scale(float sx, float sy)`
  - `GameConfig`: `chargeRate = 15f` (per s), `chargeSpeed = 260k px/s` (cruise), `lowBattery = 15` (%)
  - `GameWorld`:
    - `void setPower(boolean charging, int percent)` (percent −1 = unknown)
    - `boolean shipDim()` (true iff not charging and 0 ≤ percent < 15)
    - `ChargeStream chargeStream()`

- [ ] **Step 1: Write failing tests** in `ChargeStreamTest`:
  - `testRateAbout15PerSecond`: on for 10 s at dt 1/60 → `spawned` in 140..160.
  - `testAllAbsorbedAfterOff`: on 5 s, then off 6 s → `n == 0` and `absorbed == spawned`.
  - `testOffSpawnsNothing`: off 10 s → `spawned == 0`.
  - `testStreamCap`: on with the ship moving away at max speed for 30 s → `n <= CAP` throughout.
  - `testDotsSpawnAtEdges`: every new dot starts within 1 px of an edge.
  - `testGlowRises`: a dot's glow increases from 0 toward 1 as it closes on the ship.
  - `testResizeScalesStream`: `scale(2,1)` doubles x.
  - `testShipDim`: setPower(false,14) → dim. (false,15) → not dim. (true,5) → not dim. (false,−1) → not dim.
- [ ] **Step 2: Run** `./test.sh`. Expected: compile FAIL.
- [ ] **Step 3: Implement.**
  - **Homing:** each step, steer velocity toward the ship (`v += (dir*chargeSpeed - v) * (1 - exp(-dt/0.25))`).
  - **Absorb:** a dot is absorbed within `cfg.shipRadius * 0.6`.
  - **Glow:** glow = `1 - dist/dist0`, clamped.
  - **GameWorld:** `step` calls the stream with the fx-derived `Rng`, and `resize` scales it.
  - **Renderer:** stream dots draw just before the ship, light weight, `dot46` blended toward `dot65` by glow. Draw both at alphas `1-glow` and `glow`; there are no new paints. When `shipDim()`, the hull and nozzle bars draw with `dot46` in place of `user` and `dot65`.
  - **GameHub:** in `onVisible`, register `ACTION_BATTERY_CHANGED`. The sticky intent gives the initial state: `EXTRA_PLUGGED != 0` means charging, and the percent is `level*100/scale`. Unregister in `onHidden` when nothing is visible. Each update calls `world.setPower`.
- [ ] **Step 4: Run** `./test.sh` and `./build.sh`. Expected: pass and a clean build.
- [ ] **Step 5: Commit** with the message `feat(game): charging stream into the ship, ship dims under 15% battery`.

### Task 5: Milestone easter eggs

**Files:**
- Create: `core/Milestones.java`, `core/MilestoneShapes.java`
- Modify: `core/ScoreBook.java` (add `public String day()`), `core/GameWorld.java` (`Particle` gains fields), `core/GameConfig.java`, `DotRenderer.java`, `GameHub.java`
- Test: `tests/dev/gawel/dothome/core/MilestonesTest.java`

**Interfaces:**
- Produces:
  - `Milestones`:
    - `static final int[] COUNTS = {100, 250, 500, 1000}`
    - `Milestones(KeyValueStore kv)`
    - `int reached(int todayCount, String day)`: the milestone this count hits, or 0. It is persisted under `milestone.day` and `milestone.fired` (a bitmask) so that each one fires once per day.
  - `MilestoneShapes.points(int milestone)` → `float[]` of x,y pairs centred on 0, with width ≤ 2 and the aspect kept:
    - 500 is four six-pointed stars in a row (the Chicago flag). Each star is rasterised on a 9-cell-tall grid as the union of two equilateral triangles, and the stars are separated by 3 empty cells.
    - The others are the lit cells of `DotMatrixFont.render(String.valueOf(m))`.
  - `GameWorld.Particle` gains:
    - `float tx, ty, sx0, sy0`
    - `float formT = -1` (≥ 0 while forming)
    - `boolean lit` (draws L100)
  - `GameWorld.formShape(float[] unitPts)`: centres the shape at (0.5w, 0.45h), with the shape's width = `cfg.shapeWidth` = 0.6·min(w,h).
  - `GameConfig`: `shapeGather = 0.6f`, `shapeHold = 3.0f` (total seconds before release), `shapeWidth`
  - `ScoreBook.day()` → the current "yyyy-MM-dd" key.

- [ ] **Step 1: Write failing tests** in `MilestonesTest`:
  - `testMilestonesOncePerDay`: `reached(100, "2026-11-17")` → 100. Again → 0. `reached(99,…)` → 0. `reached(250,…)` → 250. `reached(100, "2026-11-18")` → 100. Use the `MapStore` test store.
  - `testChicagoStars`: for `points(500)`, sorting the x values gives exactly 3 gaps wider than 2 cells' spacing. The count is between 4×20 and 4×60, and the shape is left-right symmetric (each point's mirror is present, tol 1e-4).
  - `testDigitsShape`: `points(100).length/2` = the lit-cell count of `DotMatrixFont.render("100")`.
  - `testShapeForms`: start a world, kill a rock (`addRock` plus a shot, or use the package-private kill path through a test hook `killForTest(int i)`), then call `formShape(points(250))`. After 0.6 s each forming particle is within 1 px of its target. At 2.9 s they still hold. After 3.1 s every one has `formT < 0` and a nonzero speed.
  - `testShapeRespectsParticleCap`: fill the particles to `particleCap - 10`, then call `formShape(points(500))` → `particles().size() <= particleCap`.
  - `testResizeScalesShapeTargets`: after `formShape`, `resize(w/2, h)` halves `tx`.
  - `testShapeDoesNotTouchGameRng`: same-seed worlds with and without `formShape` have identical rock positions after 300 steps.
- [ ] **Step 2: Run** `./test.sh`. Expected: compile FAIL.
- [ ] **Step 3: Implement.**
  - **Recruiting:**
    - `kill()` records `lastKillFirst` (the particle index before its debris) and the kill point.
    - `formShape` recruits the particles from `lastKillFirst` to the end if a kill happened this step. It spawns extras at the kill point (or the shape centre when there was no kill) up to the point count, never past `particleCap`, then assigns targets in order.
  - **Forming particle:**
    - Position = `lerp(start, target, easeOut(min(1, formT/shapeGather)))`. It skips gravity, drag and the life countdown.
    - At `shapeHold` it releases: `formT = -1`, a velocity of `rand(10..30)k px/s` in a random direction from the fx stream, and `life = life0 = rand(0.7..1.7)`, keeping `lit`.
    - Surplus debris stays ordinary.
  - **Renderer:** `lit` particles draw with `user` (L100 live, L65 on stills) at regular radius.
  - **GameHub `onRockDestroyed`:** `int m = milestones.reached(scoreBook.today(), scoreBook.day()); if (m > 0) world.formShape(MilestoneShapes.points(m));`. Cache the four shapes in a static array, so nothing is allocated per kill.
- [ ] **Step 4: Run** `./test.sh` and `./build.sh`. Expected: pass and a clean build.
- [ ] **Step 5: Commit** with the message `feat(game): milestone shapes at 100/250/500/1000 (Chicago stars at 500)`.

### Task 6: Unfold burst

**Files:**
- Create: `core/FoldBurst.java`
- Modify: `core/GameWorld.java`, `DotRenderer.java` (per-axis view scale)
- Test: `tests/dev/gawel/dothome/core/FoldBurstTest.java`

**Interfaces:**
- Produces:
  - `FoldBurst`:
    - `static final float SECONDS = 0.6f`
    - `static float[] startScale(float ow, float oh, float nw, float nh)` → {sx, sy}. The result is {1,1} unless `Zones.isCover(ow,oh) != Zones.isCover(nw,nh)`.
      - Unfold: `min(1, ow/nw)`, `min(1, oh/nh)`.
      - Fold: `max(1, ow/nw)`, `max(1, oh/nh)`.
    - `static float scaleAt(float start, float t)`: for start < 1, easeOutBack (overshoot constant 1.2) from start to 1. For start > 1, cubic ease-out to 1. 1 at t ≥ SECONDS.
  - `GameWorld`: `float burstScaleX()`, `float burstScaleY()` (1 when idle). `resize` arms the burst, and `step` advances it.

- [ ] **Step 1: Write failing tests** in `FoldBurstTest`:
  - `testUnfoldStartsCoverSized`: 968×2376 → 1856×2160 gives {0.5216 (tol 1e-3), 1}.
  - `testFoldStartsOutside`: the reverse gives {1.917 (tol 1e-3), 1}.
  - `testRotationNoBurst`: 1856×2160 → 2160×1856 gives {1,1}.
  - `testScaleEndsAtOne`: `scaleAt(0.52f, 0.6f) == 1` and `scaleAt(1.9f, 0.6f) == 1`.
  - `testUnfoldOvershootSmall`: max over t of `scaleAt(0.52f,t)` is in (1, 1.08].
  - `testFoldNoOvershoot`: `scaleAt(1.9f,t)` is monotonically non-increasing and ≥ 1.
  - `testWorldBurst`: a world sized 968×2376 is resized to 1856×2160. `burstScaleX()` < 0.6 right after, and is 1 after 0.7 s of steps.
- [ ] **Step 2: Run** `./test.sh`. Expected: compile FAIL.
- [ ] **Step 3: Implement.**
  - **DotRenderer:** replace the uniform `vs` with `vsx`/`vsy` in `sx()`/`sy()`. On live frames, set `vsx = burstScaleX`, `vsy = burstScaleY`, with `vx = (1-vsx)*w/2` and `vy = (1-vsy)*h/2`, so the scale is about the centre. Stills keep their uniform cover fit (`vsx = vsy = s`). Dot sizes don't change.
- [ ] **Step 4: Run** `./test.sh` and `./build.sh`. Expected: pass and a clean build.
- [ ] **Step 5: Commit** with the message `feat(game): unfold burst and fold collapse (0.6 s, one world)`.

### Task 7: Lock screen shows where you left off

**Files:**
- Create: `core/LockGate.java`, `LockWallpaper.java`
- Modify: `DotRenderer.renderStill` (add a `clearBottomFraction` parameter; update the `LockStills` call with 0), `DotWallpaperService.java` (SCREEN_OFF hook), `SetupActivity.java` (switch), `AndroidManifest.xml` (`android.permission.SET_WALLPAPER`)
- Test: `tests/dev/gawel/dothome/core/LockGateTest.java`

**Interfaces:**
- Produces:
  - `LockGate`:
    - `static final long MIN_GAP_MS = 60_000`
    - `static int fingerprint(int today, float shipX, float shipY, float w, float h)`: the score × 64 + the ship's cell on an 8×8 grid.
    - `boolean shouldPush(long nowMs, int fp)`
    - `void pushed(long nowMs, int fp)`
  - `LockWallpaper.onScreenOff(Context ctx)`, which runs on the main thread:
    1. It returns unless prefs `lock.live` is `"true"` and the world is sized.
    2. It renders `renderStill(world, realW, realH, 0.42f, 0.18f)` for the display whose aspect matches the world (as `LockStills` finds real sizes).
    3. On a single-thread executor, it calls `WallpaperManager.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)`.
    4. It catches `IOException`/`RuntimeException` and logs them with the `DotHome` tag, then recycles the bitmap.
  - `PrefsStore.KEY_LOCK_LIVE = "lock.live"` (default `"false"`)

- [ ] **Step 1: Write failing tests** in `LockGateTest`:
  - `testFirstPushAllowed`.
  - `testThrottledUnderAMinute`: with a new fingerprint 59 s later → false. 60 s → true.
  - `testUnchangedFrameSkipped`: the same fingerprint 10 min later → false.
  - `testFingerprintChangesWithScoreAndCell`: the score +1 changes it. Moving the ship 1/8 of the width changes it. Moving it 1 px doesn't (away from cell edges).
- [ ] **Step 2: Run** `./test.sh`. Expected: compile FAIL.
- [ ] **Step 3: Implement.**
  - In `renderStill`, rock, particle, shot, ship and ring dots below `(1 - clearBottomFraction) * height` are skipped, the same way as the top band.
  - The Setup switch "Lock screen shows the game (beta)" is off by default and stored as `"true"`/`"false"`.
  - `DotWallpaperService` calls `LockWallpaper.onScreenOff` on `ACTION_SCREEN_OFF` after `onScreenOff()`.
- [ ] **Step 4: Run** `./test.sh` and `./build.sh`. Expected: pass and a clean build.
- [ ] **Step 5: Commit** with the message `feat(lock): optional live lock still on screen-off (throttled, default off)`.

### Task 8: Bears core (ESPN parsing and what to show)

**Files:**
- Create: `core/BearsGame.java`, `core/EspnParser.java`, `core/BearsView.java`
- Fixtures (already committed): `tests/fixtures/espn-chi-schedule.json` (3 events: TB@CHI final 2026-11-09T01:20Z, NO@CHI pre 2026-11-22T18:00Z, CHI@DET pre 2026-11-26T18:00Z), `espn-scoreboard-halftime.json` (WSH@NYG final, plus a halftime game where CHI is home 10 and LAC is 17; edited from a real response), `espn-scoreboard-q3.json` (the same game edited to Q3 8:42, in progress)
- Test: `tests/dev/gawel/dothome/core/BearsTest.java`

**Interfaces:**
- Produces:
  - `BearsGame`:
    - `enum State { PRE, IN, POST }`
    - `final State state`
    - `final long kickoffMs`
    - `final String opp` (abbreviation)
    - `final boolean home`
    - `final int chi, oppScore` (−1 when unknown)
    - `final int period`
    - `final String clock` (displayClock)
    - `final String detail` (`status.type.shortDetail`)
  - `EspnParser`:
    - `static List<BearsGame> schedule(String json)` (empty on garbage)
    - `static BearsGame scoreboard(String json)` (the event with a CHI competitor, or null). Scores are a `{displayValue}` object in the schedule and a plain string on the scoreboard; accept both.
    - `static final String SCHEDULE_URL = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/teams/chi/schedule"`
    - `static final String SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"`
  - `BearsView`:
    - `enum Mode { BLANK, PRE, LIVE, FINAL }`
    - `static BearsGame today(List<BearsGame> games, long nowMs, TimeZone tz)`: the game whose kickoff's local date equals now's local date.
    - `static Mode mode(BearsGame g)`: null → BLANK, PRE → PRE, IN → LIVE, POST → FINAL.
    - `static String preText(BearsGame g, TimeZone tz)` → `"SUN 12:00 · vs NO"` at home or `"THU 12:00 · @ DET"` away. The day is the 3-letter upper-case day and the time is `h:mm` local.
    - `static String liveLabel(BearsGame g)` → `"Q3 8:42"`, or `"HALF"` when the detail is "Halftime", or `"OT 2:00"` for period ≥ 5.
    - `static boolean pollNow(Mode m, long kickoffMs, long nowMs, long lastFetchMs)`: true if (LIVE, or PRE with now ≥ kickoff − 10 min) and now − last ≥ 55 s. Otherwise true only if now − last ≥ 6 h.

- [ ] **Step 1: Write failing tests** in `BearsTest` (tz America/Chicago):
  - `testScheduleParses`: 3 games. The first is POST with chi 27, TB 24, and home. The second is PRE vs NO at home with kickoff 2026-11-22T18:00Z.
  - `testEveningKickoffIsLocalDay`: at 2026-11-08 22:00 CST, `today` is the TB game → FINAL. At 2026-11-09 10:00 CST → null → BLANK.
  - `testPreText`: at 2026-11-22 09:00 → `"SUN 12:00 · vs NO"`. For 11-26 → `"THU 12:00 · @ DET"`.
  - `testScoreboardHalftime`: CHI 10, LAC 17, state IN, `liveLabel` `"HALF"`. The WSH@NYG event is skipped.
  - `testScoreboardQ3`: `"Q3 8:42"`.
  - `testPollPolicy`: LIVE with last 56 s ago → true, and 30 s ago → false. PRE 5 min before kickoff with last 60 s ago → true. PRE 2 h before with last 1 h ago → false. BLANK with last 6 h + 1 ms ago → true.
  - `testGarbageIsNull`: `schedule("{}")`, `schedule("<html>")` and `schedule(null)` → empty. `scoreboard("<html>")` → null.
- [ ] **Step 2: Run** `./test.sh`. Expected: compile FAIL.
- [ ] **Step 3: Implement** on `MiniJson`. Catch `RuntimeException` around the parse and return empty or null.
- [ ] **Step 4: Run** `./test.sh`. Expected: pass.
- [ ] **Step 5: Commit** with the message `feat(bears): ESPN schedule/scoreboard parsing and game-day view rules`.

### Task 9: Dot Bears widget (plus feed plumbing and the preview tool)

**Files:**
- Create:
  - `BearsWidget.java` (AppWidgetProvider)
  - `BearsRepo.java` (fetch and cache, prefs `bears.schedule`, `bears.board`, `bears.at`)
  - `BearsArt.java` (bitmap drawing)
  - `LiveFeeds.java`
  - `FeedJob.java` (JobService, id 803, periodic 6 h, network any)
  - `res/layout/widget_bears.xml` (FrameLayout: `card_img` ImageView + `bears_img` ImageView)
  - `res/layout/widget_bears_empty.xml` (a single FrameLayout)
  - `res/xml/widget_bears_info.xml` (min 250×76 dp, `previewImage="@drawable/preview_bears"`, `initialLayout="@layout/widget_bears_empty"`, `updatePeriodMillis="0"`)
  - `res/drawable-nodpi/preview_bears.png`
  - `tools/WidgetPreview.java` (a java.awt CLI)
- Modify: `AndroidManifest.xml` (receiver labelled `@string/widget_bears_label` = "Dot Bears", and `FeedJob`), `res/values/strings.xml`, `DotWallpaperService.java` (`widgetTick` → `LiveFeeds.onMinute`), `GameHub.onVisible` (`FeedJob.schedule`)
- Test: additions to `tests/dev/gawel/dothome/core/WidgetResourcesTest.java`

**Interfaces:**
- Consumes: `EspnParser`, `BearsView` (Task 8), `WidgetArt.card`, `WidgetUpdater.shapeRadiusDp`, `Fonts.mono`, `DotMatrixFont`.
- Produces:
  - `LiveFeeds.onMinute(Context ctx)`: called on the main thread once a minute while the screen is on. It hands any due fetch to one background executor, then updates the widgets on completion. Task 11 adds Blue Line to it.
  - `FeedJob.schedule(Context)` (idempotent, as `WeatherJob.schedule`).
  - `BearsWidget.updateAll(Context)`.
  - `tools/WidgetPreview.java`:
    - Usage: `java -cp build/test:tools WidgetPreview <kind> <out.png>`.
    - Kinds are `bears` and `blueline`. The tool renders at density 2.625 onto a #000 rounded card with the 24 dp-corner glass look (approximated: a flat L10 fill), using JetBrains Mono from `app/src/main/assets/fonts/`.
    - Build it with `javac -cp build/test -d build/test tools/WidgetPreview.java` after `./test.sh`.

- [ ] **Step 1: Write failing tests** in `WidgetResourcesTest`:
  - `testBearsWidgetResources`:
    - `widget_bears_info.xml` has `previewImage="@drawable/preview_bears"` and `initialLayout="@layout/widget_bears_empty"`.
    - The empty layout has exactly one FrameLayout and no ImageView.
    - `strings.xml` has `widget_bears_label` = `Dot Bears`.
    - `drawable-nodpi/preview_bears.png` exists.
  - `testNoPlainViewInWidgetLayouts`: no `layout/widget_*.xml` file contains `<View ` or `<View>`.
- [ ] **Step 2: Run** `./test.sh`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **BearsArt layouts** (16 dp padding, left-aligned, on the glass card when `WidgetArt.backdropOn`):
    - **LIVE/FINAL:** row 1 has "CHI" with its score, a gap, then the opponent abbreviation with its score. Digits use the matrix font at step 8 dp. Team labels are 13 dp mono caps at L65. Row 2 is `liveLabel` or `FINAL`, 11 dp mono at L65. The order follows away @ home: the away team is on the left.
    - **PRE:** one 13 dp mono line of `preText`.
    - **BLANK:** push `widget_bears_empty`.
  - **BearsRepo.refresh** (blocking, on a worker):
    - Fetch the schedule if 6 h have passed. Fetch the scoreboard when `BearsView.pollNow` says so on game day.
    - Use 10 s timeouts. Failures keep the cache.
    - Log only the status codes.
  - **LiveFeeds.onMinute** checks that at least one widget exists before any network.
- [ ] **Step 4: Generate the preview.** Run `./test.sh && javac -cp build/test -d build/test tools/WidgetPreview.java && java -cp build/test WidgetPreview bears app/src/main/res/drawable-nodpi/preview_bears.png`. Expected: the PNG is written. Then run `./test.sh` and `./build.sh`: all pass and the build is clean.
- [ ] **Step 5: Commit** with the message `feat(bears): Dot Bears widget (pre/live/final on game day, blank otherwise)`.

### Task 10: Blue Line core (stations, CTA parsing, platform board)

**Files:**
- Create: `core/BlueLineStations.java`, `core/CtaParser.java`, `core/PlatformBoard.java`, `core/CtaUrls.java`
- Fixtures (already committed): `tests/fixtures/cta-blue-stations.csv` (33 stations from the City of Chicago "L" stops dataset 8pix-ypme, `blue=true`, unique `map_id`), `cta-arrivals.json` (in the documented `ttarrivals` JSON shape, Clark/Lake, tmst 2026-11-17T08:00:00), `cta-arrivals-badkey.json` (errCd 101)
- Test: `tests/dev/gawel/dothome/core/BlueLineTest.java`

**Interfaces:**
- Produces:
  - `BlueLineStations`:
    - `static final int[] IDS`, `static final String[] NAMES`, `static final double[] LAT, LON` (the 33 rows of the CSV, in its order)
    - `static int nearest(double lat, double lon, double maxKm)` → a mapid, or −1 beyond `maxKm`
    - `static String label(int mapid)` → the upper-case name, with these disambiguations: 40670 `WESTERN (O'HARE BR)`, 40220 `WESTERN (FOREST PK BR)`, 40750 `HARLEM (O'HARE BR)`, 40980 `HARLEM (FOREST PK BR)`
    - `static int index(int mapid)` (−1 if unknown)
  - `CtaParser.parse(String json)` → a `CtaBoard`, never null. On garbage it is `errCd = -1` with empty lists.
  - `CtaBoard`:
    - `final int errCd`
    - `final List<Arrival> ohare, forestPark`, each sorted by minutes
  - `Arrival`:
    - `final String dest` (upper case)
    - `final int minutes` = floor((arrT − tmst)/60 s), clamped to ≥ 0
    - `final boolean due` (`isApp == "1"` or minutes ≤ 1)
  - The direction comes from `trDr`: "1" is O'Hare-bound and "5" is Forest Park-bound.
  - `PlatformBoard.text(Arrival a)` → `"DUE"` or the minutes as digits. `static final int ROWS = 3`.
  - `CtaUrls.arrivals(String key, int mapid)` → `https://lapi.transitchicago.com/api/1.0/ttarrivals.aspx?key=<urlencoded>&mapid=<id>&rt=Blue&outputType=JSON`

- [ ] **Step 1: Write failing tests** in `BlueLineTest`:
  - `testTableMatchesCsv`: every CSV row equals the table row (id, name, lat and lon to 1e-6). There are 33 rows.
  - `testNearest`: (41.8857, −87.6309) → 40380. (41.8735, −87.8069) → 40980. (40.7128, −74.0060) with 10 km → −1.
  - `testLabels`: `label(40380)` is `CLARK/LAKE`, and the four disambiguations are as listed.
  - `testParsesFixture`:
    - The O'Hare column is [due O'HARE 0 → "DUE", JEFFERSON PARK 7, O'HARE 15, O'HARE 24].
    - The Forest Park column is [FOREST PARK 4, UIC-HALSTED 12, FOREST PARK 20].
    - `PlatformBoard.text` of the first O'Hare arrival is "DUE", and of the first Forest Park arrival is "4".
  - `testGarbageAndBadKey`: the bad-key fixture gives errCd 101 and empty lists. `"<html>"` and `null` give errCd −1 and empty lists.
  - `testUrlEncodesKey`: the key `a b&c` appears as `a+b%26c`, and the URL contains `mapid=40380` and `outputType=JSON`.
- [ ] **Step 2: Run** `./test.sh`. Expected: compile FAIL.
- [ ] **Step 3: Implement.** The distance is equirectangular in km. Times are parsed with `LocalDateTime.parse`; both are local, so there is no zone math.
- [ ] **Step 4: Run** `./test.sh`. Expected: pass.
- [ ] **Step 5: Commit** with the message `feat(cta): Blue Line stations, ttarrivals parsing and platform board model`.

### Task 11: Dot Blue Line widget and Setup

**Files:**
- Create:
  - `BlueLineWidget.java`
  - `CtaRepo.java` (prefs `cta.key`, `cta.station` (mapid, −1 = nearest), `cta.board`, `cta.at`)
  - `BoardArt.java`
  - `res/layout/widget_blueline.xml`, `widget_blueline_empty.xml`
  - `res/xml/widget_blueline_info.xml` (min 250×110 dp, `previewImage="@drawable/preview_blueline"`, `initialLayout="@layout/widget_blueline_empty"`)
  - `res/drawable-nodpi/preview_blueline.png`
- Modify: `AndroidManifest.xml` (receiver, label `@string/widget_blueline_label` = "Dot Blue Line"), `strings.xml`, `LiveFeeds.java`, `SetupActivity.java`, `tools/WidgetPreview.java` (the `blueline` kind)
- Test: additions to `WidgetResourcesTest`

**Interfaces:**
- Consumes: Task 10 core, `LiveFeeds.onMinute` (Task 9), `LocationSource` (use its cached place only, with no new location request from the minute tick).
- Produces: `BlueLineWidget.updateAll(Context)`.

- [ ] **Step 1: Write failing tests** in `WidgetResourcesTest`:
  - `testBlueLineWidgetResources`: the same checks as for Bears, using `widget_blueline_*`, `preview_blueline` and "Dot Blue Line".
- [ ] **Step 2: Run** `./test.sh`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **Layout** (16 dp padding):
    - The station label is 13 dp mono at L65.
    - Below it, two columns headed `O'HARE` and `FOREST PK` (11 dp mono at L46), separated by a lattice dot-row divider.
    - Each column has up to `PlatformBoard.ROWS` rows: the destination is 11 dp mono at L65 on the left, and the minutes sit on the right in matrix digits at step 4 dp. "DUE" is 11 dp mono at L100.
  - **No key:** the widget shows the single 11 dp mono line `ADD CTA KEY IN DOT HOME` at L65.
  - **Error codes:** with a key, an errCd other than 0 keeps the last good board if it is under 10 min old, and is blank otherwise.
  - **Refresh:** `LiveFeeds.onMinute` fetches when the widget exists, the key is set, and the last fetch was at least 55 s ago.
  - **Logging:** errCd and HTTP status only. Never the URL or the key.
  - **Setup** gets a "Blue Line" section in the existing style:
    - an `EditText` with `inputType` textPassword and the hint "CTA Train Tracker key"
    - a "Save key" button (it trims the key, stores it and triggers an update)
    - a station `Spinner`, "Nearest (auto)" followed by the 33 labels, stored in `cta.station`
- [ ] **Step 4: Generate the preview** with `java -cp build/test WidgetPreview blueline app/src/main/res/drawable-nodpi/preview_blueline.png`. The preview uses the fixture board. Then run `./test.sh` and `./build.sh`: pass and a clean build.
- [ ] **Step 5: Commit** with the message `feat(cta): Dot Blue Line platform board widget and Setup key/station`.

### Task 12: Beat pulse

**Files:**
- Create: `core/BeatDetector.java`, `BeatPulse.java` (Visualizer glue)
- Modify: `core/GameConfig.java`, `DotRenderer.java` (star radius and alpha), `GameHub.java` (start/stop in `tick1s` and `onHidden`), `SetupActivity.java` (switch), `AndroidManifest.xml` (`RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`)
- Test: `tests/dev/gawel/dothome/core/BeatDetectorTest.java`

**Interfaces:**
- Produces:
  - `BeatDetector`:
    - `static final float BASS_HZ = 150f`
    - `static int bassBins(int captureSize, int samplingRateMilliHz)`: the count of bins k ≥ 1 with `k * rate/1000 / captureSize ≤ 150`.
    - `void onFft(byte[] fft, int samplingRateMilliHz, long nowMs)`: `fft` is in `Visualizer.getFft` layout, where [0] is DC re, [1] is Nyquist re, and then (re,im) pairs for k = 1..n/2−1.
    - `float level(long nowMs)` → 0..1, the pulse decayed to `nowMs`, allocation-free.
  - `GameConfig`: `beatScale = 0.3f` (radius ×1.0–1.3), `beatAlpha = 0.15f`
  - `DotRenderer.setBeat(float pulse)`
  - `BeatPulse`: `boolean start()` (false on any failure), `void stop()`, and `float level(long nowMs)`
  - `PrefsStore` key `beat.on` (default `"false"`)

- [ ] **Step 1: Write failing tests** in `BeatDetectorTest`, with a capture size of 1024 at 44100000 mHz and frames every 20 ms:
  - `testBassBins`: (1024, 44100000) → 3. (512, 48000000) → 1.
  - `testSilenceIsZero`: 3 s of zero frames → `level == 0`.
  - `testConstantToneFades`: a constant bass bin at amplitude 100 for 3 s → level < 0.1 at the end.
  - `testKicksPulse`: a 100 ms bass burst every 500 ms for 4 s → over the last 2 s, the max level is > 0.7 and the min is < 0.2.
  - `testTrebleIgnored`: bursts only in bin 40 → level stays 0.
  - `testLevelDecaysBetweenFrames`: after a burst, `level(t+300ms)` < half of `level(t)`.
- [ ] **Step 2: Run** `./test.sh`. Expected: compile FAIL.
- [ ] **Step 3: Implement the detector.**
  - Energy `e` is Σ(re²+im²) over the bass bins.
  - `avg` is an EMA of `e` with τ = 0.5 s.
  - `flux = max(0, e - avg)`.
  - `peak = max(flux, peak * 0.5^(dt/4s))`, with a floor of 1.0. Below the floor the pulse is 0.
  - `v = flux/peak`. The pulse takes `max(pulse_decayed, v)`, with an instant attack and an exp release of τ = 0.15 s.
  - `level` applies the release up to `nowMs`.
- [ ] **Step 4: Implement the glue.**
  - **BeatPulse:**
    - It creates `new Visualizer(0)` with a capture size of 1024, clamped into `getCaptureSizeRange()`.
    - It listens for FFT only, at `getMaxCaptureRate()/2`, and enables capture.
    - Callbacks feed the detector, whose state is guarded by `synchronized`. `level` reads it under the same lock and allocates nothing.
    - Any exception or a non-SUCCESS code releases it, logs once with the `DotHome` tag, and latches it as failed until the pref is toggled.
  - **GameHub.tick1s:** run it iff the pref is on, `RECORD_AUDIO` is granted, something is visible, and `MusicWidget.last()` is non-null with `.playing`. Otherwise stop it. Stop it in `onHidden` when nothing is visible.
  - **Each frame:** `renderer.setBeat(beat.level(now))`.
  - **Renderer:** star radius ×`(1 + beatScale*pulse)`, and alpha `+ beatAlpha*pulse` (capped at 1). Stills ignore the beat.
  - **Setup switch** "Stars pulse to music (uses the microphone permission)": turning it on requests `RECORD_AUDIO`. A denial turns the switch back off.
- [ ] **Step 5: Run** `./test.sh` and `./build.sh`. Expected: pass and a clean build.
- [ ] **Step 6: Commit** with the message `feat(game): stars pulse to the beat (Visualizer bass onsets, opt-in)`.

### Task 13: Nose retro thrusters (user request, 2026-11-16; runs right after Task 1)

Source: `docs/request-retro-thrusters.md` (David's request, relayed by the laptop session). It is binding, and it wins over this summary if they differ.

**Files:**
- Modify: `core/GameConfig.java` (`retroThrust`, about 0.45 × `thrust`; `RETRO_CONE` 30°), `core/GameWorld.java` (the pilot and the Newtonian step), `core/ShipShape.java` (the two nose retro nozzle positions, if the renderer needs them), `DotRenderer.java` (the retro plumes)
- Test: `tests/dev/gawel/dothome/core/ShipFlightTest.java`, `tests/dev/gawel/dothome/core/ManualModeTest.java`

**Interfaces:**
- Produces:
  - `GameWorld.retroThrust()` → 0..1, the retro level this step (an event the renderer reads, like `nozzleThrust`).
  - `GameConfig.retroThrust` (px/s²).

**Behaviour:**
- Two nose retros push the ship opposite its heading at `retroThrust × level`.
- They fire when the needed acceleration (the velocity error) points within 30° of straight behind the ship, with level = `cos(error)` scaled by the command. They can combine with RCS turning.
- The main engine still accelerates only forward, inside its 20° window.
- **Braking never flips,** on the autopilot or in manual mode. The wanted heading while braking stays toward the goal or the direction of travel, and it slows on the retros.
- Re-size the braking lead (the `flipTime`-based planner) and the arrival slowdown for the retro deceleration, so the ship stops on the point without overshooting. It starts slowing a little earlier.
- **Unchanged:** RCS turn rates, the drag and zero-g feel, emergency-only avoidance, instant retarget, guns, barrage and waypoints. Large direction changes still turn the ship with RCS.
- **Visuals:** two small forward plumes from the nose while the retros fire. They are the same dot-plume style as the main and RCS plumes but smaller, L65 fading to L46. They come from the renderer's existing fixed FX pool, never red, and nothing is allocated.

- [ ] **Step 1: Write failing tests.**
  - `testRetroBrakingNoFlip` (ShipFlightTest): on a clean 300k px trip with no rocks, in manual mode, the heading never strays more than 60° from the bearing to the goal. The final heading at rest is within 30° of the travel direction.
  - `testArrivesWithRetros` (ManualModeTest): it settles at the waypoint. Measure the time, then pin it with +20% slack.
  - `testOppositeTapStillReverses` (ManualModeTest): an opposite-direction tap mid-flight still reverses (a turn is expected). Measure the bound, then pin it with slack.
  - `testAutopilotWanderBrakesWithoutFlip` (ShipFlightTest): a wander leg also brakes without a flip, under the same 60° bound.
  - `testRetrosDeterministic`: the same seed gives the same trajectory.
  - Replace `ShipFlightTest.testFlipAndBurnToBrake` and `ManualModeTest.testTapAheadDropsABrakingFlip` with retro equivalents. Each keeps the property it guarded: braking happens, and a newer tap drops the old braking.
- [ ] **Step 2: Run** `./test.sh`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** in the pilot and step, then add the renderer plumes.
- [ ] **Step 4: Run** `./test.sh` and `./build.sh`. Expected: all pass, and the build is clean.
- [ ] **Step 5: Commit** with the message `feat(ship): nose retro thrusters, braking without the flip`.
