# Dot Hue Lights Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "Dot Hue" home-screen widget. It has up to 4 Hue rooms or zones, each toggled on or off by tapping its
name and dimmed to 20–100% by tapping one of five dots. It talks to the Hue Bridge on the home network.

**Architecture:**
- Pure-Java core (JVM-tested):
  - parsing CLIP v2 JSON
  - the row model (lit dots, the selection, request bodies, optimistic state)
  - the widget geometry shared by the bitmap and the tap zones
- Android layer:
  - a pinned-TLS bridge client
  - NsdManager discovery
  - a repo on its own executor
  - the widget provider, art and tap receiver
  - a Setup section
- Refresh piggybacks on LiveFeeds' screen-on minute tick.

**Tech Stack:** Java 8, Android framework APIs only (minSdk 26, targetSdk 34), no libraries. The build is the
no-Gradle `build.sh`; the tests are `test.sh`. Both run on the NAS through `tools/nas`.

**Spec:** docs/superpowers/specs/2026-11-18-hue-lights-widget-design.md

## Global Constraints

- **Core boundary:** `core/` never references `android.*`. `test.sh` enforces this.
- **Colours:**
  - Greys only, from DotStyle: L100, L65, L46, L30, L10. No red.
  - Text is drawn into bitmaps, because One UI ignores widget fonts.
- **Secrets:** the app key, client key and cert pin are never logged, never put in a Toast, never in a URL, and
  never committed. Log lines name the call and the HTTP status or exception class only, as LiveFeeds does.
- **TLS:**
  - Trust exactly the bridge certificate pinned at pairing: SHA-256 of the leaf DER.
  - Never switch TLS checks off wholesale.
  - Before pairing, accept a certificate only if its CN equals the bridge id that mDNS advertised.
- **Rows and taps:**
  - At most 4 rows.
  - Brightness for dot n (1..5) is n × 20.
  - Lit dots: 0 when off, otherwise clamp(round(brightness / 20), 1, 5).
- **Widget text:** `PAIR HUE IN DOT HOME` when unpaired, `PICK ROOMS IN DOT HOME` when paired with nothing
  picked, and `NO BRIDGE` when the bridge is unreachable.
- **Network:** nothing runs while the screen is off. Refresh happens when a widget appears, on screen-on, after a
  tap, and every 60 s while the screen is on.
- **Public repo:** fixtures and docs carry no IPs, ids or keys from David's real bridge.

## Review Focus

1. **The bridge's IP changes.** A connect failure triggers one NSD re-resolve for the stored bridge id, then a
   retry; the stored host is updated. There is no loop.
2. **A room is deleted or renamed in the Hue app.**
   - On the next group refresh a deleted pick drops out of the rows and the selection.
   - A renamed room shows its new name.
   - Test: `HueRowsTest.testMissingGroupDropped`.
3. **Fast taps.** Requests run in order on the Hue executor and the last tap wins. A refresh GET that started
   before a tap does not overwrite that tap's optimistic state.
   - Test: `HueRowsTest.testStaleRefreshIgnored`, using per-row tap sequence numbers.
4. **A room with no dimmable lights** (no `dimming` in its grouped_light).
   - When on, it shows 5 dots. A dot tap sends only `{"on":{"on":true}}`.
   - Tests: `HueParserTest.testNoDimming` and `HueRowsTest.testDimTapWithoutDimming`.
5. **Away from home Wi-Fi.**
   - The 3 s connect timeout gives `NO BRIDGE` quickly, with no retry storm.
   - Taps revert their row.
   - Test: `HueRowsTest.testRevert`.

---

### Task 1: Core: CLIP v2 parsing

**Files:**
- Create: `app/src/main/java/dev/gawel/dothome/core/HueParser.java`
- Create: `app/src/main/java/dev/gawel/dothome/core/HueGroup.java`
- Create: `app/src/main/java/dev/gawel/dothome/core/HueState.java`
- Test: `tests/dev/gawel/dothome/core/HueParserTest.java`
- Test fixtures, synthetic and following the documented CLIP v2 shapes:
  - `tests/fixtures/hue-room.json`
  - `tests/fixtures/hue-zone.json`
  - `tests/fixtures/hue-grouped-light.json`
- Modify: `tests/dev/gawel/dothome/test/AllTests.java` to register the suite.

**Interfaces (Produces):**
- `final class HueGroup { final String id, name, groupedLightId; final boolean zone; }`
- `final class HueState { final boolean on; final boolean dimmable; final double brightness; }`
- `static List<HueGroup> HueParser.groups(String json, boolean zone)`
  - Keeps items whose `services` include an `rtype` of `grouped_light`.
  - `name` comes from `metadata.name`.
  - Returns an empty list on bad JSON.
