package net.yoplitein.badmap;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;

import org.apache.commons.io.FileUtils;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

public class ModConfig
{
	private static final Gson gson = new GsonBuilder()
		.setLenient()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.registerTypeHierarchyAdapter(Path.class, new PathSerializer())
		.create()
	;
	
	@SerializedName("Number of worker threads. <= 0 uses one less than total cores")
	public int maxWorkerThreads;
	
	@SerializedName("Priority of worker threads. Recommend < 5 to keep gameplay smooth. Must be between " + Thread.MIN_PRIORITY + " and " + Thread.MAX_PRIORITY)
	public int workerThreadPriority;
	
	@SerializedName("Number of regions to render in parallel, if possible. <= 0 chooses by available memory")
	public int maxParallelRegions;
	
	@SerializedName("Path to cache directory, for storing things like discovery cache, manual markers, etc. Probably should not be web readable.")
	public Path bmapDir;
	
	@SerializedName("Path to tiles output directory. Should be web-readable. Defaults to tiles subdir of cache dir.")
	public Path tileDir;
	
	@SerializedName("List of block positions to begin chunk discovery from. If empty, will default to world spawnpoint.")
	public List<BlockPos> discoverySeeds;
	
	public ModConfig() {}
	
	public static ModConfig loadConfig(MinecraftServer server)
	{
		final var configDir = FabricLoader.getInstance().getConfigDir();
		final var configFile = configDir.resolve("bmap.json").toFile();
		final var exists = configFile.exists();
		
		if(exists)
		{
			try
			{
				final var config =  readConfig(configFile);
				validateConfig(config);
				return config;
			}
			catch(Exception err) { BadMap.LOGGER.error("failed to load configuration, falling back to default", err); }
		}
		
		BadMap.LOGGER.warn("loading default configuration");
		final var defaultCfg = getDefaultConfig(server.getRunDirectory().toPath());
		
		if(!exists)
		{
			BadMap.LOGGER.info("saving default configuration");
			try { defaultCfg.saveConfig(configFile); }
			catch(Exception err) { BadMap.LOGGER.error("failed to write default configuration", err); }
		}
		
		return defaultCfg;
	}
	
	private static void validateConfig(ModConfig config) throws Exception
	{
		final var maxRecommended = Runtime.getRuntime().availableProcessors() - 1;
		if(config.maxWorkerThreads > maxRecommended)
			BadMap.LOGGER.warn("Exceeding {} worker threads may impact game performance", maxRecommended);
		
		if(config.workerThreadPriority < Thread.MIN_PRIORITY || config.workerThreadPriority > Thread.MAX_PRIORITY)
			throw new IllegalArgumentException("worker thread priority must be between %d and %d".formatted(Thread.MIN_PRIORITY, Thread.MAX_PRIORITY));
		if(config.workerThreadPriority > Thread.NORM_PRIORITY)
			BadMap.LOGGER.warn("Requested worker thread priority of {} may severely impact game performance", config.workerThreadPriority);
		
		final var bmapDir = config.bmapDir.toFile();
		if(!bmapDir.exists() && !bmapDir.mkdirs())
			throw new IOException("Cache directory `%s` cannot be created".formatted(bmapDir.getPath()));
		if(!bmapDir.canWrite())
			throw new IOException("Cache directory `%s` cannot be written to".formatted(bmapDir.getPath()));
		
		final var tileDir = config.tileDir.toFile();
		if(!tileDir.exists() && !tileDir.mkdirs())
			throw new IOException("Tile directory `%s` cannot be created".formatted(tileDir.getPath()));
		if(!tileDir.canWrite())
			throw new IOException("Tile directory `%s` cannot be written to".formatted(tileDir.getPath()));
	}
	
	private static ModConfig getDefaultConfig(Path serverDir)
	{
		final var self = new ModConfig();
		self.maxWorkerThreads = -1;
		self.workerThreadPriority = 3;
		self.maxParallelRegions = 2;
		self.bmapDir = serverDir.resolve("bmap");
		self.tileDir = self.bmapDir.resolve("tiles");
		self.discoverySeeds = Collections.emptyList();
		
		return self;
	}
	
	private static ModConfig readConfig(File file) throws IOException
	{
		final var json = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
		return gson.fromJson(json, ModConfig.class);
	}
	
	public void saveConfig(File file) throws IOException
	{
		final var json = gson.toJson(this);
		FileUtils.writeStringToFile(file, json, StandardCharsets.UTF_8);
	}
	
	private static class PathSerializer implements JsonDeserializer<Path>, JsonSerializer<Path>
    {
        @Override
        public Path deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) throws JsonParseException
        {
            return Paths.get(json.getAsString());
        }
        
        @Override
        public JsonElement serialize(Path path, Type type, JsonSerializationContext ctx)
        {
            return new JsonPrimitive(path.toString());
        }
    }
}
