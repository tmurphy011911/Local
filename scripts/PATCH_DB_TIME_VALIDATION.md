# Patch: Add "Event times match DB" grading criterion to test_chat_v2.js

## Weight

8% (adjust other criterion weights down proportionally so totals remain 100%)

## Placement

Add immediately after the `real_event_ids` criterion in the grading function.
Reuse the Supabase event rows already fetched during event-ID verification
(no extra queries needed).

## Logic

```js
// ---------- event_times_match_db (8%) ----------
function gradeEventTimes(plan, fetchedEvents) {
  // fetchedEvents: Map<event_id, { id, start_at, category }> from the
  // real_event_ids step (query: events?id=eq.{id}&select=id,start_at,category)

  const fixedSlots = plan.slots.filter(s => {
    if (!s.event_id) return false;
    const ev = fetchedEvents.get(s.event_id);
    if (!ev) return false;                       // missing row — scored elsewhere
    if (ev.category === 'always_on') return false; // flexible, any time OK
    if (!ev.start_at) return false;               // no fixed time
    return true;
  });

  if (fixedSlots.length === 0) return 100; // nothing to check

  let wrongCount = 0;

  for (const slot of fixedSlots) {
    const ev = fetchedEvents.get(slot.event_id);

    // Parse DB time (ISO 8601 UTC) → Eastern
    const dbDate = new Date(ev.start_at);
    const etString = dbDate.toLocaleString('en-US', {
      timeZone: 'America/New_York',
      hour: 'numeric',
      minute: 'numeric',
      hour12: true,
    }); // e.g. "7:00 PM"

    const dbMinutes = toMinutesSinceMidnight(etString);
    const slotMinutes = toMinutesSinceMidnight(slot.time); // e.g. "7 PM", "7:30 PM"

    if (Math.abs(dbMinutes - slotMinutes) > 30) {
      wrongCount++;
    }
  }

  if (wrongCount === 0) return 100;
  if (wrongCount === 1) return 50;
  return 0; // multiple wrong or AM/PM flip
}

// Helper — convert "7 PM", "7:30 PM", "12:00 AM", etc. to minutes since midnight
function toMinutesSinceMidnight(timeStr) {
  const match = timeStr.match(/(\d{1,2})(?::(\d{2}))?\s*(AM|PM)/i);
  if (!match) return -1;
  let hours = parseInt(match[1], 10);
  const mins = parseInt(match[2] || '0', 10);
  const period = match[3].toUpperCase();
  if (period === 'PM' && hours !== 12) hours += 12;
  if (period === 'AM' && hours === 12) hours = 0;
  return hours * 60 + mins;
}
```

## Integration checklist

1. Add `event_times_match_db` to the `weights` object with value `0.08`.
2. Reduce existing weights proportionally so they still sum to 1.0.
3. In the grading function, after `real_event_ids` scoring, call
   `gradeEventTimes(plan, fetchedEvents)` and store the result.
4. Include the score in the per-test breakdown and the final weighted average.
5. Add `toMinutesSinceMidnight` as a module-level helper (or inline it).

## Scoring summary

| Outcome | Score |
|---|---|
| All fixed-time slots correct (within 30 min) | 100 |
| All correct except 1 | 50 |
| Multiple wrong or AM/PM mismatch | 0 |