- `static Map<String, HueState> HueParser.states(String json)`
  - Keyed by grouped_light `id`.
  - `dimmable` is false and `brightness` is 100 when `dimming` is absent.
  - Skips the item whose owner `rtype` is `bridge_home`.
- `static String HueParser.pairKey(String json)`
  - Returns `success.username`, or `null`.
- `static boolean HueParser.linkButtonNeeded(String json)`
  - True when the error has `type` 101.

- [ ] Write the tests:
  - `testRooms`: 2 rooms, with names, ids and groupedLightIds.
  - `testZones`: `zone` is true.
  - `testStates`: on/off and brightness 56.3.
  - `testNoDimming`.
  - `testBridgeHomeSkipped`.
  - `testPairSuccess`, `testPairLinkButton`.
  - `testBadJson`: empty list or map, and null.
- [ ] Run `tools/nas ./test.sh`. Expected: compile FAIL, because the classes don't exist yet.
- [ ] Implement them with MiniJson.
- [ ] Run `tools/nas ./test.sh`. Expected: all PASS.
- [ ] Commit `feat(hue): CLIP v2 room/zone/grouped_light parsing`.

### Task 2: Core: row model, selection and commands

**Files:**
- Create: `app/src/main/java/dev/gawel/dothome/core/HueRows.java`
- Test: `tests/dev/gawel/dothome/core/HueRowsTest.java`

**Interfaces (Produces):**
- `static final int HueRows.MAX = 4`
- `static int HueRows.litDots(HueState s)`. Use the rule in Global Constraints. `null` gives 0.
- `static List<String> HueRows.toggleSelection(List<String> sel, String groupId)`
  - Removes the group if present, otherwise appends it while fewer than MAX are selected.
  - Returns a new list.
- `static String HueRows.encode(List<String>)` and `static List<String> HueRows.decode(String)`
  - Comma-separated; decode drops blanks.
- `static final class Row { final String groupId, groupedLightId, name; final HueState state; }`
  - `name` is upper-cased.
- `static List<Row> HueRows.rows(List<String> sel, List<HueGroup> groups, Map<String, HueState> states)`
  - Keeps the selection order.
  - Drops picks with no group.
  - A missing state counts as off.
- `static List<String> HueRows.prune(List<String> sel, List<HueGroup> groups)`
- `static String HueRows.toggleBody(HueState current)` gives `{"on":{"on":<!on>}}`.
- `static String HueRows.dimBody(HueState current, int dot)`
  - Sends `{"on":{"on":true},"dimming":{"brightness":<dot*20>}}`.
  - When the room is not dimmable it sends `{"on":{"on":true}}` only.
- `static HueState HueRows.afterToggle(HueState)`
- `static HueState HueRows.afterDim(HueState, int dot)`
- **Stale guard:** `final class HueRows.Seq`. It holds `long tap(String groupId)`, which returns a new seq, and
  `boolean fresh(String groupId, long seqAtRefreshStart)`. A refresh result is applied only to rows whose seq has
  not moved since the refresh began.
- Message constants:
  - `HueRows.UNPAIRED = "PAIR HUE IN DOT HOME"`
  - `HueRows.NO_ROOMS = "PICK ROOMS IN DOT HOME"`
  - `HueRows.NO_BRIDGE = "NO BRIDGE"`

- [ ] Write the tests:
  - `testLitDots`: off gives 0; 1% gives 1; 10% gives 1; 30% gives 2; 50% gives 3; 100% gives 5; non-dimmable
    and on gives 5.
  - `testToggleSelection`: add, remove, and a 5th pick is ignored.
  - `testEncodeDecode`.
  - `testRowsOrder`.
  - `testMissingGroupDropped`.
  - `testBodies`.
  - `testDimTapWithoutDimming`.
  - `testAfterToggleAndDim`.
  - `testStaleRefreshIgnored`.
  - `testRevert`: keep the pre-tap state and restore it on failure.
- [ ] Run `tools/nas ./test.sh`. Expected: compile FAIL.
- [ ] Implement.
- [ ] Run `tools/nas ./test.sh`. Expected: all PASS.
- [ ] Commit `feat(hue): row model, selection, request bodies and stale-refresh guard`.

### Task 3: Core: widget geometry shared by the art and the tap zones

**Files:**
- Create: `app/src/main/java/dev/gawel/dothome/core/HueLayout.java`
- Test: `tests/dev/gawel/dothome/core/HueLayoutTest.java`
- Modify: `tests/dev/gawel/dothome/core/WidgetResourcesTest.java`. Add `testHueLayoutIds`: `widget_hue.xml` has
  `hue_row_0..3`, and each row has `hue_r{i}_name` and `hue_r{i}_d0..d4`.

