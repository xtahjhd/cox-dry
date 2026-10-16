/*
 * CoX Dry Calc sheet script. gets raids from the plugin and adds them to the sheet.
 *
 * setup: Extensions > Apps Script, paste this in, save.
 * optional: run "setup" from the function dropdown to make the tabs now. running it again
 * rebuilds Summary and Purples, that's how you update to a newer version of this script.
 * then Deploy > New deployment > Web app, execute as Me, access Anyone,
 * and paste the /exec url into the plugin config.
 */
var RAIDS = 'CoX Raids';
var SUMMARY = 'Summary';
var CHART_DATA = 'Chart data';
var PURPLES = 'Purples';
// plugin's modes, '*' matches every raid
var MODES = [['Regular', 'Regular'], ['CM', 'CM'], ['Combined', '*']];

var HEADERS = ['Date', 'Time', 'Mode', 'Team size', 'Team points', 'Your points', 'Purple chance',
  'Your purple', 'Team purples', 'Dry raids', 'Dry points',
  'Total raids', 'Total points', 'Total purples', 'Expected purples'];
var WIDTHS = [95, 75, 75, 75, 100, 100, 100, 170, 230, 80, 100, 90, 110, 100, 120];
var FORMATS = ['yyyy-mm-dd', 'hh:mm:ss', '@', '0', '#,##0', '#,##0', '0.00"%"',
  '@', '@', '#,##0', '#,##0', '#,##0', '#,##0', '0', '0.00'];
var TEXT_COLS = { 2: true, 7: true, 8: true };

var INK = '#2e1065';
var PURPLE = '#7c3aed';
var LILAC = '#ede9fe';
var MINE = '#e9d5ff';

function setup() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  [SUMMARY, CHART_DATA, PURPLES].forEach(function (name) {
    var old = ss.getSheetByName(name);
    if (old) ss.deleteSheet(old);
  });
  ensureSetup(ss);
  updatePurples(ss);
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    var rows = JSON.parse(e.postData.contents).rows || [];
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ensureSetup(ss);

    // date + time + mode is unique per raid, so resyncing won't add dupes
    var seen = {};
    var last = sheet.getLastRow();
    if (last > 1) {
      sheet.getRange(2, 1, last - 1, 3).getDisplayValues().forEach(function (r) { seen[r.join('|')] = true; });
    }
    var fresh = rows.filter(function (r) {
      var key = [r[0], r[1], r[2]].join('|');
      if (seen[key]) return false;
      seen[key] = true;
      return true;
    });

    if (fresh.length) {
      var start = sheet.getLastRow() + 1;
      var need = start + fresh.length - 1 - sheet.getMaxRows();
      if (need > 0) sheet.insertRowsAfter(sheet.getMaxRows(), need + 500);
      sheet.getRange(start, 1, fresh.length, HEADERS.length).setValues(fresh.map(function (r) {
        return HEADERS.map(function (_, i) {
          var v = r[i] === undefined ? '' : r[i];
          if (i < 2 || TEXT_COLS[i] || v === '') return v;
          var n = Number(v);
          return isNaN(n) ? v : n;
        });
      }));
    }
    if (fresh.length) updatePurples(ss);
    var skipped = rows.length - fresh.length;
    return ContentService.createTextOutput('ok ' + fresh.length + ' raid' + (fresh.length === 1 ? '' : 's') + ' added' +
      (skipped ? ', ' + skipped + ' already in the sheet' : ''));
  } finally {
    lock.releaseLock();
  }
}

// makes the raid log, summary and charts. fine to run again
function ensureSetup(ss) {
  var sheet = ss.getSheetByName(RAIDS);
  if (!sheet) {
    sheet = ss.getSheets().length === 1 && ss.getSheets()[0].getLastRow() === 0
      ? ss.getSheets()[0].setName(RAIDS)
      : ss.insertSheet(RAIDS);
    formatRaids(sheet);
  }
  if (!ss.getSheetByName(CHART_DATA)) buildChartData(ss);
  if (!ss.getSheetByName(SUMMARY)) buildSummary(ss);
  if (!ss.getSheetByName(PURPLES)) buildPurples(ss);
  return sheet;
}

