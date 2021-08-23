package net.yoplitein.badmap;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.google.common.collect.Lists;

import net.minecraft.block.MapColor;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;

public class Utils
{
	public static record RegionPos(int x, int z)
	{
		static RegionPos of(ChunkPos pos)
		{
			return new RegionPos(pos.x >> 5, pos.z >> 5);
		}
		
		ChunkPos chunkPosInRegion(ChunkPos worldspace)
		{
			return new ChunkPos(worldspace.x - 32 * x, worldspace.z - 32 * z);
		}
	}
	
	public static record RGB(int r, int g, int b)
	{
		public RGB(int argb)
		{
			this(
				(argb & 0xFF0000) >> 16,
				(argb & 0x00FF00) >> 8,
				(argb & 0x0000FF)
			);
		}
		
		public int toARGB()
		{
			return
				0xFF000000 |
				(r & 0xFF) << 16 |
				(g & 0xFF) << 8 |
				(b & 0xFF)
			;
		}
		
		public int toABGR()
		{
			return
				0xFF000000 |
				r |
				g << 8 |
				b << 16
			;
		}
	}
	
	// Container for a mutable T.
	// Comes in handy for lambdas, when you need to mutate non-local variables.
	// (as all variables captured by a lambda must be final, but it's not transitive)
	public static class Cell<T>
	{
		public T val;
		Cell(T val) { this.val = val; }
	}
	
	public static class Benchmark
	{
		long startTime = 0;
		long endTime = 0;
		
		public Benchmark() {}
		void start() { startTime = System.currentTimeMillis(); }
		void end() { endTime = System.currentTimeMillis(); }
		long msecs() { return endTime - startTime; }
	}
	
	public static String tileFilename(RegionPos pos)
	{
		return String.format("%d_%d.png", pos.x, pos.z);
	}
	
	public static void writePNG(File file, BufferedImage img)
	{
		try
		{
			ImageIO.write(img, "png", file);
		}
		catch(Exception err)
		{
			throw new RuntimeException("failed to save png", err);
		}
	}
	
	public static BufferedImage readPNG(File file)
	{
		try
		{
			return ImageIO.read(file);
		}
		catch(Exception err)
		{
			throw new RuntimeException("failed to load png", err);
		}
	}
	
	public static RGB getMapColor(MapColor color, int shade)
	{
		return new RGB(color.getRenderColor(shade));
	}
	
	public static RGB blendColors(RGB base, RGB overlay, double strength)
	{
		return new RGB(
			MathHelper.clamp(base.r + (int)(strength * overlay.r), 0, 255),
			MathHelper.clamp(base.g + (int)(strength * overlay.g), 0, 255),
			MathHelper.clamp(base.b + (int)(strength * overlay.b), 0, 255)
		);
	}
	
	public static int round(double val)
	{
		// NOTE: probably doesn't work on negative values
		return MathHelper.fractionalPart(val) >= 0.5 ? MathHelper.ceil(val) : MathHelper.floor(val);
	}
	
	public static List<MutableText> getLocationTexts(List<BlockPos> locs, boolean withChunkPos)
	{
		final var result = new ArrayList<MutableText>(locs.size());
		
		for(var pos: locs)
		{
			var text = Texts.bracketed(
				new TranslatableText(
					"chat.coordinates",
					pos.getX(), pos.getY(), pos.getZ()
				)
			).styled(style -> {
				return style
					.withColor(Formatting.GREEN)
					.withClickEvent(new ClickEvent(
						ClickEvent.Action.SUGGEST_COMMAND,
						"/tp @s %s %s %s".formatted(
							pos.getX(), pos.getY(), pos.getZ()
						)
					))
					.withHoverEvent(new HoverEvent(
						HoverEvent.Action.SHOW_TEXT,
						new TranslatableText("chat.coordinates.tooltip")
					))
				;
			});
			
			if(withChunkPos)
				text = LiteralText.EMPTY
					.copy()
					.append(text)
					.append(Text.of(" (chunk [%d,%d])".formatted(
						pos.getX() >> 4,
						pos.getZ() >> 4
					)))
				;
				
			result.add(text);
		}
		
		return result;
	}
	
	public static <T> CompletableFuture<Void> chainAsync(Stream<CompletableFuture<T>> tasks, int parallel)
    {
        final var done = new CompletableFuture<Void>();
        final var empty = CompletableFuture.completedFuture(null);
        final var iter = tasks.iterator();
        
        final var nextFutures = new CompletableFuture[parallel];
        final var scheduleNext = new Cell<Runnable>(null);
        scheduleNext.val = () -> {
            try
            {
				// java has no cheap array slicing, so we use a bogus future for the remainder
				Arrays.fill(nextFutures, empty);
				
                if(!iter.hasNext())
                    done.complete(null);
                else
                {
                    for(int i = 0; i < parallel; i++)
                    {
                        if(!iter.hasNext()) break;
                        nextFutures[i] = iter.next();
                    }
                    
                    final var next = CompletableFuture.allOf(nextFutures);
                    next.thenRunAsync(scheduleNext.val, BadMap.THREADPOOL);
                    next.exceptionallyAsync(err -> { done.completeExceptionally(err); return null; }, BadMap.THREADPOOL);
                }
            }
            catch(Throwable err)
            {
				BadMap.LOGGER.error("chainAsync.scheduleNext caught exception", err);
                done.completeExceptionally(err);
				for(var task: nextFutures) task.completeExceptionally(err);
            }
        };
        scheduleNext.val.run();
        
        return done;
    }
	
	public static <T> List<List<T>> workerBatches(List<T> list)
	{
		final var numWorkers = BadMap.THREADPOOL.getCorePoolSize();
		final var len = list.size();
		return Lists.partition(list, len < numWorkers ? len : len / numWorkers);
	}
}
