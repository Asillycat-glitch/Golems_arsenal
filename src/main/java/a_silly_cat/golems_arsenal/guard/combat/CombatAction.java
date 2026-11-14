package a_silly_cat.golems_arsenal.guard.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 一次战斗动作的聚合单位。
 *
 * <p>ItemProfile 只是 contributor 之一；将来 ProjectileProfile / SpellProfile /
 * HandlerProfile 也可以加入。supported 是“所有 contributor 的能力并集 ⊆ 已处理能力”
 * 的 all-or-nothing 结果，不是单个物品的布尔值。</p>
 */
public final class CombatAction {

    private final ItemStack item;
    private final Entity directEntity;
    private final LivingEntity causingEntity;
    private final List<CombatContributor> contributors;
    private final Set<CombatCapability> capabilities;
    private final Set<CombatCapability> handled;
    private final boolean combat;
    private final boolean supported;

    private CombatAction(ItemStack item, Entity directEntity, LivingEntity causingEntity,
                         List<CombatContributor> contributors) {
        this.item = item;
        this.directEntity = directEntity;
        this.causingEntity = causingEntity;
        this.contributors = List.copyOf(contributors);
        EnumSet<CombatCapability> caps = EnumSet.noneOf(CombatCapability.class);
        for (CombatContributor contributor : contributors) {
            caps.addAll(contributor.capabilities());
        }
        this.capabilities = Set.copyOf(caps);
        EnumSet<CombatCapability> supportedCaps = EnumSet.noneOf(CombatCapability.class);
        supportedCaps.addAll(ItemProfile.ALLOWED);
        if (item != null && !item.isEmpty()) {
            supportedCaps.addAll(CombatAdapters.handledBy(item.getItem().getClass()));
        }
        this.handled = Set.copyOf(supportedCaps);
        this.combat = !caps.isEmpty();
        this.supported = supportedCaps.containsAll(caps);
    }

    public static CombatAction forItem(ItemStack stack, Entity directEntity, LivingEntity causingEntity) {
        return new CombatAction(stack, directEntity, causingEntity,
                List.of(CombatProfiles.resolve(stack)));
    }

    public static CombatAction of(ItemStack stack, Entity directEntity, LivingEntity causingEntity,
                                  List<CombatContributor> contributors) {
        return new CombatAction(stack, directEntity, causingEntity, contributors);
    }

    public ItemStack item() {
        return item;
    }

    public Entity directEntity() {
        return directEntity;
    }

    public LivingEntity causingEntity() {
        return causingEntity;
    }

    public List<CombatContributor> contributors() {
        return contributors;
    }

    public Set<CombatCapability> capabilities() {
        return capabilities;
    }

    public Set<CombatCapability> handled() {
        return handled;
    }

    public boolean combat() {
        return combat;
    }

    public boolean supported() {
        return supported;
    }

    public String describe() {
        return "contributors=" + contributors.stream().map(CombatContributor::source).toList()
                + " caps=" + capabilities
                + " handled=" + handled
                + " combat=" + combat
                + " supported=" + supported;
    }
}
