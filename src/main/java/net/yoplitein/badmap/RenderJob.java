package net.yoplitein.badmap;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus.ChunkType;
import net.minecraft.world.chunk.ReadOnlyChunk;
import net.yoplitein.badmap.Utils.RegionPos;

public class RenderJob
{
	static final int[] ORDERED_SHADES = {2, 1, 0, 3}; // map color shades, lightest to darkest
	
	// guard value used when a transparent pixel is rendered (i.e. block column is entirely air)
	// as opposed to transparency in chunks that have yet to be rendered (unwritten pixels are black)
	static final int TRANSPARENCY_SENTINEL = 0xFF00FF;
	
	final MinecraftServer server;
	final ServerWorld world;
	final ServerChunkManager chunkManager;
	
	public RenderJob(MinecraftServer server)
	{
		this.server = server;
		this.world = server.getOverworld();
		this.chunkManager = this.world.getChunkManager();
	}
	
	public void render(boolean incremental)
	{
		BadMap.THREADPOOL.execute(() -> {
			final var benchmark = new Utils.Benchmark();
			
			benchmark.start();
			final var populated = discoverChunks(); // FIXME: this needs to be cached to disk (maybe class too for markers?)
			benchmark.end();
			BadMap.LOGGER.debug("perf: found {} chunks in {}ms", populated.size(), benchmark.msecs());
			
			benchmark.start();
			final var regions = groupRegions(populated);
			benchmark.end();
			BadMap.LOGGER.debug("perf: grouped {} chunks into {} regions in {}ms", populated.size(), regions.size(), benchmark.msecs());
			
			final var numRegionsRendered = new AtomicInteger(0); // how many regions actually had any rendering to do
			final var jobs = regions
				.stream()
				.map(set -> {
					final var outFile = BadMap.CONFIG.tileDir.resolve(Utils.tileFilename(set.pos)).toFile();
					BufferedImage prerendered = null;
					if(incremental && outFile.exists()) prerendered = Utils.readPNG(outFile);
					
					return renderRegion(prerendered, outFile.lastModified(), set, incremental)
						.thenAcceptAsync(img -> {
							if(img != null)
							{
								Utils.writePNG(outFile, img);
								numRegionsRendered.getAndIncrement();
							}
						}, BadMap.THREADPOOL)
					;
				})
			;
			
			benchmark.start();
			final var future = Utils.chainAsync(jobs, BadMap.CONFIG.maxParallelRegions);
			future.exceptionallyAsync(err -> {
				BadMap.LOGGER.error("Main render future completed exceptionally", err);
				return null;
			}, BadMap.THREADPOOL);
			future.thenRunAsync(() -> {
				benchmark.end();
				BadMap.LOGGER.debug("perf: rendered {} (out of {}) regions in {}ms", numRegionsRendered.get(), regions.size(), benchmark.msecs());
				BadMap.LOGGER.info("Render complete");
			}, BadMap.THREADPOOL);
		});
	}
	
