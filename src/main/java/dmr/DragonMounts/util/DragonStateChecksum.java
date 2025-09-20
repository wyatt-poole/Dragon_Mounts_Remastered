package dmr.DragonMounts.util;

import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.types.abilities.Ability;
import java.util.zip.CRC32;

/**
 * Utility class for calculating dragon state checksums for validation.
 * Only includes mod-specific data that isn't synced by vanilla Minecraft.
 */
public class DragonStateChecksum {

    /**
     * Calculate checksum for public dragon state visible to all players.
     * Excludes position data (handled by vanilla entity sync).
     */
    public static long calculatePublicChecksum(TameableDragonEntity dragon) {
        CRC32 crc = new CRC32();

        // Core vitals (mod-managed)
        crc.update(Float.floatToIntBits(dragon.getHealth()));
        crc.update(Float.floatToIntBits(dragon.getMaxHealth()));
        crc.update(dragon.getAge());

        // Dragon-specific state (not synced by vanilla)
        crc.update(dragon.isOrderedToSit() ? 1 : 0);

        // Equipment state (mod-specific)
        crc.update(dragon.isSaddled() ? 1 : 0);
        crc.update(dragon.hasChest() ? 1 : 0);

        // Abilities (mod-specific data)
        for (Ability ability : dragon.getAbilities()) {
            crc.update(ability.type().hashCode());
        }

        return crc.getValue();
    }

    /**
     * Calculate checksum from client-side dragon data for validation.
     * Must produce identical checksum for identical state.
     */
    public static long calculateClientChecksum(DragonClientData clientData) {
        CRC32 crc = new CRC32();

        // Mirror server calculation using client-side data
        crc.update(Float.floatToIntBits(clientData.health));
        crc.update(Float.floatToIntBits(clientData.maxHealth));
        crc.update(clientData.age);
        crc.update(clientData.isOrderedToSit ? 1 : 0);
        crc.update(clientData.isSaddled ? 1 : 0);
        crc.update(clientData.hasChest ? 1 : 0);

        // Abilities must be sorted for consistent checksum
        clientData.abilities.stream().sorted().forEach(ability -> crc.update(ability.hashCode()));

        return crc.getValue();
    }

    /**
     * Client-side dragon data structure for checksum validation
     */
    public static class DragonClientData {
        public float health;
        public float maxHealth;
        public int age;
        public boolean isOrderedToSit;
        public boolean isSaddled;
        public boolean hasChest;
        public java.util.List<String> abilities = new java.util.ArrayList<>();

        /**
         * Create client data from received public state packet
         */
        public static DragonClientData fromNBT(net.minecraft.nbt.CompoundTag tag) {
            DragonClientData data = new DragonClientData();

            data.health = tag.getFloat("health");
            data.maxHealth = tag.getFloat("maxHealth");
            data.age = tag.getInt("age");
            data.isOrderedToSit = tag.getBoolean("isOrderedToSit");
            data.isSaddled = tag.getBoolean("isSaddled");
            data.hasChest = tag.getBoolean("hasChest");

            if (tag.contains("abilities")) {
                var abilitiesList = tag.getList("abilities", 8); // String tag type
                for (int i = 0; i < abilitiesList.size(); i++) {
                    data.abilities.add(abilitiesList.getString(i));
                }
            }

            return data;
        }

        /**
         * Update this data from a dragon entity (client-side)
         */
        public void updateFromEntity(TameableDragonEntity dragon) {
            this.health = dragon.getHealth();
            this.maxHealth = dragon.getMaxHealth();
            this.age = dragon.getAge();
            this.isOrderedToSit = dragon.isOrderedToSit();
            this.isSaddled = dragon.isSaddled();
            this.hasChest = dragon.hasChest();

            this.abilities.clear();
            for (Ability ability : dragon.getAbilities()) {
                this.abilities.add(ability.type());
            }
        }
    }
}
