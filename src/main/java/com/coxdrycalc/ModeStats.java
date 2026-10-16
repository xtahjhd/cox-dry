package com.coxdrycalc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.client.config.ConfigManager;

// streak + lifetime stats for one mode. saved per osrs account so every runelite
// profile on every pc shares them
class ModeStats
{
	static final String RAIDS = "raidsSincePurple";
	static final String POINTS = "pointsSincePurple";
	static final String LOG_SURVIVAL = "logSurvival";
	static final String LAST_PURPLE = "lastPurple";
	static final String TOTAL_RAIDS = "totalRaids";
	static final String TOTAL_POINTS = "totalPoints";
	static final String TOTAL_PURPLES = "totalPurples";
	static final String EXPECTED_PURPLES = "expectedPurples";
	static final String HISTORY = "purpleHistory";
	static final int HISTORY_SIZE = 25;
	static final String BY_SIZE = "byTeamSize";
	static final String[] KEYS = {RAIDS, POINTS, LOG_SURVIVAL, LAST_PURPLE, TOTAL_RAIDS, TOTAL_POINTS, TOTAL_PURPLES,
		EXPECTED_PURPLES, HISTORY, BY_SIZE};

	// lifetime totals for one team size
	static class SizeStats
	{
		int raids;
		long points;
		int purples;
		double expected;

		SizeStats copy()
		{
			SizeStats c = new SizeStats();
			c.raids = raids;
			c.points = points;
			c.purples = purples;
			c.expected = expected;
			return c;
		}
	}

	// one of your purples, newest last
	static class Purple
	{
		final String date;
		final String item;
		final int raids;
		final long points;

		Purple(String date, String item, int raids, long points)
		{
			this.date = date;
			this.item = item;
			this.raids = raids;
			this.points = points;
		}
	}

	final RaidMode mode;

	// current dry streak
	int raids;
	long points;
	double logSurvival;
	String lastPurple;

	// lifetime, for the csv
	int totalRaids;
	long totalPoints;
	int totalPurples;
	double expectedPurples;
	final List<Purple> history = new ArrayList<>();
	final Map<Integer, SizeStats> bySize = new TreeMap<>();

	ModeStats(RaidMode mode)
	{
		this.mode = mode;
	}

	void load(ConfigManager cm)
	{
		raids = (int) readLong(cm, RAIDS);
		points = readLong(cm, POINTS);
		logSurvival = readDouble(cm, LOG_SURVIVAL);
		String last = get(cm, LAST_PURPLE);
		lastPurple = last == null ? "" : last;
		totalRaids = (int) readLong(cm, TOTAL_RAIDS);
		totalPoints = readLong(cm, TOTAL_POINTS);
		totalPurples = (int) readLong(cm, TOTAL_PURPLES);
		expectedPurples = readDouble(cm, EXPECTED_PURPLES);
		bySize.clear();
		String sizes = get(cm, BY_SIZE);
		if (sizes != null)
		{
			for (String line : sizes.split("\n"))
			{
				String[] f = line.split("\\|");
				if (f.length == 5)
				{
					try
					{
						SizeStats z = new SizeStats();
						z.raids = Integer.parseInt(f[1]);
						z.points = Long.parseLong(f[2]);
						z.purples = Integer.parseInt(f[3]);
						z.expected = Double.parseDouble(f[4]);
						bySize.put(Integer.parseInt(f[0]), z);
					}
					catch (NumberFormatException ignored)
					{
					}
				}
			}
		}
		history.clear();
		String h = get(cm, HISTORY);
		if (h != null)
		{
			for (String line : h.split("\n"))
			{
				String[] f = line.split("\\|");
				if (f.length == 4)
				{
					try
					{
						history.add(new Purple(f[0], f[1], Integer.parseInt(f[2]), Long.parseLong(f[3])));
					}
					catch (NumberFormatException ignored)
					{
					}
				}
			}
		}
	}

	void save(ConfigManager cm)
	{
		set(cm, RAIDS, Integer.toString(raids));
		set(cm, POINTS, Long.toString(points));
		set(cm, LOG_SURVIVAL, Double.toString(logSurvival));
		set(cm, LAST_PURPLE, lastPurple);
		set(cm, TOTAL_RAIDS, Integer.toString(totalRaids));
		set(cm, TOTAL_POINTS, Long.toString(totalPoints));
		set(cm, TOTAL_PURPLES, Integer.toString(totalPurples));
		set(cm, EXPECTED_PURPLES, Double.toString(expectedPurples));
		while (history.size() > HISTORY_SIZE)
		{
			history.remove(0);
		}
		StringBuilder h = new StringBuilder();
		for (Purple p : history)
		{
			if (h.length() > 0)
			{
				h.append('\n');
			}
			h.append(p.date).append('|').append(p.item).append('|').append(p.raids).append('|').append(p.points);
		}
		set(cm, HISTORY, h.toString());
		StringBuilder z = new StringBuilder();
		bySize.forEach((size, st) ->
		{
			if (z.length() > 0)
			{
				z.append('\n');
			}
			z.append(size).append('|').append(st.raids).append('|').append(st.points).append('|')
				.append(st.purples).append('|').append(st.expected);
		});
		set(cm, BY_SIZE, z.toString());
	}