**Interfaces (Produces):**
- `static final float HueLayout.PAD_DP = 16, DOT_CELL_DP = 32, MIN_ROW_DP = 32, NAME_DP = 13, DOT_R_DP = 3.5f`
- `static int HueLayout.rowsFit(float hDp, int rows)`: `min(rows, MAX, floor((hDp - 2*PAD) / MIN_ROW_DP))`, at
  least 0.
- `static float[] HueLayout.row(float wDp, float hDp, int rows, int i)` gives `{x, y, w, h}` in dp.
  - Rows are evenly spaced inside the pad.
  - With fewer rows than fit, they are centred vertically.
- `static float[] HueLayout.nameCell(float[] row)`: the row less 5 dot cells on the right.
- `static float[] HueLayout.dotCell(float[] row, int d)`: d = 0..4, left to right, each DOT_CELL_DP wide.
- `static float[] HueLayout.dotCentre(float[] row, int d)`

- [ ] Write the tests:
  - `testRowsFit`: 4x2 at 300×180 dp fits 4.
  - `testCellsTile`: name and dot cells cover the row exactly, with no overlap.
  - `testCentredWhenFewer`.
  - `testDotsInsideCells`.
  - `testHueLayoutIds`, which fails until Task 5 adds the XML.
- [ ] Run `tools/nas ./test.sh`. Expected: FAIL.
- [ ] Implement `HueLayout`. Carry `testHueLayoutIds` red into Task 5.
- [ ] Run `tools/nas ./test.sh`. Expected: everything PASS except `testHueLayoutIds`.
- [ ] Commit `feat(hue): shared widget geometry`.

### Task 4: Android: bridge client, discovery and repo

**Files:**
- Create: `app/src/main/java/dev/gawel/dothome/HueBridge.java`
- Create: `app/src/main/java/dev/gawel/dothome/HueDiscovery.java`
- Create: `app/src/main/java/dev/gawel/dothome/HueRepo.java`

**Interfaces (Produces):**
- **`HueBridge(String host, String pinB64)`.** Each call opens an HttpsURLConnection with a pinning
  `SSLSocketFactory` and a `HostnameVerifier` that passes only for the pinned certificate. Connect timeout 3 s,
  read timeout 5 s.
  - `String get(String path, String appKey)`. The header is `hue-application-key`. Returns the body, or null.
  - `boolean put(String path, String appKey, String body)`
  - `static String[] pair(String host, String bridgeId)`
    - Trust on first use: the certificate is accepted only when its CN equals `bridgeId`, compared
      case-insensitively.
    - It POSTs `/api` with `{"devicetype":"dothome#fold6","generateclientkey":true}`.
    - Returns `{appKey, pinB64}`, `{"LINK"}` when the link button hasn't been pressed, or null.
- **`HueDiscovery.find(Context, String wantBridgeId, long timeoutMs, Callback)`.** Uses NsdManager on
  `_hue._tcp`.
  - It resolves the first service, or the one whose TXT `bridgeid` matches `wantBridgeId`.
  - The callback is `onFound(String host, String bridgeId)` or `onNone()`, and it runs on the main thread.
- **`HueRepo`.**
  - Prefs: `hue.key`, `hue.host`, `hue.id`, `hue.pin`, `hue.rooms` (encoded selection), `hue.groups` (cached rooms
    and zones JSON, as "rooms\nzones"), `hue.states` (cached grouped_light JSON) and `hue.at`.
  - It has its own single-thread executor.
  - `static boolean paired(Context)`
  - `static void savePairing(Context, host, id, key, pin)` and `static void forget(Context)`
  - `static List<HueGroup> groups(Context)`, from the cache.
  - `static List<String> selection(Context)` and `static void setSelection(Context, List<String>)`
  - `static Shown current(Context)` gives the mode (UNPAIRED, NO_ROOMS, ROWS), the rows and the `reachable`
    flag.
  - `static void refresh(Context, boolean groupsToo)` is blocking and runs on the executor only.
    - It GETs grouped_light, and with `groupsToo` also rooms and zones.
    - On a connect failure it re-resolves through HueDiscovery once.
    - It applies the result through `HueRows.Seq`.
  - `static void kick(Context, boolean groupsToo)` runs refresh then `HueWidget.push`.
  - `static void tap(Context, String groupId, int dot, BroadcastReceiver.PendingResult pr)`, where dot -1 means
    toggle. It makes the optimistic update, pushes, sends the PUT, reverts on failure, pushes again and finishes
    `pr`.

- [ ] Implement. Thread rule: network work only on the HueRepo executor.
- [ ] Run `tools/nas ./build.sh`. Expected: `Built …/app-release.apk` with no javac warnings.
- [ ] Commit `feat(hue): pinned-TLS bridge client, NSD discovery, repo`.

### Task 5: Android: the widget

