package net.yoplitein.badmap;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import net.minecraft.block.MapColor;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus.ChunkType;

public class RenderJob
{
	final File bmapDir;
	final MinecraftServer server;
	final ServerWorld world;
	final ServerChunkManager chunkManager;
	
	public RenderJob(File bmapDir, MinecraftServer server)
	{
		this.bmapDir = bmapDir;
		this.server = server;
		this.world = server.getOverworld();
		this.chunkManager = this.world.getChunkManager();
	}
	
	public void fullRender()
	{
		final var tileDir = bmapDir.toPath().resolve("tiles");
		tileDir.toFile().mkdir();
		
		BadMap.THREADPOOL.execute(() -> {
			final var start = System.currentTimeMillis(); // FIXME: debugging
			final var chunks = discoverChunks(Arrays.asList(world.getSpawnPos()));
			final var totalChunks = chunks.size();
			final var chunksWritten = new AtomicInteger();
			chunksWritten.set(0);
			
			chunks.forEach(coord -> BadMap.THREADPOOL.execute(() -> {
				server.submit(() -> chunkManager.addTicket(ChunkTicketType.FORCED, coord, 0, coord)).join();
				final var chunk = server.submit(() -> world.getChunk(coord.x, coord.z)).join(); // intentionally wait for next tick, should be loaded now
				
				final var img = renderChunk(chunk);
				final var outFile = tileDir.resolve(tileFilename(coord)).toFile();
				writePNG(outFile, img);
				
				final var nchunks = chunksWritten.incrementAndGet();
				if(nchunks % 10 == 0) BadMap.LOGGER.info("wrote {} / {} chunks", nchunks, totalChunks);
				
				server.submit(() -> chunkManager.removeTicket(ChunkTicketType.FORCED, coord, 0, coord));
			}));
			
			final var end = System.currentTimeMillis(); // FIXME: debugging
			BadMap.LOGGER.info("wrote test tiles in {} ms", end - start);
		});
	}
	
	public Set<ChunkPos> discoverChunks(List<BlockPos> seeds)
	{
		final var searchRadius = 4;
		final var anvil = chunkManager.threadedAnvilChunkStorage;
		final var visited = new HashMap<ChunkPos, NbtCompound>();
		final var queue = new LinkedList<ChunkPos>();
		ChunkPos coord;
		
		seeds.forEach(pos -> queue.add(new ChunkPos(pos)));
		
		while((coord = queue.poll()) != null)
		{
			if(visited.containsKey(coord)) continue;
			
			NbtCompound chunkNBT;
			try
			{
				chunkNBT = anvil.getNbt(coord);
			}
			catch(Exception err)
			{
				chunkNBT = null;
			}
			
			if(ChunkSerializer.getChunkType(chunkNBT) != ChunkType.LEVELCHUNK) chunkNBT = null;
			
			visited.put(coord, chunkNBT);
			if(chunkNBT == null) continue;
			
			for(int dx = -searchRadius; dx < searchRadius + 1; dx++)
				for(int dz = -searchRadius; dz < searchRadius + 1; dz++)
				{
					final var otherCoord = new ChunkPos(coord.x + dx, coord.z + dz);
					if(dx == 0 && dz == 0 || visited.containsKey(otherCoord)) continue;
					queue.add(otherCoord);
				}
		}
		
		visited.entrySet().removeIf(entry -> entry.getValue() == null);
		
		return visited.keySet();
	}
	
	private BufferedImage renderChunk(Chunk chunk)
	{
		final var chunkPos = chunk.getPos();
		final var heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		final var random = new Random(chunkPos.x + chunkPos.z);
		final var blockPos = new BlockPos.Mutable();
		final var img = new BufferedImage(16, 16, BufferedImage.TYPE_4BYTE_ABGR);
		
		for(int x = 0; x < 16; x++)
			for(int z = 0; z < 16; z++)
			{
				var y = heightmap.get(x, z);
				MapColor color = MapColor.CLEAR;
				int waterDepth = 0;
				boolean isWater = false;
				
				blockPos.set(chunkPos.getStartX() + x, y, chunkPos.getStartZ() + z);
				
				while(y > chunk.getBottomY())
				{
					blockPos.setY(y);
					final var state = chunk.getBlockState(blockPos);
					color = state.getMapColor(world, blockPos);
					
					if(color != MapColor.CLEAR)
					{
						isWater = !state.getFluidState().isEmpty();
						break;
					}
					
					y--;
				}
				
				while(isWater && y > chunk.getBottomY())
				{
					y--;
					blockPos.setY(y);
					if(chunk.getBlockState(blockPos).getFluidState().isEmpty()) break;
					waterDepth += 1;
				}
				
				int shade;
				if(isWater)
				{
					final var val = MathHelper.floor(MathHelper.clamp(waterDepth / 5.0, 0.0, 3.0));
					final var fract = MathHelper.fractionalPart(val);
					
					shade = fract >= 0.5 ? MathHelper.ceil(val) : MathHelper.floor(val);
				}
				else
					shade = random.nextInt(3);
				
				img.setRGB(x, z, color == MapColor.CLEAR ? 0 : getARGB(color, shade));
			}
		
		return img;
	}
	
	private static String tileFilename(ChunkPos pos)
	{
		return String.format("%d_%d.png", pos.x, pos.z);
	}
	
	private static void writePNG(File file, BufferedImage img)
	{
		try
		{
			ImageIO.write(img, "png", file);
		}
		catch(Exception e)
		{
			throw new RuntimeException("failed to save image", e);
		}
	}
	
	private static int getARGB(MapColor color, int shade)
	{
		final var val = color.getRenderColor(shade);
		
		return
			0xFF000000 |
			(val & 0xFF0000) >> 16 |
			(val & 0x00FF00) |
			(val & 0x0000FF) << 16
		;
	}
}
