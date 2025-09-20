package dmr.DragonMounts.common.handlers;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.network.packets.*;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.types.abilities.Ability;
import dmr.DragonMounts.util.DragonStateChecksum;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Manages efficient dragon synchronization with version tracking and fallback mechanisms.
 * Provides automatic packet selection and desync detection/recovery.
 */
public class DragonSyncManager {

    // Version tracking for ordering and deduplication
    private static final Map<UUID, Map<Integer, Long>> ownerVersions = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> publicVersions = new ConcurrentHashMap<>();

    // Validation timing
    private static final Map<UUID, Long> lastValidationSent = new ConcurrentHashMap<>();
    private static final long BASE_VALIDATION_INTERVAL = 45000; // 45 seconds
    private static final double MAX_SYNC_DISTANCE_SQ = 64 * 64; // 64 block radius

    // === OWNER SYNC METHODS ===

    /**
     * Send detailed heartbeat to dragon owner (existing enhanced system)
     */
    public static void sendOwnerHeartbeat(TameableDragonEntity dragon) {
        ServerPlayer owner = (ServerPlayer) dragon.getOwner();
        if (owner == null) return;

        int dragonIndex = DragonWhistleHandler.getDragonSummonIndex(owner, dragon.getDragonUUID());
        if (dragonIndex == -1) return;

        CompoundTag ownerData = buildOwnerData(dragon);
        PacketDistributor.sendToPlayer(owner, new DragonHeartbeatPacket(dragonIndex, ownerData));
    }

    /**
     * Send targeted owner update instead of CompleteDataSync
     */
    public static void updateOwnerData(
            ServerPlayer owner, int dragonIndex, DragonOwnerUpdatePacket.UpdateType type, CompoundTag data) {
        long version = incrementOwnerVersion(owner.getUUID(), dragonIndex);
        PacketDistributor.sendToPlayer(owner, new DragonOwnerUpdatePacket(dragonIndex, type, data, version));
    }

    /**
     * Convenience method for NBT updates
     */
    public static void updateOwnerNBT(ServerPlayer owner, int dragonIndex, CompoundTag nbtData) {
        CompoundTag updateData = new CompoundTag();
        updateData.put("nbt", nbtData);
        updateOwnerData(owner, dragonIndex, DragonOwnerUpdatePacket.UpdateType.NBT_DATA, updateData);
    }

    /**
     * Convenience method for respawn delay updates
     */
    public static void updateOwnerRespawnDelay(ServerPlayer owner, int dragonIndex, int delay) {
        CompoundTag updateData = new CompoundTag();
        updateData.putInt("delay", delay);
        updateOwnerData(owner, dragonIndex, DragonOwnerUpdatePacket.UpdateType.RESPAWN_DELAY, updateData);
    }

    // === PUBLIC SYNC METHODS ===

    /**
     * Send lightweight validation heartbeat to all tracking players
     */
    public static void sendValidationHeartbeat(TameableDragonEntity dragon) {
        UUID dragonUUID = dragon.getDragonUUID();
        if (dragonUUID == null) return;

        long now = System.currentTimeMillis();
        long lastSent = lastValidationSent.getOrDefault(dragonUUID, 0L);
        long interval = calculateValidationInterval(dragon);

        if (now - lastSent < interval) return;

        long checksum = DragonStateChecksum.calculatePublicChecksum(dragon);
        DragonPublicValidationPacket packet = new DragonPublicValidationPacket(dragonUUID, checksum, now);

        PacketDistributor.sendToPlayersTrackingEntity(dragon, packet);
        lastValidationSent.put(dragonUUID, now);

        DMR.LOGGER.debug("Sent validation heartbeat for dragon {} with checksum {}", dragonUUID, checksum);
    }

    /**
     * Handle client request for dragon state
     */
    public static void handleStateRequest(ServerPlayer player, RequestDragonStatePacket request) {
        UUID dragonUUID = request.getDragonUUID();
        if (dragonUUID == null) {
            DMR.LOGGER.warn(
                    "Received state request with invalid dragon UUID from {}",
                    player.getName().getString());
            return;
        }

        TameableDragonEntity dragon = findDragonByUUID(player.serverLevel(), dragonUUID);
        if (dragon == null) {
            DMR.LOGGER.debug(
                    "Dragon {} not found for state request from {}",
                    dragonUUID,
                    player.getName().getString());
            return;
        }

        // Check if player is close enough to receive dragon data
        if (player.distanceToSqr(dragon) > MAX_SYNC_DISTANCE_SQ) {
            DMR.LOGGER.debug(
                    "Player {} too far from dragon {} for state sync",
                    player.getName().getString(),
                    dragonUUID);
            return;
        }

        CompoundTag publicState = buildPublicState(dragon);
        PacketDistributor.sendToPlayer(player, new DragonPublicStatePacket(dragonUUID, publicState));

        logStateRequest(player, dragon, request.getReason());
    }

