"""
EastVille Comedy Club Scraper — eastvillecomedy.com/calendar

=== WHY THIS SCRAPER IS DIFFERENT ===

The Comedy Listings scraper has to parse messy HTML — looking for patterns
in text, guessing what's a venue vs an address, etc. That's typical scraping.

EastVille's website is MUCH easier because it uses JSON-LD.

=== WHAT IS JSON-LD? ===

JSON-LD (JSON for Linked Data) is structured data that websites embed in
their HTML to help search engines (Google, Bing) understand their content.

It looks like this inside the HTML:
    <script type="application/ld+json">
    {
        "@type": "ComedyEvent",
        "name": "The Golden Pen Open Mic",
        "startDate": "2026-01-16T18:00:00-05:00",
        "location": {"name": "Eastville Comedy Club", ...},
        "offers": {"price": "10.00", ...}
    }
    </script>

This is a GIFT for scrapers because:
1. The data is already structured (not buried in messy HTML)
2. It follows a standard format (schema.org)
3. It's machine-readable by design
4. It rarely changes format (because Google depends on it)

We just need to:
1. Find all <script type="application/ld+json"> tags
2. Parse the JSON inside them
3. Filter for events with "Open Mic" in the name
"""

import requests
from bs4 import BeautifulSoup
import json
import time
from datetime import datetime

# ---------------------------------------------------------------------------
# CONFIGURATION
# ---------------------------------------------------------------------------
CALENDAR_URL = "https://www.eastvillecomedy.com/calendar"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/122.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept-Encoding": "gzip, deflate, br",
    "Connection": "keep-alive",
    "Upgrade-Insecure-Requests": "1",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
}

# EastVille's address (constant — all events are at the same venue)
EASTVILLE_ADDRESS = "487 Atlantic Ave"
EASTVILLE_NEIGHBORHOOD = "Brooklyn"
EASTVILLE_BOROUGH = "Brooklyn"


def scrape_eastville():
    """
    Scrapes EastVille's calendar page for open mic events.

    Returns:
        dict with:
            "mics": list of dicts (parsed open mic events)
            "all_events": list of ALL event names found (for debugging)
            "errors": list of error messages
    """
    errors = []
    mics = []
    all_event_names = []

    try:
        # =================================================================
        # STEP 1: FETCH THE PAGE
        #
        # We use a Session to mimic a real browser more closely.
        # A Session object persists cookies and headers across requests.
        # =================================================================
        session = requests.Session()
        session.headers.update(HEADERS)
        response = session.get(CALENDAR_URL, timeout=15)

        if response.status_code != 200:
            errors.append(
                f"Got status code {response.status_code}. "
                f"The calendar page might have moved."
            )
            return {"mics": [], "all_events": [], "errors": errors}

        # =================================================================
        # STEP 2: PARSE HTML AND FIND JSON-LD BLOCKS
        #
        # BeautifulSoup finds all <script> tags with type="application/ld+json".
        # Each one contains a JSON object describing an event.
        # =================================================================
        soup = BeautifulSoup(response.text, "html.parser")

        # Find all JSON-LD script tags
        json_ld_tags = soup.find_all("script", type="application/ld+json")

        if not json_ld_tags:
            errors.append(
                "No JSON-LD data found on the page. "
                "EastVille may have changed their website structure."
            )
            return {"mics": [], "all_events": [], "errors": errors}

        # =================================================================
        # STEP 3: PARSE EACH JSON-LD BLOCK
        #
        # Each <script> tag contains JSON text. We parse it with json.loads()
        # which converts JSON text → Python dicts/lists.
        #
        # The JSON might be a single event object OR a list of events,
        # so we handle both cases.
        # =================================================================
        events = []

        for tag in json_ld_tags:
            try:
                data = json.loads(tag.string)

                # Could be a single event or a list
                if isinstance(data, list):
                    events.extend(data)
                elif isinstance(data, dict):
                    # Could be a single event or a wrapper with @graph
                    if data.get("@type") in ("ComedyEvent", "Event"):
                        events.append(data)
                    elif "@graph" in data:
                        events.extend(data["@graph"])
                    # Some sites wrap events in a different structure
                    elif "event" in data:
                        evt = data["event"]
                        if isinstance(evt, list):
                            events.extend(evt)
                        else:
                            events.append(evt)
            except json.JSONDecodeError as e:
                errors.append(f"Failed to parse a JSON-LD block: {str(e)}")

        # =================================================================
        # STEP 4: FILTER FOR OPEN MIC EVENTS
        #
        # We look for events with "open mic" in the name (case-insensitive).
        # We also grab "pen mic" and "mecca mic" since those are EastVille
        # open mic brands.
        # =================================================================
        open_mic_keywords = [
            "open mic", "pen mic", "mecca mic", "new sh",
            "no name", "ethically ambiguous", "trauma dump",
            "late night open", "marathon",
        ]

        for event in events:
            event_name = event.get("name", "")
            all_event_names.append(event_name)

            # Check if this is an open mic event
            name_lower = event_name.lower()
            is_open_mic = any(kw in name_lower for kw in open_mic_keywords)

            if not is_open_mic:
                continue

            # =============================================================
            # STEP 5: EXTRACT EVENT DATA
            #
            # Parse the startDate (ISO 8601 format) to get day of week
            # and display time.
            #
            # ISO 8601 looks like: "2026-01-16T18:00:00-05:00"
            # The "-05:00" is the timezone offset (Eastern Standard Time)
            # =============================================================
            start_date_str = event.get("startDate", "")
            day_of_week = None
            start_time = None
            display_time = None
            event_date = None

            if start_date_str:
                try:
                    # Parse the ISO date string
                    # fromisoformat handles the timezone offset automatically
                    event_date = datetime.fromisoformat(start_date_str)
                    day_of_week = event_date.strftime("%A")  # "Monday", "Tuesday", etc.
                    start_time = event_date.strftime("%H:%M")  # "18:00"
                    display_time = event_date.strftime("%I:%M %p").lstrip("0")  # "6:00 PM"
                except ValueError:
                    errors.append(f"Couldn't parse date for '{event_name}': {start_date_str}")

            # Get price from the offers section
            cost = None
            offers = event.get("offers", {})
            if isinstance(offers, dict):
                price = offers.get("price")
                if price and float(price) > 0:
                    cost = f"${price}"
                elif price == "0" or price == "0.00":
                    cost = "Free"

            # Build the mic dict
            mic = {
                "name": event_name,
                "venue": "EastVille Comedy Club",
                "address": EASTVILLE_ADDRESS,
                "neighborhood": EASTVILLE_NEIGHBORHOOD,
                "borough": EASTVILLE_BOROUGH,
                "day_of_week": day_of_week,
                "start_time": start_time,
                "display_time": display_time,
                "cost": cost or "1 drink min",
                "signup_method": "in_person",
                "instagram": "@eastvillecomedy",
                "venue_url": event.get("url"),
                "source": "eastville",
                "event_date": event_date.strftime("%Y-%m-%d") if event_date else None,
            }

            mics.append(mic)

    except requests.exceptions.Timeout:
        errors.append("Request timed out after 15 seconds.")
    except requests.exceptions.ConnectionError:
        errors.append("Couldn't connect to eastvillecomedy.com. Site might be down.")
    except Exception as e:
        errors.append(f"Unexpected error: {str(e)}")

    return {
        "mics": mics,
        "all_events": all_event_names,
        "errors": errors,
    }


