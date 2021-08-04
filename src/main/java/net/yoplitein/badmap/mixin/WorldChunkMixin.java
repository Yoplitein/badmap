package net.yoplitein.badmap.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import net.yoplitein.badmap.MtimeAccessor;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin implements MtimeAccessor
{
	@Unique
	private long mtime = 0;
	
	public long getMtime()
	{
		return mtime;
	}
	
	public void setMtime(long mtime)
	{
		this.mtime = mtime;
	}
	
	@Inject(method = "setBlockState", at = @At("RETURN"), require = 1)
	public void setBlockState(BlockPos pos, BlockState newState, boolean moved, CallbackInfoReturnable<BlockState> cir)
	{
		final var oldState = cir.getReturnValue();
		if(oldState == null) return; // oldState == newState
		if(oldState.isOf(newState.getBlock())) return; // mere property changes should never change map color
		
		mtime = System.currentTimeMillis();
	}
}