	// has this account saved stats for this mode yet
	boolean isStored(ConfigManager cm)
	{
		return get(cm, TOTAL_RAIDS) != null;
	}

	// builds combined stats from both modes. exact if you have no purples yet, otherwise it uses
	// the streak from the mode of your last purple since we don't know the other mode's raids after that
	void seed(ModeStats a, ModeStats b)
	{
		totalRaids = a.totalRaids + b.totalRaids;
		totalPoints = a.totalPoints + b.totalPoints;
		totalPurples = a.totalPurples + b.totalPurples;
		expectedPurples = a.expectedPurples + b.expectedPurples;
		bySize.clear();
		for (ModeStats m : new ModeStats[]{a, b})
		{
			m.bySize.forEach((k, v) ->
			{
				SizeStats z = size(k);
				z.raids += v.raids;
				z.points += v.points;
				z.purples += v.purples;
				z.expected += v.expected;
			});
		}
		history.clear();
		history.addAll(a.history);
		history.addAll(b.history);
		history.sort((x, y) -> x.date.compareTo(y.date));

		if (a.history.isEmpty() && b.history.isEmpty())
		{
			raids = a.raids + b.raids;
			points = a.points + b.points;
			logSurvival = a.logSurvival + b.logSurvival;
			lastPurple = a.lastPurple.isEmpty() ? b.lastPurple : a.lastPurple;
			return;
		}
		ModeStats latest = b.history.isEmpty() || (!a.history.isEmpty()
			&& a.history.get(a.history.size() - 1).date.compareTo(b.history.get(b.history.size() - 1).date) > 0) ? a : b;
		raids = latest.raids;
		points = latest.points;
		logSurvival = latest.logSurvival;
		lastPurple = latest.lastPurple;
	}

	SizeStats size(int teamSize)
	{
		return bySize.computeIfAbsent(Math.max(1, teamSize), k -> new SizeStats());
	}

	// copy for the side panel, it reads on the swing thread
	ModeStats copy()
	{
		ModeStats c = new ModeStats(mode);
		c.raids = raids;
		c.points = points;
		c.logSurvival = logSurvival;
		c.lastPurple = lastPurple;
		c.totalRaids = totalRaids;
		c.totalPoints = totalPoints;
		c.totalPurples = totalPurples;
		c.expectedPurples = expectedPurples;
		c.history.addAll(history);
		bySize.forEach((k, v) -> c.bySize.put(k, v.copy()));
		return c;
	}

	// % of players who'd have a purple by now with the same streak
	double wouldHaveOne()
	{
		return 1 - Math.exp(logSurvival);
	}

	// copies old per profile values into the account, skips ones it already has
	void migrateFromProfile(ConfigManager cm)
	{
		for (String base : KEYS)
		{
			String old = cm.getConfiguration(CoxDryCalcConfig.GROUP, mode.key(base));
			if (old != null && get(cm, base) == null)
			{
				set(cm, base, old);
			}
		}
	}

	// copies the streak into the profile config fields since those are the only ones the config panel can edit
	void saveStreakFields(ConfigManager cm)
	{
		cm.setConfiguration(CoxDryCalcConfig.GROUP, mode.key(RAIDS), Integer.toString(raids));
		cm.setConfiguration(CoxDryCalcConfig.GROUP, mode.key(POINTS), Long.toString(points));
	}

	// reads a streak value you just typed in the config panel
	void loadStreakFields(ConfigManager cm)
	{
		raids = (int) parseLong(cm.getConfiguration(CoxDryCalcConfig.GROUP, mode.key(RAIDS)));
		points = parseLong(cm.getConfiguration(CoxDryCalcConfig.GROUP, mode.key(POINTS)));
	}

	private String get(ConfigManager cm, String base)
	{
		return cm.getRSProfileConfiguration(CoxDryCalcConfig.GROUP, mode.key(base));
	}

	private void set(ConfigManager cm, String base, String value)
	{
		cm.setRSProfileConfiguration(CoxDryCalcConfig.GROUP, mode.key(base), value);
	}

	private long readLong(ConfigManager cm, String base)
	{
		return parseLong(get(cm, base));
	}

	private static long parseLong(String v)
	{
		try
		{
			return v == null ? 0 : Long.parseLong(v.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private double readDouble(ConfigManager cm, String base)
	{
		String v = get(cm, base);
		try
		{
			return v == null ? 0 : Double.parseDouble(v.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
}
