@file:Suppress("DEPRECATION")
package live.localapp.local.domain.model

// Clock imported fully-qualified below to avoid kotlin.time.Clock conflict
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class Event(
    val id: String,
    val title: String,
    val description: String?,
    val category: String,
    val subcategory: String?,
    val subSubcategory: String? = null,
    val imageUrl: String?,
    val startAt: String?,
    val endAt: String?,
    val isFree: Boolean,
    val priceMin: Double?,
    val priceMax: Double?,
    val ticketUrl: String?,
    val venueName: String?,
    val venueAddress: String?,
    val venueCity: String,
    val venueState: String? = null,
    val venueLat: Double?,
    val venueLng: Double?,
    val eventCity: String? = null,
    val eventState: String? = null,
    val eventLat: Double? = null,
    val eventLng: Double? = null,
    val city: String,
    val source: String? = null,
    val sourceUrl: String? = null,
    val distanceMiles: Double? = null,
    val venueDescription: String? = null,
    /** Photo URL of the joined venue. Last-resort fallback for cards/hero when [imageUrl] is null. */
    val venueImageUrl: String? = null,
    /**
     * True when `startAt` is a real source-provided time. False when the
     * crawler had only a date and defaulted to midnight Eastern. UI should
     * render "Time TBD" / "All day" rather than "12 AM" for false rows.
     */
    val timeKnown: Boolean = true,
) {
    /**
     * City to render on cards / chips. Prefers the physical event location
     * (eventCity) over the host-venue city (venueCity), because the venue is
     * often an organization headquartered far from the actual event site.
     * Strips a trailing source qualifier the crawler leaks into the city
     * field, e.g. "Philadelphia, PA (CVB)" -> "Philadelphia, PA".
     */
    val displayCity: String
        get() {
            val city = eventCity?.takeIf { it.isNotBlank() } ?: venueCity
            return if (city.endsWith(")") && city.contains(" ("))
                city.substringBeforeLast(" (").trim()
            else city
        }

    val priceDisplay: String
        get() = when {
            isFree -> "Free"
            priceMin != null && priceMax != null && priceMin == priceMax -> "$${priceMin.toInt()}"
            priceMin != null -> "From $${priceMin.toInt()}"
            else -> ""
        }

    val distanceDisplay: String
        get() = when {
            distanceMiles == null -> ""
            distanceMiles < 0.1 -> "Here"
            distanceMiles < 10 -> {
                val whole = distanceMiles.toInt()
                val frac = ((distanceMiles - whole) * 10).toInt()
                "$whole.$frac mi"
            }
            else -> "${distanceMiles.toInt()} mi"
        }

    /** True when the event window straddles right now (start ≤ now ≤ end) —
     *  drives the blinking "happening now" indicator. */
    val happeningNow: Boolean
        get() {
            val s = startAt ?: return false
            val e = endAt ?: return false
            return try {
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                Instant.parse(s).toEpochMilliseconds() <= now && now <= Instant.parse(e).toEpochMilliseconds()
            } catch (_: Exception) {
                false
            }
        }

    /** True when there's no confirmed upcoming-or-current date: a null start, or
     *  a window that has already ended. Drives the "Dates TBD" + greyed-title
     *  treatment on the slim restaurant-week card. */
    val dateTbd: Boolean
        get() {
            val s = startAt ?: return true
            return try {
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                Instant.parse(endAt ?: s).toEpochMilliseconds() < now
            } catch (_: Exception) {
                false
            }
        }

    // Compact start–end range for multi-day events (e.g. restaurant weeks):
    // "May 27 – Jun 2", or "Aug 3 – 16" when both ends share a month, or just
    // "Aug 3" for a single day. Falls back to [dateDisplay] if dates won't parse.
    val dateRangeDisplay: String
        get() {
            val s = startAt ?: return dateDisplay
            return try {
                // UTC, not local: a multi-day range is a set of calendar dates, and
                // reading them in the viewer's tz shifts a midnight-UTC start back a
                // day ("Jan 1" → "Dec 31"). UTC matches the intended dates.
                val tz = TimeZone.UTC
                val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
                val start = Instant.parse(s).toLocalDateTime(tz).date
                val startStr = "${months[start.monthNumber - 1]} ${start.dayOfMonth}"
                val e = endAt ?: return startStr
                val end = Instant.parse(e).toLocalDateTime(tz).date
                if (end == start) return startStr
                val endStr = if (end.monthNumber == start.monthNumber) {
                    "${end.dayOfMonth}"
                } else {
                    "${months[end.monthNumber - 1]} ${end.dayOfMonth}"
                }
                "$startStr – $endStr"
            } catch (_: Exception) {
                dateDisplay
            }
        }

    val dateDisplay: String
        get() {
            val s = startAt ?: return ""
            return try {
                val instant = Instant.parse(s)
                val tz = TimeZone.currentSystemDefault()
                val local = instant.toLocalDateTime(tz)
                val now = Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds()).toLocalDateTime(tz)

                val hour = local.hour
                val minute = local.minute
                val amPm = if (hour < 12) "am" else "pm"
                val displayHour = when {
                    hour == 0 -> 12
                    hour > 12 -> hour - 12
                    else -> hour
                }
                // Date-only events (timeKnown=false) hide the time entirely
                // — showing "TBD" reads like the app is broken when in fact
                // the source just didn't publish a time. Many crawl
                // sources (libraries, museums, community calendars) are
                // date-only by design; the day alone is the right surface.
                // Also catches the midnight-local fallback for legacy rows
                // that were ingested with a 00:00 sentinel.
                val time = if (!timeKnown || (hour == 0 && minute == 0)) {
                    null
                } else if (minute == 0) {
                    "$displayHour$amPm"
                } else {
                    "$displayHour:${minute.toString().padStart(2, '0')}$amPm"
                }

                // Calculate day difference using epoch days
                val eventDate = local.date
                val todayDate = now.date
                val dayDiff = (eventDate.toEpochDays() - todayDate.toEpochDays()).toInt()

                val dayLabel = when {
                    dayDiff == 0 -> "Today"
                    dayDiff == 1 -> "Tomorrow"
                    dayDiff in 2..6 -> {
                        val dayNames = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                        dayNames[local.dayOfWeek.ordinal]
                    }
                    else -> {
                        val monthNames = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
                        "${monthNames[local.monthNumber - 1]} ${local.dayOfMonth}"
                    }
                }

                if (time == null) dayLabel else "$dayLabel, $time"
            } catch (_: Exception) {
                ""
            }
        }

    /**
     * True when the event is today or later — i.e. NOT in the past. Saved-events
     * surfaces (home "Saved Events" rail, Plans tab, Places tab) use this to drop
     * events whose date has passed. Date granularity in the viewer's timezone, so
     * a same-day event stays visible through the end of its day. A null or
     * unparseable start is treated as upcoming, so a saved event is never hidden
     * over missing/bad date data (e.g. always-on entries). Compare via parsed
     * Instant — never lexicographically, since ISO strings vary in offset and
     * fractional-second width.
     */
    val isUpcoming: Boolean
        get() {
            val s = startAt ?: return true
            return try {
                val tz = TimeZone.currentSystemDefault()
                val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val eventDay = Instant.parse(s).toLocalDateTime(tz).date.toEpochDays()
                val todayDay = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date.toEpochDays()
                eventDay >= todayDay
            } catch (_: Exception) {
                true
            }
        }
}