def compare_eastville_with_database(scraped_mics, existing_mics_df):
    """
    Compares scraped EastVille mics against our database.

    Specifically checks:
    1. Are there any NEW open mic events we don't know about?
    2. Have any TIMES changed for events we already track?

    Returns:
        dict with "new_mics", "time_changes", "confirmed"
    """
    new_mics = []
    time_changes = []
    confirmed = 0

    # Filter existing mics to just EastVille
    ev_existing = existing_mics_df[
        existing_mics_df["venue"].str.contains("EastVille", case=False, na=False)
    ]

    for scraped in scraped_mics:
        s_name = scraped.get("name", "").lower().strip()
        s_day = scraped.get("day_of_week", "")

        if not s_name or not s_day:
            continue

        # Try to match by name similarity + day of week
        # We use "contains" because scraped names might be slightly different
        # e.g. "The Golden Pen Open Mic" vs "Golden Pen Mic"
        matched = False
        for _, existing in ev_existing.iterrows():
            e_name = existing["name"].lower().strip()
            # Check if names share key words
            s_words = set(s_name.replace("the ", "").split())
            e_words = set(e_name.replace("the ", "").split())
            common_words = s_words & e_words  # & = set intersection

            if len(common_words) >= 2 and existing["day_of_week"] == s_day:
                matched = True
                # Check if the time has changed
                if (scraped.get("start_time")
                        and scraped["start_time"] != existing.get("start_time")):
                    time_changes.append({
                        "name": existing["name"],
                        "day": s_day,
                        "existing_id": int(existing["id"]),
                        "old_time": existing.get("display_time") or existing.get("start_time"),
                        "new_time": scraped.get("display_time") or scraped.get("start_time"),
                    })
                else:
                    confirmed += 1
                break

        if not matched:
            new_mics.append(scraped)

    return {
        "new_mics": new_mics,
        "time_changes": time_changes,
        "confirmed": confirmed,
    }
