# Carousel Spec: Daily / Weekly / Monthly Recurring Event Carousels

> **Status:** Pending — apply after the CarouselRepositoryImpl segmented-control refactor is complete.
> **Date:** 2026-03-19

---

## Overview

Three new carousel types need to be added to surface recurring and always-on events. These carousels appear on **every subcategory tab** (Music, Comedy, Food, Museums, Tours, etc.) and on the **For You** tab with slightly different scoping rules.

---

## New Carousel Definitions

### 1. "Happens Every Day"

- **Content:** Always-on events that have no specific day restriction — things a user can do any day (e.g., "Visit the MFA" on Museums, "Walk the Freedom Trail" on Tours).
- **Data filter:**
  - `category = "always_on"`
  - No day-of-week restriction (i.e., not limited to a specific weekday).
- **Primary data source:** The 199 curated experiences (`source = "curated"`). These are the main pool for this carousel.
- **Scoping:**
  - On a **subcategory tab** (e.g., "Music"): show only events whose `subcategory` matches that tab.
  - On the **For You** tab: show a mixed set across all subcategories.

### 2. "Weekly Happenings"

- **Content:** Events that recur on a weekly basis (e.g., weekly trivia nights, weekly open mic).
- **Data filter — identify by any of:**
  - `sub_subcategory` value that implies weekly recurrence.
  - Keywords in `title` or `description`: `"weekly"`, `"every week"`, `"every Monday"`, `"every Tuesday"`, `"every Wednesday"`, `"every Thursday"`, `"every Friday"`, `"every Saturday"`, `"every Sunday"` (case-insensitive match).
- **Scoping:**
  - On a **subcategory tab**: show only events whose `subcategory` matches that tab.
  - On the **For You** tab: show a mixed set across all subcategories.

### 3. "Once a Month"

- **Content:** Events that recur monthly (e.g., "First Friday art walk", "Last Saturday flea market").
- **Data filter — identify by any of:**
  - `sub_subcategory` value that implies monthly recurrence.
  - Keywords in `title` or `description`: `"monthly"`, `"once a month"`, `"first Monday"`, `"first Tuesday"`, `"first Wednesday"`, `"first Thursday"`, `"first Friday"`, `"first Saturday"`, `"first Sunday"`, `"last Monday"`, `"last Tuesday"`, `"last Wednesday"`, `"last Thursday"`, `"last Friday"`, `"last Saturday"`, `"last Sunday"`, `"second Saturday"`, `"third Thursday"` (case-insensitive match; expand as needed).
- **Scoping:**
  - On a **subcategory tab**: show only events whose `subcategory` matches that tab.
  - On the **For You** tab: show a mixed set across all subcategories.

---

## Always-On Events in Regular Carousels

Always-on events (`category = "always_on"`) must **also** appear in regular (non-recurring) carousels when they match the subcategory. For example, an always-on museum event should show up in both:

- The Museums tab's "Happens Every Day" carousel, **and**
- The Museums tab's regular dynamic sub-subcategory carousels.

Do not exclude always-on events from existing carousels just because they also appear in the new "Happens Every Day" carousel.

---

## Carousel Ordering

### Subcategory Tabs (e.g., Music, Comedy, Food, etc.)

| Position | Carousel | Notes |
|----------|----------|-------|
| 1 | Coming Up | Existing |
| 2 | This Weekend | Existing |
| 3 | **Happens Every Day** | NEW — always-on events filtered to this subcategory |
| 4 | **Weekly Happenings** | NEW — weekly recurring events filtered to this subcategory |
| 5 | **Once a Month** | NEW — monthly recurring events filtered to this subcategory |
| 6 | Dynamic sub-subcategory carousels | Existing |
| 7 | This Month | Existing |

### For You Tab

Keep the existing carousel order intact. Insert the following **after** the neighborhood carousels:

| Position (relative) | Carousel | Notes |
|----------------------|----------|-------|
| After neighborhoods | **Experiences Near You** | Curated always-on events (`source = "curated"`), all subcategories |
| Next | **Happens Every Day** | Mixed always-on events across all subcategories |
| Next | **Weekly Happenings** | Weekly recurring events across all subcategories |

The "Once a Month" carousel is **not** shown on the For You tab (it only appears on subcategory tabs).

---

## Implementation Guidance

### Keyword Matching

Use case-insensitive substring matching on `title` and `description` fields. Suggested approach:

```
val weeklyKeywords = listOf(
    "weekly", "every week",
    "every monday", "every tuesday", "every wednesday",
    "every thursday", "every friday", "every saturday", "every sunday"
)

val monthlyKeywords = listOf(
    "monthly", "once a month",
    "first monday", "first tuesday", "first wednesday",
    "first thursday", "first friday", "first saturday", "first sunday",
    "last monday", "last tuesday", "last wednesday",
    "last thursday", "last friday", "last saturday", "last sunday",
    "second saturday", "third thursday"
)

fun isWeekly(event: Event): Boolean {
    val text = "${event.title} ${event.description}".lowercase()
    return weeklyKeywords.any { it in text }
}

fun isMonthly(event: Event): Boolean {
    val text = "${event.title} ${event.description}".lowercase()
    return monthlyKeywords.any { it in text }
}
```

### Filtering Logic for "Happens Every Day"

```
fun isDailyAlwaysOn(event: Event): Boolean {
    return event.category == "always_on"
        // Exclude events that are weekly or monthly — they belong in those carousels instead
        && !isWeekly(event)
        && !isMonthly(event)
}
```

### Empty Carousels

If a subcategory has no events matching a given recurring carousel (e.g., no monthly Comedy events), **do not show that carousel** on that tab. Only render carousels that have at least one event.

### Deduplication

An event may match multiple carousels (e.g., an always-on event that is also weekly). Apply this priority:

1. If an event matches **Weekly Happenings**, put it there (not in "Happens Every Day").
2. If an event matches **Once a Month**, put it there (not in "Happens Every Day").
3. Otherwise, if it is always-on, put it in **Happens Every Day**.

This prevents the same event from appearing in both "Happens Every Day" and "Weekly Happenings" within the same tab view. However, always-on events **should** still appear in regular (non-recurring) carousels as noted above.

---

## Summary of Data Fields Used

| Field | Usage |
|-------|-------|
| `category` | `"always_on"` identifies always-on events |
| `subcategory` | Used to scope events to the correct tab |
| `sub_subcategory` | May indicate recurrence pattern (weekly/monthly) |
| `source` | `"curated"` identifies the 199 curated experiences |
| `title` | Keyword matching for weekly/monthly identification |
| `description` | Keyword matching for weekly/monthly identification |
