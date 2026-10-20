package a_silly_cat.golems_arsenal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 火箭实体模型 —— **由 Blockbench 5.2.2 导出**（{@code 火箭实体模型.java} 的类版本）。
 *
 * <p>转换内容只有三处，模型数据本身一行没改：
 * <ol>
 *   <li>加包名与 import</li>
 *   <li>{@code LAYER_LOCATION} 的命名空间从占位的 {@code modid} 改成 {@code golems_arsenal}，
 *       路径改成 ASCII 的 {@code rocket}（资源路径不用中文，见项目约定）</li>
 *   <li>类名改成 ASCII（原文件名是中文，Java 类名用中文虽可行但不该带进代码）</li>
 * </ol>
 *
 * <p><b>为什么必须用这份 Java 而不是 JSON</b>：模型里有 4 条着陆腿是
 * {@code PartPose.offsetAndRotation(...)} 的**旋转部件**，手写 JSON 要自己算旋转与 UV 的
 * 组合，极易出错（前面已经因为字段名和 UV 单位错过两次）。Blockbench 导出时这些都算好了。
 */
@OnlyIn(Dist.CLIENT)
public class RocketModel<T extends Entity> extends EntityModel<T> {

    /** 模型层。命名空间/路径要与 {@code ModClient} 注册的一致。 */
    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(new ResourceLocation("golems_arsenal", "rocket"), "main");

    private final ModelPart root;
    private final ModelPart bone7;
    private final ModelPart bone2;
    private final ModelPart bone;
    private final ModelPart bone3;
    private final ModelPart bone4;
    private final ModelPart bone6;
    private final ModelPart bone5;

    public RocketModel(ModelPart root) {
        this.root = root;
        this.bone7 = root.getChild("bone7");
        this.bone2 = root.getChild("bone2");
        this.bone = root.getChild("bone");
        this.bone3 = this.bone.getChild("bone3");
        this.bone4 = this.bone.getChild("bone4");
        this.bone6 = this.bone.getChild("bone6");
        this.bone5 = this.bone.getChild("bone5");
    }

