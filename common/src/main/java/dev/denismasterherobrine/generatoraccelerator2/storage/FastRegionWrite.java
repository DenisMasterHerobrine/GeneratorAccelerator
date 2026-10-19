package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.Deflater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/**
 * The IO thread's part of saving a chunk ({@code RegionFileStorage.write}). Vanilla writes the chunk's NBT through
 * {@code DataOutputStream -> BufferedOutputStream -> DeflaterOutputStream -> RegionFile.ChunkBuffer}. That chain takes a
 * lock for every value written: the buffered stream's internal lock and the data stream's synchronized writes. It also
 * sets up a new zlib stream per chunk. This class writes the same NBT bytes into a plain array. It compresses them with a
 * reused {@link Deflater} at the same default settings. zlib's output does not depend on how its input is split. The same
 * [length, version, data] buffer then goes to {@code RegionFile.write}. Only for the deflate format, which is the default.
 *
 * <p>{@code -Dgeneratoraccelerator2.fastRegionWrite=false} turns it off.
 */
public final class FastRegionWrite {
    private FastRegionWrite() {}

    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fastRegionWrite"));

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

    public static boolean enabled() {
        return ENABLED && StorageConfig.fastRegionWrite() && HookTargets.allowed(HookTargets.STORAGE_REGION_WRITE);
    }

    /** The bytes {@code RegionFile.ChunkBuffer} would hold when closed: 4-byte length, the version id, the compressed NBT. */
    public static ByteBuffer encode(CompoundTag tag, int versionId) throws IOException {
        Buffers b = BUFFERS.get();
        b.shrink();
        b.nbt.reset();
        NbtIo.write(tag, b.nbt);
        Deflater deflater = b.deflater;
        deflater.reset();
        deflater.setInput(b.nbt.buf, 0, b.nbt.count);
        deflater.finish();
        byte[] out = b.out;
        int count = 5;
        while (!deflater.finished()) {
            if (out.length - count < 4096) {
                out = b.out = Arrays.copyOf(out, out.length * 2);
            }
            count += deflater.deflate(out, count, out.length - count);
        }
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 0;
        out[4] = (byte) versionId;
        ByteBuffer buffer = ByteBuffer.wrap(out, 0, count);
        buffer.putInt(0, count - 5 + 1);
        return buffer;
    }

    private static final class Buffers {
        /** Larger arrays are dropped before the next chunk, so one huge chunk does not stay in memory once per thread. */
        static final int KEEP = 1 << 20;
        final Bytes nbt = new Bytes(1 << 16);
        final Deflater deflater = new Deflater();
        byte[] out = new byte[1 << 15];

        /** Called before encoding. By then the last result, which wraps {@code out}, has been written. */
        void shrink() {
            if (this.out.length > KEEP) {
                this.out = new byte[1 << 15];
            }
            if (this.nbt.buf.length > KEEP) {
                this.nbt.buf = new byte[1 << 16];
            }
        }
    }

    /** {@code DataOutputStream}'s encoding (big-endian, modified UTF-8) into a growable array, without locks. */
    static final class Bytes implements DataOutput {
        byte[] buf;
        int count;

        Bytes(int size) {
            this.buf = new byte[size];
        }

        void reset() {
            count = 0;
        }

        private void ensure(int more) {
            if (buf.length - count < more) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, count + more));
            }
        }

        @Override
        public void write(int b) {
            ensure(1);
            buf[count++] = (byte) b;
        }

        @Override
        public void write(byte[] b) {
            write(b, 0, b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            ensure(len);
            System.arraycopy(b, off, buf, count, len);
            count += len;
        }

        @Override
        public void writeBoolean(boolean v) {
            write(v ? 1 : 0);
        }

        @Override
        public void writeByte(int v) {
            write(v);
        }

        @Override
        public void writeShort(int v) {
            ensure(2);
            buf[count] = (byte) (v >>> 8);
            buf[count + 1] = (byte) v;
            count += 2;
        }

        @Override
        public void writeChar(int v) {
            writeShort(v);
        }

        @Override
        public void writeInt(int v) {
            ensure(4);
            buf[count] = (byte) (v >>> 24);
            buf[count + 1] = (byte) (v >>> 16);
            buf[count + 2] = (byte) (v >>> 8);
            buf[count + 3] = (byte) v;
            count += 4;
        }

        @Override
        public void writeLong(long v) {
            ensure(8);
            for (int i = 0; i < 8; i++) {
                buf[count + i] = (byte) (v >>> (56 - 8 * i));
            }
            count += 8;
        }

        @Override
        public void writeFloat(float v) {
            writeInt(Float.floatToIntBits(v));
        }

        @Override
        public void writeDouble(double v) {
            writeLong(Double.doubleToLongBits(v));
        }

        @Override
        public void writeBytes(String s) {
            int len = s.length();
            ensure(len);
            for (int i = 0; i < len; i++) {
                buf[count++] = (byte) s.charAt(i);
            }
        }

        @Override
        public void writeChars(String s) {
            int len = s.length();
            ensure(2 * len);
            for (int i = 0; i < len; i++) {
                int c = s.charAt(i);
                buf[count++] = (byte) (c >>> 8);
                buf[count++] = (byte) c;
            }
        }

        /** {@code DataOutputStream.writeUTF}: length-prefixed modified UTF-8. Past 65535 bytes it throws and writes nothing. */
        @Override
        public void writeUTF(String s) throws IOException {
            int strlen = s.length();
            if (strlen <= 65535) {
                // One pass for the usual all-ASCII string (ids, keys, values). Each char is one byte, so no length pre-count.
                ensure(strlen + 2);
                byte[] b = buf;
                int p = count + 2;
                int i = 0;
                while (i < strlen) {
                    char c = s.charAt(i);
                    if (c >= 0x80 || c == 0) {
                        break;
                    }
                    b[p + i] = (byte) c;
                    i++;
                }
                if (i == strlen) {
                    b[count] = (byte) (strlen >>> 8);
                    b[count + 1] = (byte) strlen;
                    count = p + strlen;
                    return;
                }
            }
            int utflen = strlen;
            for (int i = 0; i < strlen; i++) {
                int c = s.charAt(i);
                if (c >= 0x80 || c == 0) {
                    utflen += c >= 0x800 ? 2 : 1;
                }
            }
            if (utflen > 65535 || utflen < strlen) {
                throw new UTFDataFormatException("encoded string too long: " + utflen + " bytes");
            }
            ensure(utflen + 2);
            buf[count++] = (byte) (utflen >>> 8);
            buf[count++] = (byte) utflen;
            for (int i = 0; i < strlen; i++) {
                int c = s.charAt(i);
                if (c < 0x80 && c != 0) {
                    buf[count++] = (byte) c;
                } else if (c >= 0x800) {
                    buf[count++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
                    buf[count++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                    buf[count++] = (byte) (0x80 | (c & 0x3F));
                } else {
                    buf[count++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
                    buf[count++] = (byte) (0x80 | (c & 0x3F));
                }
            }
        }
    }
}
