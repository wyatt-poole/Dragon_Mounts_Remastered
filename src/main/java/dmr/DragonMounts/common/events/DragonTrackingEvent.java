package dmr.DragonMounts.common.events;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.network.packets.DragonAbilitiesSyncPacket;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Event handler for dragon entity tracking.
 * Sends dragon abilities data to players when they start tracking a dragon.
 */
@EventBusSubscriber(modid = DMR.MOD_ID)
public class DragonTrackingEvent {

    /**
     * Called when a player starts tracking an entity.
     * If the entity is a dragon, send the dragon's abilities to the player.
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof TameableDragonEntity dragon) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                // Send the dragon's abilities to the tracking player
                PacketDistributor.sendToPlayer(serverPlayer, new DragonAbilitiesSyncPacket(dragon));
            }
        }
    }
}
