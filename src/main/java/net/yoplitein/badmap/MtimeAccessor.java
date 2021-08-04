package net.yoplitein.badmap;

import net.minecraft.world.chunk.Chunk;

public interface MtimeAccessor extends Chunk
{
	public long getMtime();
	public void setMtime(long mtime);
}
