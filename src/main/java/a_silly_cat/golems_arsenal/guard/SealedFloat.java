package a_silly_cat.golems_arsenal.guard;

import java.security.SecureRandom;

/**
 * 多层封装的 float 存储。
 *
 * <p>不是密码学意义上的安全容器：同进程 native 仍能读代码/找 key/改字段。
 * 它只做“内存里不出现明文 float，且盲写 lo/hi 会触发校验失败”：
 * XOR → rotate → odd multiply → per-instance XOR → split/checksum。
 * 目标是让按 float 位模式扫描/盲写 Entity 的玩法失效，而不是宣称挡住任意 native。</p>
 */
public final class SealedFloat {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int K1 = RNG.nextInt();
    private static final int K2 = RNG.nextInt();
    private static final int ROT = RNG.nextInt(32);
    private static final int K3 = (RNG.nextInt() | 1); // odd => invertible
    private static final int K3_INV = Integer.parseUnsignedInt(
            java.math.BigInteger.valueOf(K3 & 0xffffffffL)
                    .modInverse(java.math.BigInteger.ONE.shiftLeft(32))
                    .toString());

    private final int nonce = RNG.nextInt();
    private final int maskLo = RNG.nextInt();
    private final int maskHi = RNG.nextInt();
    private int lo;
    private int hi;
    private int check;
    private boolean initialized;

    public synchronized void set(float value) {
        int bits = Float.floatToRawIntBits(value);
        int x = bits ^ K1;
        x = Integer.rotateLeft(x, ROT);
        x *= K3;
        x ^= nonce;
        int c = checksum(x, nonce);
        lo = x ^ maskLo;
        hi = c ^ maskHi;
        check = checksum(lo, hi) ^ nonce;
        initialized = true;
    }

    public synchronized float get() {
        if (!initialized) {
            return Float.NaN;
        }
        int x = lo ^ maskLo;
        int c = hi ^ maskHi;
        if (c != checksum(x, nonce) || check != (checksum(lo, hi) ^ nonce)) {
            initialized = false;
            return Float.NaN;
        }
        x ^= nonce;
        x *= K3_INV;
        x = Integer.rotateRight(x, ROT);
        x ^= K1;
        return Float.intBitsToFloat(x);
    }

    public synchronized boolean isValid() {
        if (!initialized) {
            return false;
        }
        int x = lo ^ maskLo;
        int c = hi ^ maskHi;
        return c == checksum(x, nonce) && check == (checksum(lo, hi) ^ nonce);
    }

    private static int checksum(int x, int n) {
        int h = x * 0x9E3779B9;
        h ^= Integer.rotateLeft(n, 13);
        h *= 0x85EBCA6B;
        h ^= h >>> 16;
        return h;
    }
}
