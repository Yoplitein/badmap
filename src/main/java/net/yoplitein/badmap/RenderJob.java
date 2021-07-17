package net.yoplitein.badmap;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
			final var populated = discoverChunks(Arrays.asList(world.getSpawnPos()));
			final var regions = groupRegions(populated);
			
			for(var set: regions)
			{
				final var img = renderRegion(set);
				final var outFile = tileDir.resolve(tileFilename(set.pos)).toFile();
				writePNG(outFile, img);
			}
			
			final var end = System.currentTimeMillis();
			BadMap.LOGGER.info("rendered {} regions in {} ms", regions.size(), end - start);
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
	
	private List<RegionSet> groupRegions(Collection<ChunkPos> populated)
	{
		final var regions = new HashMap<RegionPos, List<ChunkPos>>();
		
		for(var pos: populated)
		{
			final var regionPos = RegionPos.of(pos);
			var list = regions.get(regionPos);
			
			if(list == null)
			{
				list = new ArrayList<>();
				regions.put(regionPos, list);
			}
			
			list.add(pos);
		}
		
		return regions
			.entrySet()
			.stream()
			.map(entry -> new RegionSet(entry.getKey(), entry.getValue()))
			.collect(Collectors.toList())
		;
	}
	
	private BufferedImage renderRegion(RegionSet set)
	{
		final var regionPos = set.pos;
		final var populated = set.populatedChunks;
		final var img = new BufferedImage(512, 512, BufferedImage.TYPE_4BYTE_ABGR);
		
		// FIXME
		server.submit(() -> {
			BadMap.LOGGER.info("force loading chunks");
			for(var chunk: populated)
				chunkManager.addTicket(ChunkTicketType.FORCED, chunk, 0, chunk);
		}).join();
		BadMap.LOGGER.info("forceload done");
		final var chunks = server.submit(() -> {
			BadMap.LOGGER.info("getting chunks");
			// intentionally wait for next tick, all should be loaded now
			return populated
				.stream()
				.map(pos -> world.getChunk(pos.x, pos.z))
			;
		}).join();
		BadMap.LOGGER.info("get done");
		// final var chunk = server.submit(() -> world.getChunk(coord.x, coord.z)).join();
		
		final var start = System.currentTimeMillis();
		// TODO: parallelism
		chunks.forEach(chunk -> renderChunk(img, regionPos, chunk));
		final var end = System.currentTimeMillis();
		BadMap.LOGGER.info("rendered region in {} ms", end - start);
		
		server.submit(() -> {
			for(var chunk: populated)
				chunkManager.removeTicket(ChunkTicketType.FORCED, chunk, 0, chunk);
		});
		
		return img;
	}
	
	private void renderChunk(BufferedImage regionImage, RegionPos regionPos, Chunk chunk)
	{
		final var chunkPos = chunk.getPos();
		final var heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		final var random = new Random(chunkPos.x + chunkPos.z);
		
		// FIXME: this is all probably wrong
		final var posInRegion = new ChunkPos(chunkPos.x - 32 * regionPos.x, chunkPos.z - 32 * regionPos.z);
		final var offsetX = 16 * (posInRegion.x < 0 ? (32 - Math.abs(posInRegion.x)) : posInRegion.x);
		final var offsetZ = 16 * (posInRegion.z < 0 ? (32 - Math.abs(posInRegion.z)) : posInRegion.z);
		
		BadMap.LOGGER.info("rendering chunk {} (region {}, in region {}) with offset {},{}", chunkPos, regionPos, posInRegion, offsetX, offsetZ);
		
		final var blockPos = new BlockPos.Mutable();
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
				
				regionImage.setRGB(offsetX + x, offsetZ + z, color == MapColor.CLEAR ? 0 : getARGB(color, shade));
			}
	}
	
	private static String tileFilename(RegionPos pos)
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
	
	static record RegionPos(int x, int z)
	{
		static RegionPos of(ChunkPos pos)
		{
			return new RegionPos(pos.x >> 5, pos.z >> 5);
		}
	}
	
	static record RegionSet(RegionPos pos, List<ChunkPos> populatedChunks) {}
}
