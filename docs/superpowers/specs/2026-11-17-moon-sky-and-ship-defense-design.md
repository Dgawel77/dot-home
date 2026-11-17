# Moon in the sky + ship defence: short design (2026-11-17)

## Why

David likes the real moon phases. He wants the moon to look more like the moon (stylized, in dots), to twinkle
and change a little, and to move across the sky as the time goes by. For the ship, he wants it to shoot a rock
that comes too close instead of only swerving. His words: "do what it thinks is best, shoot or avoid, whichever
one is best".

## Decisions (David)

- Moon path: it follows the real moon. It is up when the real one is, by day as well as by night.
- Moon look: dark seas, crater rims, and a faint shimmering halo, at the same 40 dp size.
- Ship: it decides for each threat whether to shoot it or dodge it.

## Approaches

- **Moon position.**
  - Chosen: real sky maths, using a low-precision lunar and solar ephemeris from the clock and the last known
    location. It gives rise and set times to within minutes and the real arc height for each night.
  - Rejected: a phase shortcut, where the moon rises at phase × 24 h after the sun. Its rise times are 1 to 2 h
    off, and it would draw the same arc every night, so it fails "follow the real moon".
- **Ship.** There is one sensible way: a single rule picks shoot or dodge for each threat, which is what David
  asked for.

## Moon

- **Where it is.** A new core class, `MoonSky`, computes the moon's altitude and azimuth, and the sun's, from
  epoch ms, latitude and longitude.
  - It is recomputed once a minute and moves smoothly between updates.
  - By day it is drawn at half alpha.
  - Over its last 2° above the horizon it fades out.
- **Where it goes on screen.** The view faces south.
  - Azimuth 60° to 300° (east to west) maps to x from 8% to 92% of the width.
  - Altitude 0° to 75° maps to y from the bottom to the top of the open sky between the widget zones. On the main
    screen that is 64% to 30% of the height; the cover screen uses its own band.
- **Look.** It stays on the 4 dp lattice with a 40 dp disc, and keeps today's phase maths (`MoonPhase`).
  - The lit side is L65.
  - Stylized seas are L30, laid out like the real near side: Imbrium, Serenitatis, Tranquillitatis, Procellarum
    and Nubium.
  - A few crater rims are L100: Tycho, Copernicus and Kepler. Lock stills cap them at L65.
  - The shadow side is L10.
  - A halo of sparse L10 dots sits from 1.25 to 1.35 R.
- **Twinkle.**
  - Each halo dot shimmers on its own slow cycle of 3 to 6 s.
  - Every 8 to 15 s one crater rim glints, using the stars' glint style.
- **Change.** The lit side turns toward the sun's position on screen, using the same mapping. The face therefore
  tilts through the night the way the real moon does.
- **Unknown location.** The moon goes back to today's fixed corner. With unknown sun times it is hidden, as today.

## Ship defence

- **Spotting a threat.** Each step, the ship looks for the rock that is soonest on a collision course within
  2 s. It then estimates how long a shot would take: the RCS turn to the lead-aim bearing, plus any fire
  cooldown left, plus the bolt's flight time.
- **Shoot or dodge.**
  - If that estimate plus a 0.15 s margin is less than the time to impact, the ship shoots. It turns to the
    lead-aim bearing and fires once inside `gunCone`, at the intercept point.
  - Otherwise it uses today's emergency dodge, on today's horizons: 1 s on autopilot and 0.5 s in manual mode.
- **Manual mode (flying to a tap).** It shoots only when the turn is 40° or less, so it keeps following the tap.
  Otherwise it dodges, as today.
- **Fire modes.** It works in every fire mode, because it is defence.
- **Big rocks.** Shooting a big rock splits it. The fragments are judged again on the next step: shoot, dodge or
  take the bump.
- **Unchanged.** Engines, retros, RCS limits, the zero-g feel, and the tap's waypoint.

## Tests (core, JVM)

- **Moon position.** `MoonSky` altitude and azimuth match published Chicago moonrise, transit and moonset times
  within 10 minutes on several dates. The bright-limb angle points toward the sun.
- **Ship, rock dead ahead.** It shoots, the rock is gone before contact, and it never dodges.
- **Ship, rock from behind.**
  - Fast and close: it dodges.
  - Slow and far: it turns and shoots.
- **Ship, manual mode with a rock at 90°.** It dodges, and its heading stays with the waypoint.
- **Determinism.** It holds as today.

## Left to the builder

- The exact sea and crater dot maps.
- The halo dot spacing.
- How the motion is smoothed between the once-a-minute updates.
- The cover screen's sky band, read from `Zones`.