    /** 根节点。{@link RocketRenderer} 用它遍历全部 cube 测出真实包围盒。 */
    public ModelPart root() {
        return this.root;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition bone7 = partdefinition.addOrReplaceChild("bone7", CubeListBuilder.create()
                .texOffs(0, 178).addBox(-30.0F, -128.0F, -30.0F, 60.0F, 48.0F, 60.0F, new CubeDeformation(0.0F))
                .texOffs(192, 0).addBox(-28.0F, -136.0F, -28.0F, 56.0F, 8.0F, 56.0F, new CubeDeformation(0.0F))
                .texOffs(240, 183).addBox(-20.0F, -151.0F, -20.0F, 40.0F, 8.0F, 40.0F, new CubeDeformation(0.0F))
                .texOffs(240, 231).addBox(-16.0F, -159.0F, -16.0F, 32.0F, 8.0F, 32.0F, new CubeDeformation(0.0F))
                .texOffs(368, 231).addBox(-12.0F, -167.0F, -12.0F, 24.0F, 8.0F, 24.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(-24.0F, -82.0F, -24.0F, 48.0F, 130.0F, 48.0F, new CubeDeformation(0.0F))
                .texOffs(192, 64).addBox(-24.0F, -143.0F, -24.0F, 48.0F, 8.0F, 48.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, -48.0F, 0.0F));

        PartDefinition bone2 = partdefinition.addOrReplaceChild("bone2", CubeListBuilder.create()
                .texOffs(240, 120).addBox(-21.0F, -30.0F, -21.0F, 42.0F, 21.0F, 42.0F, new CubeDeformation(0.0F))
                .texOffs(192, 120).addBox(-6.0F, -39.0F, -6.0F, 12.0F, 35.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(168, 286).addBox(6.0F, -39.0F, -18.0F, 12.0F, 39.0F, 24.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 24.0F, 0.0F));

        bone2.addOrReplaceChild("cube_r1", CubeListBuilder.create()
                        .texOffs(324, 333).addBox(6.0F, -39.0F, -18.0F, 12.0F, 39.0F, 24.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, -1.5708F, 0.0F));

        bone2.addOrReplaceChild("cube_r2", CubeListBuilder.create()
                        .texOffs(240, 335).addBox(6.0F, -39.0F, -18.0F, 12.0F, 39.0F, 24.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 3.1416F, 0.0F));

        bone2.addOrReplaceChild("cube_r3", CubeListBuilder.create()
                        .texOffs(84, 348).addBox(6.0F, -39.0F, -18.0F, 12.0F, 39.0F, 24.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 1.5708F, 0.0F));

        PartDefinition bone = partdefinition.addOrReplaceChild("bone",
                CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));

        PartDefinition bone3 = bone.addOrReplaceChild("bone3",
                CubeListBuilder.create(), PartPose.offset(-24.0F, 12.0F, 24.0F));

        bone3.addOrReplaceChild("cube_r4", CubeListBuilder.create()
                        .texOffs(0, 350).addBox(-8.0F, -68.0F, -8.0F, 16.0F, 53.0F, 16.0F, new CubeDeformation(0.0F))
                        .texOffs(384, 92).addBox(-8.0F, -15.0F, -8.0F, 16.0F, 11.0F, 16.0F, new CubeDeformation(0.0F))
                        .texOffs(84, 286).addBox(-11.0F, -54.0F, -8.0F, 22.0F, 42.0F, 20.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 12.0F, 0.0F, 0.0F, -0.7854F, 0.0F));

        PartDefinition bone4 = bone.addOrReplaceChild("bone4",
                CubeListBuilder.create(), PartPose.offset(24.0F, 12.0F, 24.0F));

        bone4.addOrReplaceChild("cube_r5", CubeListBuilder.create()
                        .texOffs(312, 396).addBox(-8.0F, -68.0F, -8.0F, 16.0F, 53.0F, 16.0F, new CubeDeformation(0.0F))
                        .texOffs(220, 398).addBox(-8.0F, -15.0F, -8.0F, 16.0F, 11.0F, 16.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 12.0F, 0.0F, 0.0F, 0.7854F, 0.0F));

        bone4.addOrReplaceChild("cube_r6", CubeListBuilder.create()
                        .texOffs(240, 271).addBox(-8.0F, -42.0F, -11.0F, 20.0F, 42.0F, 22.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, -0.7854F, 0.0F));

        PartDefinition bone6 = bone.addOrReplaceChild("bone6",
                CubeListBuilder.create(), PartPose.offset(24.0F, 12.0F, -24.0F));

        bone6.addOrReplaceChild("cube_r7", CubeListBuilder.create()
                        .texOffs(156, 349).addBox(-8.0F, -68.0F, -8.0F, 16.0F, 53.0F, 16.0F, new CubeDeformation(0.0F))
                        .texOffs(400, 183).addBox(-8.0F, -15.0F, -8.0F, 16.0F, 11.0F, 16.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 286).addBox(-8.0F, -54.0F, -11.0F, 20.0F, 42.0F, 22.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 12.0F, 0.0F, 0.0F, 0.7854F, 0.0F));

        PartDefinition bone5 = bone.addOrReplaceChild("bone5",
                CubeListBuilder.create(), PartPose.offset(-24.0F, 12.0F, -24.0F));

        bone5.addOrReplaceChild("cube_r8", CubeListBuilder.create()
                        .texOffs(396, 333).addBox(-8.0F, -68.0F, -8.0F, 16.0F, 52.0F, 16.0F, new CubeDeformation(0.0F))
                        .texOffs(384, 64).addBox(-8.0F, -16.0F, -8.0F, 16.0F, 12.0F, 16.0F, new CubeDeformation(0.0F))
                        .texOffs(324, 271).addBox(-11.0F, -54.0F, -12.0F, 22.0F, 42.0F, 20.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 12.0F, 0.0F, 0.0F, 0.7854F, 0.0F));

        return LayerDefinition.create(meshdefinition, 512, 512);
    }

    /** 火箭没有可动部件，不需要动画。 */
    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer,
                               int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        // 1.20.1 的 ModelPart.render 只有 4 个参数（颜色/RGBA 参数是更高版本才加的），
        // 所以这里不传 red/green/blue/alpha。
        this.bone7.render(poseStack, vertexConsumer, packedLight, packedOverlay);
        this.bone2.render(poseStack, vertexConsumer, packedLight, packedOverlay);
        this.bone.render(poseStack, vertexConsumer, packedLight, packedOverlay);
    }
}
