package net.yoplitein.badmap;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.layout.PatternLayout;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.DefaultPosArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Texts;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public class BadMap implements DedicatedServerModInitializer
{
	public static final Logger LOGGER = LogManager.getLogger();
	private static final LoggerContext logContext = (LoggerContext)LogManager.getContext(false);
	
	public static ModConfig CONFIG;
	
	private static final ThreadGroup workers = new ThreadGroup("BadMap render pool");
	private static final Utils.Cell<Integer> workerCounter = new Utils.Cell<>(0);
	public static final ThreadPoolExecutor THREADPOOL = new ThreadPoolExecutor(
		0, 1, // start with 0 core size to ensure no threads are spawned until config is loaded
		0, TimeUnit.DAYS,
		new LinkedBlockingQueue<Runnable>(),
		runnable -> {
			final var thread = new Thread(workers, runnable, "BM-pool-%d".formatted(workerCounter.val++));
			thread.setPriority(CONFIG.workerThreadPriority);
			return thread;
		}
	);
	
	@Override
	public void onInitializeServer()
	{
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
			final var root = dispatcher.register(
				CommandManager.literal("badmap")
					.requires(executor -> executor.hasPermissionLevel(4))
					.then(
						CommandManager.literal("test")
							.executes(BadMap::cmdTest)
					)
					.then(
						CommandManager.literal("config")
							.then(
								CommandManager.literal("reload")
									.executes(BadMap::cmdCfgReload)
							)
							.then(
								CommandManager.literal("seeds")
									.then(
										CommandManager.literal("list")
											.executes(BadMap::cmdSeedsList)
									)
									.then(
										CommandManager.literal("add")
											.then(
												CommandManager.argument("pos", BlockPosArgumentType.blockPos())
													.executes(BadMap::cmdSeedsAdd)
											)
											.executes(BadMap::cmdSeedsAdd)
									)
							)
					)
					.then(
						CommandManager.literal("render")
							.then(
								CommandManager.literal("force")
									.executes(ctx -> cmdRender(ctx, false))
							)
							.executes(ctx -> cmdRender(ctx, true))
					)
			);
			dispatcher.register(
				CommandManager.literal("bm")
					.redirect(root)
			);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			CONFIG = ModConfig.loadConfig();
			onConfigReloaded(server);
			
			setupChatAppender(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("shutting down render worker pool");
			THREADPOOL.shutdown();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if(THREADPOOL.isTerminated()) return;
			
			LOGGER.warn("waiting for render pool to terminate");
			try
			{
				if(THREADPOOL.awaitTermination(10, TimeUnit.SECONDS)) return;
			}
			catch(Exception err)
			{
				LOGGER.error("awaitTermination interrupted", err);
			}
			
			final var dropped = THREADPOOL.shutdownNow();
			LOGGER.error("render pool did not terminate after 10 seconds, forcefully shutdown with {} tasks remaining", dropped.size());
		});
		
		// FIXME: debugging
		final var tickCounter = new AtomicInteger(0);
		final var lastTps = new AtomicLong(System.currentTimeMillis());
		/* ServerTickEvents.START_SERVER_TICK.register(server -> {
			LOGGER.trace("server tick start");
		}); */
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// LOGGER.trace("server tick end");
			final var now = System.currentTimeMillis();
			final var ticks = tickCounter.incrementAndGet();
			
			if(now - lastTps.get() >= 6000)
			{
				lastTps.set(now);
				tickCounter.set(0);
				LOGGER.trace("server running at {} tps", ticks / 6);
			}
		});
	}
	
	private static void setupChatAppender(MinecraftServer server)
	{
		final var cfg = logContext.getConfiguration();
		final var chatAppender = ChatAppender.createAppender(server, "BMChatAppender", PatternLayout.createDefaultLayout(cfg));
		final var loggerCfg = cfg.getLoggerConfig("net.yoplitein.badmap.BadMap");
		
		chatAppender.start();
		cfg.addAppender(chatAppender);
		loggerCfg.addAppender(chatAppender, null, null);
		cfg.addLogger("BMChatAppender", loggerCfg);
		logContext.updateLoggers();
	}
	
	private static void onConfigReloaded(MinecraftServer server)
	{
		var workerThreads = CONFIG.maxWorkerThreads;
		if(workerThreads <= 0) workerThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
		LOGGER.info("spawning {} worker threads", workerThreads);
		THREADPOOL.setMaximumPoolSize(workerThreads);
		// without this, new threads are never spun up due to dumb executor semantics around the workqueue
		THREADPOOL.setCorePoolSize(workerThreads);
		
		LOGGER.info("setting priority of existing threads to {}", CONFIG.workerThreadPriority);
		var threads = new Thread[workers.activeCount()];
		workers.enumerate(threads);
		for(var thread: threads) thread.setPriority(CONFIG.workerThreadPriority);
		
		LOGGER.info("config successfully reloaded");
	}
	
	private static int cmdTest(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException
	{
		/* THREADPOOL.execute(() -> {
			final var server = ctx.getSource().getMinecraftServer();
			final var world = server.getOverworld();
			final var job = new RenderJob(null, server);
			
			final var start = System.currentTimeMillis();
			final var chunks = job.discoverChunks(Arrays.asList(world.getSpawnPos()));
			final var end = System.currentTimeMillis();
			LOGGER.info("found {} chunks in {} ms", chunks.size(), end - start);
		}); */
		
		return 1;
	}
	
	private static int cmdCfgReload(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException
	{
		final var src = ctx.getSource();
		final var server = src.getMinecraftServer();
		
		CONFIG = ModConfig.loadConfig();
		onConfigReloaded(server);
		
		return 1;
	}
	
	private static int cmdSeedsList(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException
	{
		final var src = ctx.getSource();
		final var server = src.getMinecraftServer();
		
		final var seeds = CONFIG.discoverySeeds;
		final var customSeeds = !seeds.isEmpty();
		final var texts = Utils.getLocationTexts(
			customSeeds ? seeds : List.of(server.getOverworld().getSpawnPos()),
			true
		);
		if(customSeeds)
			src.sendFeedback(Texts.join(texts, new LiteralText(", ")), false);
		else
		{
			final var text = LiteralText.EMPTY.copy().append(texts.get(0));
			text.append(
				new LiteralText(" (default seed)")
					.formatted(Formatting.GRAY, Formatting.ITALIC)
			);
			src.sendFeedback(text, false);
		}
		
		return 1;
	}
	
	private static int cmdSeedsAdd(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException
	{
		final var src = ctx.getSource();
		
		BlockPos pos;
		try { pos = new BlockPos(ctx.getArgument("pos", DefaultPosArgument.class).toAbsolutePos(src)); }
		catch(IllegalArgumentException err) { pos = src.getPlayer().getBlockPos(); }
		
		final var locText = Utils.getLocationTexts(List.of(pos), true).get(0);
		final var text = new LiteralText("Adding new seed at ").append(locText);
		
		src.sendFeedback(text, false);
		CONFIG.discoverySeeds.add(pos);
		try { CONFIG.saveConfig(); }
		catch(Exception err) { LOGGER.error("failed to save config after adding new seed", err); }
		
		return 1;
	}
	
	private static int cmdRender(CommandContext<ServerCommandSource> ctx, boolean incremental) throws CommandSyntaxException
	{
		final var src = ctx.getSource();
		final var server = src.getMinecraftServer();
		
		final var job = new RenderJob(server);
		job.render(incremental);
		
		return 1;
	}
}
