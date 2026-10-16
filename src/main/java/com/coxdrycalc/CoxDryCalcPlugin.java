package com.coxdrycalc;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
		name = "CoX Dry Calculator",
		description = "Tracks Chambers of Xeric raids and personal points since your last purple",
		tags = {"cox", "chambers", "xeric", "raids", "purple", "dry", "unique", "points", "cm"},
		internalName = "cox-dry-calc"
)
public class CoxDryCalcPlugin extends Plugin
{
	private static final String RAID_START = "The raid has begun!";
	private static final String RAID_COMPLETE = "Congratulations - your raid is complete!";
	private static final String SPECIAL_LOOT = "Special loot:";
	// comes right after the complete msg, only real way to tell regular from cm
	private static final Pattern KC_LINE = Pattern.compile("^Your completed Chambers of Xeric( Challenge Mode)? count is: [\\d,]+\\.?$");
	// ticks to wait for the kc msg before just using the cm varbit
	private static final int KC_WAIT_TICKS = 2;
	private static final Pattern LOOT_LINE = Pattern.compile("^(?:Special loot:\\s*)?(.+?) - (.+)$");
	private static final String CSV_NAME = "cox-raids.csv";
	private static final String EXPORT_COMMAND = "coxexport";
	private static final String SHEETS_SYNC_COMMAND = "coxsheetsync";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	// write the row after this many ticks if you haven't left yet
	private static final int FLUSH_AFTER_TICKS = 200;

	// purple chance = team points / 867,600, capped at 570k (65.7%)
	// your cut of that is your points / team points
	private static final double POINTS_PER_PURPLE = 867_600d;
	private static final int POINT_CAP = 570_000;

	private static final Map<String, Integer> UNIQUES = ImmutableMap.<String, Integer>builder()
			.put("dexterous prayer scroll", ItemID.RAIDS_PRAYERSCROLL)
			.put("arcane prayer scroll", ItemID.RAIDS_PRAYERSCROLL_AUGURY)
			.put("twisted buckler", ItemID.TWISTED_BUCKLER)
			.put("dragon hunter crossbow", ItemID.DRAGONHUNTER_XBOW)
			.put("dinh's bulwark", ItemID.DINHS_BULWARK)
			.put("ancestral hat", ItemID.ANCESTRAL_HAT)
			.put("ancestral robe top", ItemID.ANCESTRAL_ROBE_TOP)
			.put("ancestral robe bottom", ItemID.ANCESTRAL_ROBE_BOTTOM)
			.put("dragon claws", ItemID.DRAGON_CLAWS)
			.put("elder maul", ItemID.ELDER_MAUL)
			.put("kodai insignia", ItemID.KODAI_INSIGNIA)
			.put("twisted bow", ItemID.TWISTED_BOW)
			.build();
	private static final Set<Integer> UNIQUE_IDS = ImmutableSet.copyOf(UNIQUES.values());

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private CoxDryCalcConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private CoxDryCalcOverlay overlay;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	private CoxDryCalcPanel panel;
	private NavigationButton navButton;

	@Getter
	private boolean inCox;
	// true from raid start til it's done or you leave
	@Getter
	private boolean raidInProgress;

	private final Map<RaidMode, ModeStats> stats = new EnumMap<>(RaidMode.class);

	// per raid stuff, reset when you enter a new raid
	private RaidRecord current;
	private int flushTick = -1;
	private boolean myPurpleThisRaid;
	private boolean anyPurpleThisRaid;
	private boolean streakResetThisRaid;
	private int completeTick = -1;
	private final List<PendingPurple> pendingPurples = new ArrayList<>();
	private int specialLootTick = -1;

	// true while we write config, so we can tell our writes from manual edits
	private boolean writing;

	// set once this profile's old stats got moved to the account
	private static final String MOVED_TO_ACCOUNT = "statsMovedToAccount";

	@Override
	protected void startUp()
	{
		for (RaidMode mode : RaidMode.values())
		{
			stats.put(mode, new ModeStats(mode));
		}
		loadStats();
		overlayManager.add(overlay);
		panel = new CoxDryCalcPanel(this::exportCsv);
		navButton = NavigationButton.builder()
				.tooltip("CoX Dry Calc")
				.icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
				.priority(8)
				.panel(panel)
				.build();
		if (config.showPanel())
		{
			clientToolbar.addNavigation(navButton);
		}
		refreshPanel();
		clientThread.invoke(() -> inCox = client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1);
	}

