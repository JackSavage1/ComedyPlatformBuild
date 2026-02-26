"""
Comedy Listings Scraper — comedylistings.com

=== HOW WEB SCRAPING WORKS (Beginner Explanation) ===

When you visit a website in your browser, here's what happens:
1. Your browser sends an HTTP "request" to the website's server
2. The server sends back HTML — the code that makes up the page
3. Your browser reads the HTML and renders it as the pretty page you see

Web scraping does the SAME THING, but with Python instead of a browser:
1. We use the `requests` library to send an HTTP request (like visiting the URL)
2. We get back raw HTML (the source code of the page)
3. We use `BeautifulSoup` to parse that HTML into a searchable tree
4. We use CSS selectors or tag names to find the specific data we want

Think of it like this:
- requests = "go to this website and bring back everything you see"
- BeautifulSoup = "now let me search through what you brought back"

=== WHAT IS A CSS SELECTOR? ===

CSS selectors are patterns used to find HTML elements. Examples:
- "div"          → finds all <div> elements
- ".classname"   → finds elements with class="classname"
- "#myid"        → finds the element with id="myid"
- "div.listing"  → finds <div class="listing"> elements
- "h2 a"         → finds <a> links inside <h2> headings

=== WHAT IS A USER-AGENT? ===

When your browser visits a website, it sends a "User-Agent" header that says
"Hi, I'm Chrome on Windows" (or whatever browser you use). Scrapers should
do the same — it's polite, and some websites block requests without one.

=== WHY ADD DELAYS? ===

If we request 7 pages instantly, the website's server gets slammed with
traffic from one source. That's rude (and might get us blocked). Adding a
1-2 second delay between requests is the polite thing to do.

=== ABOUT THIS SPECIFIC SCRAPER ===

comedylistings.com organizes NYC open mics by day of week:
  /new-york-open-mics-monday
  /new-york-open-mics-tuesday
  ...etc.

Each page lists mics with: time, venue name, address, cost, signup method.
The scraper visits each day's page, parses the listings, and compares them
against what we already have in our database.

IMPORTANT: Web scraping is FRAGILE. If the website changes its layout,
the scraper will break. That's normal — you just update the CSS selectors.
This scraper may need adjustments on first run depending on the current
site structure.
"""

import requests
from bs4 import BeautifulSoup
import time
import re
from datetime import datetime

# ---------------------------------------------------------------------------
# CONFIGURATION
# ---------------------------------------------------------------------------

# The base URL patterns — we try multiple because the site may change its paths.
# {day} gets replaced with "monday", "tuesday", etc.
URL_PATTERNS = [
    "https://www.comedylistings.com/new-york-open-mics-{day}",
    "https://www.comedylistings.com/new-york-open-mics/{day}",
    "https://www.comedylistings.com/open-mics-{day}",
]

# Days to scrape (lowercase for the URL)
DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]

# Headers to send with each request — we make this look as much like a real
# browser as possible. Some websites (especially Squarespace sites like
# comedylistings.com) check these headers and block requests that look automated.
#
# WHY 404s HAPPEN:
# A 404 doesn't always mean "page not found." Some websites return 404 to
# block scrapers while serving the real page to browsers. This is because
# browsers send many more headers (cookies, referers, etc.) than a simple
# Python script. If we keep getting 404s, the site may require JavaScript
# rendering (which requests can't do — you'd need Selenium or Playwright).
HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/122.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept-Encoding": "gzip, deflate, br",
    "DNT": "1",
    "Connection": "keep-alive",
    "Upgrade-Insecure-Requests": "1",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Sec-Fetch-User": "?1",
    "Cache-Control": "max-age=0",
}

# How long to wait between page requests (in seconds) — be polite!
REQUEST_DELAY = 2


