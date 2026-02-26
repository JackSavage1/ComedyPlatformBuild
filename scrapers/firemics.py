"""
FireMics Scraper — firemics.com/new-york-open-mics

=== WHY THIS SCRAPER WORKS ===

FireMics is built with Next.js, a popular React framework. Next.js has a
feature called "server-side rendering" where the page data is pre-loaded
into the HTML as JSON before JavaScript even runs.

It embeds ALL the data in a <script> tag like this:
    <script id="__NEXT_DATA__" type="application/json">
    { ... huge JSON blob with all event data ... }
    </script>

This is similar to Bad Slava — the data is RIGHT THERE in the HTML source.
We don't need to run JavaScript; we just need to find and parse the JSON.

=== HOW THE DATA IS STRUCTURED ===

The JSON follows a React Query "dehydrated state" pattern:
    __NEXT_DATA__
      └─ props
          └─ pageProps
              └─ dehydratedState
                  └─ queries[1]     (the second query holds event data)
                      └─ state
                          └─ data   (array of event instances)

Each event instance looks like:
{
    "id": 87677,
    "event": {
        "name": "Headliners First",
        "location": {"name": "West Side Comedy Club", "address": {"raw": "201 W 75th St..."}},
        "cost": {"kind": "USD", "value": 5.85, "option": "flat_fee"},
        "signup_type": {"option": "online_external", "value": "https://..."},
        "frequency": {"option": "weekly", "instances": [{"weekday": "thursday", ...}]},
        ...
    },
    "start_time": "2026-02-26T20:00:00Z",
    "end_time": "2026-02-26T21:30:00Z"
}

=== WHAT IS __NEXT_DATA__? ===

When Next.js renders a page on the server, it serializes all the data the
page needs into a JSON blob and embeds it in the HTML. The browser-side
JavaScript then "hydrates" this data to make the page interactive.

For us scrapers, this is a goldmine — structured, machine-readable data
without needing to parse messy HTML tables.
"""

import requests
from bs4 import BeautifulSoup
import json
from datetime import datetime

# ---------------------------------------------------------------------------
# CONFIGURATION
# ---------------------------------------------------------------------------
FIREMICS_URL = "https://www.firemics.com/new-york-open-mics"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/122.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}

# Map weekday names from FireMics format to our format
WEEKDAY_MAP = {
    "monday": "Monday",
    "tuesday": "Tuesday",
    "wednesday": "Wednesday",
    "thursday": "Thursday",
    "friday": "Friday",
    "saturday": "Saturday",
    "sunday": "Sunday",
}


