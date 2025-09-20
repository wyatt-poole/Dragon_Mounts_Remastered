package dmr.DragonMounts.server.items;

import dmr.DragonMounts.client.gui.CommandMenu.CommandMenuScreen;
import dmr.DragonMounts.client.handlers.CommandOverlayHandler;
import dmr.DragonMounts.common.capability.DragonOwnerCapability;
import dmr.DragonMounts.common.handlers.DragonSyncManager;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.network.packets.CompleteDataSync;
import dmr.DragonMounts.network.packets.DragonOwnerUpdatePacket;
import dmr.DragonMounts.registry.datapack.DragonBreedsRegistry;
import dmr.DragonMounts.registry.entity.ModCapabilities;
import dmr.DragonMounts.registry.item.ModItems;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.util.PlayerStateUtils;
import java.util.List;
import lombok.Getter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

@Getter
public class DragonWhistleItem extends Item {

    private final DyeColor color;

    public DragonWhistleItem(Properties pProperties, DyeColor color) {
        super(pProperties);
        this.color = color;
    }

    public static ItemStack getWhistleItem(DyeColor color) {
        return getWhistleItem(color, 1);
    }

    public static ItemStack getWhistleItem(DyeColor color, int count) {
        return new ItemStack(ModItems.DRAGON_WHISTLES.get(color.getId()).get(), count);
    }

