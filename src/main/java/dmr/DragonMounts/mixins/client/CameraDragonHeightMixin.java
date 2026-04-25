package dmr.DragonMounts.mixins.client;

import dmr.DragonMounts.config.ClientConfig;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a configurable vertical offset to the first-person camera while the player is
 * riding a {@link TameableDragonEntity}. The dragon's head/neck can otherwise block the
 * forward view in first person, and the existing {@code riding_camera_offset} only
 * affects the third-person detached camera distance via
 * {@code CalculateDetachedCameraDistanceEvent}.
 *
 * <p>Reads {@link ClientConfig#FIRST_PERSON_CAMERA_HEIGHT}; offset of 0 is a no-op so the
 * mixin is harmless when not configured. Skips when the camera is detached (third person)
 * since {@code RidingCameraHandler} owns that path.
 */
@Mixin(Camera.class)
public abstract class CameraDragonHeightMixin {

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    public abstract Vec3 getPosition();

    @Shadow
    public abstract Vector3f getLookVector();

    @Inject(method = "setup", at = @At("TAIL"))
    private void dmr$liftFirstPersonWhileRidingDragon(
            BlockGetter level, Entity entity, boolean detached, boolean reversed, float partialTick, CallbackInfo ci) {
        if (detached) return; // first-person only; third-person is RidingCameraHandler.
        if (!(entity instanceof LocalPlayer player)) return;
        if (!(player.getControlledVehicle() instanceof TameableDragonEntity)) return;

        int height = ClientConfig.FIRST_PERSON_CAMERA_HEIGHT;
        int forward = ClientConfig.FIRST_PERSON_CAMERA_FORWARD;
        if (height == 0 && forward == 0) return;

        Vec3 pos = getPosition();

        // Vertical lift in WORLD space (so a pitched-up gaze still moves the camera
        // straight up rather than along the look vector).
        if (height != 0) {
            pos = pos.add(0, height, 0);
        }

        // Forward / backward along the camera's look direction. We compute this manually
        // via getLookVector + setPosition rather than calling Camera.move, because the
        // private Camera.move method's mapping varies across NeoForge runtimes and an
        // @Shadow on it has been seen to fail in production. getLookVector / setPosition
        // / getPosition are public/protected and stable.
        if (forward != 0) {
            Vector3f look = getLookVector();
            pos = pos.add(look.x() * forward, look.y() * forward, look.z() * forward);
        }

        setPosition(pos);
    }
}