def scrape_comedy_listings():
    """
    Scrapes all 7 day-of-week pages from comedylistings.com.

    Returns:
        dict with:
            "mics": list of dicts (parsed mic data)
            "errors": list of error messages
            "pages_scraped": how many pages were successfully fetched
    """
    all_mics = []
    errors = []
    pages_scraped = 0

    # First, try to establish a session (like opening a browser)
    # A session reuses cookies across requests, making us look more like a real browser.
    session = requests.Session()
    session.headers.update(HEADERS)

    for day in DAYS:
        day_title = day.capitalize()  # "monday" → "Monday"

        try:
            # =============================================================
            # STEP 1: FETCH THE PAGE
            #
            # requests.get() sends an HTTP GET request — the same thing
            # your browser does when you type a URL and hit Enter.
            #
            # timeout=15 means: give up if the server doesn't respond
            # within 15 seconds (don't hang forever).
            #
            # We try multiple URL patterns because sites change their paths.
            # =============================================================
            response = None
            used_url = None

            for pattern in URL_PATTERNS:
                url = pattern.format(day=day)
                resp = session.get(url, timeout=15)
                if resp.status_code == 200:
                    response = resp
                    used_url = url
                    break

            # Check if ANY URL pattern worked
            if response is None or response.status_code != 200:
                status = response.status_code if response else "no response"
                errors.append(
                    f"{day_title}: All URL patterns returned errors (last: {status}). "
                    f"The site may be blocking automated requests or has changed its URL structure. "
                    f"This is common with Squarespace sites — they often require JavaScript to render."
                )
                continue

            # =============================================================
            # STEP 2: PARSE THE HTML
            #
            # BeautifulSoup takes the raw HTML string and builds a "tree"
            # we can search through. Think of HTML like nested boxes —
            # BS4 lets us find specific boxes by their labels.
            #
            # "html.parser" is Python's built-in HTML parser. Other options
            # like "lxml" are faster but require an extra install.
            # =============================================================
            soup = BeautifulSoup(response.text, "html.parser")

            # =============================================================
            # STEP 3: FIND THE LISTINGS
            #
            # This is the fragile part — we're looking for specific HTML
            # patterns. comedylistings.com is built on Squarespace, which
            # typically uses these patterns for content blocks.
            #
            # We try multiple selector strategies because websites change.
            # If one doesn't work, the next might.
            # =============================================================
            mics_found = _parse_page(soup, day_title)

            if mics_found:
                all_mics.extend(mics_found)
                pages_scraped += 1
            else:
                errors.append(
                    f"{day_title}: Page loaded but couldn't parse any listings. "
                    f"The site structure may have changed."
                )

        except requests.exceptions.Timeout:
            errors.append(f"{day_title}: Request timed out after 15 seconds.")
        except requests.exceptions.ConnectionError:
            errors.append(f"{day_title}: Couldn't connect. Site might be down.")
        except Exception as e:
            errors.append(f"{day_title}: Unexpected error — {str(e)}")

        # Be polite — wait between requests so we don't overload the server
        time.sleep(REQUEST_DELAY)

    return {
        "mics": all_mics,
        "errors": errors,
        "pages_scraped": pages_scraped,
    }


