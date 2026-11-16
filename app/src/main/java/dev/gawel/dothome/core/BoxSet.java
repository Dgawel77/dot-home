package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.List;

/** Accepted label boxes: a new box is added only when it overlaps none already added. */
public final class BoxSet {
    private final List<float[]> boxes = new ArrayList<>();

    /** Adds the box and returns true unless it intersects an earlier one (touching edges do not count). */
    public boolean tryAdd(float left, float top, float right, float bottom) {
        for (float[] b : boxes) {
            if (left < b[2] && right > b[0] && top < b[3] && bottom > b[1]) return false;
        }
        boxes.add(new float[] {left, top, right, bottom});
        return true;
    }

    public int size() { return boxes.size(); }
}
