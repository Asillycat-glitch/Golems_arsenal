package a_silly_cat.golems_arsenal.client;

import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side lion-slash flip state per golem. The renderer derives the rotation angle from the
 * rendered Y height (the sine arc), so the somersault can never desync from what the player
 * actually sees, even with entity position interpolation lag.
 */
public final class ClientFlipData {
    public static final class FlipState {
        public final long startTick;
        /** Max rotation of this flip in degrees (lion slash: 360, phoenix rush: the configured end angle). */
        public final float maxAngleDeg;
        /** Phoenix mode holds the angle through hover/dive and unwinds it on landing. */
        public final boolean phoenix;
        public double baseY = Double.NaN;
        public double maxY = 0;

        public FlipState(long startTick) {
            this(startTick, 360.0F, false);
        }

        public FlipState(long startTick, float maxAngleDeg, boolean phoenix) {
            this.startTick = startTick;
            this.maxAngleDeg = maxAngleDeg;
            this.phoenix = phoenix;
        }
    }

    private static final Map<Integer, FlipState> FLIPS = new ConcurrentHashMap<>();

    public static void start(int entityId) {
        start(entityId, 360.0F, false);
    }

    public static void start(int entityId, float maxAngleDeg, boolean phoenix) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            FLIPS.put(entityId, new FlipState(mc.level.getGameTime(), maxAngleDeg, phoenix));
        }
    }

    public static FlipState get(int entityId) {
        return FLIPS.get(entityId);
    }

    public static void remove(int entityId) {
        FLIPS.remove(entityId);
    }

    private ClientFlipData() {
    }
}
