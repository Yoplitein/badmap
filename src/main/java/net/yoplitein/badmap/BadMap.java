package net.yoplitein.badmap;

import static net.minecraft.server.command.CommandManager.literal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import net.minecraft.server.command.ServerCommandSource;

public class BadMap implements DedicatedServerModInitializer
{
	public static final Logger LOGGER = LogManager.getLogger();
	private static final LoggerContext logContext = (LoggerContext)LogManager.getContext(false);
	public static final ExecutorService THREADPOOL = Executors.newFixedThreadPool(Math.max(Runtime.getRuntime().availableProcessors() - 1, 1)); // TODO: config?
	
	@Override
	public void onInitializeServer()
	{
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
			dispatcher.register(
				literal("bm") // TODO: add as alias of badmap
					.requires(executor -> executor.hasPermissionLevel(4))
					.then(
						literal("test")
							.executes(BadMap::cmdTest)
					)
					.then(
						literal("render")
							.then(
								literal("force")
									.executes(ctx -> cmdRender(ctx, false))
							)
							.executes(ctx -> cmdRender(ctx, true))
					)
			);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			final var cfg = logContext.getConfiguration();
			final var chatAppender = ChatAppender.createAppender(server, "BMChatAppender", PatternLayout.createDefaultLayout(cfg));
			final var loggerCfg = cfg.getLoggerConfig("net.yoplitein.badmap.BadMap");
			
			chatAppender.start();
			cfg.addAppender(chatAppender);
			loggerCfg.addAppender(chatAppender, null, null);
			cfg.addLogger("BMChatAppender", loggerCfg);
			logContext.updateLoggers();
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
	
	private static int cmdRender(CommandContext<ServerCommandSource> ctx, boolean incremental) throws CommandSyntaxException
	{
		final var src = ctx.getSource();
		final var server = src.getMinecraftServer();
		
		final var bmapDir = server.getRunDirectory().toPath().resolve("bmap").toFile(); // TODO: config
		bmapDir.mkdir();
		
		final var job = new RenderJob(bmapDir.toPath(), server);
		job.render(incremental);
		
		return 1;
	}
}
