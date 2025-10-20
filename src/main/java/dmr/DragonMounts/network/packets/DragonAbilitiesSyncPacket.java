package dmr.DragonMounts.network.packets;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.network.AbstractMessage;
import dmr.DragonMounts.registry.datapack.DragonAbilityRegistry;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.types.abilities.Ability;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet sent to clients when they start tracking a dragon entity.
 * Syncs all of the dragon's abilities to the client.
 */
public class DragonAbilitiesSyncPacket extends AbstractMessage<DragonAbilitiesSyncPacket> {

    @Getter
    private final int dragonId;

    @Getter
    private final List<AbilityData> abilities;

    /**
     * Data structure to hold ability information for network transmission.
     */
    public record AbilityData(String abilityType, int level) {
        public static final StreamCodec<FriendlyByteBuf, AbilityData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                AbilityData::abilityType,
                ByteBufCodecs.VAR_INT,
                AbilityData::level,
                AbilityData::new);
    }

    private static final StreamCodec<FriendlyByteBuf, DragonAbilitiesSyncPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            DragonAbilitiesSyncPacket::getDragonId,
            AbilityData.STREAM_CODEC.apply(ByteBufCodecs.list()),
            DragonAbilitiesSyncPacket::getAbilities,
            DragonAbilitiesSyncPacket::new);

    /**
     * Empty constructor for NetworkHandler.
     */
    DragonAbilitiesSyncPacket() {
        this.dragonId = -1;
        this.abilities = new ArrayList<>();
    }

    /**
     * Creates a new packet with the given parameters.
     *
     * @param dragonId The entity ID of the dragon
     * @param abilities List of ability data
     */
    public DragonAbilitiesSyncPacket(int dragonId, List<AbilityData> abilities) {
        this.dragonId = dragonId;
        this.abilities = abilities;
    }

    /**
     * Creates a new packet from a dragon entity.
     *
     * @param dragon The dragon entity
     */
    public DragonAbilitiesSyncPacket(TameableDragonEntity dragon) {
        this.dragonId = dragon.getId();
        this.abilities = new ArrayList<>();

        for (Ability ability : dragon.getAbilities()) {
            this.abilities.add(new AbilityData(ability.type(), ability.getLevel()));
        }
    }

    @Override
    protected String getTypeName() {
        return "dragon_abilities_sync";
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, DragonAbilitiesSyncPacket> streamCodec() {
        return STREAM_CODEC;
    }

    @Override
    public void handle(IPayloadContext context, Player player) {}

    @Override
    public void handleClient(IPayloadContext context, Player player) {
        // Get the dragon entity on the client
        Entity entity = player.level().getEntity(dragonId);

        if (entity instanceof TameableDragonEntity dragon) {
            // Clear existing abilities and rebuild from packet data
            dragon.getAbilities().clear();

            for (AbilityData abilityData : abilities) {
                // Get ability definition from registry
                var definition = DragonAbilityRegistry.getAbilityDefinition(DMR.id(abilityData.abilityType()));
                if (definition != null) {
                    Ability ability = DragonAbilityRegistry.createAbilityInstance(definition);
                    if (ability != null) {
                        ability.onInitialize(dragon);
                        ability.setLevel(abilityData.level());
                        dragon.getAbilities().add(ability);
                    }
                }
            }
        }
    }
}