def _parse_page(soup, day_of_week):
    """
    Attempts to parse open mic listings from a BeautifulSoup page.

    We try multiple parsing strategies because Squarespace sites
    can structure content in different ways. If strategy A fails,
    we fall back to strategy B, etc.

    Args:
        soup: A BeautifulSoup object (the parsed HTML)
        day_of_week: "Monday", "Tuesday", etc.

    Returns:
        List of mic dicts, or empty list if parsing failed.
    """
    mics = []

    # ===================================================================
    # STRATEGY 1: Look for structured content blocks
    #
    # Squarespace often wraps content in divs with specific classes.
    # Common patterns include .sqs-block-content, .entry-content,
    # .page-section, etc.
    # ===================================================================

    # Try to find all text content blocks on the page
    content_blocks = soup.find_all(["div", "section"], class_=re.compile(
        r"(sqs-block-content|entry-content|content-wrapper|page-section|"
        r"col sqs-col|rich-text|preFade)"
    ))

    # If we didn't find structured blocks, just grab all the page text
    if not content_blocks:
        content_blocks = [soup.find("main") or soup.find("body") or soup]

    # ===================================================================
    # STRATEGY 2: Parse text content looking for time patterns
    #
    # Regardless of HTML structure, mic listings usually follow a pattern:
    #   TIME — VENUE NAME — ADDRESS — COST — etc.
    #
    # We scan through all text looking for time patterns like
    # "4:30PM", "7:00 PM", "9pm" which signal the start of a listing.
    # ===================================================================

    # Get all text from the page, split by newlines/paragraphs
    full_text = ""
    for block in content_blocks:
        full_text += block.get_text(separator="\n", strip=True) + "\n"

    # Split into lines and look for listing patterns
    lines = [line.strip() for line in full_text.split("\n") if line.strip()]

    # Time pattern: matches "4:30PM", "7:00 PM", "9pm", "10:30 pm", etc.
    time_pattern = re.compile(
        r"^(\d{1,2}(?::\d{2})?\s*(?:AM|PM|am|pm))",
        re.IGNORECASE
    )

    current_mic = None

    for line in lines:
        time_match = time_pattern.match(line)

        if time_match:
            # If we were building a previous mic, save it
            if current_mic and current_mic.get("name"):
                mics.append(current_mic)

            # Start a new mic entry
            raw_time = time_match.group(1).strip().upper()
            military_time = _convert_to_24hr(raw_time)

            # The rest of the line after the time might contain the venue name
            remainder = line[time_match.end():].strip().lstrip("—–-:").strip()

            current_mic = {
                "day_of_week": day_of_week,
                "display_time": raw_time,
                "start_time": military_time,
                "name": remainder if remainder else None,
                "venue": None,
                "address": None,
                "cost": None,
                "signup_method": None,
                "source": "comedy_listings",
            }

        elif current_mic:
            # We're in the middle of parsing a listing — this line is
            # additional detail (venue, address, cost, etc.)
            line_lower = line.lower()

            # Try to detect what kind of info this line contains
            if _looks_like_address(line):
                current_mic["address"] = line
            elif any(word in line_lower for word in ["$", "free", "drink min", "item min", "no cover"]):
                current_mic["cost"] = line
            elif any(word in line_lower for word in ["sign up", "signup", "first come", "bucket", "list"]):
                if not current_mic.get("signup_method"):
                    current_mic["signup_method"] = _detect_signup_method(line)
                    current_mic["signup_notes"] = line
            elif not current_mic.get("name"):
                current_mic["name"] = line
            elif not current_mic.get("venue"):
                current_mic["venue"] = line
            elif current_mic.get("name") and not current_mic.get("venue"):
                current_mic["venue"] = line

    # Don't forget the last mic being built
    if current_mic and current_mic.get("name"):
        mics.append(current_mic)

    # Clean up: if venue is missing, use name as venue
    for mic in mics:
        if not mic.get("venue"):
            mic["venue"] = mic.get("name", "Unknown Venue")
        # Try to extract neighborhood from address
        mic["neighborhood"] = _guess_neighborhood(mic.get("address", ""))
        mic["borough"] = _guess_borough(mic.get("neighborhood", ""))

    return mics


def _convert_to_24hr(time_str):
    """
    Converts "7:30 PM" → "19:30", "4PM" → "16:00", etc.

    This is needed because we store times in 24-hour format for proper
    sorting (so "9:00 PM" sorts AFTER "1:00 PM", not before).
    """
    time_str = time_str.strip().upper().replace(" ", "")

    try:
        # Try "7:30PM" format
        if ":" in time_str:
            dt = datetime.strptime(time_str, "%I:%M%p")
        else:
            # Try "7PM" format (no minutes)
            dt = datetime.strptime(time_str, "%I%p")
        return dt.strftime("%H:%M")
    except ValueError:
        return time_str  # Return as-is if we can't parse


def _looks_like_address(text):
    """
    Heuristic: does this text look like a street address?

    Looks for patterns like "123 Main St" or "487 Atlantic Ave".
    A heuristic is a "good enough" rule — not perfect, but works most of the time.
    """
    # Check for a number followed by a street-like word
    return bool(re.search(
        r"\d+\s+(W|E|N|S|West|East|North|South)?\s*\d*\s*\w+\s+"
        r"(St|Ave|Blvd|Rd|Way|Pl|Dr|Ln|Ct|Broadway|Street|Avenue|Place)",
        text,
        re.IGNORECASE
    ))


def _detect_signup_method(text):
    """Tries to figure out the signup method from descriptive text."""
    text_lower = text.lower()
    if any(w in text_lower for w in ["online", "website", "link", "book"]):
        return "online"
    elif any(w in text_lower for w in ["email", "e-mail"]):
        return "email"
    elif any(w in text_lower for w in ["dm", "instagram", "ig"]):
        return "instagram_dm"
    else:
        return "in_person"


