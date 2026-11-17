# Fold6 Home Screen Redesign: Design

- **Date:** 2026-11-15
- **Status:** draft, awaiting the user's approval. Nothing on the phone changes until it is approved, and each install step after that also gets its own go-ahead.
- **Device:** Samsung Galaxy Z Fold6 (SM-F956U1). It has two displays: the cover screen and the main screen.

## Intent

The user wants the phone to look slick, organized and black-and-white. The wallpaper should keep moving and become something they can play. Today the moving wallpaper comes from Wallpaper Engine (the Android companion app).

The user's direction is "we are gonna make all our own stuff". That means our own game wallpaper, our own widgets, our own weather and our own icons, all running on top of Samsung's home screen.

There are four surfaces:

| Surface | Use |
|---|---|
| Cover lock screen | regular |
| Cover home screen | regular |
| Main (unfolded) home screen | regular |
| Main lock screen | least used |

## Goals

1. **Game wallpaper on both home screens.** It is an original space shooter that never ends. The ship flies itself; tilting the phone drives effects (see the 2026-11-16 change), and tapping fires.
   - There are no lives and no game over. A rock that hits the ship knocks it a little, with a visible "ding".
   - The score is the number of rocks shot today, and it resets every day.
   - The best day is kept as the high score, shared by both screens.
   - It is drawn in white dots on black (see Visual direction).
   - The feel matches the user's current wallpaper, an endless dinosaur runner: always alive, ambient, and never in a lose state.
2. **Our own widgets:**
   - a time-and-weather widget for Chicago
   - music controls
   - both black and white and "slick"
3. **Our own radar screen,** opened from the weather widget.
4. **Our own black-and-white icon pack,** applied to Samsung's home screen and app drawer through Theme Park (Samsung Good Lock).
5. **Lock screens that match,** using Samsung's lock screen with black-and-white content.
6. **Organized layouts** on both home screens, with lots of open space so there is room to play.

## Non-goals

- **Replacing Samsung's home screen** (option B below). This is deferred. Revisit it only if taps never reach the wallpaper *and* auto-fire feels bad, or if Theme Park cannot make the icons black and white.
- **Theming inside apps.** The most we do there is turn on dark mode.
- **A playable game on the lock screens.** It would fight with swipe-to-unlock.
- **Copying Asteroids.** The game is our own take on the genre: its own name, ship, rocks, sounds and look. It does not reuse Asteroids' name, art or presentation.

## Anti-goals (failure even if it technically works)

- **Battery drain.** The game must not render, and the sensors must be off, whenever the wallpaper is not visible. That includes screen off, inside apps, and the lock screen.
- **Colour creeping back:**
  - an icon Theme Park didn't change sitting in the dock
  - a colourful widget
  - a Samsung element that clashes
- **Clutter.** If the screens end up as busy as before, it failed.
- **The game getting in the way of the phone:**
  - taps on icons or widgets being eaten by the game
  - page swipes breaking
  - accidental app launches while shooting
- **Anything that can't be undone.**
  - Keep Wallpaper Engine installed and its wallpaper settings recorded.
  - Screenshot the current layouts first.
  - Every change must have a written way back.
- **Ads, trackers or accounts.**

## Constraints and known facts

### Platform

- **Samsung's taskbar.** The inner-screen taskbar only works while Samsung's own home screen (One UI Home) is the default home app. A third-party home app greys it out, and this is still reported in One UI 7. This is a main reason for choosing A.
- **One UI version: unknown.** Check it in step 0. It affects:
  - whether Theme Park has its monochrome option, which came with the December 2025 update
  - which lock screen options exist
- **Touch on the wallpaper: unconfirmed.** A forum report says touch does not reach live wallpapers under One UI Home on a Fold, although it worked with another launcher. The step 1 spike settles this. Android offers two routes:
  - `WallpaperService.Engine.setTouchEventsEnabled(true)` with `onTouchEvent`
  - `onCommand` with `WallpaperManager.COMMAND_TAP`, which home screens send when someone taps empty space