**Files:**
- Create: `app/src/main/java/dev/gawel/dothome/HueWidget.java`
  - Modelled on BlueLineWidget: `exists`, `push(ctx, force)` with a content key, and `views`.
  - Tap zones are placed with `setViewLayoutWidth/Height/Margin` (API 31+) from HueLayout, as `attachSeekZones`
    does.
- Create: `app/src/main/java/dev/gawel/dothome/HueArt.java`
  - The bitmap inside the pad.
  - Names are 13 dp mono: L65 when on, L46 when off.
  - Dots are radius 3.5 dp, centred in their cells: lit L100, unlit L10.
  - When unreachable, names are L30, dots L10, and `NO_BRIDGE` sits bottom-right in 9 dp mono (L46).
  - Single-message states show one 11 dp line in L65.
- Create: `app/src/main/java/dev/gawel/dothome/HueCommandReceiver.java`
  - Not exported.
  - Actions `dev.gawel.dothome.HUE_TOGGLE` and `dev.gawel.dothome.HUE_DIM`, with extras `group` and `dot`.
  - It uses `goAsync` and calls `HueRepo.tap`.
- Create: `app/src/main/res/layout/widget_hue.xml`
  - card_img and hue_img.
  - 4 `LinearLayout hue_row_i`, each holding 6 FrameLayouts: one name cell and 5 dot cells.
- Create: `app/src/main/res/layout/widget_hue_empty.xml`
- Create: `app/src/main/res/xml/widget_hue_info.xml`
  - Target 4x2, minWidth 250dp, minHeight 110dp, resizable.
  - `updatePeriodMillis` 0.
  - previewImage `@drawable/preview_hue`.
- Create the preview: `tools/WidgetPreview.java` gains a Hue preview. Write `app/src/main/res/drawable-nodpi/preview_hue.png`
  following the other previews.
- Modify: `AndroidManifest.xml` to add the HueWidget receiver (APPWIDGET_UPDATE) and HueCommandReceiver. No new
  permissions.
- Modify: `res/values/strings.xml` to add `widget_hue_label` ("Dot Hue") and `widget_hue_desc`.
- Request codes: HueWidget PendingIntents use codes 200 + row*6 + cell.

- [ ] Implement.
- [ ] Run `tools/nas ./test.sh`. Expected: all PASS, including `testHueLayoutIds`.
- [ ] Run `tools/nas ./build.sh`. Expected: built.
- [ ] Commit `feat(hue): Dot Hue widget, art and tap receiver`.

### Task 6: Setup and refresh hooks

**Files:**
- Modify: `app/src/main/res/layout/activity_setup.xml` to add a "Hue lights" section after Blue Line:
  - status text
  - a Pair button
  - a Forget button
  - a `LinearLayout hue_rooms` for the checkboxes
- Modify: `app/src/main/java/dev/gawel/dothome/SetupActivity.java` to add `setupHue()`.
  - **Pair:**
    1. Run HueDiscovery for 8 s.
    2. Show "Press the button on your Hue bridge".
    3. Poll `HueBridge.pair` every 1 s for 30 s on a worker thread.
    4. Save the pairing.
    5. Run `HueRepo.kick(groupsToo=true)`.
    6. Fill in the checkboxes.
  - **Checkboxes:**
    - One per room or zone, labelled with its name and "(zone)" where it is one.
    - Checked shows the 1-based order.
    - Toggling a box calls `HueRows.toggleSelection` and then `HueRepo.kick`.
  - **Forget** clears everything.
- Modify: `app/src/main/java/dev/gawel/dothome/LiveFeeds.java`. `onMinute` also calls
  `HueRepo.kick(ctx, groupsEvery6h)` when `HueWidget.exists`, and the early return now includes HueWidget.

- [ ] Implement.
- [ ] Run `tools/nas ./test.sh && tools/nas ./build.sh`. Expected: PASS and built.
- [ ] Commit `feat(hue): Setup pairing and room picker; minute refresh`.

### Task 7: Install and device checks (phone on USB)

- [ ] Check that the phone can reach the bridge: `adb shell` ping or nc to the bridge host. If it can't, stop and
  ask, because the cloud fallback is a new decision.
- [ ] Install with `adb install -r`.
- [ ] Have David do the Setup step: Pair, press the bridge button, pick rooms. Afterwards confirm in logcat that
  nothing secret is logged.
- [ ] Have David add the "Dot Hue" widget.
- [ ] Taps: a name toggles its room. Dots 1..5 set the brightness. Several fast taps end on the last one.
- [ ] Compare a captured real grouped_light response with the fixture shapes. If any field differs, fix the
  parser and its test.
- [ ] Final whole-branch review on the most capable model.
- [ ] Fix any Critical or Important findings, then commit and push phase2 and main.
