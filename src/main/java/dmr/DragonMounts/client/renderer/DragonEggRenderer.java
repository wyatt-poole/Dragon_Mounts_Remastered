package dmr.DragonMounts.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dmr.DragonMounts.client.model.DragonEggModel;
import dmr.DragonMounts.client.model.DragonEggModel.Baked;
import dmr.DragonMounts.config.ClientConfig;
import dmr.DragonMounts.server.blockentities.DMREggBlockEntity;
import dmr.DragonMounts.server.blocks.DMREggBlock;
import dmr.DragonMounts.types.dragonBreeds.DragonHybridBreed;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.RenderTypeHelper;

public class DragonEggRenderer implements BlockEntityRenderer<DMREggBlockEntity> {

    @Override
    public void render(
            DMREggBlockEntity blockEntity,
            float v,
            PoseStack poseStack,
            MultiBufferSource multiBufferSource,
            int i,
            int i1) {
        if (!ClientConfig.RENDER_HATCHING_EGG) {
            return;
        }

        if (!blockEntity.getBlockState().getValue(DMREggBlock.HATCHING)) {
            return;
        }

        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(blockEntity.getBlockState());

        // Unwrap potential CTM/connected-texture wrappers (e.g. Continuity) that would otherwise
        // fail the `instanceof Baked` check and leave the egg invisible during hatching, since the
        // block reports RenderShape.INVISIBLE while hatching and relies entirely on this renderer.
        // See https://github.com/Wyrmheart-Team/Dragon_Mounts_Remastered/issues/112
        DragonEggModel.Baked eggModel = DragonEggModel.Baked.unwrap(model);
        if (eggModel != null) {
            var bakedModel = eggModel.models.getOrDefault(blockEntity.getBreedId(), Baked.FALLBACK.get());

            poseStack.pushPose();
            var time = blockEntity.tickCount;
            float hatchProgress =
                    ((float) blockEntity.getHatchTime() / blockEntity.getBreed().getHatchTime());
            float oscillationPeriod = 100;
            float angle = (float) Math.sin((time % oscillationPeriod) * ((2 * Math.PI) / oscillationPeriod))
                    * (2 + (5 * hatchProgress));

            poseStack.translate(0.5, 0, 0.5);
            poseStack.rotateAround(Axis.XN.rotationDegrees(angle), 0, 0, 0);
            poseStack.translate(-0.5, 0, -0.5);

            if (blockEntity.getBreed() != null && blockEntity.getBreed() instanceof DragonHybridBreed hybridBreed) {
                bakedModel = eggModel.models.getOrDefault(hybridBreed.parent1.getId(), Baked.FALLBACK.get());
            }

            // Read render types from the per-breed sub-model (which is NOT wrapped by Continuity),
            // not the top-level wrapped model. The wrapper can return a render-type set that's empty
            // or incompatible with getEntityRenderType, which causes the BER to draw nothing -- the
            // actual symptom of issue #112 even after the instanceof unwrap.
            var renderType = pickEntityRenderType(
                    bakedModel, blockEntity.getBlockState(), blockEntity.getLevel().random, blockEntity.getModelData());

            Minecraft.getInstance()
                    .getBlockRenderer()
                    .getModelRenderer()
                    .renderModel(
                            poseStack.last(),
                            multiBufferSource.getBuffer(renderType),
                            blockEntity.getBlockState(),
                            Objects.requireNonNullElse(bakedModel, model),
                            0,
                            0,
                            0,
                            i,
                            OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
    }

    private static RenderType pickEntityRenderType(
            BakedModel bakedModel,
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.util.RandomSource random,
            net.neoforged.neoforge.client.model.data.ModelData modelData) {
        ChunkRenderTypeSet types = bakedModel.getRenderTypes(state, random, modelData);
        var list = types.asList();
        if (!list.isEmpty()) {
            return RenderTypeHelper.getEntityRenderType(list.getFirst(), true);
        }
        // Defensive fallback: if the model reports no render types (e.g. a wrapped or stripped
        // model from a CTM mod), use the standard cutout entity sheet so we still draw something.
        return Sheets.cutoutBlockSheet();
    }
}
