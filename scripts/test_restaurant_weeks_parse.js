/**
 * Offline unit tests for sync_restaurant_weeks.js pure helpers.
 * Run: node scripts/test_restaurant_weeks_parse.js
 * No network, no DB — exercises date parsing + the organizer-page date fallback.
 */
const assert = require("assert");
const {
  parseDateRange, parseDateRanges, slugOf, extractDatesFromText, looksDiscontinued,
} = require("./sync_restaurant_weeks");

let passed = 0;
const t = (name, fn) => { fn(); passed++; console.log(`  ✓ ${name}`); };

// Fixed "today" so year-inference tests are deterministic.
const TODAY = "2026-08-10";

console.log("parseDateRange / parseDateRanges:");
t("basic cross-month range", () => {
  assert.deepStrictEqual(parseDateRange("February 20 – March 1, 2026"),
    { start: "2026-02-20", end: "2026-03-01" });
});
t("day-only end", () => {
  assert.deepStrictEqual(parseDateRange("August 21 – 30, 2026"),
    { start: "2026-08-21", end: "2026-08-30" });
});
t("multi-edition split on &", () => {
  const r = parseDateRanges("March 2 – 8, 2026 & September 14 – 20, 2026");
  assert.strictEqual(r.length, 2);
  assert.strictEqual(r[0].start, "2026-03-02");
  assert.strictEqual(r[1].start, "2026-09-14");
});
t("placeholder → empty", () => {
  assert.deepStrictEqual(parseDateRanges("Check back for 2026 dates"), []);
});

console.log("\nextractDatesFromText (organizer fallback):");
t("Beverly real string: 'August 14th-23rd' (ordinals, no year) → this-year upcoming", () => {
  const txt = "Restaurant Week as Eat Your Heart Out Beverly! Taking place from August 14th-23rd, this event features restaurants...";
  const r = extractDatesFromText(txt, TODAY);
  assert.strictEqual(r.length >= 1, true);
  assert.strictEqual(r[0].start, "2026-08-14");
  assert.strictEqual(r[0].end, "2026-08-23");
});
t("same-month 'Aug 14-23'", () => {
  const r = extractDatesFromText("Aug 14-23 join us", TODAY);
  assert.deepStrictEqual([r[0].start, r[0].end], ["2026-08-14", "2026-08-23"]);
});
t("cross-month with explicit year", () => {
  const r = extractDatesFromText("August 14 - September 2, 2026", TODAY);
  assert.deepStrictEqual([r[0].start, r[0].end], ["2026-08-14", "2026-09-02"]);
});
t("'through' separator", () => {
  const r = extractDatesFromText("September 14 through 20", TODAY);
  assert.deepStrictEqual([r[0].start, r[0].end], ["2026-09-14", "2026-09-20"]);
});
t("explicit PAST year is rejected (not upcoming)", () => {
  assert.deepStrictEqual(extractDatesFromText("August 15 - 24, 2025", TODAY), []);
});
t("no-year range past THIS year is NOT speculatively rolled to next year", () => {
  // Bare "February 1-8" with today Aug 10 2026 → past this year, no explicit
  // year → rejected (likely last year's leftover, not a real announcement).
  assert.deepStrictEqual(extractDatesFromText("February 1 - 8 downtown", TODAY), []);
});
t("explicit FUTURE year is trusted at any horizon", () => {
  const r = extractDatesFromText("June 6 - 15, 2027", TODAY);
  assert.deepStrictEqual([r[0].start, r[0].end], ["2027-06-06", "2027-06-15"]);
});
t("Dec→Jan wrap with explicit end year", () => {
  const r = extractDatesFromText("December 28 - January 3, 2027", TODAY);
  assert.deepStrictEqual([r[0].start, r[0].end], ["2026-12-28", "2027-01-03"]);
});
t("single dates and non-ranges are ignored", () => {
  assert.deepStrictEqual(extractDatesFromText("Open August 2024. Call 9-5 daily.", TODAY), []);
});
t("implausibly long window (>60d) rejected", () => {
  assert.deepStrictEqual(extractDatesFromText("August 14 - December 30, 2026", TODAY), []);
});
t("soonest-first ordering", () => {
  const r = extractDatesFromText("also November 6 - 15 and first August 14 - 23", TODAY);
  assert.strictEqual(r[0].start, "2026-08-14");
});

console.log("\nlooksDiscontinued:");
t("explicit cancellation fires", () => {
  assert.strictEqual(looksDiscontinued("Sadly, our Restaurant Week has been discontinued."), true);
});
t("'no longer host' fires", () => {
  assert.strictEqual(looksDiscontinued("We no longer host a restaurant week."), true);
});
t("normal page does NOT fire", () => {
  assert.strictEqual(looksDiscontinued("Join us for Restaurant Week, August 14-23!"), false);
});
t("cancellation-policy boilerplate does NOT fire", () => {
  assert.strictEqual(looksDiscontinued("Reservation cancellation policy: 24 hours notice."), false);
});

console.log("\nslugOf:");
t("slug derivation", () => {
  assert.strictEqual(slugOf("Beverly Restaurant Week"), "beverly");
  assert.strictEqual(slugOf("Center City District Restaurant Week"), "center-city-district");
});

console.log(`\n${passed} tests passed.`);
