package net.yoplitein.badmap;

import java.io.Serializable;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import net.minecraft.server.MinecraftServer;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Util;

public class ChatAppender extends AbstractAppender
{
	static final boolean inDevEnv = System.getProperties().containsKey("fabric.development");
	MinecraftServer server;
	
	protected ChatAppender(String name, Filter filter, Layout<? extends Serializable> layout, boolean ignoreExceptions, Property[] properties, MinecraftServer server)
	{
		super(name, filter, layout, ignoreExceptions, properties);
		this.server = server;
	}
	
	public static ChatAppender createAppender(MinecraftServer server, String name, Layout<? extends Serializable> layout)
	{
		return new ChatAppender(name, null, layout, false, null, server);
	}

	@Override
	public void append(LogEvent event)
	{
		if(!event.getLevel().isMoreSpecificThan(inDevEnv ? Level.DEBUG : Level.INFO)) return;
		
		final var playerMgr = server.getPlayerManager();
		final var strMsg = String.format("[BadMap %5s] %s", event.getLevel().name(), event.getMessage().getFormattedMessage());
		final var msg = new LiteralText(strMsg);
		
		for(var player: playerMgr.getPlayerList())
			if(playerMgr.isOperator(player.getGameProfile()))
				player.sendSystemMessage(msg, Util.NIL_UUID);
	}
}
