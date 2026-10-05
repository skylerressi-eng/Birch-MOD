package com.birchmod.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which hop on a route is losing the time.
 *
 * <h2>Why ranking by seconds and not by ratio</h2>
 * This is the whole judgement the report makes, and it is easy to get
 * plausibly wrong. Ratio feels like the right measure — "this hop takes four
 * times as long as it should" is a louder sentence than "this hop loses six
 * seconds" — and it is the wrong one, because what a lap is made of is seconds.
 * A two-block step between neighbouring trees predicts at a third of a second
 * and really takes a second and a half, which is 450% and costs about nothing;
 * a trek across a gully predicts at eighteen seconds and takes twenty-six,
 * which is 44% and costs eight. Ranked by ratio the report points at the step
 * and tells the player to re-record a hop that was never the problem.
 *
 * So: ratio only decides whether a hop is <em>eligible</em> to be named, via a
 * floor on absolute seconds lost, and seconds decide which of the eligible ones
 * is worst.
 */
class SplitsTest {

    /** A hop that has been walked enough times to be trusted. */
    private static Splits.Leg measured(int from, int to, double expected, double actual) {
        return new Splits.Leg(from, to, expected * 7.0, expected, actual, true);
    }

    /** A hop nobody has walked yet: its "actual" is only the prediction. */
    private static Splits.Leg estimated(int from, int to, double expected) {
        return new Splits.Leg(from, to, expected * 7.0, expected, expected, false);
    }

    @Test
    @DisplayName("the hop losing the most seconds is the one named")
    void worstBySeconds() {
        List<Splits.Leg> legs = List.of(
                // 450% slow, costs 1.2s — loud and harmless.
                measured(0, 1, 0.3, 1.5),
                // 44% slow, costs 8s — quiet and the real problem.
                measured(1, 2, 18.0, 26.0),
                measured(2, 0, 5.0, 5.2));

        assertEquals(1, Splits.worstOf(legs),
                "ranked by seconds lost, the gully beats the stride");
    }

    @Test
    @DisplayName("a hop losing almost nothing is not named at all")
    void tinyOverheadIsNotAFinding() {
        // Every measured hop is a little slower than a straight line — you turn
        // to face the tree. A report that always finds a culprit is a report
        // that cannot be trusted when it finds a real one.
        List<Splits.Leg> legs = List.of(
                measured(0, 1, 2.0, 2.4),
                measured(1, 0, 3.0, 3.9));
        assertEquals(-1, Splits.worstOf(legs));
    }

    @Test
    @DisplayName("a hop nobody has walked is never the culprit")
    void unmeasuredIsNotEvidence() {
        // An unmeasured hop reports its own prediction as its time, so its
        // overhead is exactly zero and it looks perfect. It is not perfect, it
        // is unknown, and the one thing this must not do is hand out a clean
        // bill of health it has not earned.
        List<Splits.Leg> legs = List.of(
                estimated(0, 1, 30.0),
                measured(1, 0, 4.0, 9.0));
        assertEquals(1, Splits.worstOf(legs), "the measured hop, not the long unknown one");
    }

    @Test
    @DisplayName("a hop faster than predicted is not a problem")
    void fasterThanPredicted() {
        // Downhill, or a prediction made from a walk speed measured while
        // standing in a tree.
        List<Splits.Leg> legs = List.of(
                measured(0, 1, 10.0, 6.0),
                measured(1, 0, 10.0, 9.0));
        assertEquals(-1, Splits.worstOf(legs));
    }

    @Test
    @DisplayName("nothing measured anywhere means nothing to report")
    void nothingMeasured() {
        assertEquals(-1, Splits.worstOf(List.of(estimated(0, 1, 5.0), estimated(1, 0, 5.0))));
        assertEquals(-1, Splits.worstOf(List.of()));
    }

    // ---- What the report adds up to ----

    private static Splits.Report report(List<Splits.Leg> legs) {
        return new Splits.Report(legs, Splits.worstOf(legs));
    }

    @Test
    @DisplayName("a lap's travel time is the sum of its hops")
    void totals() {
        Splits.Report r = report(List.of(
                measured(0, 1, 4.0, 6.0),
                measured(1, 2, 3.0, 3.5),
                estimated(2, 0, 5.0)));

        assertEquals(14.5, r.totalSeconds(), 1e-9, "6 + 3.5 + 5");
        assertEquals(12.0, r.expectedSeconds(), 1e-9, "4 + 3 + 5");
        assertEquals(2, r.measuredCount());
    }

    @Test
    @DisplayName("the worst hop is reachable, and is null when there isn't one")
    void worstLeg() {
        Splits.Report found = report(List.of(measured(0, 1, 4.0, 12.0), measured(1, 0, 2.0, 2.1)));
        assertNotNull(found.worstLeg());
        assertEquals(0, found.worstLeg().from());

        assertNull(report(List.of(measured(0, 1, 4.0, 4.1))).worstLeg());
        assertNull(report(List.of()).worstLeg());
    }

    @Test
    @DisplayName("a route too short to have hops reports no hops")
    void tooShortToHaveLegs() {
        // Not an error and not an empty finding — there is genuinely nothing
        // between one stop and itself.
        assertEquals(0, Splits.of(null).legs().size());
        assertEquals(0, Splits.of(new RecordedRoute("x")).legs().size());

        RecordedRoute one = new RecordedRoute("one");
        one.points.add(new RecordedRoute.Point(0, 70, 0));
        assertEquals(0, Splits.of(one).legs().size());
    }

    @Test
    @DisplayName("ratio is reported, and does not divide by a zero prediction")
    void ratioIsSafe() {
        // Two stops recorded on the same block predict at zero seconds apart.
        Splits.Leg samePlace = new Splits.Leg(0, 1, 0.0, 0.0, 1.0, true);
        assertEquals(1.0, samePlace.ratio(), 1e-9, "no prediction to be a multiple of");
        assertEquals(1.0, samePlace.overhead(), 1e-9);

        assertEquals(2.0, measured(0, 1, 5.0, 10.0).ratio(), 1e-9);
    }
}
