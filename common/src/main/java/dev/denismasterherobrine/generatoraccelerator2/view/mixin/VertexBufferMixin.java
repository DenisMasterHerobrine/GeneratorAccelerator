package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.denismasterherobrine.generatoraccelerator2.view.SectionBuffers;
import dev.denismasterherobrine.generatoraccelerator2.view.VramStats;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts a buffer's uploads and their bytes, and empties its storage ({@link SectionBuffers}). */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin implements SectionBuffers.Buffer {
    @Shadow private int vertexBufferId;
    @Shadow private int indexBufferId;
    @Shadow private int indexCount;
    @Shadow @Nullable private RenderSystem.AutoStorageIndexBuffer sequentialIndices;

    @Shadow
    public abstract void bind();

    @Unique
    private long ga2$uploads;
    /** Bytes of the vertex storage. */
    @Unique
    private long ga2$vertexBytes;
    /** Bytes of the index storage. */
    @Unique
    private long ga2$indexBytes;

    /** Sets the bytes of the vertex storage and keeps {@link VramStats#HELD} in step. */
    @Unique
    private void ga2$setVertexBytes(long bytes) {
        VramStats.HELD.add(bytes - this.ga2$vertexBytes);
        this.ga2$vertexBytes = bytes;
    }

    /** Sets the bytes of the index storage and keeps {@link VramStats#HELD} in step. */
    @Unique
    private void ga2$setIndexBytes(long bytes) {
        VramStats.HELD.add(bytes - this.ga2$indexBytes);
        this.ga2$indexBytes = bytes;
    }

    @Inject(method = "upload", at = @At("HEAD"))
    private void ga2$countUpload(MeshData meshData, CallbackInfo ci) {
        this.ga2$uploads++;
    }

    @Inject(method = "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V", at = @At("HEAD"))
    private void ga2$countIndexUpload(ByteBufferBuilder.Result result, CallbackInfo ci) {
        this.ga2$uploads++;
    }

    /** Every new storage of the buffer, with its size. */
    @WrapOperation(method = { "uploadVertexBuffer",
        "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/MeshData$DrawState;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/systems/RenderSystem$AutoStorageIndexBuffer;",
        "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V" },
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;glBufferData(ILjava/nio/ByteBuffer;I)V"))
    private void ga2$store(int target, ByteBuffer data, int usage, Operation<Void> original) {
        long size = data.remaining();
        original.call(target, data, usage);
        if (target == GL15.GL_ARRAY_BUFFER) {
            this.ga2$setVertexBytes(size);
        } else {
            this.ga2$setIndexBytes(size);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void ga2$closed(CallbackInfo ci) {
        this.ga2$setVertexBytes(0);
        this.ga2$setIndexBytes(0);
    }

    @Override
    public long ga2$uploads() {
        return this.ga2$uploads;
    }

    @Override
    public long ga2$free() {
        long bytes = this.ga2$vertexBytes + this.ga2$indexBytes;
        if (bytes == 0 || this.vertexBufferId < 0) {
            return 0;
        }
        RenderSystem.assertOnRenderThread();
        // The element array binding belongs to the vertex array. It is bound first.
        this.bind();
        if (this.ga2$vertexBytes > 0) {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vertexBufferId);
            GlStateManager._glBufferData(GL15.GL_ARRAY_BUFFER, 0L, GL15.GL_STATIC_DRAW);
        }
        if (this.ga2$indexBytes > 0) {
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.indexBufferId);
            GlStateManager._glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0L, GL15.GL_STATIC_DRAW);
            // The vertex array now points at the emptied index buffer. The next upload binds the shared quad indices again.
            this.sequentialIndices = null;
        }
        VertexBuffer.unbind();
        this.indexCount = 0;
        this.ga2$setVertexBytes(0);
        this.ga2$setIndexBytes(0);
        return bytes;
    }
}
