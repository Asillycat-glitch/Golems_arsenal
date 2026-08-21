package a_silly_cat.golems_arsenal.compat.golemmagicka;

import a_silly_cat.golems_arsenal.compat.golemmagicka.GolemScrollData;
import a_silly_cat.golems_arsenal.compat.golemmagicka.GolemScrollModifier;
import com.mojang.logging.LogUtils;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * Right-click a golem that has the scroll upgrade with a spell container (scroll, spellbook,
 * wand...) to record the first active spell onto the golem. Empty hand clears it.
 */
public final class GolemScrollRecordHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private GolemScrollRecordHandler() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getTarget() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (!GolemScrollModifier.hasUpgrade(golem)) {
            return;
        }
        ItemStack stack = event.getEntity().getItemInHand(event.getHand());
        if (stack.isEmpty()) {
            GolemScrollData.clear(golem);
            LOGGER.info("[GolemsArsenal] scroll upgrade: cleared recorded spell on golem {}", golem);
            event.getEntity().displayClientMessage(
                    Component.translatable("message.golems_arsenal.scroll_clear"), true);
            return;
        }
        ISpellContainer container = ISpellContainer.get(stack);
        if (container == null || container.isEmpty()) {
            return;
        }
        SpellSlot slot = container.getActiveSpells().stream().findFirst().orElse(null);
        if (slot == null) {
            return;
        }
        AbstractSpell spell = slot.getSpell();
        if (spell == null || spell == SpellRegistry.none()) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(spell.getSpellId());
        if (id == null) {
            return;
        }
        GolemScrollData.set(golem, id, slot.getLevel());
        LOGGER.info("[GolemsArsenal] scroll upgrade: recorded spell {} level {} on golem {} (mainhand: {}, offhand: {})",
                id, slot.getLevel(), golem, golem.getMainHandItem(), golem.getOffhandItem());
        event.getEntity().displayClientMessage(
                Component.translatable("message.golems_arsenal.scroll_record",
                        spell.getDisplayName(event.getEntity())), true);
    }
}
