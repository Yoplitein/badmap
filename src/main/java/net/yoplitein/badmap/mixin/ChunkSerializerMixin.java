package net.yoplitein.badmap.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.throwables.InjectionError;

import net.fabricmc.fabric.api.util.NbtType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.yoplitein.badmap.BadMap;
import net.yoplitein.badmap.MtimeAccessor;

@Mixin(ChunkSerializer.class)
public class ChunkSerializerMixin
{
    @Unique
    private static final Logger LOGGER = LogManager.getLogger();
    
    @Inject(method = "deserialize", at = @At(value = "RETURN", ordinal = 0), require = 1, allow = 1)
    private static void deserialize(ServerWorld world, StructureManager structureManager, PointOfInterestStorage poiStorage, ChunkPos pos, NbtCompound nbt, CallbackInfoReturnable<ProtoChunk> cir)
    {
        final var protoChunk = cir.getReturnValue();
        if(!(protoChunk instanceof ReadOnlyChunk))
        {
            final var err = new InjectionError("deserialize mixin not injected at return of ReadOnlyChunk");
            
            // The target method is called from IO worker threads, which seem to happily swallow any Throwable
            // so, just in case.
            BadMap.LOGGER.error("", err);
            throw err;
        }
        
        final var chunk = ((ReadOnlyChunk)protoChunk).getWrappedChunk();
        final var level = nbt.getCompound("Level");
        
        final var mtime = level.contains("bm__mtime", NbtType.LONG) ? level.getLong("bm__mtime") : System.currentTimeMillis();
        ((MtimeAccessor)chunk).setMtime(mtime);
    }
    
    @Inject(method = "serialize", at = @At(value = "RETURN"), require = 1, allow = 1)
    private static void serialize(ServerWorld world, Chunk chunk, CallbackInfoReturnable<NbtCompound> cir)
    {
        if(!(chunk instanceof WorldChunk)) return;
        
        final var nbt = cir.getReturnValue();
        final var level = nbt.getCompound("Level");
        level.putLong("bm__mtime", ((MtimeAccessor)chunk).getMtime());
    }
}
