"""
Bad Slava Scraper — badslava.com

=== WHY THIS SCRAPER WORKS WHEN THE OTHERS DIDN'T ===

Comedy Listings and EastVille load their data AFTER the page loads using
JavaScript. Our scraper can't run JavaScript, so it sees an empty page.

Bad Slava is different: the mic data is embedded directly in the HTML as
a JavaScript array variable. Even though a browser would use JavaScript
to display it, the RAW DATA is right there in the source code. We don't
need to run JavaScript — we just need to find and parse the text.

Think of it like this:
- Comedy Listings: "Here's an empty page. Run this code to fill it in."
  (Our scraper: "I can't run code, so I see nothing.")
- Bad Slava: "Here's all the data AND the code to display it."
  (Our scraper: "I can't run the code, but I can read the data!")

=== HOW THE DATA IS STRUCTURED ===

Bad Slava stores all mics in a JavaScript array called `venue`:
    var venue = ["entry1", "entry2", ...];

Each entry is a string with fields separated by <br> tags:
    "Monday<br>Mic Name<br><b>Venue Name</b><br>Address<br>City, State<br>Time<br>Cost<br>Frequency<br>Phone"

Field positions (0-indexed):
    0: Day of week (Monday, Tuesday, etc.)
    1: Mic name (can be empty)
    2: Venue name (wrapped in <b> tags)
    3: Street address
    4: City, State
    5: Time (e.g., "7:00pm")
    6: Cost (e.g., "Free", "$5 for 5 minutes", "Paid")
    7: Frequency (e.g., "Weekly", "Monthly", "Biweekly")
    8: Phone number

=== WHAT IS REGEX? ===

regex (Regular Expressions) is a pattern-matching language for text.
Think of it like "Find & Replace" on steroids:
    - re.findall(r"\\d+", "abc123def") → ["123"]  (find all digits)
    - re.sub(r"<.*?>", "", "<b>hello</b>") → "hello"  (strip HTML tags)

We use regex here to:
1. Find the JavaScript array in the page source
2. Extract each entry from the array
3. Strip HTML tags from the venue names
"""

import requests
from bs4 import BeautifulSoup
import re
from datetime import datetime

# ---------------------------------------------------------------------------
# CONFIGURATION
# ---------------------------------------------------------------------------

# The URL for New York comedy open mics
# state=NY filters to New York, type=Comedy filters to comedy mics
BADSLAVA_URL = "https://badslava.com/open-mics-state.php?state=NY&type=Comedy"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/122.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}

# NYC-area cities to keep (Bad Slava lists ALL of New York state)
NYC_CITIES = {
    "new york", "brooklyn", "bronx", "queens", "staten island",
    "long island city", "astoria", "flushing", "jamaica",
}


def scrape_badslava():
    """
    Scrapes Bad Slava's New York comedy open mic page.

    Returns:
        dict with:
            "mics": list of parsed mic dicts (NYC only)
            "all_count": total entries found (all of NY state)
            "nyc_count": entries that are in NYC
            "errors": list of error messages
    """
    errors = []
    mics = []
    all_count = 0

    try:
        # =================================================================
        # STEP 1: FETCH THE PAGE
        # =================================================================
        response = requests.get(BADSLAVA_URL, headers=HEADERS, timeout=15)

        if response.status_code != 200:
            errors.append(f"Got status code {response.status_code}")
            return {"mics": [], "all_count": 0, "nyc_count": 0, "errors": errors}

        page_text = response.text

        # =================================================================
        # STEP 2: EXTRACT THE JAVASCRIPT ARRAY
        #
        # We use regex to find: var venue = ["...", "...", ...];
        #
        # The pattern breakdown:
        #   var\s+venue\s*=\s*\[   → "var venue = [" (with flexible spacing)
        #   (.*?)                  → capture everything inside (non-greedy)
        #   \];                    → ends with "];"
        #
        # re.DOTALL means "." matches newlines too (the array spans many lines)
        # =================================================================
        array_match = re.search(
            r'var\s+venue\s*=\s*\[(.*?)\];',
            page_text,
            re.DOTALL
        )

        if not array_match:
            errors.append(
                "Could not find the 'venue' JavaScript array in the page. "
                "Bad Slava may have changed their page structure."
            )
            return {"mics": [], "all_count": 0, "nyc_count": 0, "errors": errors}

        raw_array = array_match.group(1)

        # =================================================================
        # STEP 3: PARSE EACH ENTRY
        #
        # Each entry is a quoted string: "day<br>name<br>..."
        # We extract all quoted strings from the array.
        #
        # The regex "([^"]*)" captures everything between double quotes.
        # [^"] means "any character that is NOT a double quote."
        # =================================================================
        entries = re.findall(r'"([^"]*)"', raw_array)
        all_count = len(entries)

        for entry in entries:
            # Split on <br> tags to get individual fields
            # The \n in some entries is a literal newline in the source
            fields = [f.strip() for f in entry.replace("\n", "").split("<br>")]

            # We need at least 6 fields (day, name, venue, address, city, time)
            if len(fields) < 6:
                continue

            day = fields[0].strip()
            mic_name = _strip_html(fields[1]).strip()
            venue_name = _strip_html(fields[2]).strip()
            address = _strip_html(fields[3]).strip()
            city_state = _strip_html(fields[4]).strip()
            time_str = fields[5].strip()
            cost = _strip_html(fields[6]).strip() if len(fields) > 6 else None
            frequency = _strip_html(fields[7]).strip() if len(fields) > 7 else None
            phone = _strip_html(fields[8]).strip() if len(fields) > 8 else None

            # =============================================================
            # STEP 4: FILTER TO NYC ONLY
            #
            # Bad Slava lists all of New York state. We only want NYC.
            # We check if the city part matches known NYC areas.
            # =============================================================
            city_lower = city_state.lower().split(",")[0].strip()
            is_nyc = any(nyc_city in city_lower for nyc_city in NYC_CITIES)

            if not is_nyc:
                continue

            # Skip non-weekly mics unless biweekly
            is_biweekly = frequency and "biweekly" in frequency.lower()
            is_monthly = frequency and "monthly" in frequency.lower()

            # If no mic name, use the venue name
            if not mic_name:
                mic_name = f"{venue_name} Open Mic"

            # Validate day of week
            valid_days = {"Monday", "Tuesday", "Wednesday", "Thursday",
                          "Friday", "Saturday", "Sunday"}
            if day not in valid_days:
                continue

            # Convert time to 24hr format
            military_time = _convert_to_24hr(time_str)

            # Guess neighborhood and borough from address/city
            neighborhood = _guess_neighborhood(address, city_state)
            borough = _guess_borough(city_state, neighborhood)

            mic = {
                "name": mic_name,
                "venue": venue_name,
                "address": address,
                "neighborhood": neighborhood,
                "borough": borough,
                "day_of_week": day,
                "start_time": military_time,
                "display_time": time_str.upper().replace("PM", " PM").replace("AM", " AM"),
                "cost": cost,
                "signup_method": "in_person",  # Bad Slava doesn't specify
                "is_biweekly": 1 if is_biweekly else 0,
                "notes": f"Frequency: {frequency}" if is_monthly else None,
                "source": "badslava",
            }

            mics.append(mic)

    except requests.exceptions.Timeout:
        errors.append("Request timed out after 15 seconds.")
    except requests.exceptions.ConnectionError:
        errors.append("Couldn't connect to badslava.com.")
    except Exception as e:
        errors.append(f"Unexpected error: {str(e)}")

    return {
        "mics": mics,
        "all_count": all_count,
        "nyc_count": len(mics),
        "errors": errors,
    }


