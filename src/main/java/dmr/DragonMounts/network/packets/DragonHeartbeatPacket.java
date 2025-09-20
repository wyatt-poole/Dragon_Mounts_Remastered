package dmr.DragonMounts.network.packets;

import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.network.AbstractMessage;
import dmr.DragonMounts.util.PlayerStateUtils;
import lombok.Getter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet to sync dragon heartbeat data from server to client
 * Contains current dragon status for whistle system
 */
public class DragonHeartbeatPacket extends AbstractMessage<DragonHeartbeatPacket> {
    public static final StreamCodec<FriendlyByteBuf, DragonHeartbeatPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT,
            DragonHeartbeatPacket::getDragonIndex,
            ByteBufCodecs.COMPOUND_TAG,
            DragonHeartbeatPacket::getHeartbeatData,
            DragonHeartbeatPacket::new);

    @Getter
    private final int dragonIndex;

    @Getter
    private final CompoundTag heartbeatData;

    /**
     * Empty constructor for NetworkHandler.
     */
    DragonHeartbeatPacket() {
        this.dragonIndex = -1;
        this.heartbeatData = new CompoundTag();
    }

    /**
     * Creates a new heartbeat packet with dragon status data
     */
    public DragonHeartbeatPacket(int dragonIndex, CompoundTag heartbeatData) {
        this.dragonIndex = dragonIndex;
        this.heartbeatData = heartbeatData;
    }

    /**
     * Creates a new heartbeat packet with dragon status data
     */
    public DragonHeartbeatPacket(
            int dragonIndex,
            String dimension,
            BlockPos position,
            boolean isAlive,
            float health,
            float maxHealth,
            boolean isOrderedToSit,
            long lastUpdate) {
        this.dragonIndex = dragonIndex;
        this.heartbeatData = new CompoundTag();

        // Pack all dragon status data into the CompoundTag
        heartbeatData.putString("dimension", dimension);
        heartbeatData.putLong("position", position.asLong());
        heartbeatData.putBoolean("isAlive", isAlive);
        heartbeatData.putFloat("health", health);
        heartbeatData.putFloat("maxHealth", maxHealth);
        heartbeatData.putBoolean("isOrderedToSit", isOrderedToSit);
        heartbeatData.putLong("lastUpdate", lastUpdate);
    }

    @Override
    protected String getTypeName() {
        return "dragon_heartbeat";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, DragonHeartbeatPacket> streamCodec() {
        return STREAM_CODEC;
    }

    @Override
    public boolean autoSync() {
        return true;
    }

    public void handle(IPayloadContext supplier, Player player) {
        // Update client-side dragon cache for whistle system
        var handler = PlayerStateUtils.getHandler(player);
        var instance = handler.dragonInstances.get(dragonIndex);

        if (instance != null && instance.getUUID() != null) {
            // Extract heartbeat data from CompoundTag
            String dimension = heartbeatData.getString("dimension");
            BlockPos position = BlockPos.of(heartbeatData.getLong("position"));
            boolean isAlive = heartbeatData.getBoolean("isAlive");
            float health = heartbeatData.getFloat("health");
            boolean isOrderedToSit = heartbeatData.getBoolean("isOrderedToSit");

            // Cache the heartbeat data for fast access by whistle system
            DragonWhistleHandler.cacheHeartbeatData(
                    instance.getUUID(), position, dimension, isAlive, health, isOrderedToSit);

            // This cached data can now be used by client-side whistle system
            // for real-time dragon status without needing to search across dimensions
        }
    }
}