	@Override
	protected void shutDown()
	{
		flushRecord();
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		stats.clear();
	}

	ModeStats getStats(RaidMode mode)
	{
		return stats.get(mode);
	}

	RaidMode currentMode()
	{
		return client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE) == 1 ? RaidMode.CM : RaidMode.REGULAR;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() != VarbitID.RAIDS_CLIENT_INDUNGEON)
		{
			return;
		}
		boolean now = event.getValue() == 1;
		if (now && !inCox)
		{
			flushRecord();
			resetRaidState();
		}
		else if (!now && inCox)
		{
			raidInProgress = false;
			recordPendingRaid();
			flushRecord();
		}
		inCox = now;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (completeTick >= 0 && client.getTickCount() - completeTick >= KC_WAIT_TICKS)
		{
			recordRaid(currentMode());
		}
		if (current != null && client.getTickCount() >= flushTick)
		{
			flushRecord();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		if (!stats.isEmpty())
		{
			loadStats();
		}
	}

	// loads stats for the logged in account. first time a profile sees an account it copies
	// the old profile stats over so they don't get lost
	private void loadStats()
	{
		boolean account = configManager.getRSProfileKey() != null;
		boolean migrate = account && configManager.getConfiguration(CoxDryCalcConfig.GROUP, MOVED_TO_ACCOUNT) == null;
		writing = true;
		try
		{
			for (ModeStats s : stats.values())
			{
				if (migrate)
				{
					s.migrateFromProfile(configManager);
				}
				s.load(configManager);
				if (account)
				{
					s.saveStreakFields(configManager);
				}
			}
			if (migrate)
			{
				configManager.setConfiguration(CoxDryCalcConfig.GROUP, MOVED_TO_ACCOUNT, "true");
			}
			ModeStats all = stats.get(RaidMode.COMBINED);
			if (account && !all.isStored(configManager))
			{
				all.seed(stats.get(RaidMode.REGULAR), stats.get(RaidMode.CM));
				all.save(configManager);
				all.saveStreakFields(configManager);
			}
		}
		finally
		{
			writing = false;
		}
		refreshPanel();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			recordPendingRaid();
			flushRecord();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.FRIENDSCHATNOTIFICATION
				&& type != ChatMessageType.CLAN_MESSAGE && type != ChatMessageType.CLAN_GIM_MESSAGE)
		{
			return;
		}

		String msg = Text.removeTags(event.getMessage()).replace('\u00a0', ' ').trim();

		if (msg.startsWith(RAID_START) && inCox)
		{
			raidInProgress = true;
			return;
		}

		if (msg.startsWith(RAID_COMPLETE) && inCox)
		{
			raidInProgress = false;
			// gets counted once the kc msg says which mode it was
			if (current == null)
			{
				completeTick = client.getTickCount();
			}
			return;
		}

		Matcher kc = KC_LINE.matcher(msg);
		if (kc.matches())
		{
			if (completeTick >= 0)
			{
				recordRaid(kc.group(1) != null ? RaidMode.CM : RaidMode.REGULAR);
			}
			return;
		}

		if (!inCox && current == null)
		{
			return;
		}

		if (msg.equals(SPECIAL_LOOT))
		{
			specialLootTick = client.getTickCount();
			return;
		}

		boolean afterHeader = specialLootTick >= 0 && client.getTickCount() - specialLootTick <= 2;
		if (!afterHeader && !msg.startsWith(SPECIAL_LOOT))
		{
			return;
		}

		Matcher m = LOOT_LINE.matcher(msg);
		if (!m.matches() || !UNIQUES.containsKey(m.group(2).trim().toLowerCase()))
		{
			return;
		}
		String name = m.group(1).trim();
		onPurple(m.group(2).trim(), name, isLocalPlayer(name));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.RAIDS_REWARDS || myPurpleThisRaid)
		{
			return;
		}
		for (Item it : event.getItemContainer().getItems())
		{
			if (UNIQUE_IDS.contains(it.getId()))
			{
				String item = UNIQUES.entrySet().stream()
						.filter(e -> e.getValue() == it.getId())
						.map(e -> capitalize(e.getKey()))
						.findFirst().orElse("Purple");
				Player local = client.getLocalPlayer();
				onPurple(item, local == null ? "You" : local.getName(), true);
				return;
			}
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (EXPORT_COMMAND.equalsIgnoreCase(event.getCommand()))
		{
			exportCsv();
		}
		else if (SHEETS_SYNC_COMMAND.equalsIgnoreCase(event.getCommand()))
		{
			syncAllToSheets();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (writing || !CoxDryCalcConfig.GROUP.equals(event.getGroup()) || stats.isEmpty())
		{
			return;
		}
		if ("showPanel".equals(event.getKey()))
		{
			if (config.showPanel())
			{
				clientToolbar.addNavigation(navButton);
			}
			else
			{
				clientToolbar.removeNavigation(navButton);
			}
			return;
		}
		// streak edited by hand, reload it and guess the dry chance (assumes no point cap)
		for (RaidMode mode : RaidMode.values())
		{
			String key = event.getKey();
			if (mode.key(ModeStats.RAIDS).equals(key) || mode.key(ModeStats.POINTS).equals(key))
			{
				ModeStats s = stats.get(mode);
				s.loadStreakFields(configManager);
				s.logSurvival = -s.points / POINTS_PER_PURPLE;
				save(s);
			}
		}
	}

	private void resetRaidState()
	{
		raidInProgress = false;
		current = null;
		flushTick = -1;
		myPurpleThisRaid = false;
		anyPurpleThisRaid = false;
		streakResetThisRaid = false;
		completeTick = -1;
		pendingPurples.clear();
		specialLootTick = -1;
	}

	// kc msg never came, so count the raid off the cm varbit
	private void recordPendingRaid()
	{
		if (completeTick >= 0)
		{
			recordRaid(currentMode());
		}
	}

	private void recordRaid(RaidMode mode)
	{
		completeTick = -1;
		if (current != null)
		{
			return;
		}

		int team = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSCORE);
		int personal = client.getVarpValue(VarPlayerID.RAIDS_PLAYERSCORE);
		int teamSize = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE);
		double teamChance = team <= 0 ? 0 : Math.min(team, POINT_CAP) / POINTS_PER_PURPLE;
		double myChance = team <= 0 ? 0 : teamChance * Math.min(1d, (double) personal / team);
		double streakChance = config.resetOn() == CoxDryCalcConfig.ResetOn.ANY_PURPLE ? teamChance : myChance;

		ModeStats s = stats.get(mode);
		ModeStats all = stats.get(RaidMode.COMBINED);
		for (ModeStats t : new ModeStats[]{s, all})
		{
			t.raids++;
			t.points += personal;
			t.logSurvival += Math.log1p(-Math.min(streakChance, 0.999999));
			t.totalRaids++;
			t.totalPoints += personal;
			t.expectedPurples += myChance;
			ModeStats.SizeStats z = t.size(teamSize);
			z.raids++;
			z.points += personal;
			z.expected += myChance;
			save(t);
		}

		current = new RaidRecord(mode, teamSize, team, personal, myChance);
		current.dryRaids = s.raids;
		current.dryPoints = s.points;
		flushTick = client.getTickCount() + FLUSH_AFTER_TICKS;
		log.debug("CoX complete: mode={} team={} personal={} p={}", mode, team, personal, myChance);

		if (!pendingPurples.isEmpty())
		{
			for (PendingPurple p : pendingPurples)
			{
				applyPurple(p.item, p.player, p.mine);
			}
			pendingPurples.clear();
		}
		else if (config.chatSummary())
		{
			chat(new ChatMessageBuilder()
					.append(ChatColorType.NORMAL).append("Dry calc (" + mode.getLabel() + "): ")
					.append(ChatColorType.HIGHLIGHT).append(QuantityFormatter.formatNumber(s.raids))
					.append(ChatColorType.NORMAL).append(s.raids == 1 ? " raid and " : " raids and ")
					.append(ChatColorType.HIGHLIGHT).append(QuantityFormatter.formatNumber(s.points))
					.append(ChatColorType.NORMAL).append(" points since your last purple (")
					.append(ChatColorType.HIGHLIGHT).append(String.format("%.1f%%", 100 * s.wouldHaveOne()))
					.append(ChatColorType.NORMAL).append(" of players would have one by now). Both modes: ")
					.append(ChatColorType.HIGHLIGHT).append(QuantityFormatter.formatNumber(all.raids))
					.append(ChatColorType.NORMAL).append(all.raids == 1 ? " raid, " : " raids, ")
					.append(ChatColorType.HIGHLIGHT).append(QuantityFormatter.formatNumber(all.points))
					.append(ChatColorType.NORMAL).append(" points, ")
					.append(ChatColorType.HIGHLIGHT).append(String.format("%.1f%%", 100 * all.wouldHaveOne()))
					.append(ChatColorType.NORMAL).append(".")
					.build());
		}
	}

	private void onPurple(String item, String player, boolean mine)
	{
		if (mine)
		{
			if (myPurpleThisRaid)
			{
				return;
			}
			myPurpleThisRaid = true;
		}
		anyPurpleThisRaid = true;

		if (current == null)
		{
			// loot came in before the raid got counted, hold it til then (yours goes first)
			PendingPurple p = new PendingPurple(item, player, mine);
			if (mine)
			{
				pendingPurples.add(0, p);
			}
			else
			{
				pendingPurples.add(p);
			}
			return;
		}
		applyPurple(item, player, mine);
	}

	private void applyPurple(String item, String player, boolean mine)
	{
		ModeStats s = stats.get(current.mode);
		ModeStats all = stats.get(RaidMode.COMBINED);
		ModeStats[] both = {s, all};
		if (mine)
		{
			current.myPurple = item;
			for (ModeStats t : both)
			{
				t.totalPurples++;
				t.size(current.teamSize).purples++;
				t.history.add(new ModeStats.Purple(LocalDate.now().toString(), item, t.raids, t.points));
			}
		}
		else
		{
			current.teamPurples.add(player + ": " + item);
		}

		boolean resets = mine || config.resetOn() == CoxDryCalcConfig.ResetOn.ANY_PURPLE;
		if (!resets || streakResetThisRaid)
		{
			save(s);
			save(all);
			return;
		}
		streakResetThisRaid = true;

		int raids = s.raids;
		long points = s.points;
		int allRaids = all.raids;
		long allPoints = all.points;
		ModeStats[] reset = config.resetAllModes() ? stats.values().toArray(new ModeStats[0]) : both;
		for (ModeStats t : reset)
		{
			t.lastPurple = item;
			t.raids = 0;
			t.points = 0;
			t.logSurvival = 0;
			save(t);
		}

		chat(new ChatMessageBuilder()
				.append(ChatColorType.NORMAL).append(mine ? "Purple! " : "Team purple! ")
				.append(ChatColorType.HIGHLIGHT).append(item)
				.append(ChatColorType.NORMAL).append(" after ")
				.append(ChatColorType.HIGHLIGHT).append(QuantityFormatter.formatNumber(raids))
				.append(ChatColorType.NORMAL).append(" " + current.mode.getLabel() + (raids == 1 ? " raid and " : " raids and "))
				.append(ChatColorType.HIGHLIGHT).append(QuantityFormatter.formatNumber(points))
				.append(ChatColorType.NORMAL).append(String.format(" points (%.2fx the average). ", points / POINTS_PER_PURPLE))
				.append("Both modes: ")
				.append(ChatColorType.HIGHLIGHT).append(QuantityFormatter.formatNumber(allRaids))
				.append(ChatColorType.NORMAL).append(allRaids == 1 ? " raid, " : " raids, ")
				.append(ChatColorType.HIGHLIGHT).append(QuantityFormatter.formatNumber(allPoints))
				.append(ChatColorType.NORMAL).append(" points. Streak reset.")
				.build());
	}

	private void flushRecord()
	{
		RaidRecord r = current;
		if (r == null)
		{
			return;
		}
		current = null;
		flushTick = -1;
		ModeStats totals = stats.get(r.mode);
		if (config.sheetsSync())
		{
			List<List<String>> rows = new ArrayList<>();
			rows.add(RaidRecord.withoutNames(r.toValues(totals)));
			postToSheets(rows, false);
		}
		if (!config.logRaids())
		{
			return;
		}
		String line = r.toCsv(totals);
		executor.execute(() ->
		{
			try
			{
				Filepath file = csvFile();
				boolean isNew = !file.exists();
				try (BufferedWriter w = file.openBufferedWriter(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
				{
					if (isNew)
					{
						w.write(RaidRecord.HEADER);
						w.newLine();
					}
					w.write(line);
					w.newLine();
				}
			}
			catch (IOException e)
			{
				log.warn("Unable to write CoX raid log", e);
			}
		});
	}

	private Filepath csvFile() throws IOException
	{
		Filepath dir = getPluginDirectory();
		dir.createDirectories();
		return dir.join(CSV_NAME);
	}

	private void exportCsv()
	{
		SwingUtilities.invokeLater(() ->
		{
			List<Filepath> picked = new Filepath.Chooser()
					.setIsSave()
					.setAcceptsFiles()
					.setDialogTitle("Export CoX raids")
					.addExtensionFilter("CSV (opens in Excel)", "csv")
					.setDefaultExtension("csv")
					.setFileName(CSV_NAME)
					.showDialog(client);
			if (picked == null || picked.isEmpty())
			{
				return;
			}
			Filepath dest = picked.get(0);
			executor.execute(() ->
			{
				try
				{
					Filepath src = csvFile();
					if (!src.exists())
					{
						chat("No raids logged yet. Complete a raid first.");
						return;
					}
					src.copyTo(dest, StandardCopyOption.REPLACE_EXISTING);
					chat("Exported CoX raids to " + dest);
				}
				catch (IOException e)
				{
					log.warn("Unable to export CoX raid log", e);
					chat("Export failed: " + e.getMessage());
				}
			});
		});
	}

	// sends every raid in the csv, the sheet skips ones it already has
	private void syncAllToSheets()
	{
		if (!config.sheetsSync())
		{
			chat("Turn on \"Send raids to Google Sheets\" in the CoX Dry Calc config first.");
			return;
		}
		executor.execute(() ->
		{
			List<List<String>> rows = new ArrayList<>();
			try
			{
				Filepath src = csvFile();
				if (src.exists())
				{
					try (BufferedReader reader = src.openBufferedReader())
					{
						String line;
						boolean header = true;
						while ((line = reader.readLine()) != null)
						{
							if (header)
							{
								header = false;
							}
							else if (!line.isEmpty())
							{
								rows.add(RaidRecord.withoutNames(RaidRecord.parseCsv(line)));
							}
						}
					}
				}
			}
			catch (IOException e)
			{
				log.warn("Unable to read CoX raid log", e);
				chat("Couldn't read the raid log: " + e.getMessage());
				return;
			}
			if (rows.isEmpty())
			{
				chat("No raids logged yet. Complete a raid first.");
				return;
			}
			postToSheets(rows, true);
		});
	}

	private void postToSheets(List<List<String>> rows, boolean announce)
	{
		HttpUrl url = HttpUrl.parse(config.sheetsUrl().trim());
		if (url == null || !url.isHttps())
		{
			chat("Google Sheets: paste your web app URL into the CoX Dry Calc config.");
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("header", RaidRecord.headerColumns());
		body.put("rows", rows);
		Request request = new Request.Builder()
				.url(url)
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Google Sheets upload failed", e);
				chat("Google Sheets upload failed: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					String text = r.body() == null ? "" : r.body().string().trim();
					if (!r.isSuccessful() || !text.startsWith("ok"))
					{
						log.warn("Google Sheets rejected upload: {} {}", r.code(), text);
						chat("Google Sheets didn't accept the raid. Check the web app URL and that the script is deployed with access for \"Anyone\".");
					}
					else if (announce)
					{
						chat("Google Sheets: " + text.substring(2).trim());
					}
				}
			}
		});
	}

	private void save(ModeStats s)
	{
		writing = true;
		try
		{
			s.save(configManager);
			s.saveStreakFields(configManager);
		}
		finally
		{
			writing = false;
		}
		refreshPanel();
	}

	private void refreshPanel()
	{
		CoxDryCalcPanel p = panel;
		if (p == null)
		{
			return;
		}
		Map<RaidMode, ModeStats> copies = new EnumMap<>(RaidMode.class);
		stats.forEach((mode, s) -> copies.put(mode, s.copy()));
		SwingUtilities.invokeLater(() -> p.update(copies));
	}

	private boolean isLocalPlayer(String name)
	{
		Player local = client.getLocalPlayer();
		return local != null && local.getName() != null
				&& Text.toJagexName(local.getName()).equalsIgnoreCase(Text.toJagexName(name));
	}

	private void chat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(message)
				.build());
	}

	private static String capitalize(String s)
	{
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	@Provides
	CoxDryCalcConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CoxDryCalcConfig.class);
	}

	// purple that showed up before its raid got counted
	private static class PendingPurple
	{
		final String item;
		final String player;
		final boolean mine;

		PendingPurple(String item, String player, boolean mine)
		{
			this.item = item;
			this.player = player;
			this.mine = mine;
		}
	}
}