function formatRaids(sheet) {
  var n = HEADERS.length;
  if (sheet.getMaxColumns() > n) sheet.deleteColumns(n + 1, sheet.getMaxColumns() - n);
  sheet.getRange(1, 1, 1, n).setValues([HEADERS])
    .setBackground(INK).setFontColor('#ffffff').setFontWeight('bold')
    .setHorizontalAlignment('center').setVerticalAlignment('middle').setWrap(true);
  sheet.setRowHeight(1, 40);
  sheet.setFrozenRows(1);
  sheet.setFrozenColumns(3);
  WIDTHS.forEach(function (w, i) { sheet.setColumnWidth(i + 1, w); });

  var body = sheet.getRange(2, 1, sheet.getMaxRows() - 1, n);
  body.setNumberFormats(body.getValues().map(function () { return FORMATS; }));
  body.setVerticalAlignment('middle');
  sheet.getRange(2, 1, sheet.getMaxRows() - 1, 4).setHorizontalAlignment('center');
  sheet.getRange(1, 1, sheet.getMaxRows(), n).applyRowBanding(SpreadsheetApp.BandingTheme.LIGHT_GREY, true, false);

  var rules = [
    SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied('=$H2<>""')
      .setBackground(MINE).setFontColor(INK).setBold(true).setRanges([body]).build(),
    SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied('=$I2<>""')
      .setBackground(LILAC).setRanges([body]).build(),
    SpreadsheetApp.newConditionalFormatRule().whenTextEqualTo('CM')
      .setFontColor('#c2410c').setBold(true).setRanges([sheet.getRange(2, 3, sheet.getMaxRows() - 1, 1)]).build()
  ];
  sheet.setConditionalFormatRules(rules);
  sheet.getRange(1, 1, sheet.getMaxRows(), n).createFilter();
}

// running totals per mode for the charts, on a hidden tab
function buildChartData(ss) {
  var cd = ss.insertSheet(CHART_DATA);
  var r = "'" + RAIDS + "'!";
  MODES.forEach(function (m, k) {
    var col = 1 + k * 4;
    cd.getRange(1, col, 1, 3).setValues([[m[0] + ' points', 'Your purples', 'Expected purples']]);
    if (m[1] === '*') {
      cd.getRange(2, col).setFormula('=IFERROR(FILTER({SCAN(0,' + r + 'F2:F,LAMBDA(a,x,a+N(x))),' +
        'SCAN(0,' + r + 'H2:H,LAMBDA(a,x,a+IF(x<>"",1,0))),SCAN(0,' + r + 'G2:G,LAMBDA(a,x,a+N(x)/100))},' + r + 'A2:A<>""),{0,0,0})');
    } else {
      cd.getRange(2, col).setFormula('=IFERROR(FILTER({' + r + 'M2:M,' + r + 'N2:N,' + r + 'O2:O},' + r + 'C2:C="' + m[1] + '"),{0,0,0})');
    }
  });
  cd.hideSheet();
}

