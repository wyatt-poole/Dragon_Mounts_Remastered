package dmr.DragonMounts.network.packets;

import dmr.DragonMounts.client.handlers.DragonClientSyncManager;
import dmr.DragonMounts.network.AbstractMessage;
import java.util.UUID;
import lombok.Getter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Lightweight packet sent to all tracking players to validate their cached dragon state.
 * Contains only a checksum - if client detects mismatch, they request full state update.
 */
public class DragonPublicValidationPacket extends AbstractMessage<DragonPublicValidationPacket> {

    public static final StreamCodec<FriendlyByteBuf, DragonPublicValidationPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            DragonPublicValidationPacket::getDragonUUIDString,
            ByteBufCodecs.VAR_LONG,
            DragonPublicValidationPacket::getStateChecksum,
            ByteBufCodecs.VAR_LONG,
            DragonPublicValidationPacket::getServerTimestamp,
            ByteBufCodecs.BYTE,
            DragonPublicValidationPacket::getValidationVersion,
            DragonPublicValidationPacket::new);

    @Getter
    private final String dragonUUIDString;

    @Getter
    private final long stateChecksum;

    @Getter
    private final long serverTimestamp;

    @Getter
    private final byte validationVersion;

    private static final byte VALIDATION_PROTOCOL_VERSION = 1;

    /**
     * Empty constructor for NetworkHandler.
     */
    DragonPublicValidationPacket() {
        this.dragonUUIDString = "";
        this.stateChecksum = 0L;
        this.serverTimestamp = 0L;
        this.validationVersion = VALIDATION_PROTOCOL_VERSION;
    }

    /**
     * Creates a new validation packet
     */
    public DragonPublicValidationPacket(UUID dragonUUID, long stateChecksum, long serverTimestamp) {
        this.dragonUUIDString = dragonUUID.toString();
        this.stateChecksum = stateChecksum;
        this.serverTimestamp = serverTimestamp;
        this.validationVersion = VALIDATION_PROTOCOL_VERSION;
    }

    public DragonPublicValidationPacket(
            String dragonUUIDString, long stateChecksum, long serverTimestamp, byte validationVersion) {
        this.dragonUUIDString = dragonUUIDString;
        this.stateChecksum = stateChecksum;
        this.serverTimestamp = serverTimestamp;
        this.validationVersion = validationVersion;
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
        return "dragon_public_validation";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, DragonPublicValidationPacket> streamCodec() {
        return STREAM_CODEC;
    }

    @Override
    public boolean autoSync() {
        return true;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext supplier, Player player) {
        DragonClientSyncManager.handleValidationPacket(this);
    }
}