def scrape_firemics():
    """
    Scrapes FireMics' New York open mics page.

    Returns:
        dict with:
            "mics": list of parsed mic dicts (deduplicated by event)
            "all_count": total event instances found
            "errors": list of error messages
    """
    errors = []
    mics = []
    all_count = 0

    try:
        # =================================================================
        # STEP 1: FETCH THE PAGE
        # =================================================================
        response = requests.get(FIREMICS_URL, headers=HEADERS, timeout=15)

        if response.status_code != 200:
            errors.append(f"Got status code {response.status_code}")
            return {"mics": [], "all_count": 0, "errors": errors}

        # =================================================================
        # STEP 2: EXTRACT THE __NEXT_DATA__ JSON
        #
        # BeautifulSoup finds the <script id="__NEXT_DATA__"> tag,
        # then we parse its contents as JSON.
        # =================================================================
        soup = BeautifulSoup(response.text, "html.parser")
        next_data_tag = soup.find("script", id="__NEXT_DATA__")

        if not next_data_tag or not next_data_tag.string:
            errors.append(
                "Could not find __NEXT_DATA__ script tag. "
                "FireMics may have changed their website structure."
            )
            return {"mics": [], "all_count": 0, "errors": errors}

        try:
            next_data = json.loads(next_data_tag.string)
        except json.JSONDecodeError as e:
            errors.append(f"Failed to parse __NEXT_DATA__ JSON: {str(e)}")
            return {"mics": [], "all_count": 0, "errors": errors}

        # =================================================================
        # STEP 3: NAVIGATE TO THE EVENT DATA
        #
        # The path: props → pageProps → dehydratedState → queries → state → data
        # We check each level exists to avoid crashes if the structure changes.
        # =================================================================
        try:
            queries = (
                next_data["props"]["pageProps"]["dehydratedState"]["queries"]
            )
        except (KeyError, TypeError):
            errors.append(
                "Could not find dehydrated state in page data. "
                "FireMics may have changed their data structure."
            )
            return {"mics": [], "all_count": 0, "errors": errors}

        # Find the query that contains event data (it's usually the one
        # with the largest data array)
        event_instances = []
        for query in queries:
            data = query.get("state", {}).get("data")
            if isinstance(data, list) and len(data) > len(event_instances):
                event_instances = data

        if not event_instances:
            errors.append("No event data found in any query.")
            return {"mics": [], "all_count": 0, "errors": errors}

        all_count = len(event_instances)

        # =================================================================
        # STEP 4: PARSE EACH EVENT
        #
        # FireMics lists individual event INSTANCES (each specific date),
        # but we want RECURRING events (the mic itself). So we deduplicate
        # by event ID — we only need one entry per unique mic.
        # =================================================================
        seen_event_ids = set()

        for instance in event_instances:
          try:
            event = instance.get("event", {})
            event_id = event.get("id")

            # Skip duplicates (same mic, different date)
            if event_id in seen_event_ids:
                continue
            seen_event_ids.add(event_id)

            # Skip non-comedy events
            event_types = event.get("types", [])
            if event_types and "comedy" not in event_types:
                continue

            # --- Extract basic info ---
            mic_name = event.get("name", "").strip()
            if not mic_name:
                continue

            # --- Location ---
            location = event.get("location", {})
            venue_name = location.get("name", "").strip()
            address_data = location.get("address", {})
            raw_address = address_data.get("raw", "")

            # Parse address: "201 W 75th St, New York, NY 10023, USA"
            # We want just the street part for "address" and city for filtering
            address_parts = raw_address.split(",")
            street_address = address_parts[0].strip() if address_parts else ""
            city = address_parts[1].strip() if len(address_parts) > 1 else ""

            # --- Cost ---
            cost_data = event.get("cost", {})
            cost = _parse_cost(cost_data)

            # --- Signup method ---
            signup_data = event.get("signup_type", {})
            signup_method, signup_url = _parse_signup(signup_data)

            # --- Frequency ---
            frequency = event.get("frequency", {})
            freq_option = frequency.get("option", "")
            is_biweekly = 1 if freq_option == "biweekly" else 0

            # --- Neighborhood & borough ---
            neighborhood = _guess_neighborhood(street_address, city)
            borough = _guess_borough(city, neighborhood)

            # --- Website ---
            venue_url = event.get("website", "")

            # =============================================================
            # STEP 4b: EXTRACT DAY(S) AND TIME(S)
            #
            # FireMics' "weekday" field can be either:
            #   - A string: "thursday"
            #   - A list: ["tuesday", "monday", "wednesday", ...]
            #
            # When it's a list, the mic runs on MULTIPLE days per week,
            # so we create one entry per day (each is a separate mic slot).
            # =============================================================
            freq_instances = frequency.get("instances", [])

            # Collect all (day, start_time) pairs for this event
            day_time_pairs = []

            if freq_instances:
                for freq_inst in freq_instances:
                    weekday_raw = freq_inst.get("weekday", "")
                    freq_start = freq_inst.get("start_time", "")
                    start_time = freq_start[:5] if freq_start else None

                    if not start_time:
                        continue

                    # Handle weekday being a list OR a string
                    if isinstance(weekday_raw, list):
                        for wd in weekday_raw:
                            day = WEEKDAY_MAP.get(wd.lower(), "")
                            if day:
                                day_time_pairs.append((day, start_time))
                    elif isinstance(weekday_raw, str) and weekday_raw:
                        day = WEEKDAY_MAP.get(weekday_raw.lower(), "")
                        if day:
                            day_time_pairs.append((day, start_time))
            else:
                # Fallback: parse from the instance's start_time
                start_str = instance.get("start_time", "")
                if start_str:
                    try:
                        dt = datetime.fromisoformat(
                            start_str.replace("Z", "+00:00")
                        )
                        day_time_pairs.append((
                            dt.strftime("%A"),
                            dt.strftime("%H:%M"),
                        ))
                    except ValueError:
                        pass

            if not day_time_pairs:
                continue

            # Create one mic entry per day/time pair
            for day_of_week, start_time in day_time_pairs:
                display_time = _format_display_time(start_time)

                mic = {
                    "name": mic_name,
                    "venue": venue_name,
                    "address": street_address,
                    "neighborhood": neighborhood,
                    "borough": borough,
                    "day_of_week": day_of_week,
                    "start_time": start_time,
                    "display_time": display_time,
                    "cost": cost,
                    "signup_method": signup_method,
                    "signup_url": signup_url if signup_url else None,
                    "venue_url": venue_url if venue_url else None,
                    "is_biweekly": is_biweekly,
                    "source": "firemics",
                }

                mics.append(mic)

          except Exception as e:
            # Don't let one bad event kill the whole scraper
            errors.append(f"Error parsing event: {str(e)}")

    except requests.exceptions.Timeout:
        errors.append("Request timed out after 15 seconds.")
    except requests.exceptions.ConnectionError:
        errors.append("Couldn't connect to firemics.com.")
    except Exception as e:
        errors.append(f"Unexpected error: {str(e)}")

    return {
        "mics": mics,
        "all_count": all_count,
        "errors": errors,
    }


def _format_display_time(time_24h):
    """Converts '20:00' → '8:00 PM', '14:30' → '2:30 PM'."""
    try:
        dt = datetime.strptime(time_24h, "%H:%M")
        return dt.strftime("%I:%M %p").lstrip("0")
    except ValueError:
        return time_24h


