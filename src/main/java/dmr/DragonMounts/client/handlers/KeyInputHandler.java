package dmr.DragonMounts.client.handlers;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.InputConstants.Type;
import dmr.DragonMounts.DMR;
import dmr.DragonMounts.client.gui.CommandMenu.CommandMenuScreen;
import dmr.DragonMounts.common.handlers.DragonWhistleHandler;
import dmr.DragonMounts.config.ClientConfig;
import dmr.DragonMounts.network.packets.DismountDragonPacket;
import dmr.DragonMounts.network.packets.DragonBreathPacket;
import dmr.DragonMounts.network.packets.SummonDragonPacket;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.util.PlayerStateUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = DMR.MOD_ID, value = Dist.CLIENT, bus = Bus.MOD)
public class KeyInputHandler {

    public static KeyMapping SUMMON_DRAGON = new KeyMapping(
            "dmr.keybind.summon_dragon",
            KeyConflictContext.IN_GAME,
            Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "dmr.keybind.category");

    public static KeyMapping ATTACK_KEY = new KeyMapping(
            "dmr.keybind.attack",
            KeyConflictContext.IN_GAME,
            Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            "dmr.keybind.category");

    public static KeyMapping DRAGON_COMMAND_KEY = new KeyMapping(
            "dmr.keybind.dragon_command", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, "dmr.keybind.category");

    public static KeyMapping DISMOUNT_KEY = new KeyMapping(
            "dmr.keybind.dismount", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, "dmr.keybind.category");

    public static KeyMapping DESCEND_KEY = new KeyMapping(
            "dmr.keybind.descend", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, "dmr.keybind.category");

    public static KeyMapping BREATH_KEY = new KeyMapping(
            "dmr.keybind.breath", KeyConflictContext.IN_GAME, Type.KEYSYM, GLFW.GLFW_KEY_B, "dmr.keybind.category");

    private static boolean lastWheelState = false;

    @SubscribeEvent
    public static void registerBindings(RegisterKeyMappingsEvent event) {
        event.register(SUMMON_DRAGON);
        event.register(ATTACK_KEY);
        event.register(DRAGON_COMMAND_KEY);
        event.register(DISMOUNT_KEY);
        event.register(DESCEND_KEY);
        event.register(BREATH_KEY);
    }

    public static void onKeyboardTick() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null) {
            return;
        }

        if (mc.player == null) {
            return;
        }

        var whistleItem = DragonWhistleHandler.getDragonWhistleItem(mc.player);

        if (whistleItem == null) {
            return;
        }

        var capability = PlayerStateUtils.getHandler(mc.player);

        if (!capability.dragonNBTs.containsKey(whistleItem.getColor().getId())) {
            return;
        }

        long handle = Minecraft.getInstance().getWindow().getWindow();
        int keycode = DRAGON_COMMAND_KEY.getKey().getValue();

        if (keycode >= 0) {
            boolean radialMenuKeyDown = (DRAGON_COMMAND_KEY.matchesMouse(keycode)
                    ? GLFW.glfwGetMouseButton(handle, keycode) == 1
                    : InputConstants.isKeyDown(handle, keycode));

            if (radialMenuKeyDown != lastWheelState) {
                if (radialMenuKeyDown != CommandMenuScreen.active) {
                    if (radialMenuKeyDown) {
                        if (mc.screen == null || mc.screen instanceof CommandMenuScreen) {
                            CommandOverlayHandler.resetTimer();
                            CommandMenuScreen.activate();
                            DMR.LOGGER.debug("Command Menu activated");
                        }
                    }
                }
            }
            lastWheelState = radialMenuKeyDown;
        }
    }

    @OnlyIn(Dist.CLIENT)
    @EventBusSubscriber(modid = DMR.MOD_ID, value = Dist.CLIENT, bus = Bus.GAME)
    public static class KeyClickHandler {

        // Tap-vs-hold detection state for the double-tap-shift-to-dismount feature.
        //
        // Previously this used `lastUnshift` (release-time of any shift hold) plus a
        // 2-second window to detect "double tap". That mis-classified normal descend
        // releases as taps, so any later shift press within 2s of *any* descend
        // release would dismount. With long enough chains it could appear to dismount
        // on a single press from minutes earlier.
        //
        // New logic: track the start of each press. On release, only count the press
        // as a "tap" if it was held for less than TAP_MAX_DURATION_MS. Two
        // consecutive taps within DOUBLE_TAP_WINDOW_MS dismount. Holds are descends
        // and never count.
        private static Long pressStartedAt = null;
        private static Long lastTapEndedAt = null;
        private static final long TAP_MAX_DURATION_MS = 250L;
        private static final long DOUBLE_TAP_WINDOW_MS = 500L;

        @OnlyIn(Dist.CLIENT)
        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Pre event) {
            onKeyboardTick();
        }

        @OnlyIn(Dist.CLIENT)
        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Post event) {
            if (Minecraft.getInstance().level == null) return;
            if (Minecraft.getInstance().player == null) return;
            if (Minecraft.getInstance().screen != null) return;

            var player = Minecraft.getInstance().player;

            if (player.getControlledVehicle() instanceof TameableDragonEntity dragon) {
                while (BREATH_KEY.consumeClick()) {
                    PacketDistributor.sendToServer(new DragonBreathPacket(dragon.getId()));
                }

                var shift = Minecraft.getInstance().options.keyShift;
                long now = System.currentTimeMillis();
                boolean shiftPressed = shift.consumeClick();
                boolean shiftDown = shift.isDown();

                if (shiftPressed) {
                    pressStartedAt = now;

                    if (ClientConfig.DOUBLE_PRESS_DISMOUNT) {
                        // Did the previous tap end recently enough? Then this press completes a double-tap.
                        if (lastTapEndedAt != null && (now - lastTapEndedAt) <= DOUBLE_TAP_WINDOW_MS) {
                            PacketDistributor.sendToServer(new DismountDragonPacket(player.getId(), true));
                            lastTapEndedAt = null;
                            pressStartedAt = null;
                            return;
                        }
                    } else {
                        PacketDistributor.sendToServer(new DismountDragonPacket(player.getId(), true));
                        return;
                    }
                }

                // On release, classify the just-completed press as a tap or a hold.
                if (pressStartedAt != null && !shiftDown) {
                    long heldFor = now - pressStartedAt;
                    if (heldFor <= TAP_MAX_DURATION_MS) {
                        lastTapEndedAt = now;
                    }
                    // Holds (descends) deliberately don't update lastTapEndedAt, so a
                    // descend can't be retroactively chained into a double-tap.
                    pressStartedAt = null;
                }

                if (DISMOUNT_KEY.consumeClick()) {
                    PacketDistributor.sendToServer(new DismountDragonPacket(player.getId(), true));
                    return;
                }
            } else {
                if (SUMMON_DRAGON.consumeClick()) {
                    PacketDistributor.sendToServer(new SummonDragonPacket());
                    return;
                }
            }
        }
    }
}
