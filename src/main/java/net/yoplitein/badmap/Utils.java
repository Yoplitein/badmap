package net.yoplitein.badmap;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.Nullable;

import net.minecraft.block.MapColor;
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
	
	public static int getARGB(MapColor color, int shade, @Nullable MapColor blend)
	{
		final var val = color.getRenderColor(shade);
		var valChans = getChannels(val);
		
		if(blend != null)
		{
			final var blendVal = blend.getRenderColor(2);
			var blendChans = getChannels(blendVal);
			for(int c = 0; c < 3; c++)
				valChans[c] = MathHelper.clamp(valChans[c] + (int)(0.25 * blendChans[c]), 0, 255);
		}
		
		return
			0xFF000000 |
			valChans[0] |
			valChans[1] << 8 |
			valChans[2] << 16
		;
	}
	
	public static int[] getChannels(int color)
	{
		return new int[]{
			(color & 0xFF0000) >> 16,
			(color & 0x00FF00) >> 8,
			(color & 0x0000FF)
		};
	}
	
	public static int round(double val)
	{
		final var fract = MathHelper.fractionalPart(val);
		return fract >= 0.5 ? MathHelper.ceil(val) : MathHelper.floor(val);
	}
}
