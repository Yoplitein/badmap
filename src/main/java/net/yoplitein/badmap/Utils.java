package net.yoplitein.badmap;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

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
}
