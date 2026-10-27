package dev.ironsight.guns;

/**
 * Where one player stood over the last {@link #SIZE} server ticks, for lag compensation.
 * Fixed arrays, written in place every tick: no allocation once created.
 */
public final class PositionHistory {
    public static final int SIZE = 20;

    private final double[] x = new double[SIZE];
    private final double[] y = new double[SIZE];
    private final double[] z = new double[SIZE];
    private final float[] height = new float[SIZE];
    private final long[] tick = new long[SIZE];
    private int count;

    public void record(long now, double px, double py, double pz, float h) {
        int i = (int) (now % SIZE);
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        height[i] = h;
        tick[i] = now;
        if (count < SIZE) count++;
    }

    public void clear() {
        count = 0;
    }

    /** Fills out[0..3] with x,y,z,height as of {@code ticksAgo} ticks ago (clamped to what is stored). */
    public boolean sample(long now, int ticksAgo, double[] out) {
        if (count == 0) return false;
        int back = Math.min(ticksAgo, count - 1);
        for (; back >= 0; back--) {
            int i = (int) Math.floorMod(now - back, (long) SIZE);
            if (tick[i] == now - back) {
                out[0] = x[i];
                out[1] = y[i];
                out[2] = z[i];
                out[3] = height[i];
                return true;
            }
        }
        return false;
    }
}
