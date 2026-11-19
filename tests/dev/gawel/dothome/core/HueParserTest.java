package dev.gawel.dothome.core;

import dev.gawel.dothome.test.T;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** CLIP v2 parsing. The fixtures are synthetic, in the documented shapes (no ids from a real bridge). */
public final class HueParserTest {
    private HueParserTest() {}

    static String fixture(String name) {
        try {
            return new String(Files.readAllBytes(Paths.get("tests/fixtures/" + name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read fixture " + name + ": " + e);
        }
    }

    public static void testRooms() {
        List<HueGroup> g = HueParser.groups(fixture("hue-room.json"), false);
        T.eq(2, g.size(), "rooms with a grouped_light (Garage has none)");
        T.eq("3f1a0c52-1b7e-4c3a-9d2e-0a1b2c3d4e01", g.get(0).id, "id");
        T.eq("Living room", g.get(0).name, "name");
        T.eq("7e6d5c4b-0000-4000-8000-00000000a001", g.get(0).groupedLightId, "grouped light");
        T.eq("Bedroom", g.get(1).name, "second name");
        T.check(!g.get(0).zone, "a room is not a zone");
    }

    public static void testZones() {
        List<HueGroup> g = HueParser.groups(fixture("hue-zone.json"), true);
        T.eq(1, g.size(), "zones");
        T.eq("Upstairs", g.get(0).name, "name");
        T.check(g.get(0).zone, "zone flag");
    }

    public static void testStates() {
        Map<String, HueState> s = HueParser.states(fixture("hue-grouped-light.json"));
        HueState living = s.get("7e6d5c4b-0000-4000-8000-00000000a001");
        T.check(living.on, "living on");
        T.check(living.dimmable, "living dimmable");
        T.near(56.3, living.brightness, 1e-9, "living brightness");
        HueState bed = s.get("7e6d5c4b-0000-4000-8000-00000000a002");
        T.check(!bed.on, "bedroom off");
        T.near(100.0, bed.brightness, 1e-9, "bedroom brightness");
    }

    public static void testNoDimming() {
        HueState up = HueParser.states(fixture("hue-grouped-light.json")).get("7e6d5c4b-0000-4000-8000-00000000a004");
        T.check(up.on, "zone on");
        T.check(!up.dimmable, "no dimming object: not dimmable");
        T.near(100.0, up.brightness, 1e-9, "not dimmable reads as full");
    }

    public static void testBridgeHomeSkipped() {
        Map<String, HueState> s = HueParser.states(fixture("hue-grouped-light.json"));
        T.eq(3, s.size(), "bridge_home's all-lights group is skipped");
        T.check(!s.containsKey("7e6d5c4b-0000-4000-8000-00000000a000"), "no bridge_home entry");
    }

    public static void testPairSuccess() {
        String json = "[{\"success\":{\"username\":\"k3yK3yK3y\",\"clientkey\":\"00112233\"}}]";
        T.eq("k3yK3yK3y", HueParser.pairKey(json), "app key");
        T.check(!HueParser.linkButtonNeeded(json), "success is not a link-button error");
    }

    public static void testPairLinkButton() {
        String json = "[{\"error\":{\"type\":101,\"address\":\"\",\"description\":\"link button not pressed\"}}]";
        T.eq(null, HueParser.pairKey(json), "no key yet");
        T.check(HueParser.linkButtonNeeded(json), "link button");
        T.check(!HueParser.linkButtonNeeded("[{\"error\":{\"type\":7}}]"), "another error is not the link button");
    }

    public static void testBadJson() {
        T.eq(0, HueParser.groups("not json", false).size(), "bad rooms");
        T.eq(0, HueParser.groups(null, false).size(), "null rooms");
        T.eq(0, HueParser.states("{\"data\":5}").size(), "bad states");
        T.eq(null, HueParser.pairKey("{}"), "bad pair");
        T.check(!HueParser.linkButtonNeeded(null), "null pair");
    }
}
