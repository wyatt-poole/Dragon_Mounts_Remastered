package dmr.DragonMounts.network.packets;

import dmr.DragonMounts.network.AbstractMessage;
import dmr.DragonMounts.util.PlayerStateUtils;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Targeted packet for specific owner-only data updates.
 * More efficient than CompleteDataSync for single dragon changes.
 */
public class DragonOwnerUpdatePacket extends AbstractMessage<DragonOwnerUpdatePacket> {

    public static final StreamCodec<FriendlyByteBuf, DragonOwnerUpdatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            DragonOwnerUpdatePacket::getDragonIndex,
            ByteBufCodecs.BYTE,
            DragonOwnerUpdatePacket::getUpdateTypeOrdinal,
            ByteBufCodecs.COMPOUND_TAG,
            DragonOwnerUpdatePacket::getUpdateData,
            ByteBufCodecs.VAR_LONG,
            DragonOwnerUpdatePacket::getVersion,
            DragonOwnerUpdatePacket::new);

    @Getter
    private final int dragonIndex;

    @Getter
    private final byte updateTypeOrdinal;

    @Getter
    private final CompoundTag updateData;

    @Getter
    private final long version;

    public enum UpdateType {
        NBT_DATA, // Dragon NBT storage
        RESPAWN_DELAY, // Death/respawn timer
        INSTANCE_DATA, // Dragon instance reference
        LINK_OPERATION, // Complete link/unlink
        CROSS_DIMENSIONAL // Cross-dimension tracking
    }

    /**
     * Empty constructor for NetworkHandler.
     */
    DragonOwnerUpdatePacket() {
        this.dragonIndex = -1;
        this.updateTypeOrdinal = 0;
        this.updateData = new CompoundTag();
        this.version = 0L;
    }

    /**
     * Creates a new owner update packet
     */
    public DragonOwnerUpdatePacket(int dragonIndex, UpdateType updateType, CompoundTag updateData, long version) {
        this.dragonIndex = dragonIndex;
        this.updateTypeOrdinal = (byte) updateType.ordinal();
        this.updateData = updateData;
        this.version = version;
    }

    public DragonOwnerUpdatePacket(int dragonIndex, byte updateTypeOrdinal, CompoundTag updateData, long version) {
        this.dragonIndex = dragonIndex;
        this.updateTypeOrdinal = updateTypeOrdinal;
        this.updateData = updateData;
        this.version = version;
    }

    public UpdateType getUpdateType() {
        try {
            return UpdateType.values()[updateTypeOrdinal];
        } catch (ArrayIndexOutOfBoundsException e) {
            return UpdateType.NBT_DATA;
        }
    }

    @Override
    protected String getTypeName() {
        return "dragon_owner_update";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, DragonOwnerUpdatePacket> streamCodec() {
        return STREAM_CODEC;
    }

    @Override
    public boolean autoSync() {
        return true;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext supplier, Player player) {
        var handler = PlayerStateUtils.getHandler(player);

        // Apply the targeted update based on type
        switch (getUpdateType()) {
            case NBT_DATA -> {
                if (updateData.contains("nbt")) {
                    handler.dragonNBTs.put(dragonIndex, updateData.getCompound("nbt"));
                }
            }
            case RESPAWN_DELAY -> {
                if (updateData.contains("delay")) {
                    int delay = updateData.getInt("delay");
                    if (delay > 0) {
                        handler.respawnDelays.put(dragonIndex, delay);
                    } else {
                        handler.respawnDelays.remove(dragonIndex);
                    }
                }
            }
            case INSTANCE_DATA -> {
                // Handle dragon instance updates
                if (updateData.contains("remove") && updateData.getBoolean("remove")) {
                    handler.dragonInstances.remove(dragonIndex);
                } else {
                    // Update or create instance data - would need DragonInstance deserialization
                }
            }
            case LINK_OPERATION -> {
                // Handle complete link/unlink operations
                if (updateData.contains("unlink") && updateData.getBoolean("unlink")) {
                    handler.dragonInstances.remove(dragonIndex);
                    handler.dragonNBTs.remove(dragonIndex);
                    handler.respawnDelays.remove(dragonIndex);
                } else if (updateData.contains("cleanup") && updateData.getBoolean("cleanup")) {
                    // Handle data corruption cleanup
                    handler.dragonInstances.remove(dragonIndex);
                    handler.dragonNBTs.remove(dragonIndex);
                    handler.respawnDelays.remove(dragonIndex);
                } else {
                    // Handle dragon linking - update NBT data
                    if (updateData.contains("nbt")) {
                        handler.dragonNBTs.put(dragonIndex, updateData.getCompound("nbt"));
                    }
                    // Additional link data like dragonName, dragonUUID can be used for validation
                }
            }
        }
    }
}