def _parse_cost(cost_data):
    """Parses FireMics cost object into a display string."""
    if not cost_data:
        return None

    option = cost_data.get("option", "")
    value = cost_data.get("value")

    if option == "free" or value == 0:
        return "Free"
    elif value is not None:
        try:
            value = float(value)
        except (ValueError, TypeError):
            return f"${value}"
        # Round to whole dollar if it's close (e.g., 5.85 → "$6")
        rounded = round(value)
        if abs(value - rounded) < 0.5:
            return f"${rounded}"
        return f"${value:.2f}"
    elif option == "custom":
        return cost_data.get("value_custom", "Varies")
    return None


def _parse_signup(signup_data):
    """Parses FireMics signup_type object into method + URL."""
    if not signup_data:
        return "in_person", None

    option = signup_data.get("option", "")
    value = signup_data.get("value", "")

    if "online" in option:
        return "online", value if value else None
    elif "email" in option:
        return "email", value if value else None
    else:
        return "in_person", None


def _guess_neighborhood(address, city):
    """Tries to guess NYC neighborhood from address/city text."""
    combined = (address + " " + city).lower()

    neighborhood_hints = {
        "rivington": "LES", "orchard": "LES", "ludlow": "LES",
        "st marks": "East Village", "e 1": "East Village", "e. 1": "East Village",
        "macdougal": "Greenwich Village", "bleecker": "Greenwich Village",
        "sullivan": "Greenwich Village",
        "vandam": "SoHo", "spring": "SoHo",
        "w 7": "UWS", "w 8": "UWS", "w. 7": "UWS", "w. 8": "UWS",
        "broadway": "Midtown",
        "2nd ave": "UES", "1st ave": "East Village",
        "atlantic": "Brooklyn", "fulton": "Brooklyn",
        "5th ave": "Park Slope",
        "harlem": "Harlem", "nicholas": "Harlem",
        "astoria": "Astoria",
        "bruckner": "Bronx",
        "75th st": "UWS", "w 75": "UWS",
        "14th st": "Union Square", "w 14": "Chelsea", "e 14": "East Village",
        "23rd": "Chelsea", "w 23": "Chelsea",
        "42nd": "Midtown", "times sq": "Midtown",
        "houston": "SoHo",
        "allen": "LES",
        "ave a": "East Village", "ave b": "East Village",
        "ave c": "East Village",
        "smith st": "Boerum Hill", "court st": "Cobble Hill",
        "bedford": "Williamsburg", "berry": "Williamsburg",
    }

    for hint, hood in neighborhood_hints.items():
        if hint in combined:
            return hood
    return None


def _guess_borough(city, neighborhood):
    """Maps city/neighborhood to borough."""
    city_lower = city.lower()

    if "brooklyn" in city_lower:
        return "Brooklyn"
    elif "bronx" in city_lower:
        return "Bronx"
    elif "queens" in city_lower or "astoria" in city_lower:
        return "Queens"
    elif "staten island" in city_lower:
        return "Staten Island"
    elif neighborhood in ("Brooklyn", "Park Slope", "Gowanus",
                          "Williamsburg", "Bushwick", "South Slope",
                          "Boerum Hill", "Cobble Hill"):
        return "Brooklyn"
    else:
        return "Manhattan"


def compare_firemics_with_database(scraped_mics, existing_mics_df):
    """
    Compares FireMics results against our database.

    Matching logic: match by venue name + day of week,
    since mic names can differ between sources.
    """
    new_mics = []
    matched = 0

    for scraped in scraped_mics:
        s_venue = (scraped.get("venue") or "").lower().strip()
        s_name = (scraped.get("name") or "").lower().strip()
        s_day = scraped.get("day_of_week", "")

        if not s_day:
            continue

        found = False
        for _, existing in existing_mics_df.iterrows():
            e_venue = (existing.get("venue") or "").lower().strip()
            e_name = (existing.get("name") or "").lower().strip()
            e_day = existing.get("day_of_week", "")

            if e_day != s_day:
                continue

            # Check if venue names share significant words
            s_words = set(s_venue.split()) - {"the", "a", "an", "of", "at", "in"}
            e_words = set(e_venue.split()) - {"the", "a", "an", "of", "at", "in"}

            if s_words & e_words:
                found = True
                matched += 1
                break

            # Also check mic names
            s_name_words = set(s_name.split()) - {"the", "a", "an", "of", "at", "in", "open", "mic"}
            e_name_words = set(e_name.split()) - {"the", "a", "an", "of", "at", "in", "open", "mic"}

            if s_name_words & e_name_words and len(s_name_words & e_name_words) >= 2:
                found = True
                matched += 1
                break

        if not found:
            new_mics.append(scraped)

    return {
        "new_mics": new_mics,
        "matched_mics": matched,
    }
