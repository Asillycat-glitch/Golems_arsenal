package a_silly_cat.golems_arsenal.guard.combat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按物品**类行为**解析能力画像，不按 item id / mod id。
 *
 * <p>已知基础家族（剑/斧/镐/锹/锄/三叉戟/弓/弩/投掷物）给正常能力；
 * 如果模组类自己声明了战斗/使用钩子，就加 {@link CombatCapability#CUSTOM_HOOK}，
 * 没有适配器时整个物品交互不支持。继承自原版基类、自己不覆写的模组子类不会误报。</p>
 */
public final class CombatProfiles {

    private static final Map<Class<?>, ItemProfile> CACHE = new ConcurrentHashMap<>();

    private CombatProfiles() {
    }

    public static ItemProfile resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemProfile.of("empty", Set.of());
        }
        Item item = stack.getItem();
        return CACHE.computeIfAbsent(item.getClass(), cls -> compute(item, cls));
    }

    private static ItemProfile compute(Item item, Class<?> cls) {
        Set<CombatCapability> caps = EnumSet.noneOf(CombatCapability.class);
        boolean melee = item instanceof SwordItem
                || item instanceof AxeItem
                || item instanceof PickaxeItem
                || item instanceof ShovelItem
                || item instanceof HoeItem
                || item instanceof TieredItem
                || item instanceof TridentItem;
        boolean ranged = item instanceof BowItem
                || item instanceof CrossbowItem
                || item instanceof TridentItem;

        if (melee) {
            caps.add(CombatCapability.NORMAL_MELEE);
        }
        if (ranged) {
            caps.add(CombatCapability.NORMAL_RANGED);
        }

        boolean vanilla = cls.getName().startsWith("net.minecraft.");
        boolean attackHook = overrides(cls, "onLeftClickEntity",
                ItemStack.class, Player.class, Entity.class)
                || overrides(cls, "hurtEnemy",
                ItemStack.class, LivingEntity.class, LivingEntity.class)
                || overrides(cls, "onEntitySwing",
                ItemStack.class, LivingEntity.class);
        boolean customUse = overrides(cls, "use",
                Level.class, Player.class, InteractionHand.class)
                || overrides(cls, "useOn", UseOnContext.class);
        boolean continuous = overrides(cls, "inventoryTick",
                ItemStack.class, Level.class, Entity.class, int.class, boolean.class);

        // 没有已知武器家族，但自己声明了攻击钩子：也是战斗物品，未知 -> custom。
        // 这里只按行为判定，不按模组包名判定。
        boolean combat = melee || ranged || attackHook;
        boolean meleeLike = melee || attackHook;
        boolean pureRanged = ranged && !melee;
        // 纯远程的 use/inventoryTick 是弓/弩的正常拉弓/装填行为，不算自定义危险 hook；
        // 纯远程的攻击钩子也多为动画/拉弓逻辑，兼容性优先交给 backstop。
        if (combat && continuous && meleeLike) {
            caps.add(CombatCapability.CONTINUOUS_HOOK);
        }
        if (combat && !vanilla && !pureRanged
                && (attackHook || (melee && (customUse || continuous)))) {
            caps.add(CombatCapability.CUSTOM_HOOK);
        }
        return ItemProfile.of(cls.getName(), caps);
    }

    private static boolean overrides(Class<?> cls, String name, Class<?>... params) {
        try {
            Method method = cls.getMethod(name, params);
            Class<?> declaring = method.getDeclaringClass();
            // 继承自原版基类的实现不算“模组自定义钩子”；
            // 模组类自己覆写（declaring 是模组类）才加 CUSTOM_HOOK。
            return declaring != Item.class && !declaring.getName().startsWith("net.minecraft.");
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }
}
