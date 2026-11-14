package a_silly_cat.golems_arsenal.guard;

/**
 * HealthLedger 的纯函数部分。刻意不 import 任何 Minecraft 类，方便直接单测。
 */
public final class HealthAuditMath {

    public static final float EPS = 1.0E-3F;

    private HealthAuditMath() {
    }

    /** 吸收后的实际生命损失。 */
    public static float absorbedLoss(float amount, float absorption) {
        if (!Float.isFinite(amount) || amount <= 0.0F) {
            return 0.0F;
        }
        float absorb = Float.isFinite(absorption) ? Math.max(0.0F, absorption) : 0.0F;
        return Math.max(0.0F, amount - absorb);
    }

    /** raw 和 expected 的差是否超出浮点误差。 */
    public static boolean unexplained(float expected, float raw) {
        return Float.isFinite(expected) && Float.isFinite(raw)
                && Math.abs(raw - expected) > EPS;
    }

    public static float expectedAfterDamage(float expected, float amount, float absorption) {
        return Math.max(0.0F, expected - absorbedLoss(amount, absorption));
    }

    public static float expectedAfterHeal(float expected, float maxHealth, float amount) {
        float healed = Float.isFinite(amount) ? Math.max(0.0F, amount) : 0.0F;
        float max = Float.isFinite(maxHealth) ? maxHealth : expected + healed;
        return Math.min(max, expected + healed);
    }
}
