package dmr.DragonMounts.common.handlers;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.ModConstants;
import dmr.DragonMounts.common.capability.DragonOwnerCapability;
import dmr.DragonMounts.common.capability.types.NBTInterface;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.network.packets.CompleteDataSync;
import dmr.DragonMounts.network.packets.DragonStatePacket;
import dmr.DragonMounts.registry.ModSounds;
import dmr.DragonMounts.registry.entity.ModCapabilities;
import dmr.DragonMounts.registry.item.ModItems;
import dmr.DragonMounts.server.entity.DragonConstants;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.items.DragonWhistleItem;
import dmr.DragonMounts.server.worlddata.DragonWorldDataManager;
import dmr.DragonMounts.util.PlayerStateUtils;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.network.PacketDistributor;

public class DragonWhistleHandler {

    private static final java.util.Map<UUID, DragonCacheEntry> DRAGON_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_EXPIRY_MS = 5000;

    public static final TicketController DRAGON_TRANSFER_CONTROLLER =
            new TicketController(ResourceLocation.fromNamespaceAndPath(DMR.MOD_ID, "dragon_transfer"));

    /**
     * Registers the TicketController for dragon transfers
     */
    public static void registerTicketController(
            net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent event) {
        event.register(DRAGON_TRANSFER_CONTROLLER);
        DMR.LOGGER.debug("Registered dragon transfer ticket controller: {}", DRAGON_TRANSFER_CONTROLLER.id());
    }

    /**
     * Cache entry for dragon state tracking
     */
    public static class DragonCacheEntry {
        public final BlockPos lastPosition;
        public final String dimension;
        public final boolean isAlive;
        public final float health;
        public final long cacheTime;
        public final boolean isOrderedToSit;

        public DragonCacheEntry(TameableDragonEntity dragon) {
            this.lastPosition = dragon.blockPosition();
            this.dimension = dragon.level.dimension().location().toString();
            this.isAlive = dragon.isAlive();
            this.health = dragon.getHealth();
            this.isOrderedToSit = dragon.isOrderedToSit();
            this.cacheTime = System.currentTimeMillis();
        }

        /**
         * Constructor for creating cache entries from heartbeat packet data
         */
        public DragonCacheEntry(
                BlockPos position, String dimension, boolean isAlive, float health, boolean isOrderedToSit) {
            this.lastPosition = position;
            this.dimension = dimension;
            this.isAlive = isAlive;
            this.health = health;
            this.isOrderedToSit = isOrderedToSit;
            this.cacheTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - cacheTime > CACHE_EXPIRY_MS;
        }

        public boolean isInDimension(String targetDimension) {
            return dimension.equals(targetDimension);
        }
    }

    /**
     * Result enum for dragon whistle operations with user-friendly messaging
     */
    public enum WhistleCallResult {
        SUCCESS(""),
        NO_DRAGON_BOUND("dmr.dragon_call.no_whistle"),
        DRAGON_ON_COOLDOWN("dmr.dragon_call.respawn"),
        INSUFFICIENT_SPACE("dmr.dragon_call.nospace"),
        ALREADY_RIDING("dmr.dragon_call.riding"),
        WHISTLE_ON_COOLDOWN("dmr.dragon_call.on_cooldown"),
        DRAGON_TRAVELING("dmr.dragon_call.traveling");

        private final String translationKey;

        WhistleCallResult(String translationKey) {
            this.translationKey = translationKey;
        }

