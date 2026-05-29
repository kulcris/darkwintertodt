package com.darkwintertodtplugin;

import com.google.inject.Provides;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PreMapLoad;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Dark Wintertodt",
	description = "Darkens Wintertodt terrain and ground object colors",
	tags = {"visuals", "graphics", "recolor"}
)
public class DarkWintertodtPlugin extends Plugin
{
	private static final int NEXT_REFRESH_UNSET = -1;
	private static final int MAX_HSL = 0xFFFF;
	private static final int HSL_HUE_SHIFT = 10;
	private static final int HSL_SATURATION_SHIFT = 7;
	private static final int HSL_HUE_MASK = 0x3F;
	private static final int HSL_SATURATION_MASK = 0x7;
	private static final int HSL_LIGHTNESS_MASK = 0x7F;
	private static final int HSL_HIDDEN_COLOR = 12345678;
	private static final Set<Integer> WINTERTODT_REGION_IDS = Set.of(6461, 6462);
	private static final Set<Integer> TARGET_OBJECT_IDS = Set.of(
		24720,
		29279,
		29287,
		29288,
		29289,
		29290,
		29291,
		29292,
		29293,
		29294,
		29295,
		29296,
		29297,
		29298,
		29322,
		29323
	);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DarkWintertodtConfig config;

	private final int[] remappedHsl = new int[MAX_HSL + 1];
	private final Set<Renderable> processedRenderables = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<Model> processedModels = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<Model, ModelSnapshot> modelSnapshots = new IdentityHashMap<>();
	private int nextReloadTick = NEXT_REFRESH_UNSET;

	@Override
	protected void startUp()
	{
		updateColorMap();
		triggerMapReload(false);
	}

	@Override
	protected void shutDown()
	{
		triggerMapReload(true);
	}

	@Subscribe
	public void onPreMapLoad(PreMapLoad preMapLoad)
	{
		updateColorMap();
		recolorMap(preMapLoad.getScene());
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"darkwintertodtplugin".equals(event.getGroup()))
		{
			return;
		}

