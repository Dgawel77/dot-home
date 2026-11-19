# Dot Hue: a lights widget, short design (2026-11-18)

## Why

David wants to control his Philips Hue lights from the home screen. What he does is "really just on and off, and
also sometimes dimming". There is a Hue Bridge (the square v2 model) on the home Wi-Fi.

## Decisions (David)

- Each row is a Hue room or zone, not a single bulb.
- Controls are on/off plus dimming.

## Approach

There is one sensible way: the bridge's local API (CLIP v2, HTTPS on the home network).

- It needs no Hue account and no internet.
- Pairing is a single press of the bridge's link button.

Hue's cloud "remote API" was rejected. It needs a Hue developer account, an OAuth sign-in and the internet, all
for no gain at home. It comes back only if the phone turns out to be unable to reach the bridge over Wi-Fi.
Check that first on the device.

## Design

- **Widget "Dot Hue"**, 4x2 by default, in the same dot-matrix look as the other widgets: greys only, no red.
  - Up to 4 rows, one per room or zone. Each row shows the name on the left and five dots on the right.
  - Lit dots show the brightness: L100 for the lit dots, L10 for the rest. When the room is off, all five dots
    are L10 and the name drops to L46.
  - Tapping the name toggles the room on or off.
  - Tapping dot n (1 to 5) turns the room on at n × 20% brightness.
  - The update shows straight away (optimistically), then the bridge's answer confirms it.
- **States.**
  - Not paired: the widget reads "PAIR HUE IN DOT HOME", like the Blue Line widget.
  - Bridge unreachable: the rows dim and the bottom reads "NO BRIDGE".
  - A failed tap reverts its row.
- **Setup: pairing.** A "Pair Hue bridge" button finds the bridge with Android's NsdManager (`_hue._tcp`).
  - It asks David to press the bridge's button, then polls `POST /api` with `generateclientkey` for 30 s.
  - The app key is stored in private prefs. It is never logged or committed, like the CTA key.
  - The bridge's TLS certificate is pinned at pairing (trust on first use). TLS checks are never switched off
    wholesale.
- **Setup: choosing rooms.** After pairing, Setup lists the bridge's rooms and zones, from
  `/clip/v2/resource/room`, `zone` and `grouped_light`. David ticks up to 4 and sets their order.
- **Refresh.** Hue state is read:
  - when a widget appears;
  - when the screen turns on;
  - after every tap;
  - every 60 s while the screen is on, through the existing once-a-second hub hook.

  Nothing runs while the screen is off.
- **Control.** A tap is a broadcast to a receiver, which uses `goAsync` to send one short PUT to
  `grouped_light/{id}`: `{on}` for a toggle, or `{on:true, dimming:{brightness}}` for a dot.
- **Core, JVM-tested.** This covers:
  - parsing the CLIP v2 room, zone and grouped_light JSON;
  - the row model: the name, the lit dot count from brightness (rounded to the nearest 20%), and on/off;
  - the tap-to-request mapping;
  - the widget text for the unpaired and unreachable states.

## Non-goals

Colours, scenes, single bulbs, schedules, and control from away from home.

## Left to the builder

- The exact row layout, sized to the widget.
- How the bridge is rediscovered if its IP changes (re-resolve by bridge id through NsdManager).
- Timeouts.

## Revision (2026-11-18, after the first install): a 1 x 4 strip

David asked for a 1 x 4 widget, finer dimming "just like the music scroller", and an all-off button. He chose a
single strip that flips between rooms, and an ALL OFF that switches off every light in the home.

- **Layout:** a top line with ‹ ROOM › on the left and ALL OFF on the right. Under it, 20 dots across the full
  width, each one 5% of brightness, with tap zones like the music widget's seek zones.
- **‹ ›** step through the picked rooms. Each widget remembers its own room, and flipping is local with no
  network call. The arrows show only when two or more rooms are picked.
- **The name** toggles the room on and off. **A dot** turns the room on at that step × 5%.
- **ALL OFF** sends `{"on":{"on":false}}` to the bridge_home grouped_light. It shows at once, the state is read
  back from the bridge afterwards, and a failure reverts it.
- **Picks:** up to 8 rooms instead of 4.
- **Off dot (added later):** the dot row starts with a red off dot (LASER). David's pick: red is used as an
  occasional accent. Tapping it turns the room off and keeps its brightness for the next time it comes on.
