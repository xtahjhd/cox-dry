package com.coxdrycalc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// one finished raid, written as a csv row once the loot is known
class RaidRecord
{
	static final String HEADER = "Date,Time,Mode,Team size,Team points,Personal points,Purple chance %,"
		+ "Your purple,Team purples,Dry raids,Dry points,"
		+ "Total raids (mode),Total points (mode),Total purples (mode),Expected purples (mode)";

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	final LocalDateTime when = LocalDateTime.now();
	final RaidMode mode;
	final int teamSize;
	final int teamPoints;
	final int personalPoints;
	final double purpleChance;
	String myPurple = "";
	final List<String> teamPurples = new ArrayList<>();

	// streak as of this raid, including it
	int dryRaids;
	long dryPoints;

	RaidRecord(RaidMode mode, int teamSize, int teamPoints, int personalPoints, double purpleChance)
	{
		this.mode = mode;
		this.teamSize = teamSize;
		this.teamPoints = teamPoints;
		this.personalPoints = personalPoints;
		this.purpleChance = purpleChance;
	}

	static List<String> headerColumns()
	{
		return Arrays.asList(HEADER.split(","));
	}

	String toCsv(ModeStats totals)
	{
		return toValues(totals).stream().map(RaidRecord::quote).collect(Collectors.joining(","));
	}

	List<String> toValues(ModeStats totals)
	{
		return Arrays.asList(
			DATE.format(when),
			TIME.format(when),
			mode.getLabel(),
			Integer.toString(teamSize),
			Integer.toString(teamPoints),
			Integer.toString(personalPoints),
			String.format("%.3f", purpleChance * 100),
			myPurple,
			String.join("; ", teamPurples),
			Integer.toString(dryRaids),
			Long.toString(dryPoints),
			Integer.toString(totals.totalRaids),
			Long.toString(totals.totalPoints),
			Integer.toString(totals.totalPurples),
			String.format("%.4f", totals.expectedPurples));
	}

	private static String quote(String s)
	{
		if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf(';') < 0)
		{
			return s;
		}
		return '"' + s.replace("\"", "\"\"") + '"';
	}

	// copy of a row with teammates' names stripped out, for sending off the client
	static List<String> withoutNames(List<String> values)
	{
		List<String> out = new ArrayList<>(values);
		if (out.size() > 8 && !out.get(8).isEmpty())
		{
			out.set(8, Arrays.stream(out.get(8).split(";"))
				.map(String::trim)
				.map(p -> p.indexOf(": ") >= 0 ? p.substring(p.indexOf(": ") + 2) : p)
				.collect(Collectors.joining("; ")));
		}
		return out;
	}

	// splits one csv line from toCsv
	static List<String> parseCsv(String line)
	{
		List<String> out = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++)
		{
			char c = line.charAt(i);
			if (quoted)
			{
				if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"')
				{
					cell.append('"');
					i++;
				}
				else if (c == '"')
				{
					quoted = false;
				}
				else
				{
					cell.append(c);
				}
			}
			else if (c == '"')
			{
				quoted = true;
			}
			else if (c == ',')
			{
				out.add(cell.toString());
				cell.setLength(0);
			}
			else
			{
				cell.append(c);
			}
		}
		out.add(cell.toString());
		return out;
	}
}
