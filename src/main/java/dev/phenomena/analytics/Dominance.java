package dev.phenomena.analytics;

import java.util.*;

/**
 * Encounter dominance: who held the combat advantage over time. "Momentum" in Phenomena means this
 * combat advantage, never physical mass times velocity.
 *
 * <p>Model (deterministic and bounded):
 *
 * <pre>
 *   P(t) = sum of w_i * exp(-(t - t_i) / tau) over positive evidence observed at t_i <= t
 *   N(t) = sum of |w_i| * exp(-(t - t_i) / tau) over negative evidence
 *   D(t) = (P - N) / (P + N + 1)           in (-1, 1); +1 local, -1 opponent
 *   M(t) = P + N                            evidence mass
 *   U(t) = sum of exp(-(t - t_i) / tau)     over unattributed incoming damage
 * </pre>
 *
 * The "+1" prior pulls D toward zero when evidence is thin, so a single event cannot produce a
 * confident advantage. Each sample is classified as INSUFFICIENT when M &lt; {@link #MIN_MASS}
 * (inactivity or no evidence: no curve is drawn), INCOMPLETE when U &ge; {@link #MAX_UNATTRIBUTED}
 * (incoming damage could not be attributed, so the opponent's side may be missing), else LOCAL,
 * OPPONENT or NEUTRAL by the {@link #THRESHOLD}. Time shares use only active (sufficient and
 * complete) samples, so inactivity is never counted as anyone's advantage.
 */
public final class Dominance {
  public static final double TAU_SECONDS = 3,
      THRESHOLD = .2,
      MIN_MASS = .35,
      MAX_UNATTRIBUTED = .5,
      SWING_WINDOW_SECONDS = 3;

  /** Evidence weights. Confirmed or attributed observations only. */
  public static final double W_HIT = 1,
      W_STREAK_BONUS = .25,
      W_SHIELD_BLOCKED = .25,
      W_SHIELD_DISABLED = .5,
      W_LOCAL_BLOCK = .25;

  public enum State {
    LOCAL('L'),
    OPPONENT('O'),
    NEUTRAL('N'),
    INCOMPLETE('C'),
    INSUFFICIENT('I');

    /** One-letter code used by the GUI chart and the HTML report payload. */
    public final char code;

    State(char code) {
      this.code = code;
    }
  }

  /** One weighted observation: positive favors the local player. */
  public record Input(double time, double weight, String kind) {}

  public record Period(State side, double start, double end) {
    public double length() {
      return end - start;
    }
  }

  public record Swing(double from, double to, double fromValue, double toValue) {
    public double size() {
      return Math.abs(toValue - fromValue);
    }
  }

  public final double step;
  public final double[] times, values;
  public final State[] states;
  public final double activeSeconds, localSeconds, opponentSeconds, neutralSeconds;
  public final double incompleteSeconds, insufficientSeconds;
  public final Period longestAdvantage;
  public final Swing largestSwing;
  public final int changes;
  public final List<Period> periods;
  public final List<Input> inputs;
  public final Availability availability;

  public enum Availability {
    AVAILABLE("Available"),
    INCOMPLETE("Incomplete attribution"),
    INSUFFICIENT("Insufficient evidence");

    public final String label;

    Availability(String label) {
      this.label = label;
    }
  }