- **Samsung's "Double tap to turn off screen"** reacts to taps on empty home-screen space, which are the same taps that fire. Rapid shots would put the phone to sleep.
  - The user says they don't use it, so it gets turned off during setup. That step still gets the user's go-ahead.
  - The spike should test with it both on and off.
- **Location-based weather (user's request).** This needs Android location permission.
  - Coarse location is enough. "Allow all the time" is needed so the widget can refresh while the app isn't open.
  - Coordinates sent to the weather services are rounded to about 1 km.
  - NWS and the IEM radar are US-only. Outside the US the widget falls back to Open-Meteo alone, and radar isn't available.
  - If location isn't available, the widget uses the last known location.
- **Third-party wallpapers on Samsung's lock screen: unconfirmed.** Samsung's lock screen reliably takes photos and video. The lock screen design below only needs a still image.
- **One wallpaper per screen: unconfirmed.** Samsung treats the cover and main screens separately by default. Whether a third-party live wallpaper can be applied to each screen independently, or both at once, needs checking in the spike. Step 0 gives an early hint, since Wallpaper Engine is itself a third-party live wallpaper.
  - **If it can only run on one home screen (Claude's call):** that screen gets the game, and the other gets a still black-and-white frame from it. This alone is not a reason to switch to option B. Switching stays the user's decision.

### Build

- **Toolchain:** the same as the Jewel coupon clipper. That means building on the NAS (`ssh nas`) with javac, d8 and aapt2 and no Gradle, using `~/androidtools/{jdk,sdk}`.
  - No AndroidX, so use only the built-in Android APIs: JobScheduler instead of WorkManager, plain Canvas or GLES, and plain `RemoteViews` widgets.
  - Use a new signing keystore for this app; don't reuse the Jewel key.
- **Install** over ADB. The phone is not currently connected anywhere. At the time of the first install, the user plugs it into the laptop or enables wireless debugging.

### Data sources

- **Open-Meteo:**
  - Free for non-commercial use under 10,000 calls a day. No key.
  - Requires a CC BY 4.0 credit.
  - Uses NOAA's HRRR model in North America: 3 km resolution, updated hourly, with 15-minute steps for 18 hours.
- **National Weather Service** (`api.weather.gov`):
  - Free, no key. Supplies alerts, current observations (KORD/KMDW) and the Chicago office (LOT) forecast.
  - It is sometimes slow or briefly down, so it must never be the only source for the widget.
  - It **requires a User-Agent that includes contact info.** Don't put the user's email in it without asking them. Ask which contact to use.
- **Iowa Environmental Mesonet radar tiles:**
  - NEXRAD base reflectivity as XYZ tiles: the current frame plus 5-minute steps back over the past hour. HRRR forecast-reflectivity layers also exist.
  - **The terms of use were not verified.** Check them before building on these tiles.
- **RainViewer: excluded.** It cut its free API back sharply through 2025 and dropped nowcast in January 2026.

## Approaches considered

**A. Samsung's home screen, with our wallpaper, widgets and icon pack. Chosen by the user.**
- Pros:
  - keeps the taskbar
  - keeps the smooth gestures and recent-apps animations
  - keeps folders, search and the drawer
  - a smaller build, where each piece can be removed independently
- Cons:
  - the layout is limited to Samsung's grid
  - icons depend on Theme Park, and some icons reportedly resist it
  - tap-to-shoot is unconfirmed
- The key property: everything built for A carries over to B unchanged.

**B. Our own home screen app** (like Clean Home on the TV). **Deferred.**
- Pros:
  - full control over everything
  - taps definitely work, because the game is part of the home screen
- Cons:
  - loses the taskbar
  - Samsung adds a recents delay for third-party home apps, and swipe animations look different
  - we would have to build the drawer, search, folders, widget hosting and two layouts ourselves
  - Samsung updates can break it

**C. Build the game as a Wallpaper Engine scene. Rejected.**
- It would keep the user's current app.
- But the editor is Windows-only, and the user is on Linux.
- It is unconfirmed that its scripting can read the tilt or save a high score.

**D. Use store weather apps and widgets** (MyRadar and similar). **Rejected.** The user wants everything to be our own, and store widgets are rarely true black-and-white.

## Decided by the user

- Cover in full: both home screens and both lock screens. The main lock screen is used least.
- The wallpaper is a space shooter with a self-flying ship with tilt effects, tap to shoot, and a kept high score.
- Build our own wallpaper app rather than a Wallpaper Engine scene.
- Make all our own stuff.
- The widgets are time, weather and music controls, and they must look slick.
- Keep Samsung's home screen (A).
- The game never ends, with no lose state. A hit knocks the ship a little with a "ding", and the score resets every day.
- The user doesn't use double-tap-to-turn-off-screen.
- Weather follows the phone's location.
- **Look, from the mockups:**
  - fine dots
  - glyph-only icons with no shape behind them
  - a fine dot-matrix clock with the unlit grid faintly visible
  - radar as a flat map with storm dots rolling in, a richer map (highways with number labels), and no latitude or longitude labels
  - the radar-scope sweep was rejected
- **Wants all the extras:** Phase 2 and Phase 3 below, plus the Always On Display.
- **Scores:** Chicago Bears.
- **Transit:** Blue Line, both directions.

## Proposed by Claude (stated to the user; open to correction)

These are not explicitly confirmed. Each was stated to the user as a default they could change.

- **Scoring:** today's score and the best day's score sit small in a corner. The reset happens at local midnight. The user said "keep the high score" and also "the score resets every day"; reading those together, the high score is the best single day.
- **Tilt calibration:** the angle the phone is held at when the home screen appears counts as level, so it works lying down.
- **Battery:** the game pauses whenever the wallpaper isn't visible.
- **Lock screens:** a still black-and-white frame from the game, plus Samsung's own clock and Samsung's built-in music controls (Now Bar).
- **Tap fallback:** if taps never reach the wallpaper, the ship fires on its own and the user just steers.
- **Icon art source:** use each app's own monochrome (themed-icon) layer where the APK has one, and draw the rest. The style itself is decided: glyph only.
- **Radar:** tapping the weather widget opens a full-screen radar.
- **Widget style:** bare, with text right on the game and the game dimmed behind it. The user never picked this explicitly; it was the mockup default and they didn't object.
- **Aiming:** a tap fires toward the tapped point, and the ship turns to face it. This was shown in the mockups and the user didn't object.
- **Blue Line station:** the station nearest the phone's location, with an option to pin a home station. The user named the line and both directions but no stop.


## Change 2026-11-16: autopilot steering, tilt as effects (user's decision)

After trying tilt steering in the preview, the user found it awkward and chose:
- **Movement (option B):** the ship flies itself. It wanders around the middle of the screen and dodges incoming rocks, like the old dino runner playing on its own. **Tapping empty space fires toward the tap**, as before; AUTO fire mode still auto-fires.
- **Tilt is no longer steering. It drives three effects:**
  1. **Depth:** each star has a depth; stars shift with tilt by an amount proportional to their depth (parallax), so the screen feels 3D. Only stars move — rocks and the ship stay in game coordinates so taps still aim correctly.
  2. **Gravity:** debris dots from destroyed rocks accelerate toward whichever edge is lower. Phase 2 snow will reuse this.
  3. **Shake:** a hard shake sends a shockwave ring of dots out from the ship that destroys every rock it reaches. Each rock counts toward today's score. Cooldown about 60 s.
- **Hold/drag controls were rejected:** on One UI Home, long-pressing empty space opens the edit screen and dragging swipes pages or opens the drawer.

## Design

### Visual direction (user's request)

A clean, polished, black-and-white monochrome theme. It draws on two references: OpenAI's black-and-white Super Bowl ad, and Nothing phones (Nothing OS). The two share one idea: images built out of dots.

This is inspiration only. There are no copied logos or brand marks, none of the ad's actual imagery, and no proprietary fonts (Nothing's NDot font is proprietary).

**From the ChatGPT Super Bowl ad** ("The Intelligence Age", Super Bowl LIX, February 2025):
- black-and-white pointillism, in which [one pulsing dot builds into shapes](https://www.adweek.com/brand-marketing/chatgpt-openai-super-bowl-ad/) and abstract dots resolve into recognizable forms
- no colour and no clutter
- smooth, deliberate motion that feels polished rather than busy

**From Nothing:**
- dot-matrix numerals for the clock, the temperature and the game score
- monochrome glyph icons
- widgets that are simple tiles showing one thing each

**Fonts:** open-licensed only.
- [Doto](https://fontsource.org/fonts/doto/about) (SIL OFL 1.1, dot-matrix) for the big numbers.
- A clean OFL sans for text, such as Inter or Geist. The mockup step picks one.

**Colour:** strictly black, white and greys. Nothing OS uses one red accent, but it's left out unless the user asks for it at the mockup step.

**The game is built from dots (Claude's call, following the ad).** This replaces the earlier "thin white strokes" idea.
- The ship and the rocks are outlines or fields of white dots on black.
- Shots are single bright dots.
- A destroyed rock bursts into dots that drift and fade.
- The "ding" on a hit is a ring of dots pulsing out from the ship.
- The score uses dot-matrix digits.
- Motion is smooth, with easing, so it feels polished.
- The mockup step compares dot density and dot size, and can compare against thin strokes if the user wants.
- **Performance:** dots are cheap to draw if they're batched. The builder should still check that busy moments, such as a rock bursting, stay smooth on both screens.

### Mockup decisions (2026-11-15)

The mockups are an interactive canvas: https://claude.ai/artifact/KjSXGZLhXadNegC6B5PYpL. It has the main and cover home screens (both playable), the lock screens and the radar. Build to match it.

- **Dots:** fine, about 1.2 px dots spaced about 4 px apart along rock outlines, with a sparse interior stipple. The ship is denser.
- **Clock:** a 5×7 dot-matrix grid of small, spaced dots, not a font.
  - Unlit dots stay faintly visible (about #1a1a1a), and lit dots have a soft glow.
  - Lit dots pulse slowly with offset timing, and the colon fades in and out.
  - The lock screens use larger dots. The cover lock stacks the hours above the minutes.
- **Small numbers** (temperature, score): Doto at its regular weight. **Words:** Inter.
- **Icons:** white glyph only, with no circle, square or tile behind it.
- **Widgets:** bare.
- **Opening moment:** one pulsing dot grows into the scene, as in the ad.
- **Radar** (see section 3): the flat map, not a scope.

### Overall shape

There is one app, working name "home kit" (the final name is the user's call). It contains:

- the live wallpaper service (the game)
- the time-and-weather widget and the music widget
- the radar screen
- a notification listener, used only to read the current music session
- the weather fetcher (a scheduled job), whose cached results are shared by the widget, the radar and the game's weather effect

The icon pack can live in the same app or a separate one; that's the builder's call. Theme Park finds icon packs through the standard icon-pack intent filters.

### 1. Game wallpaper

- **Engines:** a `WallpaperService`. Android may give each screen its own engine, or it may use one engine whose surface changes size when the phone folds. The spike shows which. Size everything from the surface the engine is given. Don't hard-code the Fold's resolutions.
- **Folding mid-game (Claude's call):** one game world and one run carry across both screens. Folding or unfolding pauses the run, refits the world to the new screen shape, and resumes it.
- **Sound (Claude's call):** silent, with no haptics. It must never play over the user's music.
- **Look:** white dots on pure black, as described under Visual direction. No colour.
- **Play:**
  - the ship, drifting rocks that break into smaller rocks, and shots
  - the ship flies itself (autopilot, dodging rocks); tilt drives effects (star parallax, debris gravity, shake shockwave), and taps fire
  - it never ends: no lives, no game over, no idle or start screen
  - a rock hitting the ship makes a visible "ding" (a flash or ring, no sound), knocks the ship back a little, and bounces the rock away
  - new rocks keep drifting in so there's always something to shoot, but never so many that the screen gets busy
- **Coexisting with the home screen:**
  - Keep the game calm behind the widget areas, for example by dimming it or not spawning there, so the widgets stay readable.
  - Taps on icons and widgets belong to Samsung. The game only acts on taps on empty space. That is what `COMMAND_TAP` means; whether raw `onTouchEvent` delivers taps that also hit icons is a spike question.
  - Page swipes must keep working. Whether the game reacts to wallpaper offsets (parallax) is the builder's call.
- **Sensors:** register them only while the wallpaper is visible, and unregister on hide. Re-capture the "level" angle each time it becomes visible.
- **Persistence:** today's score and the best-day score are saved on the device and shared by both screens. They survive reboots and app updates. Today's score resets at local midnight, checked whenever the wallpaper becomes visible, so it doesn't need an alarm.
- **Battery:** no rendering and no sensors while invisible. The builder sets a frame-rate target, which could be lower when nothing is moving, and measures the drain.

### 2. Widgets

- **Time and weather:**
  - **Time:** `TextClock`, so it ticks by itself without our app waking up.
  - **Weather:** current temperature, condition, and the day's high and low.
    - When a National Weather Service alert is active, add one line for it (for example "Winter Storm Warning").
    - The forecast comes from Open-Meteo; NWS supplies alerts and current conditions.
  - **Refresh:** every 15–30 minutes via JobScheduler, with cached data shown when offline. The builder picks the interval within that range.
  - **Location:** the phone's current location, using coarse location with "Allow all the time". See Constraints for the details and the fallbacks. Show the place name in small text.
  - Tapping the weather opens the radar. Tapping the clock opens Samsung's Clock app.
- **Music:**
  - Uses whatever app is playing, read from the active media session through the notification listener.
  - Shows the track and artist with previous, play/pause and next buttons. It may show album art in greyscale.
  - Collapses or hides cleanly when nothing is playing.
- **Sizes:** each widget needs a layout that works on the narrow cover screen and on the wide main screen.
- **Style:** follows the mockup decisions. The dot-matrix clock grid, Doto for small numbers, Inter for labels, and bare widgets with no tiles.

### 3. Radar screen

- A full-screen activity on black.
- **View (Claude's call):** two fixed views with no free pan or zoom, and a tap switches between them.
  - **Metro view:** centred on the phone's location.
  - **Regional view:** reaches about 250 miles west of it, so storms coming from the west, for example Iowa and Wisconsin from Chicago, show up early.
  - The basemap is bundled at those two scales for the US. Chicago gets the most detail, since it's home.
- **Basemap** (the user wants it rich, not minimal): drawn in dots from data bundled in the app, so there is no tile server for the basemap. It shows:
  - Lake Michigan filled with a fine dot texture and a crisp dotted shoreline
  - a faint speckle on land
  - interstates as dotted lines with small boxed route numbers (90, 94, 290, 55, 88, 294, 80, 57 and 355 around the city; the main interstates in the region view)
  - state lines
  - suburb and city labels
  - spaced-caps names for the lake and the states
  - a very faint dotted lat/lon grid, with **no** coordinate labels (the user called them clutter)
  - Real geometry comes from an OpenStreetMap or Natural Earth extract (with attribution). The builder picks the source.
- **The storm:** halftone dots rolling in, with dot size following intensity, and a "−45 MIN … NOW" readout. No rotating sweep.
- **Overlay:** IEM NEXRAD reflectivity in greyscale, animated over the last hour, if the tile terms allow it. A forecast loop from the HRRR reflectivity layer is a nice-to-have.
- Credits for IEM and Open-Meteo appear on this screen.

### 4. Icon pack

- A standard icon-pack app (an `appfilter.xml` that maps each app's component name to a drawable), applied through Theme Park's Icons section.
- **Coverage:** every launchable app installed on the phone, taken from the step 0 snapshot.
- **Source art:** pull each APK, extract its monochrome adaptive-icon layer if it has one, and recolour it to white. Draw any missing ones by hand in the same line style.
- **Check:** after applying the pack, list any icons Theme Park didn't change. Decide per icon (keep it off the home screen, or find another route).
- **Apps installed later (Claude's call):**
  - The icon pack is built by a repeatable script: pull the APKs, extract or flag icons, rebuild, and reinstall. Adding new apps is then a re-run, not a project.
  - Until a re-run, a new app may show in colour in the drawer. Use Theme Park's monochrome option as a backstop if the One UI version has it.
  - The home screens and dock must always be fully ours.
- **Optional:** Theme Park can also theme the quick settings panel and the keyboard. This was offered to the user with no answer yet, so it's optional.

### 5. Layouts

Picture both home screens as a clock and weather at the top, music controls, and one short dock row of icons. The rest is open space for the game. Everything else lives in the app drawer.

```
COVER                 MAIN (unfolded)
┌──────────┐          ┌──────────────────────────┐
│ 4:12     │          │ 4:12  38° cloudy  Chicago│
│ 38° ☁    │          │              ◇    1240   │
│   ▲   ◇  │          │    ▲  ·          ◇       │
│ ◇     ·  │          │ ◇                        │
│ ⏮ ⏯ ⏭  │          │ ⏮ ⏯ ⏭  Song · Artist    │
│ ○ ○ ○ ○  │          │ ○  ○  ○  ○  ○  ○  ○      │
└──────────┘          └──────────────────────────┘
```

- **Dock apps:** chosen by the user from the snapshot.
- **Number of pages:** default to one per screen.
- **Lock screens:** a still frame rendered from the game, with Samsung's thinnest clock style and the Now Bar for music. Use a separate still for each screen shape.

### Phase 2: bringing the game to life

These all live inside the same app and start after Phase 1 works.

- **Real weather in the game.** It uses the cached Open-Meteo data, so there are no extra requests.
  - Snow drifts down as slow dots, and rain falls as short dotted streaks. The amount follows the intensity.
  - The rocks' drift leans with the real wind direction.
  - Fog makes the stars dimmer.
- **Day and night.**
  - Star count and brightness ramp up around sunset and down around sunrise, using sunrise and sunset times from Open-Meteo.
  - A small dot moon shows the real moon phase, computed locally, at night only.
- **Charging.** While plugged in and visible, dots stream into the ship. Below about 15% battery, the ship dims.
- **Milestone easter eggs.** At set daily rock counts, the debris briefly (about 3 s) forms a shape.
  - At 500 rocks it's the Chicago flag's four six-pointed stars.
  - The other milestones and shapes are the builder's call; keep them rare and short.
- **Dot album art.** The music widget redraws the playing track's album art from the media session as a black-and-white halftone dot image.
- **The lock screen shows where you left off.**
  - When the screen turns off, render the current game frame (dimmed, with clear space where the clock and Now Bar go) and set it as the lock wallpaper (`WallpaperManager`, `FLAG_LOCK`).
  - Throttle this, for example only when the frame has changed meaningfully, at most once a minute.
  - Samsung's per-screen behaviour needs verifying. The fallback is the fixed still.
- **Unfold burst.** On unfolding, the scene's dots burst outward from the cover-screen-sized centre to fill the main screen, in about 0.6 s. Folding collapses them inward. One game world carries across.

### Phase 3: live Chicago and extras

- **Bears scores** (a widget or strip, shown only around game time).
  - Before the game: a countdown and the opponent, such as "SUN 12:00 · vs GB".
  - During the game: the score in dot-matrix numbers, with the quarter and game clock.
  - After the game: the final score for the rest of that day. Hidden otherwise.
  - **Data:** ESPN's public scoreboard JSON is unofficial, needs no key, and can change without notice. The builder confirms the source. Poll about once a minute during live games only.
- **Blue Line arrivals.**
  - Laid out like the dot-matrix platform boards.
  - Shows the next 2–3 trains each way (O'Hare-bound and Forest Park-bound) at the nearest Blue Line station, or a pinned home station.
  - **Data:** CTA Train Tracker (`ttarrivals`). This **needs a free CTA API key that the user requests themselves** through CTA's developer form.
  - The station list is bundled. Refresh frequently only while the screen is on.
- **Stars pulse to the beat.**
  - `android.media.audiofx.Visualizer` on the output mix, used only while the wallpaper is visible and music is playing. Bass energy scales the dots.
  - It needs the RECORD_AUDIO permission, whose system prompt says "microphone" even though the app only reads the phone's own output. The user OKed this prompt on 2026-11-15.
- **Always On Display** (cover screen only, because the Fold6's inner screen has none).
  - Built with Good Lock **ClockFace**: a very dim, sparse dot-pattern image under a thin clock.
  - No third-party API to draw on the always-on display was found, so the live game can't run there.
  - Getting the dot-matrix look into the clock font is uncertain. Check which fonts ClockFace can use.
  - Keep it sparse and dim for battery and burn-in.

### 6. Rollout and how to undo it

Each step after step 0 needs the user's go-ahead.

| Step | What happens |
|---|---|
| 0. Snapshot (look-only) | Record the One UI version, the installed apps, screenshots of all four screens, and the current Wallpaper Engine setup. Save these with the project so the old setup can be restored. |
| 1. Spike (throwaway) | A test live wallpaper that logs `onTouchEvent`, `COMMAND_TAP` and sensor data on both screens. It also checks whether a live wallpaper can be set per screen, and whether one can go on the lock screen. Uninstall it afterwards and record the findings. |
| 2. Mockups | **Done 2026-11-15.** See the mockup decisions. |
| 3. Game wallpaper | Uses the tap or auto-fire route the spike found. |
| 4. Widgets | Time and weather, and music. |
| 5. Radar screen | |
| 6. Icon pack + Theme Park | |
| 7. Layouts | Set up both home screens and both lock screens. **End of Phase 1.** |
| 8. Phase 2 | Weather in the game, day/night and moon, charging, easter eggs, dot album art, the lock screen showing where you left off, and the unfold burst. Each is shipped and checked on the phone in turn. |
| 9. Phase 3 | Bears scores, Blue Line arrivals (after the user has their CTA key), beat pulse (after the user OKs the microphone prompt), and the Always On Display through ClockFace. |

The way back: set Wallpaper Engine as the wallpaper again, reset the icons in Theme Park, remove our widgets, and restore the layouts from the step 0 screenshots. Our app uninstalls cleanly.

## Still to verify on the phone

- The One UI version, Theme Park availability, and whether Theme Park has the monochrome option.
- Whether taps reach the wallpaper (spike).
- Whether a live wallpaper can be applied per screen, and to the lock screen (spike).
- The installed apps, the current layouts, and which apps the user wants in the dock.
- The IEM tile terms.
- The contact for the NWS User-Agent. Ask the user at build time, and don't default to their email.
- Whether setting the lock wallpaper from our app (`FLAG_LOCK`) works on Samsung, and for which screen (Phase 2).
- Which fonts and images ClockFace accepts for the always-on display (Phase 3).
- The user's CTA API key (Phase 3). The user is requesting it.

## Left to the builder

- Canvas or OpenGL rendering.
- Game tuning: speeds, spawn rate and difficulty.
- Sensor choice and filtering.
- Exactly how the game stays calm behind widgets.
- Parallax on page swipes.
- The weather refresh interval within 15–30 minutes, and caching.
- Whether the icon pack is a separate app or part of the main one.
- The radar basemap data source.
- Widget layouts for each screen size.
- Code structure.