        public void displayToPlayer(Player player, Object... args) {
            if (!translationKey.isEmpty()) {
                ChatFormatting color = this == DRAGON_TRAVELING ? ChatFormatting.YELLOW : ChatFormatting.RED;
                Component message = args.length > 0
                        ? Component.translatable(translationKey, args).withStyle(color)
                        : Component.translatable(translationKey).withStyle(color);
                player.displayClientMessage(message, true);
            }
        }
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DragonInstance implements NBTInterface {

        private static final long STALE_THRESHOLD_MS = 30000; // 30 seconds

        String dimension;
        UUID entityId;
        UUID UUID;

        // Enhanced position tracking for cross-dimensional operations
        BlockPos lastKnownPosition;
        long lastUpdateTime;
        boolean isOrderedToSit;
        float lastKnownHealth;

        public DragonInstance(Level level, UUID entityId, UUID dragonUUID) {
            this.dimension = level.dimension().location().toString();
            this.entityId = entityId;
            this.UUID = dragonUUID;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        /**
         * Constructor for legacy compatibility - accepts String dimension
         */
        public DragonInstance(String dimension, UUID entityId, UUID dragonUUID) {
            this.dimension = dimension;
            this.entityId = entityId;
            this.UUID = dragonUUID;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public DragonInstance(TameableDragonEntity dragon) {
            this.dimension = dragon.level.dimension().location().toString();
            this.entityId = dragon.getUUID();
            this.UUID = dragon.getDragonUUID();
            this.lastKnownPosition = dragon.blockPosition();
            this.lastUpdateTime = System.currentTimeMillis();
            this.isOrderedToSit = dragon.isOrderedToSit();
            this.lastKnownHealth = dragon.getHealth();
        }

        /**
         * Updates the dragon instance with current dragon data
         */
        public void updateFromDragon(TameableDragonEntity dragon) {
            this.dimension = dragon.level.dimension().location().toString();
            this.entityId = dragon.getUUID();
            this.lastKnownPosition = dragon.blockPosition();
            this.lastUpdateTime = System.currentTimeMillis();
            this.isOrderedToSit = dragon.isOrderedToSit();
            this.lastKnownHealth = dragon.getHealth();
        }

        /**
         * Checks if this dragon instance requires chunk loading to be accessed
         */
        public boolean requiresChunkLoading() {
            return lastKnownPosition != null;
        }

        /**
         * Gets the chunk position for chunk loading operations
         */
        public ChunkPos getChunkPos() {
            return lastKnownPosition != null ? new ChunkPos(lastKnownPosition) : null;
        }

        /**
         * Checks if the dragon data is stale and needs refresh
         */
        public boolean isStale() {
            return System.currentTimeMillis() - lastUpdateTime > STALE_THRESHOLD_MS;
        }

        @Override
        public CompoundTag writeNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension);
            tag.putUUID("entityId", entityId);
            tag.putUUID("uuid", UUID);
            tag.putLong("lastUpdateTime", lastUpdateTime);
            tag.putBoolean("isOrderedToSit", isOrderedToSit);
            tag.putFloat("lastKnownHealth", lastKnownHealth);

            if (lastKnownPosition != null) {
                tag.putLong("lastKnownPosition", lastKnownPosition.asLong());
            }

            return tag;
        }

        @Override
        public void readNBT(CompoundTag base) {
            if (base.contains("dimension")) {
                dimension = base.getString("dimension");
            }
            if (base.contains("entityId")) {
                entityId = base.getUUID("entityId");
            }
            if (base.contains("uuid")) {
                UUID = base.getUUID("uuid");
            }
            if (base.contains("lastUpdateTime")) {
                lastUpdateTime = base.getLong("lastUpdateTime");
            } else {
                lastUpdateTime = System.currentTimeMillis(); // Default for legacy data
            }
            if (base.contains("isOrderedToSit")) {
                isOrderedToSit = base.getBoolean("isOrderedToSit");
            }
            if (base.contains("lastKnownHealth")) {
                lastKnownHealth = base.getFloat("lastKnownHealth");
            }
            if (base.contains("lastKnownPosition")) {
                lastKnownPosition = BlockPos.of(base.getLong("lastKnownPosition"));
            }
        }
    }

    public static DragonWhistleItem getDragonWhistleItem(Player player) {
        return getDragonWhistleItem(player, false);
    }

