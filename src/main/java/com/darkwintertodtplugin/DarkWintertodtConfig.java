package com.darkwintertodtplugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("darkwintertodtplugin")
public interface DarkWintertodtConfig extends Config
{
	@ConfigItem(
		keyName = "darknessStrength",
		name = "Darkness Strength",
		description = "How strongly to darken Wintertodt terrain and ground object colors"
	)
	@Range(
		min = 0,
		max = 100
	)
	default int darknessStrength()
	{
		return 45;
	}
}