  private Dominance(
      double duration,
      List<Input> inputs,
      List<Double> unattributed,
      int attributedIncoming,
      List<double[]> activeIntervals) {
    this.inputs = List.copyOf(inputs);
    double span = Math.max(duration, inputs.isEmpty() ? 0 : inputs.getLast().time());
    step = Math.max(.05, span / 2000);
    int n = Math.max(2, (int) Math.ceil(span / step) + 1);
    times = new double[n];
    values = new double[n];
    states = new State[n];
    // One time-ordered pass over attributed evidence and unattributed incoming damage.
    var sorted = new ArrayList<Input>(inputs);
    for (double ut : unattributed) sorted.add(new Input(ut, 0, "UNATTRIBUTED"));
    sorted.sort(Comparator.comparingDouble(Input::time));
    double p = 0, q = 0, u = 0, at = 0;
    int i = 0, intervalIndex = 0;
    for (int k = 0; k < n; k++) {
      double t = k * step;
      times[k] = t;
      while (i < sorted.size() && sorted.get(i).time() <= t) {
        var in = sorted.get(i++);
        double f = Math.exp(-Math.max(0, in.time() - at) / TAU_SECONDS);
        p *= f;
        q *= f;
        u *= f;
        at = Math.max(at, in.time());
        if ("EXCHANGE_RESET".equals(in.kind())) {
          p = q = u = 0;
        } else if ("UNATTRIBUTED".equals(in.kind())) u += 1;
        else if (in.weight() > 0) p += in.weight();
        else q -= in.weight();
      }
      double f = Math.exp(-(t - at) / TAU_SECONDS);
      double pt = p * f, qt = q * f, ut = u * f;
      double mass = pt + qt;
      double d = (pt - qt) / (pt + qt + 1);
      State s =
          mass < MIN_MASS
              ? State.INSUFFICIENT
              : ut >= MAX_UNATTRIBUTED
                  ? State.INCOMPLETE
                  : d >= THRESHOLD ? State.LOCAL : d <= -THRESHOLD ? State.OPPONENT : State.NEUTRAL;
      if (activeIntervals != null) {
        while (intervalIndex < activeIntervals.size() && t >= activeIntervals.get(intervalIndex)[1])
          intervalIndex++;
        if (intervalIndex >= activeIntervals.size() || t < activeIntervals.get(intervalIndex)[0])
          s = State.INSUFFICIENT;
      }
      states[k] = s;
      values[k] = s == State.INSUFFICIENT ? Double.NaN : d;
    }
    double active = 0, local = 0, opp = 0, neutral = 0, incomplete = 0, insufficient = 0;
    for (var s : states) {
      switch (s) {
        case LOCAL -> local += step;
        case OPPONENT -> opp += step;
        case NEUTRAL -> neutral += step;
        case INCOMPLETE -> incomplete += step;
        case INSUFFICIENT -> insufficient += step;
      }
    }
    active = local + opp + neutral;
    activeSeconds = active;
    localSeconds = local;
    opponentSeconds = opp;
    neutralSeconds = neutral;
    incompleteSeconds = incomplete;
    insufficientSeconds = insufficient;

    List<Period> ps = new ArrayList<>();
    int startK = 0;
    for (int k = 1; k <= n; k++)
      if (k == n || states[k] != states[startK]) {
        ps.add(new Period(states[startK], times[startK], k == n ? times[n - 1] + step : times[k]));
        startK = k;
      }
    periods = List.copyOf(ps);
    Period best = null;
    for (var per : ps)
      if ((per.side() == State.LOCAL || per.side() == State.OPPONENT)
          && (best == null || per.length() > best.length())) best = per;
    longestAdvantage = best;

    int c = 0;
    State lastSide = null;
    for (var s : states)
      if (s == State.LOCAL || s == State.OPPONENT) {
        if (lastSide != null && lastSide != s) c++;
        lastSide = s;
      } else if (s == State.INSUFFICIENT || s == State.INCOMPLETE) lastSide = null;
    changes = c;

    // Largest |D(b) - D(a)| with 0 < b - a <= window inside one contiguous active run, in O(n):
    // monotonic deques keep the running maximum and minimum of the preceding window.
    int window = (int) Math.round(SWING_WINDOW_SECONDS / step);
    Swing swing = null;
    int[] maxQ = new int[n], minQ = new int[n];
    int maxH = 0, maxT = 0, minH = 0, minT = 0;
    for (int b = 0; b < n; b++) {
      if (!active(states[b])) {
        maxH = maxT = minH = minT = 0;
        continue;
      }
      while (maxH < maxT && maxQ[maxH] < b - window) maxH++;
      while (minH < minT && minQ[minH] < b - window) minH++;
      if (maxH < maxT) {
        int a = maxQ[maxH];
        double size = Math.abs(values[b] - values[a]);
        if (swing == null || size > swing.size() + 1e-12)
          swing = new Swing(times[a], times[b], values[a], values[b]);
      }
      if (minH < minT) {
        int a = minQ[minH];
        double size = Math.abs(values[b] - values[a]);
        if (swing == null || size > swing.size() + 1e-12)
          swing = new Swing(times[a], times[b], values[a], values[b]);
      }
      while (maxH < maxT && values[maxQ[maxT - 1]] <= values[b]) maxT--;
      maxQ[maxT++] = b;
      while (minH < minT && values[minQ[minT - 1]] >= values[b]) minT--;
      minQ[minT++] = b;
    }
    largestSwing = swing;
    double evidence = active + incomplete;
    // Missing attribution takes precedence: a curve built without the opponent's side must never
    // be presented as merely "thin" evidence that could be read as one-sided dominance.
    availability =
        incomplete > .25 * evidence
                || unattributed.size()
                    > Math.max(1, .25 * (attributedIncoming + unattributed.size()))
            ? Availability.INCOMPLETE
            : active < 1 || inputs.size() < 3 ? Availability.INSUFFICIENT : Availability.AVAILABLE;
  }

  private static boolean active(State s) {
    return s == State.LOCAL || s == State.OPPONENT || s == State.NEUTRAL;
  }

  /**
   * @param inputs weighted observations in seconds since fight start
   * @param unattributed times of incoming damage that could not be attributed to the opponent
   * @param attributedIncoming number of attributed opponent hits (for the fight-level check)
   */
  public static Dominance of(
      double duration, List<Input> inputs, List<Double> unattributed, int attributedIncoming) {
    return new Dominance(duration, inputs, unattributed, attributedIncoming, null);
  }

  public static Dominance of(
      double duration,
      List<Input> inputs,
      List<Double> unattributed,
      int attributedIncoming,
      List<double[]> activeIntervals) {
    return new Dominance(duration, inputs, unattributed, attributedIncoming, activeIntervals);
  }

  public double share(State s) {
    if (activeSeconds <= 0) return Double.NaN;
    return 100
        * (s == State.LOCAL ? localSeconds : s == State.OPPONENT ? opponentSeconds : neutralSeconds)
        / activeSeconds;
  }

  /** Value at a time (seconds), NaN where evidence is insufficient. */
  public double at(double t) {
    int k = (int) Math.round(t / step);
    return k < 0 || k >= values.length ? Double.NaN : values[k];
  }

  public State stateAt(double t) {
    int k = Math.max(0, Math.min(states.length - 1, (int) Math.round(t / step)));
    return states[k];
  }

  /** Times where the advantage passes from one side to the other, with the value after it. */
  public List<double[]> turningPoints() {
    List<double[]> out = new ArrayList<>();
    State last = null;
    for (int k = 0; k < states.length; k++) {
      var s = states[k];
      if (s == State.LOCAL || s == State.OPPONENT) {
        if (last != null && last != s) out.add(new double[] {times[k], values[k]});
        last = s;
      } else if (s == State.INSUFFICIENT || s == State.INCOMPLETE) last = null;
    }
    return out;
  }
}