    /**
     * Enhanced whistle selection with predictable prioritization
     *
     * Uses stable criteria for consistent selection:
     * 1. Inventory position (main hand > off hand > hotbar order > inventory order)
     * 2. Dragon death status (dead dragons are filtered out)
     * 3. Dimension match (small preference for same dimension)
     */
    public static DragonWhistleItem getDragonWhistleItem(Player player, boolean smartSelection) {
        var state = PlayerStateUtils.getHandler(player);
        Function<DragonWhistleItem, Boolean> isValid = (DragonWhistleItem whistleItem) -> {
            if (whistleItem.getColor() == null) {
                return false;
            }
            return state.dragonNBTs.containsKey(whistleItem.getColor().getId());
        };

        java.util.List<DragonWhistleItem> availableWhistles = new java.util.ArrayList<>();

        // Collect all available whistles with priority scoring
        java.util.Map<DragonWhistleItem, Integer> whistlePriorities = new java.util.HashMap<>();

        // Priority 1: Check main hand for a valid dragon whistle
        if (player.getInventory().getSelected().getItem() instanceof DragonWhistleItem whistleItem) {
            if (isValid.apply(whistleItem)) {
                if (!smartSelection) return whistleItem; // Legacy behavior
                availableWhistles.add(whistleItem);
                whistlePriorities.put(whistleItem, 100); // Highest priority
            }
        }

        // Priority 2: Check off hand for a valid dragon whistle
        if (player.getInventory().offhand.getFirst().getItem() instanceof DragonWhistleItem whistleItem) {
            if (isValid.apply(whistleItem)) {
                if (!smartSelection) return whistleItem; // Legacy behavior
                availableWhistles.add(whistleItem);
                whistlePriorities.put(whistleItem, 90);
            }
        }

        // Priority 3: Check hotbar slots for a valid dragon whistle
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof DragonWhistleItem whistleItem) {
                if (isValid.apply(whistleItem)) {
                    if (!smartSelection) return whistleItem; // Legacy behavior
                    availableWhistles.add(whistleItem);
                    whistlePriorities.put(whistleItem, 80 - i); // Closer to hand = higher priority
                }
            }
        }

