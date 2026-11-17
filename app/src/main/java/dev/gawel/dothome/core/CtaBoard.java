package dev.gawel.dothome.core;

import java.util.Collections;
import java.util.List;

/** Parsed ttarrivals response: error code and the two directions, each sorted by minutes. */
public final class CtaBoard {
    public final int errCd;
    public final List<Arrival> ohare;
    public final List<Arrival> forestPark;

    public CtaBoard(int errCd, List<Arrival> ohare, List<Arrival> forestPark) {
        this.errCd = errCd;
        this.ohare = Collections.unmodifiableList(ohare);
        this.forestPark = Collections.unmodifiableList(forestPark);
    }
}