def _strip_html(text):
    """
    Removes HTML tags from a string.

    re.sub(r'<.*?>', '', text) replaces anything between < and > with nothing.
    The ? makes it "non-greedy" — it matches the SHORTEST possible string,
    so "<b>hello</b>" becomes "hello" (not empty string).
    """
    return re.sub(r'<.*?>', '', text).replace("\\/", "/")


def _convert_to_24hr(time_str):
    """Converts '7:00pm' → '19:00', '5:30PM' → '17:30', etc."""
    time_str = time_str.strip().upper().replace(" ", "")
    try:
        if ":" in time_str:
            dt = datetime.strptime(time_str, "%I:%M%p")
        else:
            dt = datetime.strptime(time_str, "%I%p")
        return dt.strftime("%H:%M")
    except ValueError:
        return time_str


def _guess_neighborhood(address, city_state):
    """Tries to guess NYC neighborhood from address text."""
    combined = (address + " " + city_state).lower()

    neighborhood_hints = {
        "rivington": "LES", "orchard": "LES", "ludlow": "LES",
        "st marks": "East Village", "e. 1": "East Village",
        "macdougal": "Greenwich Village", "bleecker": "Greenwich Village",
        "sullivan": "Greenwich Village",
        "vandam": "SoHo", "spring": "SoHo",
        "w. 7": "UWS", "w. 8": "UWS", "broadway": "Midtown",
        "2nd ave": "UES", "1st ave": "East Village",
        "atlantic": "Brooklyn", "fulton": "Brooklyn",
        "5th ave": "Park Slope",
        "harlem": "Harlem", "nicholas": "Harlem",
        "astoria": "Astoria",
        "bruckner": "Bronx",
    }

    for hint, hood in neighborhood_hints.items():
        if hint in combined:
            return hood
    return None


def _guess_borough(city_state, neighborhood):
    """Maps city/neighborhood to borough."""
    combined = city_state.lower()

    if "brooklyn" in combined:
        return "Brooklyn"
    elif "bronx" in combined:
        return "Bronx"
    elif "queens" in combined or "astoria" in combined:
        return "Queens"
    elif "staten island" in combined:
        return "Staten Island"
    elif neighborhood in ("Brooklyn", "Park Slope", "Gowanus",
                          "Williamsburg", "Bushwick", "South Slope"):
        return "Brooklyn"
    else:
        return "Manhattan"


def compare_badslava_with_database(scraped_mics, existing_mics_df):
    """
    Compares Bad Slava results against our database.

    Matching logic: we try to match by venue name + day of week,
    since mic names can differ between sources.
    """
    new_mics = []
    matched = 0

    for scraped in scraped_mics:
        s_venue = (scraped.get("venue") or "").lower().strip()
        s_day = scraped.get("day_of_week", "")
        s_time = scraped.get("start_time", "")

        if not s_venue or not s_day:
            continue

        # Try matching by venue + day
        found = False
        for _, existing in existing_mics_df.iterrows():
            e_venue = (existing.get("venue") or "").lower().strip()
            e_day = existing.get("day_of_week", "")

            # Check if venue names share significant words
            s_words = set(s_venue.split()) - {"the", "a", "an", "of", "at", "in"}
            e_words = set(e_venue.split()) - {"the", "a", "an", "of", "at", "in"}

            if s_words & e_words and s_day == e_day:
                found = True
                matched += 1
                break

            # Also try matching by address + day + similar time
            if (existing.get("address") and scraped.get("address")
                    and existing["address"][:10].lower() == scraped["address"][:10].lower()
                    and s_day == e_day):
                found = True
                matched += 1
                break

        if not found:
            new_mics.append(scraped)

    return {
        "new_mics": new_mics,
        "matched_mics": matched,
    }
