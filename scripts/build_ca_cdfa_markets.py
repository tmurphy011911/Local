#!/usr/bin/env python3
"""
build_ca_cdfa_markets.py — parse the CDFA "Certified Farmers' Markets by County"
PDF into a normalized JSON the Node farmers-market sync reads.

This is a PERIODIC PREP step, NOT part of the weekly Node pipeline: CA markets
are stable, the source is a PDF (no clean Node table parser), and geocoding 700+
addresses every week would be wasteful. Re-run when CDFA publishes a new PDF.

  GOOGLE_PLACES_API_KEY=... python3 scripts/build_ca_cdfa_markets.py

Output: scripts/data/ca_cdfa_markets.json — [{ id, name, address, city, state,
zip, county, lat, lng, phone, occurrences:[{weekday, startLocal, endLocal,
seasonStart, seasonEnd}] }]. Times are Pacific local (the Node builder stamps
the America/Los_Angeles offset). Rows that fail to geocode are dropped (logged).

Requires: pip install pdfplumber
"""
import json, os, re, sys, time, hashlib, urllib.parse, urllib.request

PDF_URL = "https://www.cdfa.ca.gov/is/docs/CurrentMrktsCounty.pdf"
OUT = os.path.join(os.path.dirname(__file__), "data", "ca_cdfa_markets.json")
KEY = os.environ.get("GOOGLE_PLACES_API_KEY", "").strip()

DAY = {"sun": 6, "mon": 0, "tue": 1, "tues": 1, "wed": 2, "weds": 2,
       "thu": 3, "thur": 3, "thurs": 3, "fri": 4, "sat": 5}
MON = {m: i + 1 for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"])}


def parse_days(s):
    out = []
    for tok in re.split(r"[,/&]| and ", (s or "").lower()):
        tok = tok.strip().rstrip(".")
        for k, v in DAY.items():
            if tok.startswith(k):
                out.append(v)
                break
    return sorted(set(out))


def to24(t):
    m = re.match(r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)?", t.strip().lower())
    if not m:
        return None
    h = int(m.group(1)); mn = m.group(2) or "00"; ap = m.group(3)
    if ap == "pm" and h != 12:
        h += 12
    if ap == "am" and h == 12:
        h = 0
    return f"{h:02d}:{mn}"


def parse_hours(s):
    p = re.split(r"\s*[-–]\s*", (s or "").strip())
    if len(p) != 2:
        return (None, None)
    a, b = p
    if not re.search(r"(am|pm)", a.lower()) and re.search(r"(am|pm)", b.lower()):
        a = a + re.search(r"(am|pm)", b.lower()).group(1)
    return (to24(a), to24(b))


def parse_season(s, year):
    s = (s or "").lower().replace("-", " ").replace("–", " ")
    if "year" in s and "round" in s:
        return (None, None)
    found = [MON[k[:3]] for k in re.findall(r"[a-z]{3,}", s) if k[:3] in MON]
    if len(found) >= 2:
        return (f"{year}-{found[0]:02d}-01", f"{year}-{found[-1]:02d}-28")
    return (None, None)


def parse_location(loc):
    # "777 B St. x Watkins St., Hayward, 94541" → (address, city, zip)
    parts = [p.strip() for p in (loc or "").split(",")]
    zip_ = None
    m = re.search(r"\b(\d{5})\b", loc or "")
    if m:
        zip_ = m.group(1)
    city = None
    if len(parts) >= 2:
        # city is the part before the one containing the zip (or last non-zip part)
        for i, p in enumerate(parts):
            if zip_ and zip_ in p and i > 0:
                city = parts[i - 1]
                break
        if not city:
            city = parts[-2] if len(parts) >= 2 else None
    return (loc, city, zip_)


def geocode(address):
    if not KEY:
        return (None, None)
    url = ("https://maps.googleapis.com/maps/api/geocode/json?"
           + urllib.parse.urlencode({"address": address + ", CA", "key": KEY}))
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            d = json.load(r)
        if d.get("status") == "OK" and d.get("results"):
            loc = d["results"][0]["geometry"]["location"]
            return (loc["lat"], loc["lng"])
        if d.get("status") in ("REQUEST_DENIED", "OVER_QUERY_LIMIT"):
            print(f"  geocode {d.get('status')}: {d.get('error_message','')[:120]}")
            return ("ERR", d.get("status"))
    except Exception as e:
        print(f"  geocode error: {e}")
    return (None, None)


def main():
    import pdfplumber
    pdf_path = "/tmp/ca.pdf"
    if not os.path.exists(pdf_path):
        urllib.request.urlretrieve(PDF_URL, pdf_path)
    year = time.gmtime().tm_year

    rows = []
    with pdfplumber.open(pdf_path) as pdf:
        for pg in pdf.pages:
            for t in pg.extract_tables():
                for r in t:
                    rows.append([(c or "").replace("\n", " ").strip() for c in r])
    data = [r for r in rows if len(r) >= 8 and r[0]
            and r[0].lower() != "county" and "market name" not in (r[1] or "").lower()]

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    out = []
    dropped_nocoord = denied = 0
    for r in data:
        county, name, loc, mgr, days, hours, months, phone = r[:8]
        wd = parse_days(days)
        st, en = parse_hours(hours)
        ms, me = parse_season(months, year)
        occurrences = [{"weekday": w, "startLocal": st or "09:00", "endLocal": en or "13:00",
                        "seasonStart": ms, "seasonEnd": me} for w in wd] if wd else []
        address, city, zip_ = parse_location(loc)
        lat, lng = geocode(address)
        if lat == "ERR":
            denied += 1
            if denied >= 3:
                print("Geocoding repeatedly denied — check GOOGLE_PLACES_API_KEY has Geocoding API enabled. Aborting.")
                sys.exit(1)
            lat = lng = None
        if lat is None or lng is None:
            dropped_nocoord += 1
            continue
        sid = hashlib.md5(f"{name}|{loc}".encode()).hexdigest()[:16]
        out.append({"id": sid, "name": name, "address": address, "city": city,
                    "state": "CA", "zip": zip_, "county": county, "lat": lat, "lng": lng,
                    "phone": phone or None, "occurrences": occurrences})
        time.sleep(0.04)  # be polite to the geocoder

    with open(OUT, "w") as f:
        json.dump(out, f, indent=1)
    print(f"Wrote {len(out)} markets to {OUT} ({dropped_nocoord} dropped for no coords).")


if __name__ == "__main__":
    main()
