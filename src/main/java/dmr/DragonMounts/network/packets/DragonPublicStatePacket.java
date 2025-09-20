package dmr.DragonMounts.network.packets;

import dmr.DragonMounts.client.handlers.DragonClientSyncManager;
import dmr.DragonMounts.network.AbstractMessage;
import java.util.UUID;
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
 * Packet containing current dragon public state data.
 * Sent on-demand when client detects checksum mismatch.
 */
public class DragonPublicStatePacket extends AbstractMessage<DragonPublicStatePacket> {

    public static final StreamCodec<FriendlyByteBuf, DragonPublicStatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            DragonPublicStatePacket::getDragonUUIDString,
            ByteBufCodecs.COMPOUND_TAG,
            DragonPublicStatePacket::getPublicState,
            DragonPublicStatePacket::new);

    @Getter
    private final String dragonUUIDString;

    @Getter
    private final CompoundTag publicState;

    /**
     * Empty constructor for NetworkHandler.
     */
    DragonPublicStatePacket() {
        this.dragonUUIDString = "";
        this.publicState = new CompoundTag();
    }

    /**
     * Creates a new public state packet
     */
    public DragonPublicStatePacket(UUID dragonUUID, CompoundTag publicState) {
        this.dragonUUIDString = dragonUUID.toString();
        this.publicState = publicState;
    }

    public DragonPublicStatePacket(String dragonUUIDString, CompoundTag publicState) {
        this.dragonUUIDString = dragonUUIDString;
        this.publicState = publicState;
    }

    public UUID getDragonUUID() {
        try {
            return UUID.fromString(dragonUUIDString);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected String getTypeName() {
        return "dragon_public_state";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, DragonPublicStatePacket> streamCodec() {
        return STREAM_CODEC;
    }

    @Override
    public boolean autoSync() {
        return true;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext supplier, Player player) {
        DragonClientSyncManager.handlePublicStatePacket(this);
    }
}
