package net.yoplitein.badmap;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.util.NbtType;
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
import net.yoplitein.badmap.Utils.RegionPos;

public class RenderJob
{
	static final int[] ORDERED_SHADES = {2, 1, 0, 3}; // lightest to darkest
	final Path bmapDir;
	final Path tileDir;
	final MinecraftServer server;
	final ServerWorld world;
	final ServerChunkManager chunkManager;
	
	public RenderJob(Path bmapDir, MinecraftServer server)
	{
		this.bmapDir = bmapDir;
		this.tileDir = bmapDir.resolve("tiles"); // TODO: config
		this.server = server;
		this.world = server.getOverworld();
		this.chunkManager = this.world.getChunkManager();
	}
	
	public void render(boolean force)
	{
		tileDir.toFile().mkdir();
		
		BadMap.THREADPOOL.execute(() -> {
			final var start = System.currentTimeMillis(); // FIXME: debugging
			final var populated = discoverChunks(Arrays.asList(world.getSpawnPos())); // FIXME: this needs to be cached
			final var regions = groupRegions(populated);
			
			if(!force)
			{
				final int before = regions.size();
				regions.removeIf(set -> !isRegionOutdated(tileDir, set));
				BadMap.LOGGER.info("skipping {} up-to-date regions", before - regions.size());
			}
			
			// FIXME: use futures to free up threadpool
			for(var set: regions)
			{
				final var outFile = tileDir.resolve(Utils.tileFilename(set.pos)).toFile();
				
				BufferedImage existing = null;
				long mtime = 0;
				if(!force && outFile.exists())
				{
					existing = Utils.readPNG(outFile);
					mtime = outFile.lastModified();
				}
				
				final var img = renderRegion(existing, mtime, set);
				Utils.writePNG(outFile, img);
			}
			
			final var end = System.currentTimeMillis();
			BadMap.LOGGER.info("rendered {} regions in {} ms", regions.size(), end - start);
		});
	}
	
	private List<ChunkInfo> discoverChunks(List<BlockPos> seeds)
	{
		final var searchRadius = 4;
		final var anvil = chunkManager.threadedAnvilChunkStorage;
		final var visited = new HashMap<ChunkPos, Long>();
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
			
			if(chunkNBT == null)
			{
				visited.put(coord, null);
				continue;
			}
			else
			{
				final var level = chunkNBT.getCompound("Level");
				final var mtime = level.contains("bm__mtime", NbtType.LONG) ? level.getLong("bm__mtime") : 0;
				visited.put(coord, mtime);
			}
			
			for(int dx = -searchRadius; dx < searchRadius + 1; dx++)
				for(int dz = -searchRadius; dz < searchRadius + 1; dz++)
				{
					final var otherCoord = new ChunkPos(coord.x + dx, coord.z + dz);
					if(dx == 0 && dz == 0 || visited.containsKey(otherCoord)) continue;
					queue.add(otherCoord);
				}
		}
		
		visited.entrySet().removeIf(entry -> entry.getValue() == null);
		