function buildSummary(ss) {
  var s = ss.insertSheet(SUMMARY, 0);
  var r = "'" + RAIDS + "'!";
  s.setHiddenGridlines(true);
  s.setColumnWidth(1, 24);
  s.setColumnWidth(2, 230);
  [3, 4, 5].forEach(function (c) { s.setColumnWidth(c, 140); });

  s.getRange('B2').setValue('CoX Dry Calc').setFontSize(20).setFontWeight('bold').setFontColor(INK);
  s.getRange('B3').setValue('Your purples against the drop rate, from every raid logged by the plugin.')
    .setFontColor('#6b7280');

  s.getRange('B5:E5').setValues([['', 'Regular', 'CM', 'Both modes']])
    .setBackground(INK).setFontColor('#ffffff').setFontWeight('bold').setHorizontalAlignment('center');

  // rows for the mode (or all raids) since your last purple in either mode, same as the plugin default
  function cond(m) { return m === '*' ? '(' + r + 'A2:A<>"")' : '(' + r + 'C2:C="' + m + '")'; }
  function since(m, values, fallback, fn) {
    return '=LET(last,IFERROR(MAX(FILTER(ROW(' + r + 'H2:H),' + r + 'H2:H<>"")),1),' +
      'f,IFERROR(FILTER(' + values + ',ROW(' + r + 'A2:A)>last,' + cond(m) + '),' + fallback + '),' + fn + ')';
  }
  var stats = [
    ['Raids', function (m) { return '=COUNTIF(' + r + 'C2:C,"' + m + '")'; }, '#,##0'],
    ['Your points', function (m) { return '=SUMIF(' + r + 'C2:C,"' + m + '",' + r + 'F2:F)'; }, '#,##0'],
    ['Your purples', function (m) { return '=COUNTIFS(' + r + 'C2:C,"' + m + '",' + r + 'H2:H,"<>")'; }, '0'],
    ['Expected purples', function (m) { return '=SUMIF(' + r + 'C2:C,"' + m + '",' + r + 'G2:G)/100'; }, '0.00'],
    ['Luck (purples ÷ expected)', function (m, c) { return '=IFERROR(' + c + '8/' + c + '9,"")'; }, '0.00"x"'],
    ['Points per purple', function (m, c) { return '=IFERROR(' + c + '7/' + c + '8,"No purples yet")'; }, '#,##0'],
    ['Current dry raids', function (m) { return since(m, r + 'F2:F', '""', 'COUNT(f)'); }, '#,##0'],
    ['Current dry points', function (m) { return since(m, r + 'F2:F', '0', 'SUM(f)'); }, '#,##0'],
    ['Would have a purple by now', function (m) { return since(m, r + 'G2:G/100', '0', '1-PRODUCT(ARRAYFORMULA(1-f))'); }, '0.0%'],
    ['Last purple', function (m) {
      return '=IFERROR(LET(f,FILTER(' + r + 'H2:H,(' + r + 'H2:H<>"")*' + cond(m) + '),INDEX(f,ROWS(f))),"None yet")';
    }, '@']
  ];
  stats.forEach(function (st, i) {
    var row = 6 + i;
    s.getRange(row, 2).setValue(st[0]).setFontWeight('bold').setFontColor(INK);
    ['C', 'D', 'E'].forEach(function (c, k) {
      s.getRange(c + row).setFormula(st[1](MODES[k][1], c)).setNumberFormat(st[2]).setHorizontalAlignment('center');
    });
  });
  s.getRange(6, 5, stats.length, 1).setFontWeight('bold');
  var table = s.getRange(5, 2, stats.length + 1, 4);
  table.setBorder(true, true, true, true, false, true, '#d4d4d8', SpreadsheetApp.BorderStyle.SOLID);
  s.getRange(6, 2, stats.length, 4).applyRowBanding(SpreadsheetApp.BandingTheme.LIGHT_GREY, false, false);

  buildSizeTable(s, r);

  var cd = ss.getSheetByName(CHART_DATA);
  [2, 0, 1].forEach(function (k, i) {
    var col = 1 + k * 4;
    var chart = s.newChart()
      .setChartType(Charts.ChartType.LINE)
      .addRange(cd.getRange(1, col, 5000, 3))
      .setNumHeaders(1)
      .setPosition(32 + i * 22, 2, 0, 0)
      .setOption('title', (k === 2 ? 'Both modes' : MODES[k][0]) + ': your purples vs the drop rate')
      .setOption('hAxis', { title: 'Your total points' })
      .setOption('vAxis', { title: 'Purples' })
      .setOption('legend', { position: 'bottom' })
      .setOption('series', { 0: { color: PURPLE, lineWidth: 3 }, 1: { color: '#9ca3af', lineWidth: 2, lineDashStyle: [6, 4] } })
      .setOption('width', 620)
      .setOption('height', 380)
      .build();
    s.insertChart(chart);
  });
}