def _guess_neighborhood(address):
    """
    Tries to guess the neighborhood from an address string.
    This is very rough — just checks for known NYC neighborhood keywords.
    """
    if not address:
        return None

    address_lower = address.lower()
    neighborhoods = {
        "brooklyn": "Brooklyn",
        "gowanus": "Gowanus",
        "williamsburg": "Williamsburg",
        "bushwick": "Bushwick",
        "south slope": "South Slope",
        "park slope": "Park Slope",
        "atlantic ave": "Brooklyn",
        "harlem": "Harlem",
        "les": "LES",
        "rivington": "LES",
        "east village": "East Village",
        "st marks": "East Village",
        "west village": "West Village",
        "soho": "SoHo",
        "vandam": "SoHo",
        "uws": "UWS",
        "upper west": "UWS",
        "ues": "UES",
        "upper east": "UES",
        "gramercy": "Gramercy",
        "midtown": "Midtown",
        "chelsea": "Chelsea",
        "hells kitchen": "Hell's Kitchen",
        "hell's kitchen": "Hell's Kitchen",
        "astoria": "Astoria",
        "queens": "Queens",
        "bronx": "Bronx",
        "bruckner": "Bronx",
    }

    for keyword, hood in neighborhoods.items():
        if keyword in address_lower:
            return hood
    return None


def _guess_borough(neighborhood):
    """Maps a neighborhood to its borough."""
    if not neighborhood:
        return None

    brooklyn_hoods = {"Brooklyn", "Gowanus", "Williamsburg", "Bushwick",
                      "South Slope", "Park Slope"}
    bronx_hoods = {"Bronx"}
    queens_hoods = {"Queens", "Astoria"}

    if neighborhood in brooklyn_hoods:
        return "Brooklyn"
    elif neighborhood in bronx_hoods:
        return "Bronx"
    elif neighborhood in queens_hoods:
        return "Queens"
    else:
        return "Manhattan"


def compare_with_database(scraped_mics, existing_mics_df):
    """
    Compares scraped mics against what's already in our database.

    This is the KEY safety feature — we NEVER auto-insert scraped data.
    Instead, we flag what's NEW and what's CHANGED so you can review it.

    Args:
        scraped_mics: List of dicts from the scraper
        existing_mics_df: DataFrame from get_all_mics()

    Returns:
        dict with:
            "new_mics": list of mics not in our database
            "changed_mics": list of mics where data differs
            "matched_mics": count of mics that matched perfectly
    """
    new_mics = []
    changed_mics = []
    matched = 0

    for scraped in scraped_mics:
        s_name = (scraped.get("name") or "").lower().strip()
        s_day = (scraped.get("day_of_week") or "").strip()

        if not s_name or not s_day:
            continue

        # Try to find a match in the database by name + day
        matches = existing_mics_df[
            (existing_mics_df["name"].str.lower().str.strip() == s_name)
            & (existing_mics_df["day_of_week"] == s_day)
        ]

        if matches.empty:
            # Also try matching by venue + day + similar time
            matches = existing_mics_df[
                (existing_mics_df["venue"].str.lower().str.contains(
                    s_name.split("@")[0].strip() if "@" in s_name else s_name[:10],
                    na=False
                ))
                & (existing_mics_df["day_of_week"] == s_day)
            ]

        if matches.empty:
            new_mics.append(scraped)
        else:
            # Check if key fields have changed
            existing = matches.iloc[0]
            changes = {}

            if scraped.get("start_time") and scraped["start_time"] != existing.get("start_time"):
                changes["start_time"] = {
                    "old": existing.get("start_time"),
                    "new": scraped["start_time"]
                }
            if scraped.get("cost") and scraped["cost"] != existing.get("cost"):
                changes["cost"] = {
                    "old": existing.get("cost"),
                    "new": scraped["cost"]
                }

            if changes:
                changed_mics.append({
                    "name": scraped.get("name"),
                    "day": s_day,
                    "existing_id": int(existing["id"]),
                    "changes": changes,
                })
            else:
                matched += 1

    return {
        "new_mics": new_mics,
        "changed_mics": changed_mics,
        "matched_mics": matched,
    }