	private Collection<ChunkInfo> discoverChunks()
	{
		final var searchRadius = 4;
		final var anvil = chunkManager.threadedAnvilChunkStorage;
		final var visited = new HashMap<ChunkPos, ChunkInfo>(1 << 14);
		final var queue = new LinkedList<ChunkPos>();
		ChunkPos coord;
		
		final var seeds = BadMap.CONFIG.discoverySeeds;
		if(seeds.isEmpty()) queue.add(new ChunkPos(world.getSpawnPos()));
		else seeds.forEach(pos -> queue.add(new ChunkPos(pos)));
		
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
				final var mtime = level.contains("bm__mtime", NbtType.LONG) ? level.getLong("bm__mtime") : System.currentTimeMillis();
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
	
	private CompletableFuture<@Nullable BufferedImage> renderRegion(@Nullable BufferedImage prerendered, long imageMtime, RegionSet set, boolean incremental)
	{
		final var benchmark = new Utils.Benchmark();
		final var regionPos = set.pos;
		
		if(prerendered != null && incremental)
		{
			final var populated = set.populatedChunks;
			final var sizeBefore = populated.size();
			populated.removeIf(info -> !isChunkOutdated(prerendered, imageMtime, regionPos, info));
			final var sizeNow = populated.size();
			
			if(sizeNow > 0 && sizeNow != sizeBefore)
				BadMap.LOGGER.debug("reusing renders for {} (out of {}) up to date chunks", sizeBefore - sizeNow, sizeBefore);
			
			if(sizeNow == 0) return CompletableFuture.completedFuture(null);
		}
		final var imgFuture = new CompletableFuture<BufferedImage>();
		
		final var allChunksFuture = parseChunks(set);
		benchmark.start();
		allChunksFuture.exceptionallyAsync(err -> {
			BadMap.LOGGER.trace("allChunksFuture exception", err);
			imgFuture.completeExceptionally(err);
			return null;
		}, BadMap.THREADPOOL);
		allChunksFuture.thenAcceptAsync(allChunks -> {
			benchmark.end();
			BadMap.LOGGER.debug("perf: parsed {} chunks in {}ms", allChunks.size(), benchmark.msecs());
			
			final var chunks = allChunks
				.entrySet()
				.stream()
				.map(pair -> {
					final var pos = pair.getKey();
					final var north = new ChunkPos(pos.x, pos.z - 1);
					return new ChunkPair(pair.getValue(), allChunks.getOrDefault(north, null));
				})
				.collect(Collectors.toList())
			;
			benchmark.end();
			
			final var img = prerendered != null ? prerendered : new BufferedImage(512, 512, BufferedImage.TYPE_4BYTE_ABGR);
			final var tasks = Utils.workerBatches(chunks)
				.stream()
				.map(batch -> CompletableFuture.runAsync(
					() -> batch.forEach(pair -> renderChunk(img, regionPos, pair.main, pair.toNorth)),
					BadMap.THREADPOOL
				))
				.toArray(CompletableFuture[]::new)
			;
			
			final var chunksFuture = CompletableFuture.allOf(tasks);
			
			benchmark.start();
			chunksFuture.exceptionallyAsync(err -> {
				BadMap.LOGGER.trace("chunksFuture exception", err);
				imgFuture.completeExceptionally(err);
				return null;
			}, BadMap.THREADPOOL);
			chunksFuture.thenRunAsync(() -> {
				benchmark.end();
				BadMap.LOGGER.debug("perf: rendered region ({} chunks) in {}ms", chunks.size(), benchmark.msecs());
				imgFuture.complete(img);
			}, BadMap.THREADPOOL);
		}, BadMap.THREADPOOL);
		
		return imgFuture;
	}
	
	private CompletableFuture<Map<ChunkPos, Chunk>> parseChunks(RegionSet set)
	{
		final var structureManager = world.getStructureManager();
		final var poiStorage = POIStorageFaker.getInstance();
		
		// FIXME: can we efficiently reimplement fetching preloaded chunks?
		
		final var batches = Utils.workerBatches(set.populatedChunks)
			.stream()
			.map(batch -> CompletableFuture.supplyAsync(() -> {
				final var submap = new HashMap<ChunkPos, Chunk>(batch.size(), 1f);
				for(var info: batch)
				{
					final var chunk = (ReadOnlyChunk)ChunkSerializer.deserialize(world, structureManager, poiStorage, info.pos, info.nbt);
					submap.put(info.pos, chunk.getWrappedChunk());
				}
				return submap;
			}, BadMap.THREADPOOL))
			.collect(Collectors.toList())
		;
		
		final var mapFuture = new CompletableFuture<Map<ChunkPos, Chunk>>();
		final var batchesFuture = CompletableFuture.allOf(batches.toArray(CompletableFuture[]::new)); // storing as array directly causes fun of non-reified-generics flavour
		batchesFuture.exceptionallyAsync(err -> {
			BadMap.LOGGER.trace("batchesFuture exception", err);
			mapFuture.completeExceptionally(err); return null;
		}, BadMap.THREADPOOL);
		batchesFuture.thenRunAsync(
			() -> {
				final var combined = new HashMap<ChunkPos, Chunk>(set.populatedChunks.size(), 1f);
				for(var job: batches) combined.putAll(job.getNow(null));
				mapFuture.complete(combined);
			},
			BadMap.THREADPOOL
		);
		return mapFuture;
	}
	
	private void renderChunk(BufferedImage regionImage, RegionPos regionPos, Chunk chunk, @Nullable Chunk toNorth)
	{
		final var chunkPos = chunk.getPos();
		final var pixelOffset = getPixelOffset(regionPos, chunkPos);
		
		final var heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		final var toNorthHeightmap = toNorth == null ? null : toNorth.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		
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
				while(y >= chunk.getBottomY())
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
				if(color == MapColor.CLEAR) // set (tagged) transparent if this block column is entirely air
					finalColor = TRANSPARENCY_SENTINEL;
				else if(blendColor == null) // fast path when not blending
					finalColor = Utils.getMapColor(color, shade).toABGR();
				else
					finalColor = Utils.blendColors(
						Utils.getMapColor(color, shade),
						Utils.getMapColor(blendColor, ORDERED_SHADES[0]), // blend water shade with brightest shade of submerged block
						0.25 // TODO: vary blend strength with water depth, maybe expand maxSearch to 30-60?
					).toABGR();
				
				regionImage.setRGB(pixelOffset.getX() + x, pixelOffset.getY() + z, finalColor);
				prevHeight = waterTop;
			}
		}
	}
	
	private boolean isChunkOutdated(BufferedImage prerendered, long imageMtime, RegionPos regionPos, ChunkInfo info)
	{
		final var pixelOffset = getPixelOffset(regionPos, info.pos);
		
		// if there is transparency at this chunk's origin, it has not been rendered yet.
		// this fixes unrendered chunks being skipped (until a block change) if they were
		// generated just before or during a render, and before the world was flushed to disk.
		// (happens because their mtime is then older than a render where they had not been included)
		final var color = prerendered.getRGB(pixelOffset.getX(), pixelOffset.getY());
		if((color & 0xFF000000) >>> 24 < 0xFF)
			if((color & 0xFFFFFF) != TRANSPARENCY_SENTINEL) // check if chunk *has* been rendered, but it's devoid of blocks
				return true;
		
		return info.mtime >= imageMtime;
	}
	
	private static Vec3i getPixelOffset(RegionPos regionPos, ChunkPos chunkPos)
	{
		final var posInRegion = regionPos.chunkPosInRegion(chunkPos);
		return new Vec3i(16 * posInRegion.x, 16 * posInRegion.z, 0); // no vec2i -_-
	}
	
	static record ChunkPair(Chunk main, @Nullable Chunk toNorth) {}
	
	static record ChunkInfo(ChunkPos pos, long mtime, NbtCompound nbt) {}
	static record RegionSet(RegionPos pos, List<ChunkInfo> populatedChunks) {}
}
