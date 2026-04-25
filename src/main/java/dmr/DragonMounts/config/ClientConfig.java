package dmr.DragonMounts.config;

import dmr.DragonMounts.config.annotations.Config;
import dmr.DragonMounts.config.annotations.RangeConstraint;
import dmr.DragonMounts.config.annotations.SyncedConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.ModConfigSpec;

@OnlyIn(Dist.CLIENT)
public class ClientConfig {

    public static final ModConfigSpec MOD_CONFIG_SPEC;

    @Config(key = "camera_flight", comment = "Should the dragon be controlled by the camera during flight?")
    @SyncedConfig
    public static boolean CAMERA_FLIGHT = true;

    @Config(
            key = "alternate_dismount",
            comment =
                    "Should dismounting the dragon require double pressing the dismount button? Disabling this will not allow using sneak or the dismount button to descend.")
    @SyncedConfig
    public static boolean DOUBLE_PRESS_DISMOUNT = true;

    @Config(
            key = "alternate_attack_key",
            comment =
                    "Require a modifier key to send dragon actions to the server.\n"
                            + "  false (default) -> while mounted, left-click ALWAYS bites and right-click ALWAYS breathes; vanilla block break / place / item use are suppressed.\n"
                            + "  true            -> mount actions only fire while you hold the 'Dragon Action Modifier' keybind (Options -> Controls). When you are not holding it, left/right-click pass through to vanilla (break/place/use) as normal.")
    public static boolean USE_ALTERNATE_ATTACK_KEY = false;

    @Config(
            key = "riding_camera_offset",
            comment = "The zoom offset for the riding camera. Higher values will zoom the camera out further.")
    @RangeConstraint(min = 1, max = 100)
    public static int RIDING_CAMERA_OFFSET = 10;

    @Config(
            key = "first_person_camera_height",
            comment = "Vertical camera offset (in blocks) when riding a dragon in FIRST person.\n"
                    + "Useful when the dragon's head/neck blocks your view forward. Positive values raise the\n"
                    + "camera; negative lower it. Has no effect in third person -- use 'riding_camera_offset'\n"
                    + "for that.\n"
                    + "  Default: 0\n"
                    + "  Range: -2 to 5")
    @RangeConstraint(min = -2, max = 5)
    public static int FIRST_PERSON_CAMERA_HEIGHT = 0;

    @Config(
            key = "first_person_camera_forward",
            comment = "Forward / backward camera offset (in blocks) when riding a dragon in FIRST person, along\n"
                    + "your look direction. Positive moves the camera forward (toward what you're looking at);\n"
                    + "negative moves it backward (behind the rider). Pair with first_person_camera_height to dial\n"
                    + "in a comfortable view past the dragon.\n"
                    + "  Default: 0\n"
                    + "  Range: -3 to 5")
    @RangeConstraint(min = -3, max = 5)
    public static int FIRST_PERSON_CAMERA_FORWARD = 0;

    @Config(key = "render_hatching_egg", comment = "Should the dragon egg render the hatching animation?")
    public static boolean RENDER_HATCHING_EGG = true;

    @Config(
            key = "colored_whistle_menu",
            comment = "Should the dragon whistle command menu display colors matching the whistle's color?")
    public static boolean COLORED_WHISTLE_MENU = true;

    // Initialize the config
    static {
        MOD_CONFIG_SPEC = ConfigProcessor.processConfig(ClientConfig.class);
    }
}
