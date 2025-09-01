package net.hellomouse.alexscavesenriched.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class RailgunShockwaveParticle extends HugeExplosionParticle {
    private final float lookX, lookY, lookZ;

    protected RailgunShockwaveParticle(ClientLevel arg, double x, double y, double z, double lookX, double lookY, double lookZ, SpriteSet arg2) {
        super(arg, x, y, z, 1F, arg2);
        this.lifetime = 8;
        this.quadSize = 0.6F; // 1.5
        this.setSpriteFromAge(arg2);
        this.lookX = (float)lookX;
        this.lookY = (float)lookY;
        this.lookZ = (float)lookZ;
    }

    @Override
    public void render(VertexConsumer buffer, Camera renderInfo, float partialTicks) {
        Vec3 vec3 = renderInfo.getPosition();
        float deltaX = (float)(Mth.lerp(partialTicks, this.xo, this.x) - vec3.x());
        float deltaY = (float)(Mth.lerp(partialTicks, this.yo, this.y) - vec3.y());
        float deltaZ = (float)(Mth.lerp(partialTicks, this.zo, this.z) - vec3.z());
        Quaternionf quaternionf = new Quaternionf().rotateTo(new Vector3f(0, 0, 1), new Vector3f(lookX, lookY, lookZ));

        Vector3f[] vector3fs = new Vector3f[]{new Vector3f(-1.0F, -1.0F, 0.0F), new Vector3f(-1.0F, 1.0F, 0.0F), new Vector3f(1.0F, 1.0F, 0.0F), new Vector3f(1.0F, -1.0F, 0.0F)};
        float i = this.getQuadSize(partialTicks);
        for(int j = 0; j < 4; ++j) {
            Vector3f vector3f = vector3fs[j];
            vector3f.rotate(quaternionf);
            vector3f.mul(i);
            vector3f.add(deltaX, deltaY, deltaZ);
        }

        float k = this.getU0();
        float l = this.getU1();
        float m = this.getV0();
        float n = this.getV1();
        int o = this.getLightColor(partialTicks);
        buffer.vertex(vector3fs[0].x(), vector3fs[0].y(), vector3fs[0].z()).uv(l, n).color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(o).endVertex();
        buffer.vertex(vector3fs[1].x(), vector3fs[1].y(), vector3fs[1].z()).uv(l, m).color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(o).endVertex();
        buffer.vertex(vector3fs[2].x(), vector3fs[2].y(), vector3fs[2].z()).uv(k, m).color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(o).endVertex();
        buffer.vertex(vector3fs[3].x(), vector3fs[3].y(), vector3fs[3].z()).uv(k, n).color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(o).endVertex();
    }

    @OnlyIn(Dist.CLIENT)
    public static class Factory implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet spriteProvider;

        public Factory(SpriteSet spriteProvider) {
            this.spriteProvider = spriteProvider;
        }

        public Particle createParticle(SimpleParticleType arg, ClientLevel arg2, double x, double y, double z, double lookX, double lookY, double lookZ) {
            return new RailgunShockwaveParticle(arg2, x, y, z, lookX, lookY, lookZ, this.spriteProvider);
        }
    }
}