// raids, purples and luck per team size, for each mode and both
function buildSizeTable(s, r) {
  var top = 19;
  var rows = 10;
  var cols = 1 + MODES.length * 3;
  s.getRange(top - 1, 2).setValue('By team size').setFontSize(13).setFontWeight('bold').setFontColor(INK);
  for (var c = 6; c <= cols + 1; c++) s.setColumnWidth(c, 110);
  var head = ['Team size'];
  MODES.forEach(function (m) {
    var name = m[1] === '*' ? 'All' : m[0];
    head.push(name + ' raids', name + ' purples', name + ' luck');
  });
  s.getRange(top, 2, 1, cols).setValues([head])
    .setBackground(INK).setFontColor('#ffffff').setFontWeight('bold').setHorizontalAlignment('center').setWrap(true);
  s.getRange(top + 1, 2).setFormula('=IFERROR(SORT(UNIQUE(FILTER(' + r + 'D2:D,' + r + 'D2:D<>""))),"")');
  for (var i = 1; i <= rows; i++) {
    var row = top + i;
    var size = '$B' + row;
    MODES.forEach(function (m, k) {
      var c = 3 + k * 3;
      var match = r + 'C2:C,"' + m[1] + '",' + r + 'D2:D,' + size;
      s.getRange(row, c).setFormula('=IF(' + size + '="","",COUNTIFS(' + match + '))');
      s.getRange(row, c + 1).setFormula('=IF(' + size + '="","",COUNTIFS(' + match + ',' + r + 'H2:H,"<>"))');
      s.getRange(row, c + 2).setFormula('=IF(' + size + '="","",IFERROR(COUNTIFS(' + match + ',' + r + 'H2:H,"<>")/(SUMIFS(' + r + 'G2:G,' + match + ')/100),""))');
    });
  }
  var body = s.getRange(top + 1, 2, rows, cols);
  body.setHorizontalAlignment('center');
  s.getRange(top + 1, 2, rows, 1).setNumberFormat('[=1]"Solo";[=2]"Duo";0"-man"').setFontWeight('bold').setFontColor(INK);
  var rules = s.getConditionalFormatRules();
  MODES.forEach(function (m, k) {
    var c = 3 + k * 3;
    s.getRange(top + 1, c, rows, 2).setNumberFormat('#,##0');
    var luck = s.getRange(top + 1, c + 2, rows, 1).setNumberFormat('0.00"x"');
    rules.push(SpreadsheetApp.newConditionalFormatRule().whenNumberGreaterThanOrEqualTo(1)
      .setFontColor('#15803d').setBold(true).setRanges([luck]).build());
    rules.push(SpreadsheetApp.newConditionalFormatRule().whenNumberLessThan(1)
      .setFontColor('#b91c1c').setRanges([luck]).build());
  });
  s.setConditionalFormatRules(rules);
  s.getRange(top, 2, rows + 1, cols).setBorder(true, true, true, true, false, true, '#d4d4d8', SpreadsheetApp.BorderStyle.SOLID);
  body.applyRowBanding(SpreadsheetApp.BandingTheme.LIGHT_GREY, false, false);
}

// purples: a row per purple you got, plus a tab for each one with the raids it took

var PURPLE_TOP = 10; // first row of the purples table
var PURPLE_HEAD = ['#', 'Date', 'Item', 'Mode', 'Team size', 'Raids it took', 'Points it took',
  'Expected purples', 'Chance you\'d have one', 'Verdict'];
var PURPLE_FORMATS = ['0', '@', '@', '@', '[=1]"Solo";[=2]"Duo";0"-man"', '#,##0', '#,##0', '0.00', '0.0%', '@'];
var STREAK_HEAD = ['Raids', 'Points', 'Expected purples', 'Chance you\'d have one', 'So far', 'Avg points per purple'];

// how lucky the purple was: % of players who'd have one by then. under half means you beat the odds
function verdict(chance) {
  if (chance < 0.25) return ['Big spoon', '#15803d'];
  if (chance < 0.5) return ['Spoon', '#16a34a'];
  if (chance < 0.75) return ['Dry', '#b91c1c'];
  return ['Very dry', '#7f1d1d'];
}