    @Override
    public void inventoryTick(ItemStack pStack, Level pLevel, Entity pEntity, int pSlotId, boolean pIsSelected) {
        super.inventoryTick(pStack, pLevel, pEntity, pSlotId, pIsSelected);

        if (pEntity instanceof Player player) {
            var state = player.getData(ModCapabilities.PLAYER_CAPABILITY);
            if (state.respawnDelays.containsKey(color.getId())) {
                if (!player.getCooldowns().isOnCooldown(this)) {
                    player.getCooldowns().addCooldown(this, state.respawnDelays.get(color.getId()));
                }
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);

        var player = Minecraft.getInstance().player;
        assert player != null;
        var state = PlayerStateUtils.getHandler(player);
        var nbt = state.dragonNBTs.get(color.getId());

        if (nbt == null) {
            return;
        }

        var breed = nbt.getString("breed");
        var dragonBreed = DragonBreedsRegistry.getDragonBreed(breed);

        if (dragonBreed != null) {
            var name = Component.translatable("dmr.dragon_breed." + breed).getString();

            if (nbt.contains("CustomName")) {
                name = nbt.getString("CustomName").replace("\"", "") + " (" + name + ")";
            }

            tooltipComponents.add(Component.translatable("dmr.dragon_summon.tooltip.1", name)
                    .withStyle(ChatFormatting.GRAY)
                    .withStyle(ChatFormatting.ITALIC));
        }

        // Check respawn delays (dragon is dead)
        if (state.respawnDelays.containsKey(color.getId()) && state.respawnDelays.get(color.getId()) > 0) {
            tooltipComponents.add(
                    Component.translatable("dmr.dragon_summon.tooltip.2", state.respawnDelays.get(color.getId()) / 20)
                            .withStyle(ChatFormatting.ITALIC)
                            .withStyle(ChatFormatting.RED));
        } else {
            // Dragon is alive - show cached status if available
            var instance = state.dragonInstances.get(color.getId());
            if (instance != null) {
                var cachedState = DragonWhistleHandler.getCachedDragonState(instance.getUUID());
                if (cachedState != null) {
                    // Show health status
                    var healthPercent = (int) ((cachedState.health / Math.max(1, cachedState.health)) * 100);
                    tooltipComponents.add(Component.literal("Health: " + (int) cachedState.health + " HP")
                            .withStyle(ChatFormatting.GREEN));

                    // Show current behavior
                    if (cachedState.isOrderedToSit) {
                        tooltipComponents.add(
                                Component.literal("Status: Sitting").withStyle(ChatFormatting.YELLOW));
                    } else {
                        tooltipComponents.add(
                                Component.literal("Status: Following").withStyle(ChatFormatting.AQUA));
                    }

                    // Show dimension if different from player
                    if (!cachedState.isInDimension(
                            player.level().dimension().location().toString())) {
                        var dimensionName = cachedState.dimension.replace("minecraft:", "");
                        tooltipComponents.add(
                                Component.literal("Location: " + dimensionName).withStyle(ChatFormatting.GOLD));
                    }
                }
            }
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level pLevel, Player pPlayer, InteractionHand pUsedHand) {
        if (!pPlayer.isShiftKeyDown()) {
            var state = PlayerStateUtils.getHandler(pPlayer);
            var nbt = state.dragonNBTs.get(color.getId());
            if (nbt == null) {
                if (!pPlayer.level.isClientSide) {
                    pPlayer.displayClientMessage(
                            Component.translatable("dmr.dragon_call.nodragon").withStyle(ChatFormatting.RED), true);
                }
                return InteractionResultHolder.pass(pPlayer.getItemInHand(pUsedHand));
            }
            if (pPlayer.level.isClientSide) {
                CommandOverlayHandler.resetTimer();
                CommandMenuScreen.activate();
            }
            return InteractionResultHolder.pass(pPlayer.getItemInHand(pUsedHand));
        }
        return super.use(pLevel, pPlayer, pUsedHand);
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack pStack, Player pPlayer, LivingEntity pInteractionTarget, InteractionHand pUsedHand) {
        if (pPlayer.level.isClientSide) return InteractionResult.PASS;

        if (pInteractionTarget instanceof TameableDragonEntity dragon) {
            if (dragon.isTame() && dragon.isAdult() && dragon.isOwnedBy(pPlayer)) {
                DragonOwnerCapability cap = pPlayer.getData(ModCapabilities.PLAYER_CAPABILITY);
                if (cap.dragonInstances.containsKey(color.getId())) {
                    var dragonInstance = cap.dragonInstances.get(color.getId());
                    // Only unlink if the player is sneaking
                    if (!pPlayer.isShiftKeyDown()) {
                        return InteractionResult.PASS;
                    }

                    if (!dragonInstance.getUUID().equals(dragon.getDragonUUID())) {
                        pPlayer.displayClientMessage(Component.translatable("dmr.dragon_call.unlink_first"), true);
                    } else {
                        cap.dragonInstances.remove(color.getId());
                        cap.dragonNBTs.remove(color.getId());
                        cap.respawnDelays.remove(color.getId());
                        pPlayer.displayClientMessage(Component.translatable("dmr.dragon_call.unlink_success"), true);

                        // Use targeted unlink operation instead of CompleteDataSync (90% bandwidth reduction)
                        try {
                            CompoundTag unlinkData = new CompoundTag();
                            unlinkData.putBoolean("unlink", true);
                            DragonSyncManager.updateOwnerData(
                                    (ServerPlayer) pPlayer,
                                    color.getId(),
                                    DragonOwnerUpdatePacket.UpdateType.LINK_OPERATION,
                                    unlinkData);
                        } catch (Exception e) {
                            // Fallback to CompleteDataSync if targeted sync fails
                            DMR.LOGGER.warn(
                                    "Targeted unlink sync failed for player {}, falling back to CompleteDataSync",
                                    pPlayer.getName().getString(),
                                    e);
                            PacketDistributor.sendToPlayer((ServerPlayer) pPlayer, new CompleteDataSync(pPlayer));
                        }
                    }
                    return InteractionResult.SUCCESS;
                } else {
                    DragonWhistleHandler.setDragon(pPlayer, dragon, color.getId());

                    // Use targeted link operation instead of CompleteDataSync (90% bandwidth reduction)
                    try {
                        // Get the newly created NBT and instance data
                        var handler = PlayerStateUtils.getHandler(pPlayer);
                        CompoundTag linkData = new CompoundTag();

                        // Include the dragon's NBT data for the link
                        if (handler.dragonNBTs.containsKey(color.getId())) {
                            linkData.put("nbt", handler.dragonNBTs.get(color.getId()));
                        }

                        // Include basic dragon info
                        linkData.putString("dragonName", dragon.getDisplayName().getString());
                        linkData.putString("dragonUUID", dragon.getDragonUUID().toString());

                        DragonSyncManager.updateOwnerData(
                                (ServerPlayer) pPlayer,
                                color.getId(),
                                DragonOwnerUpdatePacket.UpdateType.LINK_OPERATION,
                                linkData);
                    } catch (Exception e) {
                        // Fallback to CompleteDataSync if targeted sync fails
                        DMR.LOGGER.warn(
                                "Targeted link sync failed for player {}, falling back to CompleteDataSync",
                                pPlayer.getName().getString(),
                                e);
                        PacketDistributor.sendToPlayer((ServerPlayer) pPlayer, new CompleteDataSync(pPlayer));
                    }

                    pPlayer.displayClientMessage(
                            Component.translatable(
                                    "dmr.dragon_call.link_success",
                                    dragon.getDisplayName().getString()),
                            true);
                }

                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }
}
