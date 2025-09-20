package dmr.DragonMounts.network.packets;

import dmr.DragonMounts.common.handlers.DragonSyncManager;
import dmr.DragonMounts.network.AbstractMessage;
import java.util.UUID;
import lombok.Getter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-to-server packet requesting current dragon state.
 * Sent when client detects checksum mismatch or timeout.
 */
public class RequestDragonStatePacket extends AbstractMessage<RequestDragonStatePacket> {

    public static final StreamCodec<FriendlyByteBuf, RequestDragonStatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            RequestDragonStatePacket::getDragonUUIDString,
            ByteBufCodecs.VAR_LONG,
            RequestDragonStatePacket::getExpectedChecksum,
            ByteBufCodecs.BYTE,
            RequestDragonStatePacket::getReasonOrdinal,
            RequestDragonStatePacket::new);

    @Getter
    private final String dragonUUIDString;

    @Getter
    private final long expectedChecksum;

    @Getter
    private final byte reasonOrdinal;

    public enum RequestReason {
        CHECKSUM_MISMATCH,
        VALIDATION_TIMEOUT,
        NO_LOCAL_DATA,
        MANUAL_REQUEST
    }

    /**
     * Empty constructor for NetworkHandler.
     */
    RequestDragonStatePacket() {
        this.dragonUUIDString = "";
        this.expectedChecksum = 0L;
        this.reasonOrdinal = 0;
    }

    /**
     * Creates a new state request packet
     */
    public RequestDragonStatePacket(UUID dragonUUID, long expectedChecksum, RequestReason reason) {
        this.dragonUUIDString = dragonUUID.toString();
        this.expectedChecksum = expectedChecksum;
        this.reasonOrdinal = (byte) reason.ordinal();
    }

    public RequestDragonStatePacket(String dragonUUIDString, long expectedChecksum, byte reasonOrdinal) {
        this.dragonUUIDString = dragonUUIDString;
        this.expectedChecksum = expectedChecksum;
        this.reasonOrdinal = reasonOrdinal;
    }

    public UUID getDragonUUID() {
        try {
            return UUID.fromString(dragonUUIDString);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public RequestReason getReason() {
        try {
            return RequestReason.values()[reasonOrdinal];
        } catch (ArrayIndexOutOfBoundsException e) {
            return RequestReason.MANUAL_REQUEST;
        }
    }

    @Override
    protected String getTypeName() {
        return "request_dragon_state";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, RequestDragonStatePacket> streamCodec() {
        return STREAM_CODEC;
    }

    @Override
    public boolean autoSync() {
        return false; // Client-to-server packet
    }

    @Override
    public void handle(IPayloadContext supplier, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            DragonSyncManager.handleStateRequest(serverPlayer, this);
        }
    }
}
