package com.darkwintertodtplugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DarkWintertodtPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DarkWintertodtPlugin.class);
		RuneLite.main(args);
	}
}