		updateColorMap();
		nextReloadTick = client.getTickCount() + 1;
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (nextReloadTick != NEXT_REFRESH_UNSET && client.getTickCount() >= nextReloadTick)
		{
			triggerMapReload(true);
			nextReloadTick = NEXT_REFRESH_UNSET;
		}
	}

	@Provides
	DarkWintertodtConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DarkWintertodtConfig.class);
	}

	private void triggerMapReload(boolean restoreSnapshotsFirst)
	{
		clientThread.invokeLater(() ->
		{
			if (restoreSnapshotsFirst)
			{
				// Some scene models persist across reloads, so restore them before forcing a rebuild.
				restoreSnapshots();
			}
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.setGameState(GameState.LOADING);
			}
		});
	}

	private void recolorMap(Scene scene)
	{
		processedRenderables.clear();
		processedModels.clear();
		Tile[][][] tiles = scene.isInstance() ? scene.getTiles() : scene.getExtendedTiles();
		if (tiles == null)
		{
			return;
		}

		for (Tile[][] zTiles : tiles)
		{
			for (Tile[] xTiles : zTiles)
			{
				for (Tile tile : xTiles)
				{
					if (!canRecolorTile(scene, tile))
					{
						continue;
					}

					recolorTile(tile);
				}
			}
		}
	}

	private boolean canRecolorTile(Scene scene, Tile tile)
	{
		if (tile == null)
		{
			return false;
		}

		WorldPoint worldPoint = WorldPoint.fromLocalInstance(scene, tile.getLocalLocation(), tile.getPlane());
		if (worldPoint == null)
		{
			return false;
		}

		int regionId = worldPoint.getRegionID();
		return WINTERTODT_REGION_IDS.contains(regionId);
	}

	private void recolorTile(Tile tile)
	{
		Tile current = tile;
		while (current != null)
		{
			recolorTilePaint(current.getSceneTilePaint());
			recolorTileModel(current.getSceneTileModel());
			recolorGameObjects(current.getGameObjects());
			recolorTargetObject(
				current.getDecorativeObject() != null ? current.getDecorativeObject().getId() : -1,
				current.getDecorativeObject() != null ? current.getDecorativeObject().getRenderable() : null,
				current.getDecorativeObject() != null ? current.getDecorativeObject().getRenderable2() : null
			);
			recolorGroundObject(current.getGroundObject());
			recolorTargetObject(
				current.getWallObject() != null ? current.getWallObject().getId() : -1,
				current.getWallObject() != null ? current.getWallObject().getRenderable1() : null,
				current.getWallObject() != null ? current.getWallObject().getRenderable2() : null
			);
			current = current.getBridge();
		}
	}

	private void recolorTilePaint(SceneTilePaint paint)
	{
		if (paint == null || paint.getTexture() != -1)
		{
			return;
		}

		paint.setNwColor(remappedHsl(paint.getNwColor()));
		paint.setNeColor(remappedHsl(paint.getNeColor()));
		paint.setSwColor(remappedHsl(paint.getSwColor()));
		paint.setSeColor(remappedHsl(paint.getSeColor()));
	}

	private void recolorTileModel(SceneTileModel model)
	{
		if (model == null)
		{
			return;
		}

		adjustColors(model.getTriangleColorA(), model.getTriangleTextureId());
		adjustColors(model.getTriangleColorB(), model.getTriangleTextureId());
		adjustColors(model.getTriangleColorC(), model.getTriangleTextureId());
	}

	private void recolorGroundObject(GroundObject groundObject)
	{
		recolorRenderable(groundObject == null ? null : groundObject.getRenderable(), false);
	}

	private void recolorGameObjects(GameObject[] gameObjects)
	{
		if (gameObjects == null)
		{
			return;
		}

		for (GameObject gameObject : gameObjects)
		{
			recolorTargetObject(gameObject == null ? -1 : gameObject.getId(), gameObject == null ? null : gameObject.getRenderable());
		}
	}

	private void recolorTargetObject(int objectId, Renderable... renderables)
	{
		if (!TARGET_OBJECT_IDS.contains(objectId) || renderables == null)
		{
			return;
		}

		for (Renderable renderable : renderables)
		{
			recolorRenderable(renderable, true);
		}
	}

	private void recolorRenderable(Renderable renderable, boolean snapshotModel)
	{
		if (renderable == null || !processedRenderables.add(renderable))
		{
			return;
		}

		Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		if (model == null || !processedModels.add(model))
		{
			return;
		}

		recolorModel(model, snapshotModel);
	}

	private void recolorModel(Model model, boolean snapshotModel)
	{
		if (snapshotModel)
		{
			// Snapshot target-ID structural models so reload cleanup works.
			modelSnapshots.computeIfAbsent(model, ModelSnapshot::new);
		}
		adjustColors(model.getFaceColors1(), null);
		adjustColors(model.getFaceColors2(), null);
		adjustColors(model.getFaceColors3(), null);
		adjustShortColors(model.getUnlitFaceColors());
	}

	private void adjustColors(int[] colors, int[] textures)
	{
		if (colors == null)
		{
			return;
		}

		for (int i = 0; i < colors.length; i++)
		{
			if (textures != null && textures.length > i && textures[i] != -1)
			{
				continue;
			}

			colors[i] = remappedHsl(colors[i]);
		}
	}

	private void adjustShortColors(short[] colors)
	{
		if (colors == null)
		{
			return;
		}

		for (int i = 0; i < colors.length; i++)
		{
			colors[i] = (short) remappedHsl(colors[i] & 0xFFFF);
		}
	}

	private void updateColorMap()
	{
		if (client.getGameState() != GameState.LOGGED_IN && client.getGameState() != GameState.LOADING)
		{
			return;
		}

		int darknessStrength = Math.max(0, Math.min(100, config.darknessStrength()));
		double scale = (100.0 - darknessStrength) / 100.0;
		for (int hsl = 0; hsl < remappedHsl.length; hsl++)
		{
			remappedHsl[hsl] = darkenPackedHsl(hsl, scale);
		}
	}

	private int remappedHsl(int hsl)
	{
		if (hsl == HSL_HIDDEN_COLOR || hsl < 0 || hsl >= remappedHsl.length)
		{
			return hsl;
		}

		return remappedHsl[hsl];
	}

	private int darkenPackedHsl(int packedHsl, double scale)
	{
		if (packedHsl == HSL_HIDDEN_COLOR || packedHsl < 0 || packedHsl > MAX_HSL)
		{
			return packedHsl;
		}

		int hue = (packedHsl >> HSL_HUE_SHIFT) & HSL_HUE_MASK;
		int saturation = (packedHsl >> HSL_SATURATION_SHIFT) & HSL_SATURATION_MASK;
		int lightness = packedHsl & HSL_LIGHTNESS_MASK;
		int newLightness = Math.max(1, Math.min(HSL_LIGHTNESS_MASK, (int) Math.ceil(scale * lightness)));
		return (hue << HSL_HUE_SHIFT) | (saturation << HSL_SATURATION_SHIFT) | newLightness;
	}

	private void restoreSnapshots()
	{
		modelSnapshots.forEach((model, snapshot) -> snapshot.restore(model));
		modelSnapshots.clear();
	}

	private static void restoreArray(int[] target, int[] source)
	{
		if (target != null && source != null && target.length == source.length)
		{
			System.arraycopy(source, 0, target, 0, source.length);
		}
	}

	private static void restoreArray(short[] target, short[] source)
	{
		if (target != null && source != null && target.length == source.length)
		{
			System.arraycopy(source, 0, target, 0, source.length);
		}
	}

	private static final class ModelSnapshot
	{
		private final int[] faceColors1;
		private final int[] faceColors2;
		private final int[] faceColors3;
		private final short[] unlitFaceColors;

		private ModelSnapshot(Model model)
		{
			faceColors1 = model.getFaceColors1() == null ? null : model.getFaceColors1().clone();
			faceColors2 = model.getFaceColors2() == null ? null : model.getFaceColors2().clone();
			faceColors3 = model.getFaceColors3() == null ? null : model.getFaceColors3().clone();
			unlitFaceColors = model.getUnlitFaceColors() == null ? null : model.getUnlitFaceColors().clone();
		}

		private void restore(Model model)
		{
			restoreArray(model.getFaceColors1(), faceColors1);
			restoreArray(model.getFaceColors2(), faceColors2);
			restoreArray(model.getFaceColors3(), faceColors3);
			restoreArray(model.getUnlitFaceColors(), unlitFaceColors);
		}
	}
}