function buildPurples(ss) {
  var s = ss.insertSheet(PURPLES, 1);
  s.setHiddenGridlines(true);
  s.setColumnWidth(1, 24);
  [50, 95, 190, 80, 80, 100, 110, 110, 140, 100].forEach(function (w, i) { s.setColumnWidth(i + 2, w); });
  s.getRange('B2').setValue('Your purples').setFontSize(20).setFontWeight('bold').setFontColor(INK);
  s.getRange('B3').setValue('Raids and points between your purples, across both modes. Verdict is the share of players who ' +
    'would have had one by then: under 25% big spoon, under 50% spoon, under 75% dry, otherwise very dry.')
    .setFontColor('#6b7280');

  s.getRange('B5').setValue('Current dry streak (both modes)').setFontSize(13).setFontWeight('bold').setFontColor(INK);
  s.getRange(6, 2, 1, STREAK_HEAD.length).setValues([STREAK_HEAD])
    .setBackground(INK).setFontColor('#ffffff').setFontWeight('bold').setHorizontalAlignment('center').setWrap(true);
  s.getRange(7, 2, 1, STREAK_HEAD.length).setNumberFormats([['#,##0', '#,##0', '0.00', '0.0%', '@', '#,##0']])
    .setHorizontalAlignment('center').setFontWeight('bold');
  s.getRange(6, 2, 2, STREAK_HEAD.length).setBorder(true, true, true, true, true, true, '#d4d4d8', SpreadsheetApp.BorderStyle.SOLID);

  s.getRange(PURPLE_TOP - 1, 2, 1, PURPLE_HEAD.length).setValues([PURPLE_HEAD])
    .setBackground(INK).setFontColor('#ffffff').setFontWeight('bold').setHorizontalAlignment('center').setWrap(true);
  s.setRowHeight(PURPLE_TOP - 1, 40);

  var chart = s.newChart()
    .setChartType(Charts.ChartType.COLUMN)
    .addRange(s.getRange(PURPLE_TOP - 1, 4, 201, 1))
    .addRange(s.getRange(PURPLE_TOP - 1, 8, 201, 1))
    .setNumHeaders(1)
    .setPosition(PURPLE_TOP - 1, 13, 0, 0)
    .setOption('title', 'Points it took for each purple')
    .setOption('legend', { position: 'none' })
    .setOption('vAxis', { title: 'Your points' })
    .setOption('colors', [PURPLE])
    .setOption('width', 620)
    .setOption('height', 380)
    .build();
  s.insertChart(chart);
  return s;
}

// goes through the raid log, fills Purples and adds a tab for any new purple
function updatePurples(ss) {
  var sheet = ss.getSheetByName(RAIDS);
  var s = ss.getSheetByName(PURPLES) || buildPurples(ss);
  var raids = [];
  var last = sheet.getLastRow();
  if (last > 1) {
    var range = sheet.getRange(2, 1, last - 1, 8);
    var shown = range.getDisplayValues();
    var vals = range.getValues();
    shown.forEach(function (d, i) {
      if (!d[0]) return;
      raids.push({ date: d[0], time: d[1], mode: d[2], size: Number(vals[i][3]) || 0,
        points: Number(vals[i][5]) || 0, chance: (Number(vals[i][6]) || 0) / 100, purple: d[7] });
    });
    raids.sort(function (a, b) { return (a.date + a.time) < (b.date + b.time) ? -1 : (a.date + a.time) > (b.date + b.time) ? 1 : 0; });
  }

  var purples = [];
  var streak = [];
  raids.forEach(function (r) {
    streak.push(r);
    if (r.purple) {
      purples.push(summarize(streak));
      streak = [];
    }
  });
  var now = summarize(streak);

  var totalPoints = raids.reduce(function (a, r) { return a + r.points; }, 0);
  var v = verdict(now.chance);
  s.getRange(7, 2, 1, STREAK_HEAD.length).setValues([[now.raids.length, now.points, now.expected, now.chance,
    now.raids.length ? v[0] : '-', purples.length ? totalPoints / purples.length : 'No purples yet']]);
  s.getRange(7, 6).setFontColor(now.raids.length ? v[1] : INK);

  var max = s.getMaxRows() - PURPLE_TOP + 1;
  s.getRange(PURPLE_TOP, 2, max, PURPLE_HEAD.length).clear();
  if (!purples.length) {
    s.getRange(PURPLE_TOP, 2).setValue('No purples yet. Good luck.').setFontColor('#6b7280');
    return;
  }
  if (purples.length > max) s.insertRowsAfter(s.getMaxRows(), purples.length - max + 50);
  var body = s.getRange(PURPLE_TOP, 2, purples.length, PURPLE_HEAD.length);
  body.setValues(purples.map(function (p, i) {
    var end = p.raids[p.raids.length - 1];
    return [i + 1, end.date, end.purple, end.mode, end.size, p.raids.length, p.points, p.expected, p.chance, verdict(p.chance)[0]];
  }));
  body.setNumberFormats(purples.map(function () { return PURPLE_FORMATS; }))
    .setHorizontalAlignment('center').setVerticalAlignment('middle')
    .setBorder(true, true, true, true, false, true, '#d4d4d8', SpreadsheetApp.BorderStyle.SOLID);
  s.getRange(PURPLE_TOP, 4, purples.length, 1).setHorizontalAlignment('left').setFontWeight('bold').setFontColor(INK);
  s.getRange(PURPLE_TOP, 11, purples.length, 1).setFontWeight('bold')
    .setFontColors(purples.map(function (p) { return [verdict(p.chance)[1]]; }));

  purples.forEach(function (p, i) {
    var name = tabName(i + 1, p.raids[p.raids.length - 1].purple);
    if (!ss.getSheetByName(name)) buildPurpleTab(ss, name, i + 1, p);
    s.getRange(PURPLE_TOP + i, 2).setFormula('=HYPERLINK("#gid=' + ss.getSheetByName(name).getSheetId() + '","' + (i + 1) + '")');
  });
}

