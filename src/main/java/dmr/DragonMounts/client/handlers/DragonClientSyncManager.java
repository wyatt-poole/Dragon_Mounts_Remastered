package dmr.DragonMounts.client.handlers;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.network.packets.DragonPublicStatePacket;
import dmr.DragonMounts.network.packets.DragonPublicValidationPacket;
import dmr.DragonMounts.network.packets.RequestDragonStatePacket;
import dmr.DragonMounts.util.DragonStateChecksum;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side manager for dragon state validation and sync requests.
 * Handles checksum validation and automatic state requests when desyncs are detected.
 */
@OnlyIn(Dist.CLIENT)
public class DragonClientSyncManager {

    private static final Map<UUID, DragonStateChecksum.DragonClientData> dragonCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastValidationReceived = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastStateRequest = new ConcurrentHashMap<>();

    private static final long VALIDATION_TIMEOUT = 90000; // 90 seconds - double max validation interval
    private static final long REQUEST_COOLDOWN = 5000; // 5 seconds between state requests

    /**
     * Handle validation packet from server
     */
    public static void handleValidationPacket(DragonPublicValidationPacket packet) {
        UUID dragonUUID = packet.getDragonUUID();
        if (dragonUUID == null) {
            DMR.LOGGER.warn("Received validation packet with invalid dragon UUID");
            return;
        }

        DragonStateChecksum.DragonClientData localData = dragonCache.get(dragonUUID);

        if (localData == null) {
            // No local data - request full state
            requestDragonState(dragonUUID, 0L, RequestDragonStatePacket.RequestReason.NO_LOCAL_DATA);
            DMR.LOGGER.debug("No local data for dragon {}, requesting state", dragonUUID);
            return;
        }

        long localChecksum = DragonStateChecksum.calculateClientChecksum(localData);
        if (localChecksum != packet.getStateChecksum()) {
            // Checksum mismatch - request updated state
            requestDragonState(dragonUUID, localChecksum, RequestDragonStatePacket.RequestReason.CHECKSUM_MISMATCH);
            DMR.LOGGER.debug(
                    "Checksum mismatch for dragon {} (local: {}, server: {}), requesting state",
                    dragonUUID,
                    localChecksum,
                    packet.getStateChecksum());
        } else {
            DMR.LOGGER.debug("Validation passed for dragon {} with checksum {}", dragonUUID, localChecksum);
        }

        lastValidationReceived.put(dragonUUID, packet.getServerTimestamp());
    }

    /**
     * Handle public state packet from server
     */
    public static void handlePublicStatePacket(DragonPublicStatePacket packet) {
        UUID dragonUUID = packet.getDragonUUID();
        if (dragonUUID == null) {
            DMR.LOGGER.warn("Received state packet with invalid dragon UUID");
            return;
        }

        DragonStateChecksum.DragonClientData clientData =
                DragonStateChecksum.DragonClientData.fromNBT(packet.getPublicState());
        dragonCache.put(dragonUUID, clientData);

        DMR.LOGGER.debug(
                "Updated local cache for dragon {} with {} abilities", dragonUUID, clientData.abilities.size());

        // Update local dragon entity if it exists
        updateLocalDragonEntity(dragonUUID, clientData);
    }

    /**
     * Request dragon state from server with cooldown protection
     */
    private static void requestDragonState(
            UUID dragonUUID, long expectedChecksum, RequestDragonStatePacket.RequestReason reason) {
        long now = System.currentTimeMillis();
        long lastRequest = lastStateRequest.getOrDefault(dragonUUID, 0L);

        if (now - lastRequest < REQUEST_COOLDOWN) {
            DMR.LOGGER.debug("Request cooldown active for dragon {}, skipping", dragonUUID);
            return;
        }

        PacketDistributor.sendToServer(new RequestDragonStatePacket(dragonUUID, expectedChecksum, reason));
        lastStateRequest.put(dragonUUID, now);

        DMR.LOGGER.debug("Requested state for dragon {} - Reason: {}", dragonUUID, reason);
    }

    /**
     * Update local dragon entity with new state data
     */
    private static void updateLocalDragonEntity(UUID dragonUUID, DragonStateChecksum.DragonClientData clientData) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        // Find the local dragon entity and update it
        minecraft.level.getAllEntities().forEach(entity -> {
            if (entity instanceof dmr.DragonMounts.server.entity.TameableDragonEntity dragon
                    && dragonUUID.equals(dragon.getDragonUUID())) {

                // Update client-side dragon state for visual consistency
                // Note: Some updates might need special handling for client-side only data
                updateDragonVisualState(dragon, clientData);
            }
        });
    }

    /**
     * Update dragon visual state for client-side consistency
     */
    private static void updateDragonVisualState(
            dmr.DragonMounts.server.entity.TameableDragonEntity dragon,
            DragonStateChecksum.DragonClientData clientData) {
        // Update visual state that might affect rendering
        // Be careful not to interfere with vanilla entity sync

        // Most updates should already be handled by vanilla entity sync
        // This is mainly for mod-specific visual elements that depend on abilities or equipment

        DMR.LOGGER.debug("Updated visual state for dragon {}", dragon.getDragonUUID());
    }

    /**
     * Check for validation timeouts and request updates
     * Should be called periodically (e.g., from client tick event)
     */
    public static void checkValidationTimeouts() {
        long now = System.currentTimeMillis();

        lastValidationReceived.entrySet().removeIf(entry -> {
            UUID dragonUUID = entry.getKey();
            long lastReceived = entry.getValue();

            if (now - lastReceived > VALIDATION_TIMEOUT) {
                requestDragonState(dragonUUID, 0L, RequestDragonStatePacket.RequestReason.VALIDATION_TIMEOUT);
                DMR.LOGGER.debug("Validation timeout for dragon {}, requesting state", dragonUUID);
                return true; // Remove from map
            }
            return false;
        });
    }

    /**
     * Clean up data for a specific dragon
     */
    public static void cleanupDragon(UUID dragonUUID) {
        dragonCache.remove(dragonUUID);
        lastValidationReceived.remove(dragonUUID);
        lastStateRequest.remove(dragonUUID);
    }

    /**
     * Clear all cached data (e.g., on world disconnect)
     */
    public static void clearAll() {
        dragonCache.clear();
        lastValidationReceived.clear();
        lastStateRequest.clear();
    }

    /**
     * Get cached dragon data for external use (e.g., UI display)
     */
    public static DragonStateChecksum.DragonClientData getCachedDragonData(UUID dragonUUID) {
        return dragonCache.get(dragonUUID);
    }
}
