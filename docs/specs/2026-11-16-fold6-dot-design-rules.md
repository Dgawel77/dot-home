# Fold6 Dot Home: Design Rules

- **Date:** 2026-11-16
- **Status:** draft, awaiting the user's review. Rules marked **needs OK** change an earlier mockup decision and stay proposals until the user agrees.
- **Applies to:** everything the dot home app draws: the game wallpaper, the widgets, the radar screen, the lock-screen stills and the Always On Display image. It also applies to every board on the mockup canvas (https://claude.ai/artifact/KjSXGZLhXadNegC6B5PYpL).
- **Parent spec:** [2026-11-15-fold6-home-screen-design.md](2026-11-15-fold6-home-screen-design.md). Once these rules are approved, they replace its "Visual direction" and "Mockup decisions" sections wherever the two disagree.

## Why this exists

Each mockup board looks fine on its own, but the seven boards don't read as one system. Across them there are about 15 dot spacings, 27 greys, 14 font sizes, 10 corner radii and two unrelated dot typefaces. These rules fix one shared set of values, so the build and any later boards stay consistent.

Units: mockup px = Android dp.

## What the references actually do

### The ChatGPT Super Bowl LIX ad

Claude watched the ad frame by frame on 2026-11-16 ([YouTube TdfhtaxCh5c](https://www.youtube.com/watch?v=TdfhtaxCh5c), 60 s at 1280×720). It sampled a frame about every 2.5 s, plus a few frames during transitions, and measured dots from the decoded pixels. Timestamps are m:ss.

| Trait | What the ad does | Where |
|---|---|---|
| Polarity | Mostly black dots on white. Scenes flip to white on black and back, and it ends on white. | white bg: 0:00–0:09, 0:12–0:20, 0:29–0:30, 0:37, 0:55–1:00; black bg: 0:10–0:11, 0:22–0:27, 0:32–0:35, 0:40–0:52 |
| Grid | The dots sit on **one fixed screen grid**. Dot centres land on multiples of 20 px in two frames 0.25 s apart while the horse moves. Motion means dots switching on and off, like an LED panel; they don't slide. | 0:15.00 vs 0:15.25; landscape at 0:17.5 |
| Dot size | About **60% of the grid step** for most shapes (12 px dots on a 20 px step). About **85–90%** for heavy foreground shapes. | 0:11.3, 0:17.5 (60%); horse at 0:15 (85–90%) |
| Resolution | The grid gets finer as history moves forward: a 20 px step early, about 12 px later, then about 8 px. | 0:17.5 → 0:42.5 → 0:45 |
| Shapes | Filled silhouettes, not outlines. One-dot-wide lines only where the object is thin. | figures 0:07.5, spear, wheel spokes 0:13.8 |
| Halftone (size = tone) | Only in photo-like scenes, on the same square grid. Size also shows depth on the DNA helix. | photo-like scenes 0:25, 0:35, astronaut 0:42.5; DNA 0:37.5 |
| Free dots (off the grid) | The single starting dot, giant single dots as celestial bodies, scattered transition dots, and a closing starfield whose dots stream along curves and shrink along the stream. | 0:00–0:05, 0:10, 0:29–0:30, 0:50 |
| Squash and stretch | The first dot pulses, then stretches into a blob and splits. A row of dots stretches into pills; they resemble ChatGPT's voice-mode indicator. | 0:04–0:05, 0:47.5, 0:48.8 |
| Interface | A window drawn entirely in dots: a dotted border, a title bar of dot glyphs, and a dotted connector. | 0:45 |
| Type | Words are never dot type. A one-line tagline is set in a plain sans, centred, **mid-grey** (sampled at about #969696) on black. The wordmark types on in black. | 0:52.5, 0:57.5 |
| Bookends | It starts on one dot and returns to one dot before the end card. | 0:00, 0:55 |
| Colour | None. | whole spot |

The ad is a dot-matrix display far more than it is free pointillism. That puts it much closer to Nothing than earlier notes suggested: both are grid-based. The two references differ in grid scale and in what the dots carry, not in grid versus scatter.

### Nothing OS

These sources are community and third-party, not official Nothing documentation.
- Dot-matrix type is used for display numerals and headlines.
- A plain sans is used for reading.
- Small monospaced caps are used for labels.
- Nothing reportedly cut back its own dot font in OS 3.0 because it was hard to read.
- The OS is monochrome with one red accent. This spec leaves the red out, as the parent spec already does.

## Rules

### 1. One lattice

- There is one fixed screen grid (the **lattice**) per screen, with a **4 dp step**. It never moves; shapes move across it.
- **Drawn on the lattice:** rocks, the moon, milestone shapes, the radar map and storm, album art, every dot-matrix numeral, progress rows, dividers and borders.
- **Free dots** (off the lattice) are allowed only for:
  - the hero dot: the opening dot and the ship
  - shots
  - particles in flight: debris, the charging stream, snow and rain
  - stars
  This matches the ad: free dots are single, meaningful, or in transit.
- **Needs OK: the game is rasterised onto the lattice.**
  - Each frame, a lattice cell lights when a shape covers it, so rocks become filled silhouettes.
  - Edge cells get a smaller dot in proportion to how much of the cell the shape covers. This anti-aliases with dot size and keeps edges from flickering.
  - This replaces today's free-floating outline dots with random stipple.
  - Before building, compare the two looks side by side as a Tweak on the Main board.
- Coarser steps are multiples of 4 (rule 2). Their dots therefore land exactly on fine-lattice points, so the clock lines up with the game behind it.

### 2. Dot steps and weights

**Steps:**

| Step | Used for |
|---|---|
| 4 dp | game, map, halftone images, small numerals (about 26 dp tall) |
| 8 dp | cover-screen clock, main temperature, scores, train minutes |
| 12 dp | main home clock |
| 16 dp | lock-screen clocks |

No other steps.

**Weights** (dot diameter as a share of the step):

| Weight | Dot Ø / step | Used for |
|---|---|---|
| light | 30% | texture: star field, map land speckle, rock bodies (the user's 1.2 dp at 4 dp "fine" pick) |
| regular | 60% | numerals, rock rims, every dot line (the ad's normal weight) |
| heavy | 85% | one focus element at a time: the ship, a lit progress head, a live score (the ad's foreground weight) |

Dots are always round. They are never squares, and never CSS `dotted` borders, which can render as squares.

### 3. Polarity

- Every surface is white on black: home, lock, AOD and radar. The phone has an OLED screen, and it's used at night.
- There are no polarity flips. The ad's white scenes don't carry over to a home screen.

### 4. Greys

Every grey is white at one of four opacities. Text and dots use the same four values.

| Level | Hex on black | Text | Dots |
|---|---|---|---|
| 100% | #ffffff | primary | lit clock dots, the ship, shots, the active state of a control |
| 65% | #a6a6a6 | secondary | the game on lock stills and the AOD, rock rims |
| 46% | #757575 | tertiary (the lowest that passes 4.5:1 on black) | map lines, inactive progress |
| 10% | #1a1a1a | none | unlit matrix cells |

- The ground is #000000. The canvas's #111111 backdrop and device outlines are mockup framing, not design values.
- **Only the clock and what the user controls reach 100%.** The rest of the game sits at 65% or below, so the information wins.
- Star twinkle and particle fades may pass between levels while they move, but they must come to rest on a level.
- Unlit cells (10%) show only where a matrix needs its grid to read: the clock and the progress rows. They don't appear behind everything.
- **AOD: nothing above 65%.** This is for battery and burn-in.

### 5. Type

| Role | Face | Sizes | Used for |
|---|---|---|---|
| Matrix | our own 5×7 dot font (see below) | lattice steps 4 / 8 / 12 / 16 | numbers only: time, temperature, score numbers, game scores, train minutes, radar time offset, battery % |
| Text | Inter (OFL) | 13 / 15 / 20 dp | sentences, track and artist, place names, screen titles |
| Label | JetBrains Mono (OFL), caps, +1 dp tracking | 11 dp | metadata and status words: TODAY · BEST, GAME DAY, LIVE, FINAL, CHI, GB, O'HARE, FOREST PARK, DUE, LIGHT/HEAVY, city labels on the map |

- **No words in dot type.** The ad never does it, and Nothing reportedly pulled back from it.
- Nothing smaller than 11 dp. The radar mockup's 9 dp labels go up to 11.
- Spaced caps use letter-spacing, never inserted spaces.
- **Needs OK: Doto is retired.** It was the font for small numbers. Doto's dot grid doesn't match ours, so 41° beside the clock reads as a second dot system. Small numbers move to the matrix font when they are about 26 dp or taller. Anything smaller, such as the score line, is set as a mono label instead.
- **The matrix font:**
  - It is built from the mockup's 5×7 bitmaps as a real font file (TTF), so widget `TextClock` and `TextView` can use it. It's our own design, so there are no licence questions.
  - Each lit cell is a round contour at regular weight. Every glyph has the same advance, so the time never jumps.
  - Glyphs: 0–9, `:`, `°`, `−`, `.` and `%`.
  - A second variant, **Matrix Grid**, draws every glyph as the full 5×7 cell grid. Stack two `TextClock`s showing the same time: the grid variant at 10% underneath and the normal variant at 100% on top. The unlit cells then always match the string shown.
  - Hours always have two digits (`hh:mm`), so the grid width stays fixed, as on a platform board.

### 6. Lines, containers and legibility

- **Icons are the only solid strokes:** 1.6 dp, round caps and joins, glyph only (already decided).
- **Every other line is a row of lattice dots at regular weight.** This covers progress bars, dividers, borders, the radar time scrubber, roads, state lines, the shore and the boxed route numbers (the box is drawn as a dot row). The ad's dotted window frame (0:45) is the model.
- **Progress is lit versus unlit cells:** 100% cells for elapsed time and 10% cells for what's left. It's the same look as the clock.
- **No containers:** no cards, tiles, panels, shadows, glows or gradients (widgets are bare, already decided).
- **Clear zones replace the scrim.** Behind each widget and each text block, the game leaves its lattice cells off.
  - Within 16 dp of a zone's edge, cells thin out in a fixed dither pattern, so the edge doesn't flicker.
  - Free particles crossing a zone drop to 10%.
  - This replaces the translucent black rounded rectangle on the home mockups and the CSS gradient on the radar header.
- **Radii:** 12 dp for album art, and a full pill for buttons and the radar's Area/Region switch. Nothing else is rounded, because nothing else is a container.

### 7. Images (halftone)

- Every raster image goes through **one filter**. That covers album art, the radar storm and the moon.
  - grey → contrast stretch → square lattice at step 4
  - dot Ø = √brightness × 85% of the step
  - cells below 10% brightness are skipped
- Use a square lattice, not the offset rows the Main mockup's album art uses. The ad's halftone scenes use a square grid, and so does everything else here.
- Dot size may also show depth. Near stars are bigger than far ones (the ad's DNA helix, 0:37.5).

### 8. Motion

- **Things arrive by gathering and leave by scattering.**
  - Arrivals: dots gather onto lattice cells.
  - Departures: lit cells release as free particles.
  - This already holds for the opening dot, the unfold burst, rock destruction and the milestone shapes. New moments follow the same pattern.
- **The opening dot** plays on the first show after unlock. It caps at 1.2 s and is never replayed on page swipes.
- **Squash and stretch belongs only to the hero dot and shots.** A shot may stretch into a short pill along its path instead of a three-dot trail, like the voice-mode pills at 0:47.5.
- **Tempos:**

  | Tempo | Duration | Used for |
  |---|---|---|
  | breath | 4 s | ambient: star twinkle, idle pulses |
  | signal | 2 s | colon blink, live dot, location ping |
  | event | 0.6–0.8 s | hit ring, burst, unfold |

  The beat pulse follows the music instead.
- **Easing:** cubic ease-out for arrivals and cubic ease-in for departures. Overshoot is allowed only for hero-dot moments: the opening dot and the unfold burst.
- **Widgets never animate.** Android home-screen widgets (`RemoteViews`) can't run per-dot animation. Everything that moves lives in the wallpaper.
  - **Needs OK:** on the phone, the widget clock has no pulse and no glow. The mockup's clock pulse and glow are dropped. Glow is gone everywhere, which fits rule 6.
  - If the user wants the pulse back, the other option is for the wallpaper to draw the clock. That costs tap and page-scroll alignment (see Open questions).

### 9. Layout

- All positions and sizes snap to the 4 dp grid.
- **Edge margins:** 24 dp on the main screen and 16 dp on the cover screen. Widget text, the score line and the dock all start on that margin. The mockups currently use 20, 24, 26, 32, 40, 44 and 56.
- **Alignment:** home screens are left-aligned. Lock screens and the AOD follow Samsung's clock position, and our elements centre on it.

## Board-by-board changes

These apply to the mockup canvas first, then to the build.

| Board | Changes |
|---|---|
| Main home | clock at step 12 with no glow · 41° in the matrix font at step 8 · score line as a mono label · progress bar as a lit/unlit dot row · scrim rectangle replaced by clear zones · margin 24 · game on the lattice (if OK'd) |
| Cover home | clock at step 8 · album-art placeholder through the halftone filter · score line as a mono label on the 16 dp margin · progress as a dot row |
| Lock screens | clocks at step 16 · the still caps at 65% (already 0.62) · the music pill is Samsung's Now Bar, so mock it only |
| AOD | lit dots ≤ 65% (now 82%) · 82% battery in the matrix font at step 4 · icons at 65% |
| Radar | header gradient replaced by a clear zone · route-number boxes drawn as dot rows · labels ≥ 11 dp in mono caps · tracking with letter-spacing · scrubber at step 4 · keep the three-size legend |
| Phase 3 widgets | drop the dotted cards so they sit bare · team and station names as mono labels · scores and minutes in the matrix font at step 8 · dividers as lattice dot rows |
| Unfold | edge guide dots on the lattice (now 8 dp) |

## Changes to earlier decisions (need the user's OK)

1. **Doto is retired.** Numbers use our own 5×7 matrix font, and small labels use mono caps. The earlier decision was Doto for small numbers.
2. **No clock glow anywhere, and no clock pulse in the widget.** Widgets can't animate. The earlier decision was a soft glow and a slow pulse.
3. **The game is rasterised onto a fixed lattice.** Rocks become filled silhouettes; the ship, shots and particles stay free. The earlier decision was free outline dots with stipple. Compare both on the canvas first.
4. **The AOD is capped at 65% white.** It was #d0d0d0 (82%).

## Open questions

- **Can a widget `TextClock` load a font from `res/font`?** Claude isn't sure. Test it in the throwaway test build at Phase 1, step 0.
  - Fallback 1: the wallpaper draws the clock. Pulse and dot migration become possible, but the clock's position must match a transparent tap target, and paging must not drift.
  - Fallback 2: keep Doto in the `TextClock` and accept two dot systems.
- **Three clock faces.** Home uses ours, the lock screen uses Samsung's, and the AOD uses ClockFace's. Decide which surfaces must match once the Samsung font options are known (already open in the parent spec).
- **How the lattice looks at 4 dp on the real screen.** Check for shimmer on the Fold6 before committing to rule 1's rasterised game.

## Checklist for any board or build

- [ ] Every colour is #000000 or one of the four greys.
- [ ] Every lattice dot sits on a multiple of 4 dp, at step 4, 8, 12 or 16.
- [ ] Dot weights are only 30%, 60% or 85% of the step.
- [ ] Text sizes are only 11, 13, 15 or 20 dp.
- [ ] No words in dot type.
- [ ] No solid lines except icon glyphs.
- [ ] No cards, shadows, glows or gradients.
- [ ] Only the clock and what the user controls reach 100% white.
- [ ] The AOD and lock stills stay at or below 65%.
- [ ] Nothing animates inside a widget.
- [ ] Motion uses the 4 s, 2 s or 0.6–0.8 s tempo.

## Sources

- [Super Bowl LIX Spot: OpenAI & ChatGPT (YouTube)](https://www.youtube.com/watch?v=TdfhtaxCh5c). Frames, dot sizes and grid positions were measured from the video on 2026-11-16.
- [Adweek: Super Bowl Instant Replay: OpenAI Offers a Pithy, Pointillist Chronicle of Human Ingenuity](https://www.adweek.com/brand-marketing/chatgpt-openai-super-bowl-ad/) (credits: Accenture Song and Droga5)
- [Nothing Community: Nothing OS evolution](https://nothing.community/d/61774-nothing-os-evolution) (community report that NDot was cut back in OS 3.0)
- [shadcn.io: Nothing design system](https://www.shadcn.io/design/nothing) (a third-party reading of Nothing's type roles, not official)

## Decisions (the user, 2026-11-16 afternoon)

- **Approved.** Build to these rules, the mockup canvas first and then the app.
- **Greys:** the doc's levels with a softer top. The ground is #000000. The 100% level is drawn as **#DDDDDD**, not #FFFFFF, because the user said pure white on black hurt their eyes. The 65% (#A6A6A6), 46% (#757575) and 10% (#1A1A1A) levels stay as specified.
- **All four "needs OK" items are approved:**
  1. Doto is retired.
  2. No clock glow, and no clock pulse in the widget.
  3. The game is rasterised onto the lattice. Show it side by side on the canvas, and keep an "outline rocks" setting on the phone so shimmer can be checked on the Fold6.
  4. The AOD is capped at 65%.
- **Matrix font in widgets:** the app draws its widgets as bitmaps (`WidgetArt`), not as `TextClock`, so the 5×7 matrix font is drawn in code from `DotMatrixFont`, and no TTF is needed. That settles the first open question.
- **Widget animation:** the user asked how a widget could animate. Widgets stay still for now. The way to get a moving clock later is to have the wallpaper draw it, following `onOffsetsChanged` so that it fades out when you swipe to another page. It's a separate experiment.
- **No clear zones on the wallpaper** (the user, on the device, 2026-11-16): "make the whole screen have the asteroids". The game draws behind the widgets as well. Rule 6's clear zones now apply only to the radar header.
- **Solid widget backgrounds** (the user, 2026-11-16): "the widgets have backgrounds that block out the background, I don't want the game to be too busy". Each widget draws an opaque #000000 rounded panel (24 dp corners) behind its content, so the game is hidden there. A Setup switch controls it, on by default. This overrides rule 6's "no containers" for widgets.
- **Icon stipple** (the user's pick "D"): a square grid of 48 cells, edge dots sized by coverage, and a flat tone of 0.85 + 0.15·L with no gradient.
- **One accent: red lasers** (the user, 2026-11-16). Shots are red laser bolts, the only colour in the system. The ship is being redesigned for a space feel, with inertia, rotation thrusters and a main engine. It is prototyped on the canvas first (the ShipV2 board).
