package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;

import java.util.UUID;

/**
 * "谁是自己人"的共享判定，供天基激光的光束伤害与引导选敌**共用同一套规则**。
 *
 * <p><b>为什么单独提出来。</b>引导升级负责挑目标、光束实体负责打人，两边如果各写一套友军判定，
 * 就会出现"引导器觉得该打、光束却因为判成友军而拒打"这种自相矛盾的情况。放在一处是唯一安全的做法。
 *
 * <p>规则与 {@code LightPillarEntity} 的历史实现一致：光靠 {@code isAlliedTo} 不够——
 * 本家 {@code AbstractGolemEntity#isAlliedTo} 走 {@code getOwner()}，而它 = {@code getPlayerByUUID(uuid)}，
 * **主人不在线就返回 null**，于是同一个主人的两只傀儡会被判成敌人。所以这里额外比对
 * {@code getOwnerUUID()}（与本仓库 {@code GolemTrainModifier} 判"同主"的写法一致），
 * 让判定不依赖主人在线与否。
 */
public final class GolemFriendlyFire {

    /**
     * {@code target} 是否算作 {@code owner}（引导者/施放者）的自己人。
     *
     * @param owner            引导者或施放者，可为 null（例如调试命令直接开火时没有归属者）
     * @param ownerId          施放者的 UUID；即使实体已不在场也能认出它
     * @param ownerPlayerId    施放者的**主人** UUID；主人不在线时靠它兜底
     */
    public static boolean isFriendly(LivingEntity owner, LivingEntity target,
                                     UUID ownerId, UUID ownerPlayerId) {
        if (target == owner || target.getUUID().equals(ownerId)) {
            return true;
        }
        if (owner != null && (target.isAlliedTo(owner) || owner.isAlliedTo(target))) {
            return true;
        }
        UUID master = ownerPlayerId;
        if (master == null && owner instanceof OwnableEntity ownable) {
            master = ownable.getOwnerUUID();
        }
        if (master == null) {
            return false;
        }
        if (master.equals(target.getUUID())) {
            return true;
        }
        return target instanceof OwnableEntity other && master.equals(other.getOwnerUUID());
    }

    private GolemFriendlyFire() {
    }
}
