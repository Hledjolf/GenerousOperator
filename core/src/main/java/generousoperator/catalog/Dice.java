package generousoperator.catalog;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A count in dice notation: a plain number ({@code 5}), or {@code NdM}, optionally followed by {@code +K} or
 * {@code -K} and then {@code xL} ({@code 2d6}, {@code 1d4+1}, {@code 3d6x10}, {@code d8}). The result is
 * {@code (rolls + K) * L}, never below zero.
 */
public final class Dice {
    private static final Pattern FORM = Pattern.compile("(\\d*)d(\\d+)([+-]\\d+)?(?:x(\\d+))?");
    private static final int MAX_DICE = 1000;
    private static final int MAX_SIDES = 1_000_000;
    private static final long MAX_VALUE = 1_000_000;

    private final String text;
    private final int dice;   // 0 for a plain number
    private final int sides;
    private final long plus;
    private final long times;

    private Dice(String text, int dice, int sides, long plus, long times) {
        this.text = text;
        this.dice = dice;
        this.sides = sides;
        this.plus = plus;
        this.times = times;
    }

    public static Dice constant(long n) {
        return new Dice(Long.toString(n), 0, 0, n, 1);
    }

    /** Parses a count: a JSON number (Long) or a dice string. Throws IllegalArgumentException with the reason. */
    public static Dice of(Object value) {
        if (value instanceof Long) {
            long n = (Long) value;
            if (n < 0 || n > MAX_VALUE) throw new IllegalArgumentException("count " + n + " is out of range 0 to " + MAX_VALUE);
            return constant(n);
        }
        if (!(value instanceof String)) throw new IllegalArgumentException("a count is a whole number or dice like \"2d6\"");
        String t = ((String) value).trim().toLowerCase();
        if (t.matches("\\d+")) return of(Long.parseLong(t));
        Matcher m = FORM.matcher(t);
        if (!m.matches()) throw new IllegalArgumentException("\"" + value + "\" isn't a count or dice like \"2d6\", \"1d4+1\" or \"3d6x10\"");
        int n = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
        int s = Integer.parseInt(m.group(2));
        long k = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        long l = m.group(4) == null ? 1 : Long.parseLong(m.group(4));
        if (n < 1 || n > MAX_DICE) throw new IllegalArgumentException("\"" + value + "\": 1 to " + MAX_DICE + " dice");
        if (s < 1 || s > MAX_SIDES) throw new IllegalArgumentException("\"" + value + "\": dice need 1 to " + MAX_SIDES + " sides");
        Dice d = new Dice(t, n, s, k, l);
        if (d.max() > MAX_VALUE) throw new IllegalArgumentException("\"" + value + "\" can reach " + d.max() + ", more than " + MAX_VALUE);
        return d;
    }

    public long min() {
        return Math.max(0, (dice + plus) * times);
    }

    public long max() {
        return Math.max(0, ((long) dice * sides + plus) * times);
    }

    public long roll(Random rng) {
        long sum = plus;
        for (int k = 0; k < dice; k++) sum += 1 + rng.nextInt(sides);
        return Math.max(0, sum * times);
    }

    public boolean isConstant() {
        return dice == 0;
    }

    @Override
    public String toString() {
        return text;
    }
}