        // Priority 4: Check remaining inventory slots for a valid dragon whistle
        for (int i = 9; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).getItem() instanceof DragonWhistleItem whistleItem) {
                if (isValid.apply(whistleItem)) {
                    if (!smartSelection) return whistleItem; // Legacy behavior
                    availableWhistles.add(whistleItem);
                    whistlePriorities.put(whistleItem, 50 - (i - 9)); // Lower priority for inventory
                }
            }
        }

        if (!smartSelection) {
            return null; // Legacy behavior - return null if nothing found
        }

        // Smart selection: Choose best whistle based on context
        return selectOptimalWhistle(player, availableWhistles, whistlePriorities);
    }

    /**
     * Selects optimal whistle based on stable criteria
     */
    private static DragonWhistleItem selectOptimalWhistle(
            Player player,
            java.util.List<DragonWhistleItem> availableWhistles,
            java.util.Map<DragonWhistleItem, Integer> basePriorities) {

        if (availableWhistles.isEmpty()) {
            return null;
        }

        if (availableWhistles.size() == 1) {
            return availableWhistles.get(0);
        }

        var handler = PlayerStateUtils.getHandler(player);
        DragonWhistleItem bestWhistle = null;
        int bestScore = -1;

        for (var whistle : availableWhistles) {
            int index = whistle.getColor().getId();
            int score = basePriorities.getOrDefault(whistle, 0);

            // Filter out dead dragons
            var instance = handler.dragonInstances.get(index);
            if (handler.respawnDelays.getOrDefault(index, 0) > 0) {
                continue; // Skip dead dragons
            }

            // Check cache for fast availability check
            if (instance != null) {
                var cacheEntry = getCachedDragonState(instance.getUUID());
                if (cacheEntry != null && !cacheEntry.isAlive) {
                    continue; // Skip cached dead dragons
                }

                // Prefer dragons in same dimension
                if (instance.getDimension()
                        .equals(player.level.dimension().location().toString())) {
                    score += 10;
                    // Extra bonus for cached dragons in same dimension
                    if (cacheEntry != null
                            && cacheEntry.isInDimension(
                                    player.level.dimension().location().toString())) {
                        score += 2;
                    }
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestWhistle = whistle;
            }
        }

        return bestWhistle;
    }

    public static int getDragonSummonIndex(Player player) {
        var whistleItem = getDragonWhistleItem(player);
        return whistleItem != null ? whistleItem.getColor().getId() : -1;
    }

    /**
     * Smart dragon summoning - automatically selects the best available dragon
     */
    public static int getSmartDragonSummonIndex(Player player) {
        var whistleItem = getDragonWhistleItem(player, true); // Enable smart selection
        return whistleItem != null ? whistleItem.getColor().getId() : -1;
    }

    public static int getDragonSummonIndex(Player player, UUID dragonUUID) {
        var handler = PlayerStateUtils.getHandler(player);

        return handler.dragonInstances.entrySet().stream()
                .filter(entry ->
                        entry.getValue() != null && entry.getValue().UUID.equals(dragonUUID))
                .map(Entry::getKey)
                .findFirst()
                .orElse(0);
    }

    public static void setDragon(Player player, TameableDragonEntity dragon, int index) {
        player.getData(ModCapabilities.PLAYER_CAPABILITY).setPlayerInstance(player);
        player.getData(ModCapabilities.PLAYER_CAPABILITY).setDragonToWhistle(dragon, index);
    }

    /**
     * Validates if player can call dragon at given index
     */
    public static WhistleCallResult canCall(Player player, int index) {
        var handler = PlayerStateUtils.getHandler(player);

        // Check for valid whistle
        if (index == -1) {
            return WhistleCallResult.NO_DRAGON_BOUND;
        }

        // Clean up corrupted data
        if (!player.level.isClientSide) {
            if ((handler.dragonNBTs.containsKey(index) && handler.dragonNBTs.get(index) == null)
                    || (handler.dragonInstances.containsKey(index) && handler.dragonInstances.get(index) == null)
                    || (handler.dragonInstances.containsKey(index) != handler.dragonNBTs.containsKey(index))) {

                handler.dragonNBTs.remove(index);
                handler.dragonInstances.remove(index);
                handler.respawnDelays.remove(index);

                PacketDistributor.sendToPlayer((ServerPlayer) player, new CompleteDataSync(player));

                DMR.LOGGER.debug(
                        "Cleaned up corrupted dragon data for player {} index {}",
                        player.getName().getString(),
                        index);
                return WhistleCallResult.NO_DRAGON_BOUND;
            }
        }

        // Check for dragon existence
        if (!handler.dragonInstances.containsKey(index) || handler.dragonInstances.get(index) == null) {
            return WhistleCallResult.NO_DRAGON_BOUND;
        }

        // Check respawn cooldown
        if (handler.respawnDelays.getOrDefault(index, 0) > 0) {
            return WhistleCallResult.DRAGON_ON_COOLDOWN;
        }

        // Check if already riding something
        if (player.getVehicle() != null) {
            return WhistleCallResult.ALREADY_RIDING;
        }

        // Check space availability
        if (ServerConfig.CALL_CHECK_SPACE && !GameTestHooks.isGametestEnabled()) {
            if (!player.level.noBlockCollision(
                    null, player.getBoundingBox().move(0, 1, 0).inflate(1, 1, 1))) {
                return WhistleCallResult.INSUFFICIENT_SPACE;
            }
        }

        // Check whistle cooldown
        if (handler.lastCall != null && ServerConfig.WHISTLE_COOLDOWN_CONFIG > 0) {
            if (handler.lastCall + ServerConfig.WHISTLE_COOLDOWN_CONFIG > System.currentTimeMillis()) {
                return WhistleCallResult.WHISTLE_ON_COOLDOWN;
            }
        }

        return WhistleCallResult.SUCCESS;
    }

    public static void summonDragon(Player player) {
        if (player != null) {
            // Periodically cleanup expired cache entries for performance
            if (System.currentTimeMillis() % 30000 == 0) { // Every ~30 seconds
                cleanupCache();
            }

            if (callDragon(player)) {
                var handler = PlayerStateUtils.getHandler(player);
                handler.lastCall = System.currentTimeMillis();
                ModItems.DRAGON_WHISTLES.values().forEach(s -> {
                    if (!player.getCooldowns().isOnCooldown(s.get())) {
                        player.getCooldowns()
                                .addCooldown(
                                        s.get(),
                                        (int) TimeUnit.SECONDS.convert(
                                                        ServerConfig.WHISTLE_COOLDOWN_CONFIG, TimeUnit.MILLISECONDS)
                                                * 20);
                    }
                });
            }
        }
    }

    public static boolean callDragon(Player player) {
        if (player != null) {
            DragonOwnerCapability cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);

            var summonItemIndex = getDragonSummonIndex(player);

            WhistleCallResult result = canCall(player, summonItemIndex);
            if (result != WhistleCallResult.SUCCESS) {
                if (!player.level.isClientSide) {
                    switch (result) {
                        case DRAGON_ON_COOLDOWN:
                            var handler = PlayerStateUtils.getHandler(player);
                            result.displayToPlayer(player, handler.respawnDelays.getOrDefault(summonItemIndex, 0) / 20);
                            break;
                        default:
                            result.displayToPlayer(player);
                            break;
                    }
                }
                return false;
            }

            Random rand = new Random();
            player.level.playSound(
                    null,
                    player.blockPosition(),
                    ModSounds.DRAGON_WHISTLE_SOUND.get(),
                    player.getSoundSource(),
                    0.75f,
                    (float) (ModConstants.DragonConstants.WHISTLE_BASE_PITCH
                            + rand.nextGaussian() / ModConstants.DragonConstants.WHISTLE_PITCH_DIVISOR));

            if (player.level.isClientSide) {
                return true; // Only process the remaining logic on the server side
            }

            DragonInstance instance = cap.dragonInstances.get(summonItemIndex);
            TameableDragonEntity dragon = findDragon(player, summonItemIndex);

            if (instance != null) {
                var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(instance.getDimension()));

                if (key != player.level.dimension()) {
                    var server = player.level.getServer();
                    assert server != null;
                    var level = server.getLevel(key);

                    var worldData1 = DragonWorldDataManager.getInstance(level);
                    var worldData2 = DragonWorldDataManager.getInstance(player.level);

                    // Transfer the dragon inventory
                    worldData2.dragonInventories.put(
                            instance.getUUID(), worldData1.dragonInventories.get(instance.getUUID()));
                    worldData1.dragonInventories.remove(instance.getUUID());

                    DMR.LOGGER.debug(
                            "Transferring dragon inventory from {} to {}",
                            instance.getDimension(),
                            player.level.dimension().location());
                }
            }

            if (dragon != null) {
                dragon.setHealth(Math.max(ModConstants.DragonConstants.MIN_DRAGON_HEALTH, dragon.getHealth()));
                dragon.ejectPassengers();

                if (dragon.position().distanceTo(player.position())
                        <= DragonConstants.BASE_FOLLOW_RANGE * ModConstants.DragonConstants.FOLLOW_RANGE_MULTIPLIER) {
                    // Walk to player
                    dragon.setOrderedToSit(false);
                    dragon.setWanderTarget(Optional.empty());

                    cap.lastSummons.put(summonItemIndex, dragon.getUUID());

                    DMR.LOGGER.debug(
                            "Making dragon: {} follow player: {}",
                            dragon.getDragonUUID(),
                            player.getName().getString());
                    PacketDistributor.sendToPlayersTrackingEntity(
                            dragon,
                            new DragonStatePacket(dragon.getId(), ModConstants.DragonConstants.DRAGON_STATE_FOLLOW));
                } else {
                    // Teleport to player
                    dragon.setOrderedToSit(false);
                    dragon.setWanderTarget(Optional.empty());

                    cap.lastSummons.put(summonItemIndex, dragon.getUUID());

                    DMR.LOGGER.debug(
                            "Teleporting dragon: {} to player: {}",
                            dragon.getDragonUUID(),
                            player.getName().getString());

                    dragon.setPos(player.getX(), player.getY(), player.getZ());
                    PacketDistributor.sendToPlayersTrackingEntity(
                            dragon,
                            new DragonStatePacket(dragon.getId(), ModConstants.DragonConstants.DRAGON_STATE_FOLLOW));
                }
                return true;
            }

            // Spawning a new dragon
            TameableDragonEntity newDragon = cap.createDragonEntity(player, player.level, summonItemIndex);

            if (newDragon == null) {
                return false;
            }

            DMR.LOGGER.debug(
                    "Spawning new dragon: {} for player: {}",
                    newDragon.getDragonUUID(),
                    player.getName().getString());

            newDragon.setPos(player.getX(), player.getY(), player.getZ());
            player.level.addFreshEntity(newDragon);

            PacketDistributor.sendToPlayersTrackingEntity(
                    newDragon,
                    new DragonStatePacket(newDragon.getId(), ModConstants.DragonConstants.DRAGON_STATE_FOLLOW));

            return true;
        }

        return false;
    }

    public static TameableDragonEntity findDragon(Player player, int index) {
        if (player.level.isClientSide) {
            return null;
        }

        var cap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
        var instance = cap.dragonInstances.get(index);

        if (instance != null) {
            var dim = instance.getDimension();
            var server = player.level.getServer();
            assert server != null;

            var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim));

            // Check if the dimension is the same as the players
            if (key == player.level.dimension()) {
                var level = server.getLevel(key);

                if (level != null) {
                    var entity = level.getEntity(instance.getEntityId());
                    if (entity instanceof TameableDragonEntity dragon) {
                        // Update dragon instance with current data
                        instance.updateFromDragon(dragon);
                        // Cache the dragon state for future performance
                        cacheDragonState(dragon);
                        DMR.LOGGER.debug("Found dragon: {} from entity id: {}", dragon, instance.getEntityId());
                        return dragon;
                    }
                }
            } else if (instance.requiresChunkLoading()) {
                if (!player.level.isClientSide) {
                    WhistleCallResult.DRAGON_TRAVELING.displayToPlayer(player);
                }

                var dragon = transferDragonFromOtherDimension(server, instance, player, index);
                if (dragon != null) {
                    return dragon;
                }
            }

            // Fallback: Search near player in current dimension
            DMR.LOGGER.debug(
                    "Searching for dragon: {} near player: {}",
                    instance.getUUID(),
                    player.getName().getString());

            var entities = player.level.getNearbyEntities(
                    TameableDragonEntity.class,
                    TargetingConditions.forNonCombat(),
                    player,
                    AABB.ofSize(
                            player.position(),
                            ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS,
                            ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS,
                            ModConstants.DragonConstants.DRAGON_SEARCH_RADIUS));

            for (var entity : entities) {
                if (entity.getDragonUUID() != null && entity.getDragonUUID().equals(instance.getUUID())) {
                    // Update dragon instance with found dragon data
                    instance.updateFromDragon(entity);
                    // Cache the dragon state for future performance
                    cacheDragonState(entity);
                    DMR.LOGGER.debug(
                            "Found dragon: {} near player: {}",
                            entity,
                            player.getName().getString());
                    return entity;
                }
            }
        }

        DMR.LOGGER.debug(
                "Could not find dragon: {} for player: {}",
                instance != null ? instance.getUUID() : "null",
                player.getName().getString());
        return null;
    }

    /**
     * Transfers a dragon from another dimension to the player's current dimension
     * Uses chunk loading to properly access and clean up the original entity
     */
    private static TameableDragonEntity transferDragonFromOtherDimension(
            net.minecraft.server.MinecraftServer server, DragonInstance instance, Player player, int index) {

        var sourceDimensionKey =
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(instance.getDimension()));
        var sourceLevel = server.getLevel(sourceDimensionKey);

        if (sourceLevel == null) {
            DMR.LOGGER.warn("Source dimension {} not found for dragon transfer", instance.getDimension());
            return null;
        }

        // Force load the chunk containing the dragon
        ChunkPos chunkPos = instance.getChunkPos();
        if (chunkPos == null) {
            DMR.LOGGER.debug(
                    "No position data available for dragon {}, cannot perform chunk loading", instance.getUUID());
            return null;
        }

        try {
            var serverLevel = (ServerLevel) sourceLevel;

            // Force load the chunk using the registered TicketController
            boolean loaded = DRAGON_TRANSFER_CONTROLLER.forceChunk(
                    serverLevel, instance.getUUID(), chunkPos.x, chunkPos.z, true, false);

            if (!loaded) {
                DMR.LOGGER.warn("Failed to force load chunk {} for dragon transfer", chunkPos);
                return null;
            }

            // Brief wait to ensure chunk and entities are fully loaded
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Clean up on interruption
                DRAGON_TRANSFER_CONTROLLER.forceChunk(
                        serverLevel, instance.getUUID(), chunkPos.x, chunkPos.z, false, false);
                return null;
            }

            // Try to find the dragon in the loaded chunk
            var entity = sourceLevel.getEntity(instance.getEntityId());
            if (entity instanceof TameableDragonEntity sourceDragon) {
                DMR.LOGGER.debug(
                        "Found dragon {} in dimension {}, transferring to {}",
                        sourceDragon.getDragonUUID(),
                        instance.getDimension(),
                        player.level.dimension().location());

                // Transfer dragon inventory first
                transferDragonInventoryBetweenDimensions(sourceDragon, sourceLevel, (ServerLevel) player.level);

                // Remove the source dragon to prevent duplicates
                sourceDragon.discard();

                // Create new dragon in player's dimension using NBT data
                var playerCap = player.getData(ModCapabilities.PLAYER_CAPABILITY);
                var newDragon = playerCap.createDragonEntity(player, player.level, index);

                if (newDragon != null) {
                    // Update the dragon instance to reflect the new dimension and entity
                    instance.updateFromDragon(newDragon);
                    instance.dimension = player.level.dimension().location().toString();

                    // Cache the transferred dragon for future performance
                    cacheDragonState(newDragon);

                    DMR.LOGGER.debug(
                            "Successfully transferred dragon {} to dimension {}",
                            newDragon.getDragonUUID(),
                            player.level.dimension().location());

                    return newDragon;
                }
            } else {
                DMR.LOGGER.debug(
                        "Dragon entity {} not found in source dimension {}",
                        instance.getEntityId(),
                        instance.getDimension());
            }

            // Clean up the chunk ticket after processing
            DRAGON_TRANSFER_CONTROLLER.forceChunk(
                    serverLevel, instance.getUUID(), chunkPos.x, chunkPos.z, false, false);

        } catch (Exception e) {
            DMR.LOGGER.error(
                    "Failed to transfer dragon from dimension {}: {}", instance.getDimension(), e.getMessage());
            // Clean up chunk ticket on error too
            try {
                DRAGON_TRANSFER_CONTROLLER.forceChunk(
                        (ServerLevel) sourceLevel, instance.getUUID(), chunkPos.x, chunkPos.z, false, false);
            } catch (Exception cleanupException) {
                DMR.LOGGER.warn("Failed to cleanup chunk ticket: {}", cleanupException.getMessage());
            }
        }

        return null;
    }

    /**
     * Transfers dragon inventory between dimensions to prevent item loss
     */
    private static void transferDragonInventoryBetweenDimensions(
            TameableDragonEntity sourceDragon, Level sourceLevel, ServerLevel targetLevel) {

        var sourceWorldData = DragonWorldDataManager.getInstance(sourceLevel);
        var targetWorldData = DragonWorldDataManager.getInstance(targetLevel);

        var dragonUUID = sourceDragon.getDragonUUID();
        var inventory = sourceWorldData.dragonInventories.get(dragonUUID);

        if (inventory != null) {
            // Transfer the inventory to target dimension
            targetWorldData.dragonInventories.put(dragonUUID, inventory);
            sourceWorldData.dragonInventories.remove(dragonUUID);

            DMR.LOGGER.debug(
                    "Transferred dragon inventory for dragon {} from {} to {}",
                    dragonUUID,
                    sourceLevel.dimension().location(),
                    targetLevel.dimension().location());
        }
    }

    /**
     * Cache dragon state for predictive operations
     */
    public static void cacheDragonState(TameableDragonEntity dragon) {
        if (dragon.getDragonUUID() != null) {
            DRAGON_CACHE.put(dragon.getDragonUUID(), new DragonCacheEntry(dragon));
        }
    }

    /**
     * Cache dragon state from heartbeat packet data for predictive operations
     */
    public static void cacheHeartbeatData(
            UUID dragonUUID,
            BlockPos position,
            String dimension,
            boolean isAlive,
            float health,
            boolean isOrderedToSit) {
        if (dragonUUID != null) {
            DRAGON_CACHE.put(dragonUUID, new DragonCacheEntry(position, dimension, isAlive, health, isOrderedToSit));
        }
    }

    /**
     * Get cached dragon state if available and not expired
     */
    public static DragonCacheEntry getCachedDragonState(UUID dragonUUID) {
        var entry = DRAGON_CACHE.get(dragonUUID);
        if (entry != null && entry.isExpired()) {
            DRAGON_CACHE.remove(dragonUUID);
            return null;
        }
        return entry;
    }

    /**
     * Clear expired cache entries (performance optimization)
     */
    public static void cleanupCache() {
        var iterator = DRAGON_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
            }
        }
    }

    /**
     * Check if dragon is available and alive using cached data
     */
    public static boolean isDragonAvailable(Player player, int index) {
        var handler = PlayerStateUtils.getHandler(player);
        var instance = handler.dragonInstances.get(index);

        if (instance == null) return false;

        if (handler.respawnDelays.getOrDefault(index, 0) > 0) {
            return false; // Dead dragon unavailable
        }

        var cacheEntry = getCachedDragonState(instance.getUUID());
        if (cacheEntry != null) {
            return cacheEntry.isAlive
                    && cacheEntry.isInDimension(
                            player.level.dimension().location().toString());
        }

        return !instance.isStale();
    }

    /**
     * Get dragon status from cached data
     * Returns null if no cached data is available
     */
    public static DragonCacheEntry getDragonStatus(Player player, int index) {
        var handler = PlayerStateUtils.getHandler(player);
        var instance = handler.dragonInstances.get(index);

        if (instance == null) return null;

        return getCachedDragonState(instance.getUUID());
    }

    /**
     * Check if any dragon is available using the best available whistle
     */
    public static boolean hasAvailableDragon(Player player) {
        var whistleItem = getDragonWhistleItem(player, true); // Smart selection
        if (whistleItem == null) return false;

        return isDragonAvailable(player, whistleItem.getColor().getId());
    }

    /**
     * Finds dragon with caching optimization
     */
    public static TameableDragonEntity findDragonWithCache(Player player, int index) {
        var dragon = findDragon(player, index);

        if (dragon != null) {
            cacheDragonState(dragon);
        }

        return dragon;
    }
}
