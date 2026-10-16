package com.coxdrycalc;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(CoxDryCalcConfig.GROUP)
public interface CoxDryCalcConfig extends Config
{
    String GROUP = "coxdrycalc";

    enum ResetOn
    {
        MY_PURPLE,
        ANY_PURPLE
    }

    enum OverlayMode
    {
        IN_COX,
        ALWAYS,
        NEVER
    }

    @ConfigSection(
            name = "Current streak",
            description = "Edit these if you were already dry before installing the plugin",
            position = 0
    )
    String streakSection = "streak";

    @ConfigItem(
            keyName = "raidsSincePurple",
            name = "Regular: raids",
            description = "Completed regular raids since your last regular purple",
            section = streakSection,
            position = 0
    )
    default int raidsSincePurple()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "pointsSincePurple",
            name = "Regular: points",
            description = "Personal points earned in regular raids since your last regular purple",
            section = streakSection,
            position = 1
    )
    default int pointsSincePurple()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "cmRaidsSincePurple",
            name = "CM: raids",
            description = "Completed Challenge Mode raids since your last CM purple",
            section = streakSection,
            position = 2
    )
    default int cmRaidsSincePurple()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "cmPointsSincePurple",
            name = "CM: points",
            description = "Personal points earned in Challenge Mode since your last CM purple",
            section = streakSection,
            position = 3
    )
    default int cmPointsSincePurple()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "allRaidsSincePurple",
            name = "Combined: raids",
            description = "Completed raids of either mode since your last purple",
            section = streakSection,
            position = 4
    )
    default int allRaidsSincePurple()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "allPointsSincePurple",
            name = "Combined: points",
            description = "Personal points earned in either mode since your last purple",
            section = streakSection,
            position = 5
    )
    default int allPointsSincePurple()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "resetOn",
            name = "Reset streak on",
            description = "Reset only when you get a purple, or when anyone in your team does",
            position = 1
    )
    default ResetOn resetOn()
    {
        return ResetOn.MY_PURPLE;
    }

    @ConfigItem(
            keyName = "resetAllModes",
            name = "Purple resets every mode",
            description = "A purple in either mode resets the Regular, CM and combined streaks. Turn off to reset only the mode you got it in, plus combined.",
            position = 2
    )
    default boolean resetAllModes()
    {
        return true;
    }

    @ConfigItem(
            keyName = "overlayMode",
            name = "Show overlay",
            description = "When to show the dry calc overlay",
            position = 3
    )
    default OverlayMode overlayMode()
    {
        return OverlayMode.IN_COX;
    }

    @ConfigItem(
            keyName = "hideDuringRaid",
            name = "Hide overlay during raid",
            description = "Hide the overlay from \"The raid has begun!\" until the raid is complete",
            position = 4
    )
    default boolean hideDuringRaid()
    {
        return true;
    }

    @ConfigItem(
            keyName = "chatSummary",
            name = "Chat summary after raid",
            description = "Post your current dry streak in chat after each completed raid",
            position = 5
    )
    default boolean chatSummary()
    {
        return true;
    }

    @ConfigItem(
            keyName = "showPanel",
            name = "Show side panel",
            description = "Add a CoX Dry Calc button to the RuneLite sidebar with your stats and recent purples",
            position = 6
    )
    default boolean showPanel()
    {
        return true;
    }

    @ConfigItem(
            keyName = "logRaids",
            name = "Log raids to CSV",
            description = "Write every completed raid to cox-raids.csv, which opens in Excel. Type ::coxexport to save a copy anywhere.",
            position = 7
    )
    default boolean logRaids()
    {
        return true;
    }

    @ConfigSection(
            name = "Google Sheets",
            description = "Add each raid to your own Google Sheet. See the plugin README for the one-time setup.",
            position = 8
    )
    String sheetsSection = "sheets";

    @ConfigItem(
            keyName = "sheetsSync",
            name = "Send raids to Google Sheets",
            description = "Post each completed raid to your Google Apps Script web app. Teammates' names are not sent, only the items they got. Type ::coxsheetsync to upload raids logged before you turned this on.",
            warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
            section = sheetsSection,
            position = 0
    )
    default boolean sheetsSync()
    {
        return false;
    }

    @ConfigItem(
            keyName = "sheetsUrl",
            name = "Web app URL",
            description = "The https://script.google.com/macros/s/.../exec URL from deploying the script",
            secret = true,
            section = sheetsSection,
            position = 1
    )
    default String sheetsUrl()
    {
        return "";
    }
}