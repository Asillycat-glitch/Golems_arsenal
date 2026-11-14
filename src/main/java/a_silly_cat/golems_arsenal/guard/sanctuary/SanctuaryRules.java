package a_silly_cat.golems_arsenal.guard.sanctuary;

/**
 * Sanctuary 的判定表。纯函数，不碰 Minecraft 状态，单测直接打这张表。
 *
 * <p>语义：<b>只防“自己人 / 说不清来路 / 操作级”的伤害，不防普通敌人的正常伤害</b>。
 * 这一层不参与数值、不改伤害、不加 tag。</p>
 *
 * @param denyOwner        主人自己打（左键、主人的 AoE）是否拒绝
 * @param denyAlly         同主傀儡之间是否拒绝（友军 AoE / 分摊 / 反伤）
 * @param denyUnattributed 无攻击者且非环境是否拒绝
 * @param allowEnvironment 环境伤害是否放行（false = 也拒绝）
 * @param denyOperation    操作级伤害（{@code bypasses_invulnerability}）是否拒绝，不看攻击者是谁。
 *                         战斗态测试期间默认 false；来自 `deny_operation` 配置。
 */
public record SanctuaryRules(boolean denyOwner, boolean denyAlly,
                             boolean denyUnattributed, boolean allowEnvironment,
                             boolean denyOperation) {

    /** 默认：主人、友军、说不清来路的拒绝；环境放行；操作级当前放行。 */
    public static final SanctuaryRules DEFAULT = new SanctuaryRules(true, true, true, true, false);

    public boolean allows(SourceKind kind) {
        return switch (kind) {
            case INTERNAL, ENEMY -> true;
            case OPERATION -> !denyOperation;
            case ENVIRONMENT -> allowEnvironment;
            case OWNER, OWNER_PROJECTILE -> !denyOwner;
            case ALLY -> !denyAlly;
            case UNATTRIBUTED -> !denyUnattributed;
        };
    }
}
