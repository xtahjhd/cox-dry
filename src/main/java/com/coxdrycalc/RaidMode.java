package com.coxdrycalc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
enum RaidMode
{
	REGULAR("Regular", ""),
	CM("CM", "cm"),
	// both modes together, never an actual raid's mode
	COMBINED("Combined", "all");

	private final String label;
	private final String keyPrefix;

	// config key for this mode, regular keeps the old unprefixed keys
	String key(String base)
	{
		return keyPrefix.isEmpty() ? base : keyPrefix + Character.toUpperCase(base.charAt(0)) + base.substring(1);
	}
}
