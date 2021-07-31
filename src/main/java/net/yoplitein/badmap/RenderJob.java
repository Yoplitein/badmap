package net.yoplitein.badmap;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.util.NbtType;
import net.minecraft.block.MapColor;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ChunkStatus.ChunkType;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.minecraft.world.chunk.WorldChunk;
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
			final var populated = Utils.logPerf(
				() -> discoverChunks(Arrays.asList(world.getSpawnPos())),
				(log, res) -> log.call("found {} chunks", res.size())
			); // FIXME: this needs to be cached
			final var regions = Utils.logPerf(
				() -> groupRegions(populated),
				(log, res) -> log.call("grouped {} chunks into {} regions", populated.size(), res.size())
			);
			
			if(!force)
			{
				final int before = regions.size();
				regions.removeIf(set -> !isRegionOutdated(tileDir, set));
				BadMap.LOGGER.info("skipping {} up-to-date regions", before - regions.size());
			}
			
			// FIXME: use futures to free up threadpool
			Utils.logPerf(
				() -> {
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
					
					return null;
				},
				(log, res) -> log.call("rendered {} regions", regions.size())
			);
		});
	}
	
	private Collection<ChunkInfo> discoverChunks(List<BlockPos> seeds)
	{
		final var searchRadius = 4;
		final var anvil = chunkManager.threadedAnvilChunkStorage;
		final var visited = new HashMap<ChunkPos, ChunkInfo>(1 << 14);
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
				visited.put(coord, new ChunkInfo(coord, mtime, chunkNBT));
			}
			
			for(int dx = -searchRadius; dx < searchRadius + 1; dx++)
				for(int dz = -searchRadius; dz < searchRadius + 1; dz++)
				{
					final var otherCoord = new ChunkPos(coord.x + dx, coord.z + dz);
					if(dx == 0 && dz == 0 || visited.containsKey(otherCoord)) continue;
					queue.add(otherCoord);
				}
		}
		
		// TODO: query the nulls against world in case the chunk was recently generated without saving
		
		visited.entrySet().removeIf(entry -> entry.getValue() == null);
		
		return visited.values();
	}
	
	private static List<RegionSet> groupRegions(Collection<ChunkInfo> populated)
	{
		final var regions = new HashMap<RegionPos, List<ChunkInfo>>(32);
		
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
		final var img = existingImg == null ? new BufferedImage(512, 512, BufferedImage.TYPE_4BYTE_ABGR) : existingImg;
		
		final var allChunks = parseChunks(set);
		final var chunks = allChunks
			.entrySet()
			.stream()
			.collect(Collectors.toMap(pair -> pair.getKey(), pair -> {
				final var pos = pair.getKey();
				final var north = new ChunkPos(pos.x, pos.z - 1);
				return new ChunkPair(pair.getValue(), allChunks.getOrDefault(north, null));
			}))
		;
		
		if(existingImg != null)
		{
			final var before = chunks.size();
			chunks.entrySet().removeIf(pair -> ((MtimeAccessor)pair.getValue().main).getMtime() < imageMtime);
			BadMap.LOGGER.info("reusing renders for {} up to date chunks", before - chunks.size());
		}
		
		Utils.logPerf(
			() -> { chunks.values().forEach(pair -> renderChunk(img, regionPos, pair.main, pair.toNorth)); return null; },
			(log, res) -> log.call("rendered region ({} chunks)", chunks.size())
		);
		
		return img;
	}
	
	private Map<ChunkPos, Chunk> parseChunks(RegionSet set)
	{
		final var result = new HashMap<ChunkPos, Chunk>(set.populatedChunks.size(), 1f);
		final var alreadyLoaded = server.submit(() ->
			set
				.populatedChunks
				.stream()
				.map(info -> (WorldChunk)world.getChunk(info.pos.x, info.pos.z, ChunkStatus.FULL, false))
				.filter(chunk -> chunk != null)
		).join();
		
		alreadyLoaded.forEach(chunk -> result.put(chunk.getPos(), chunk));
		
		final var structureManager = world.getStructureManager();
		final var poiStorage = world.getPointOfInterestStorage();
		
		// TODO: this can be done in parallel, probably
		for(var info: set.populatedChunks)
		{
			final var pos = info.pos;
			if(result.containsKey(pos)) continue;
			
			final var chunk = (ReadOnlyChunk)ChunkSerializer.deserialize(world, structureManager, poiStorage, pos, info.nbt);
			result.put(pos, chunk.getWrappedChunk());
		}
		
		return result;
	}
	
	private void renderChunk(BufferedImage regionImage, RegionPos regionPos, Chunk chunk, @Nullable Chunk toNorth)
	{
		final var chunkPos = chunk.getPos();
		final var heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		final var toNorthHeightmap = toNorth == null ? null : toNorth.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		
		final var posInRegion = regionPos.chunkPosInRegion(chunkPos);
		final var offsetX = 16 * (posInRegion.x < 0 ? (32 - Math.abs(posInRegion.x)) : posInRegion.x);
		final var offsetZ = 16 * (posInRegion.z < 0 ? (32 - Math.abs(posInRegion.z)) : posInRegion.z);
		
		final var blockPos = new BlockPos.Mutable();
		for(int x = 0; x < 16; x++)
		{
			// tracks prior topmost block, controlling terrain shading
			// for chunks not at the top of the region, we use the real value
			int prevHeight = toNorth == null ? world.getBottomY() : toNorthHeightmap.get(x, 15) - 1;
			
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
	
	static record ChunkPair(Chunk main, @Nullable Chunk toNorth) {}
	
	static record ChunkInfo(ChunkPos pos, long mtime, NbtCompound nbt) {}
	static record RegionSet(RegionPos pos, List<ChunkInfo> populatedChunks) {}
}
