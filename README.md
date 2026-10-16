# CoX Dry Calculator

A RuneLite plugin that tracks how dry you are in Chambers of Xeric.

It counts your raids and personal points since your last purple, and shows how likely it is that you'd have gotten one by now. Regular raids, CM, and both modes together are tracked separately.

## Features

- Overlay with your current dry streak for each mode
- Side panel with lifetime stats, luck, stats by team size, and your recent purples
- Chat message after each raid with your streak
- Choose whether the streak resets on your own purple or on any purple in your team
- Every raid is saved to a CSV file you can open in Excel
- Optional sync to your own Google Sheet

## Commands

- `::coxexport` saves a copy of your raid log CSV
- `::coxsheetsync` sends all logged raids to your Google Sheet

## Already dry before installing?

You can type in your current raids and points in the plugin settings under "Current streak".

## Google Sheets setup

1. Open your Google Sheet, go to the Extensions menu and pick Apps Script.
2. Paste in the contents of `google-sheets/Code.gs` and save.
3. Click Deploy, then New deployment, and pick Web app. Set "Execute as" to Me and "Who has access" to Anyone.
4. Copy the URL that ends in /exec and paste it into the plugin settings.

Your teammates' names are never sent to the sheet, only the items they got.

## How the odds work

Your chance of a purple each raid is your team's points divided by 867,600, capped at 570,000 team points. Your share of that is your points divided by the team's points.