		return visited
			.entrySet()
			.stream()
			.map(pair -> new ChunkInfo(pair.getKey(), pair.getValue()))
			.collect(Collectors.toList())
		;
	}
	
	private static List<RegionSet> groupRegions(List<ChunkInfo> populated)
	{
		final var regions = new HashMap<RegionPos, List<ChunkInfo>>();
		
		for(var info: populated)
		{
			final var regionPos = RegionPos.of(info.pos);
			var list = regions.get(regionPos);
			
			if(list == null)
			{
				list = new ArrayList<>();
				regions.put(regionPos, list);
			}
			
			list.add(info);
		}
		
		return regions
			.entrySet()
			.stream()
			.map(entry -> new RegionSet(entry.getKey(), entry.getValue()))
			.collect(Collectors.toList())
		;
	}
	
	private BufferedImage renderRegion(@Nullable BufferedImage existingImg, long imageMtime, RegionSet set)
	{
		final var regionPos = set.pos;
		final var populated = set.populatedChunks;
		final var img = existingImg == null ? new BufferedImage(512, 512, BufferedImage.TYPE_4BYTE_ABGR) : existingImg;
		
		server.submit(() -> populated.forEach(info -> chunkManager.addTicket(ChunkTicketType.FORCED, info.pos, 0, info.pos))).join();
		
		// intentionally wait for next tick, all should be loaded now
		final var chunks = server.submit(() ->
			populated
				.stream()
				.map(info -> world.getChunk(info.pos.x, info.pos.z))
				.collect(Collectors.toList())
		).join();
		
		if(existingImg != null)
		{
			final var before = chunks.size();
			chunks.removeIf(chunk -> ((MtimeAccessor)chunk).getMtime() < imageMtime);
			BadMap.LOGGER.info("reusing renders for {} up to date chunks", before - chunks.size());
		}
		
		final var start = System.currentTimeMillis();
		chunks.forEach(chunk -> renderChunk(img, regionPos, chunk)); // TODO: parallelism
		final var end = System.currentTimeMillis();
		BadMap.LOGGER.info("rendered region in {} ms", end - start);
		
		server.submit(() -> populated.forEach(info -> chunkManager.removeTicket(ChunkTicketType.FORCED, info.pos, 0, info.pos)));
		
		return img;
	}
	
	private void renderChunk(BufferedImage regionImage, RegionPos regionPos, Chunk chunk)
	{
		final var chunkPos = chunk.getPos();
		final var heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		
		final var posInRegion = new ChunkPos(chunkPos.x - 32 * regionPos.x(), chunkPos.z - 32 * regionPos.z());
		final var offsetX = 16 * (posInRegion.x < 0 ? (32 - Math.abs(posInRegion.x)) : posInRegion.x);
		final var offsetZ = 16 * (posInRegion.z < 0 ? (32 - Math.abs(posInRegion.z)) : posInRegion.z);
		
		final var blockPos = new BlockPos.Mutable();
		for(int x = 0; x < 16; x++)
		{
			// tracks prior topmost block, controlling terrain shading
			int prevHeight = world.getTopY(Heightmap.Type.WORLD_SURFACE, chunkPos.getStartX() + x, chunkPos.getStartZ() - 1) - 1;
			
			for(int z = 0; z < 16; z++)
			{
				var y = heightmap.get(x, z);
				MapColor color = MapColor.CLEAR;
				
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
				
				final var waterTop = y; // value of prevHeight for next z
				
				int waterDepth = 0;
				MapColor blendColor = null; // color of submerged block
				for(int maxSearch = 0; isWater && y > chunk.getBottomY() && maxSearch < 15; maxSearch++)
				{
					y--;
					blockPos.setY(y);
					final var state = chunk.getBlockState(blockPos);
					
					if(state.getFluidState().isEmpty())
					{
						blendColor = state.getMapColor(world, blockPos);
						break;
					}
					
					waterDepth += 1;
				}
				
				int shade;
				if(isWater)
					shade = ORDERED_SHADES[Utils.round(MathHelper.clamp(waterDepth / 5.0, 0.0, 3.0))];
				else
				{
					final var delta = prevHeight <= world.getBottomY() ? 0 : waterTop - prevHeight;
					
					// select shade from a (sort-of uniform) height-dependent distribution over [0, 3] with median 2
					// i.e. negative delta gives darker colors, positive gives lighter
					final var val = Utils.round(MathHelper.clamp(1.5 + delta, 0.0, 3.0));
					shade = ORDERED_SHADES[3 - val];
				}
				
				
				int finalColor;
				if(color == MapColor.CLEAR) // set transparent on void
					finalColor = 0;
				else if(blendColor == null) // fast path when not blending
					finalColor = Utils.getMapColor(color, shade).toABGR();
				else
					finalColor = Utils.blendColors(
						Utils.getMapColor(color, shade),
						Utils.getMapColor(blendColor, ORDERED_SHADES[0]), // blend water shade with brightest shade of submerged block
						0.25 // TODO: vary blend strength with water depth, maybe expand maxSearch to 30-60?
					).toABGR();
				
				regionImage.setRGB(offsetX + x, offsetZ + z, finalColor);
				prevHeight = waterTop;
			}
		}
	}
	
	private static boolean isRegionOutdated(Path tileDir, RegionSet set)
	{
		final var pos = set.pos;
		final var tile = tileDir.resolve(Utils.tileFilename(pos)).toFile();
		if(!tile.exists()) return true;
		
		final var newestChunk = set
			.populatedChunks
			.stream()
			.map(info -> info.mtime)
			.max(Long::compare)
			.orElseThrow()
		;
		if(tile.lastModified() < newestChunk) return true;
		
		return false;
	}
	
	static record ChunkInfo(ChunkPos pos, long mtime) {}
	static record RegionSet(RegionPos pos, List<ChunkInfo> populatedChunks) {}
}