function summarize(streak) {
  var points = 0, expected = 0, dry = 1;
  var rows = streak.map(function (r) {
    points += r.points;
    expected += r.chance;
    dry *= 1 - r.chance;
    return { raid: r, total: points, chance: 1 - dry };
  });
  return { raids: streak, rows: rows, points: points, expected: expected, chance: 1 - dry };
}

function tabName(n, item) {
  return ('Purple ' + n + ' · ' + item).replace(/[\[\]*?\/\\:]/g, '').slice(0, 90);
}

// tab for one purple: how long it took and every raid since the last one
function buildPurpleTab(ss, name, n, p) {
  var end = p.raids[p.raids.length - 1];
  var t = ss.insertSheet(name);
  var v = verdict(p.chance);
  t.setHiddenGridlines(true);
  t.setColumnWidth(1, 24);
  [95, 75, 80, 80, 100, 100, 110, 120].forEach(function (w, i) { t.setColumnWidth(i + 2, w); });

  t.getRange('B2').setValue('Purple ' + n + ': ' + end.purple).setFontSize(18).setFontWeight('bold').setFontColor(INK);
  t.getRange('B3').setValue(end.date + ' · ' + end.mode + ' · ' + (end.size === 1 ? 'Solo' : end.size === 2 ? 'Duo' : end.size + '-man'))
    .setFontColor('#6b7280');

  var stats = [
    ['Raids it took', p.raids.length, '#,##0'],
    ['Points it took', p.points, '#,##0'],
    ['Expected purples over these raids', p.expected, '0.00'],
    ['Chance you\'d have one by then', p.chance, '0.0%'],
    ['Verdict', v[0], '@']
  ];
  stats.forEach(function (st, i) {
    t.getRange(5 + i, 2, 1, 3).merge().setValue(st[0]).setFontWeight('bold').setFontColor(INK);
    t.getRange(5 + i, 5, 1, 2).merge().setValue(st[1]).setNumberFormat(st[2]).setHorizontalAlignment('center');
  });
  t.getRange(9, 5).setFontColor(v[1]).setFontWeight('bold');
  t.getRange(5, 2, stats.length, 5).setBorder(true, true, true, true, false, true, '#d4d4d8', SpreadsheetApp.BorderStyle.SOLID);

  var head = ['Date', 'Time', 'Mode', 'Team size', 'Your points', 'Purple chance', 'Points so far', 'Chance by now'];
  t.getRange(11, 2, 1, head.length).setValues([head])
    .setBackground(INK).setFontColor('#ffffff').setFontWeight('bold').setHorizontalAlignment('center').setWrap(true);
  var body = t.getRange(12, 2, p.rows.length, head.length);
  body.setValues(p.rows.map(function (x) {
    var r = x.raid;
    return [r.date, r.time, r.mode, r.size, r.points, r.chance, x.total, x.chance];
  }));
  body.setNumberFormats(p.rows.map(function () {
    return ['@', '@', '@', '[=1]"Solo";[=2]"Duo";0"-man"', '#,##0', '0.00%', '#,##0', '0.0%'];
  })).setHorizontalAlignment('center');
  t.getRange(11, 2, p.rows.length + 1, head.length).applyRowBanding(SpreadsheetApp.BandingTheme.LIGHT_GREY, true, false);
  t.getRange(11 + p.rows.length, 2, 1, head.length).setBackground(MINE).setFontWeight('bold').setFontColor(INK);
  t.setFrozenRows(11);
}