    // === FALLBACK METHODS ===

    /**
     * Force complete sync as emergency fallback
     */
    public static void forceCompleteSync(ServerPlayer player, String reason) {
        DMR.LOGGER.warn(
                "Forcing CompleteDataSync for {} - Reason: {}", player.getName().getString(), reason);
        PacketDistributor.sendToPlayer(player, new CompleteDataSync(player));
    }

    // === UTILITY METHODS ===

    private static long calculateValidationInterval(TameableDragonEntity dragon) {
        // Dynamic interval based on activity and owner proximity
        ServerPlayer owner = (ServerPlayer) dragon.getOwner();

        // More frequent validation if owner is nearby and dragon is active
        if (owner != null && dragon.distanceToSqr(owner) < 100) {
            return 30000; // 30 seconds when owner is close
        }

        // TODO: Add combat/activity detection when available
        // if (dragon.isInCombat()) return 20000; // 20 seconds during combat

        return BASE_VALIDATION_INTERVAL; // 45 seconds default
    }

    private static CompoundTag buildOwnerData(TameableDragonEntity dragon) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("health", dragon.getHealth());
        tag.putFloat("maxHealth", dragon.getMaxHealth());
        tag.putBoolean("isAlive", dragon.isAlive());
        tag.putString("dimension", dragon.level().dimension().location().toString());
        tag.putLong("position", dragon.blockPosition().asLong());
        tag.putBoolean("isOrderedToSit", dragon.isOrderedToSit());
        tag.putLong("lastUpdate", System.currentTimeMillis());

        // TODO: Add respawn delay data if dragon is dead
        // TODO: Add cross-dimensional tracking data

        return tag;
    }

    private static CompoundTag buildPublicState(TameableDragonEntity dragon) {
        CompoundTag tag = new CompoundTag();

        // Core vitals
        tag.putFloat("health", dragon.getHealth());
        tag.putFloat("maxHealth", dragon.getMaxHealth());
        tag.putInt("age", dragon.getAge());

        // Dragon-specific behavior
        tag.putBoolean("isOrderedToSit", dragon.isOrderedToSit());

        // Equipment state
        tag.putBoolean("isSaddled", dragon.isSaddled());
        tag.putBoolean("hasChest", dragon.hasChest());

        // Abilities
        ListTag abilitiesList = new ListTag();
        for (Ability ability : dragon.getAbilities()) {
            abilitiesList.add(StringTag.valueOf(ability.type()));
        }
        tag.put("abilities", abilitiesList);

        return tag;
    }

    private static long incrementOwnerVersion(UUID playerUUID, int dragonIndex) {
        return ownerVersions
                .computeIfAbsent(playerUUID, k -> new ConcurrentHashMap<>())
                .compute(dragonIndex, (k, v) -> (v == null) ? 1L : v + 1L);
    }

    private static TameableDragonEntity findDragonByUUID(ServerLevel level, UUID dragonUUID) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof TameableDragonEntity dragon && dragonUUID.equals(dragon.getDragonUUID())) {
                return dragon;
            }
        }
        return null;
    }

    private static void logStateRequest(
            ServerPlayer player, TameableDragonEntity dragon, RequestDragonStatePacket.RequestReason reason) {
        DMR.LOGGER.debug(
                "Handled state request from {} for dragon {} - Reason: {}",
                player.getName().getString(),
                dragon.getDragonUUID(),
                reason);
    }

    // === CLEANUP METHODS ===

    /**
     * Clean up tracking data for disconnected player
     */
    public static void cleanupPlayer(UUID playerUUID) {
        ownerVersions.remove(playerUUID);
    }

    /**
     * Clean up tracking data for removed dragon
     */
    public static void cleanupDragon(UUID dragonUUID) {
        publicVersions.remove(dragonUUID);
        lastValidationSent.remove(dragonUUID);
    }
}
