package net.yoplitein.badmap;

import java.io.File;

import com.google.common.io.Files;

import org.apache.commons.io.FileUtils;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.poi.PointOfInterestStorage;

class POIStorageFaker extends PointOfInterestStorage
{
	private static POIStorageFaker instance;
	
	private POIStorageFaker(File tmpdir)
	{
		super(tmpdir, null, false, null);
	}
	
	public static POIStorageFaker getInstance()
	{
		if(instance == null)
		{
			final var tmpdir = Files.createTempDir();
			instance = new POIStorageFaker(tmpdir);
			
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try { FileUtils.deleteDirectory(tmpdir); }
				catch(Exception err)
				{
					BadMap.LOGGER.error("unable to delete POIStorageFaker tmpdir", err);
				}
			}));
		}
		
		return instance;
	}
	
	@Override
	public void initForPalette(ChunkPos chunkPos, ChunkSection chunkSection)
	{
		// the logic that's supposed to happen here causes race conditions
	}
}
