package a_silly_cat.golems_arsenal.guard.sanctuary;

/**
 * 一次伤害在"谁打的"这件事上的分类。判定只看身份，不看 item id / mod id。
 */
public enum SourceKind {

    /** 本模组自己注册的伤害类型（{@code golems_arsenal:*}）。这就是 L2 内部标记。 */
    INTERNAL,

    /**
     * “操作级”伤害：伤害类型带 {@code minecraft:bypasses_invulnerability}。
     *
     * <p>这一层不看谁打的——生物、别的玩家、甚至脚本都算。toaru 的 {@code gungnir}、
     * Avaritia 的 {@code infinity}、Jerotes 的 {@code magic_effect} 都在这条 tag 里。
     * 战斗态测试期间默认放行（{@code deny_operation=false}）；恢复旧行为时设 true。</p>
     */
    OPERATION,

    /** 环境伤害：火/爆炸/摔落/溺水/冰冻/雷击，以及原版的几个行政伤害类型。 */
    ENVIRONMENT,

    /** 活体敌人（包括别的玩家、别人家的傀儡、野生生物）。正常放行。 */
    ENEMY,

    /** 主人自己（左键、主人的 AoE、主人手持的任何物品）。 */
    OWNER,

    /** 主人射出去的投射物/召唤物。 */
    OWNER_PROJECTILE,

    /** 同一主人的另一只傀儡（友军 AoE、分摊、反伤）。 */
    ALLY,

    /** 没有攻击者、也不是环境类型：可能是 true damage、DoT、脚本伤害。 */
    UNATTRIBUTED
}
