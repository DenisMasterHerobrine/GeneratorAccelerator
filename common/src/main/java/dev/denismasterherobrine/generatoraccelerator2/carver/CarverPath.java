package dev.denismasterherobrine.generatoraccelerator2.carver;

import java.util.Arrays;
import java.util.List;

/**
 * Everything one carver start carves: the rooms and tunnels of the start, in execution order. Recorded
 * from a simulation of the vanilla carver with the target-dependent parts left out. Rooms and tunnel
 * steps are ellipsoids. A tunnel keeps only its carving steps, i.e. the steps that drew
 * {@code nextInt(4) != 0}. Each step keeps what vanilla's {@code canReach} needs. A tunnel that
 * branched also keeps the two branch tunnels it ends in. Immutable once built.
 */
public final class CarverPath {
    /** Nothing carved: the start check failed. */
    public static final CarverPath NONE = new CarverPath(new Op[0], Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    final Op[] ops;
    /** Conservative box around every ellipsoid's reach (centre +- (16 + 2 * horizontal radius) + 1). */
    final double minX;
    final double maxX;
    final double minZ;
    final double maxZ;

    CarverPath(Op[] ops, double minX, double maxX, double minZ, double maxZ) {
        this.ops = ops;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    public boolean isEmpty() {
        return ops.length == 0;
    }

    /** True when no ellipsoid of the path can touch a chunk whose middle block is at (midX, midZ). */
    boolean misses(double midX, double midZ) {
        return ops.length == 0 || midX < minX || midX > maxX || midZ < minZ || midZ > maxZ;
    }

    /** Skip test of one start: a cave's floor level or a canyon's per-y width factors. */
    static final class Skip {
        final boolean canyon;
        final double floor;
        final float[] widths;

        Skip(double floor) {
            this.canyon = false;
            this.floor = floor;
            this.widths = null;
        }

        Skip(float[] widths) {
            this.canyon = true;
            this.floor = 0.0;
            this.widths = widths;
        }
    }

    abstract static sealed class Op permits Room, Tunnel {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        void include(double x, double z, double hr) {
            double reach = 16.0 + hr * 2.0 + 1.0;
            minX = Math.min(minX, x - reach);
            maxX = Math.max(maxX, x + reach);
            minZ = Math.min(minZ, z - reach);
            maxZ = Math.max(maxZ, z + reach);
        }

        void include(Op other) {
            minX = Math.min(minX, other.minX);
            maxX = Math.max(maxX, other.maxX);
            minZ = Math.min(minZ, other.minZ);
            maxZ = Math.max(maxZ, other.maxZ);
        }

        boolean misses(double midX, double midZ) {
            return midX < minX || midX > maxX || midZ < minZ || midZ > maxZ;
        }
    }

    /** {@code createRoom}: one ellipsoid, carved without a {@code canReach} test. */
    static final class Room extends Op {
        final double x;
        final double y;
        final double z;
        final double hr;
        final double vr;
        final Skip skip;

        Room(double x, double y, double z, double hr, double vr, Skip skip) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.hr = hr;
            this.vr = vr;
            this.skip = skip;
            include(x, z, hr);
        }
    }

    /** {@code createTunnel} / {@code doCarve}: carving steps, then the branches if the tunnel branched. */
    static final class Tunnel extends Op {
        final int end;
        final float thickness;
        final Skip skip;
        /**
         * The steps, recorded into these arrays as the simulation goes. The first {@link #n} entries are used.
         * {@link #freeze} trims the rest.
         */
        double[] x = new double[16];
        double[] y = new double[16];
        double[] z = new double[16];
        double[] hr = new double[16];
        double[] vr = new double[16];
        int[] j = new int[16];
        int n;
        Tunnel branchA;
        Tunnel branchB;

        Tunnel(int end, float thickness, Skip skip) {
            this.end = end;
            this.thickness = thickness;
            this.skip = skip;
        }

        void step(int j, double x, double y, double z, double hr, double vr) {
            int i = n;
            if (i == this.x.length) {
                int cap = i * 2;
                this.x = Arrays.copyOf(this.x, cap);
                this.y = Arrays.copyOf(this.y, cap);
                this.z = Arrays.copyOf(this.z, cap);
                this.hr = Arrays.copyOf(this.hr, cap);
                this.vr = Arrays.copyOf(this.vr, cap);
                this.j = Arrays.copyOf(this.j, cap);
            }
            this.j[i] = j;
            this.x[i] = x;
            this.y[i] = y;
            this.z[i] = z;
            this.hr[i] = hr;
            this.vr[i] = vr;
            n = i + 1;
            include(x, z, hr);
        }

        void branch(Tunnel a, Tunnel b) {
            branchA = a;
            branchB = b;
            include(a);
            include(b);
        }

        /** Trims the recorded steps to their number (a path is kept while chunks around its start carve). */
        void freeze() {
            if (x.length != n) {
                x = Arrays.copyOf(x, n);
                y = Arrays.copyOf(y, n);
                z = Arrays.copyOf(z, n);
                hr = Arrays.copyOf(hr, n);
                vr = Arrays.copyOf(vr, n);
                j = Arrays.copyOf(j, n);
            }
        }
    }

    static CarverPath of(List<Op> ops) {
        if (ops.isEmpty()) {
            return new CarverPath(new Op[0], Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Op op : ops) {
            minX = Math.min(minX, op.minX);
            maxX = Math.max(maxX, op.maxX);
            minZ = Math.min(minZ, op.minZ);
            maxZ = Math.max(maxZ, op.maxZ);
        }
        return new CarverPath(ops.toArray(new Op[0]), minX, maxX, minZ, maxZ);
    }
}
