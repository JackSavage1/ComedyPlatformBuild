"""
Build the sample data that powers "demo mode" in the iOS and Android apps.

Demo mode lets anyone open the apps and click around without a Supabase
login. It also lets CI take real screenshots of every screen.

The mic list comes straight from scrapers/manual_mics.py, so the demo shows
the same NYC mics the real database was seeded with. The sets, plans and
scraper results below are made up, but realistic.

Dates are stored RELATIVE to today (e.g. "weeks_ago": 2) so the demo always
looks current. Each app turns them into real dates when it loads the file —
see the "date rules" in docs/MOBILE_SPEC.md, section 8.

Run from the repo root after changing anything here:
    python3 scripts/build_demo_data.py
"""

import ast
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Where each app bundles the file
OUTPUTS = [
    ROOT / "ios" / "MicTracker" / "Resources" / "DemoData.json",
    ROOT / "android" / "app" / "src" / "main" / "assets" / "demo_data.json",
]

# Approximate coordinates per address. Nominatim can't be reached from CI,
# and the demo only needs pins in the right neighborhoods.
COORDINATES = {
    "406 3rd Ave": (40.6773, -73.9858),
    "508 E 117th St": (40.7954, -73.9323),
    "750A St Nicholas Ave": (40.8245, -73.9385),
    "172 Rivington St": (40.7203, -73.9858),
    "53 Bruckner Blvd": (40.8068, -73.9266),
    "12 St Marks Pl": (40.7290, -73.9886),
    "487 Atlantic Ave": (40.6852, -73.9840),
    "15 Vandam St": (40.7262, -74.0050),
    "236 W 78th St": (40.7834, -73.9800),
    "627 5th Ave": (40.6633, -73.9911),
    "1568 2nd Ave": (40.7745, -73.9535),
    "241 E 24th St": (40.7387, -73.9805),
}

# Mic ids are 1-based positions in MANUAL_MICS.
# 2  = Barrio Boogie (Mon, Comedy In Harlem)
# 4  = Comedians on the Loose (Tue, Black Cat LES, medium urgency, 3 days ahead)
# 8  = Try New Sh*t (Wed, EastVille)
# 10 = Stand Up NY Open Mic (Wed, high urgency)
# 11 = Freddy's Bar (Wed, Free)
# 15 = Best G.D. Open Mic (Thu, EastVille)
# 16 = Comedy Mob (Thu, NY Comedy Club, high urgency)
# 19 = Black Cat LES 9pm (Thu)
# 21 = Golden Pen Mic (Fri, EastVille)
# 27 = Lifeboat Mic (Sat, SoHo Playhouse)
# 1  = Halyards (Mon, Free)

# Logged sets. Date = the mic's most recent weekday strictly before today,
# minus 7 * weeks_ago. Ratings drift upward so the trendline has a story.
SETS = [
    dict(open_mic_id=8, weeks_ago=9, set_rating=3, crowd_rating=4, crowd_size="sparse",
         tags="bombed,nervous", new_material=True, would_return=True,
         set_list="1. Subway bit\n2. Dating app opener\n3. Coffee bit (new)",
         notes="Rushed everything. Coffee bit has no ending yet."),
    dict(open_mic_id=1, weeks_ago=8, set_rating=4, crowd_rating=5, crowd_size="decent",
         tags="nervous,short_set", would_return=True,
         notes="Bar crowd, half watching the game. Got one big laugh on the landlord tag."),
    dict(open_mic_id=11, weeks_ago=7, set_rating=5, crowd_rating=6, crowd_size="decent",
         tags="got_laughs", would_return=True,
         set_list="1. Landlord\n2. Subway bit\n3. Mom's texts",
         notes="Mom's texts is working. Slow down on the subway setup."),
    dict(open_mic_id=19, weeks_ago=6, set_rating=6, crowd_rating=7, crowd_size="packed",
         tags="got_laughs,crowd_work", recording_url="https://youtube.com/watch?v=demo1",
         recording_type="video", would_return=True,
         notes="Packed room. Crowd work on the guy in the Mets hat killed."),
    dict(open_mic_id=2, weeks_ago=6, set_rating=5, crowd_rating=5, crowd_size="sparse",
         tags="new_bit", new_material=True, would_return=True,
         notes="Tried the gym bit. Needs a tighter premise."),
    dict(open_mic_id=15, weeks_ago=5, set_rating=6, crowd_rating=6, crowd_size="decent",
         tags="confident,got_laughs", would_return=True,
         got_feedback=True, feedback_notes="Host said cut the first 30 seconds and open on the landlord line."),
    dict(open_mic_id=21, weeks_ago=4, set_rating=7, crowd_rating=7, crowd_size="decent",
         tags="killed,confident", would_return=True,
         set_list="1. Landlord (new opener)\n2. Mom's texts\n3. Gym bit\n4. Subway closer",
         notes="New opener works. Closer still a little long."),
    dict(open_mic_id=4, weeks_ago=4, set_rating=4, crowd_rating=3, crowd_size="empty",
         tags="bombed,long_set", would_return=False,
         notes="Six people, all comics. Went a minute over and got the light."),
    dict(open_mic_id=27, weeks_ago=3, set_rating=7, crowd_rating=8, crowd_size="packed",
         tags="killed,got_laughs", recording_url="https://drive.google.com/demo-lifeboat",
         recording_type="audio", would_return=True,
         notes="Best room in weeks. Mom's texts got an applause break."),
    dict(open_mic_id=10, weeks_ago=2, set_rating=8, crowd_rating=7, crowd_size="decent",
         tags="killed,confident,got_spotted", would_return=True,
         got_feedback=True, feedback_notes="Booker asked me to send a tape for the Sunday show!",
         notes="Got spotted. Send the tape this week."),
    dict(open_mic_id=8, weeks_ago=2, set_rating=6, crowd_rating=6, crowd_size="decent",
         tags="new_bit,crowd_work", new_material=True, would_return=True,
         notes="Coffee bit finally has an ending. Crowd work bailed me out on the middle."),
    dict(open_mic_id=16, weeks_ago=1, set_rating=8, crowd_rating=8, crowd_size="packed",
         tags="killed,got_laughs,confident", would_return=True,
         set_list="1. Landlord\n2. Coffee bit\n3. Mom's texts\n4. Subway closer",
         recording_url="https://youtube.com/watch?v=demo2", recording_type="video",
         notes="Tightest five yet. Watch the tape for pacing on the closer."),
    dict(open_mic_id=11, weeks_ago=1, set_rating=7, crowd_rating=6, crowd_size="decent",
         tags="got_laughs", would_return=True,
         notes="Solid. Freddy's is a reliable Wednesday."),
    dict(open_mic_id=19, weeks_ago=0, set_rating=9, crowd_rating=9, crowd_size="packed",
         tags="killed,crowd_work,got_laughs", would_return=True,
         notes="Everything hit. Keep this order."),
]

# A skeletal "planned" set from the last few days: marked Going, not logged
# yet. Shows up under "Planned Sets — Ready to Log".
PLANNED_SETS = [
    dict(open_mic_id=21, weeks_ago=0),
]

# Calendar plans relative to today.
#   "past": the mic's most recent weekday strictly before today (- 7 * weeks_ago)
#   "upcoming": the mic's next weekday on/after today (+ 7 * weeks_ahead)
PLANS = [
    dict(open_mic_id=21, status="going", when="past", weeks_ago=0),
    dict(open_mic_id=4, status="going", when="upcoming", weeks_ahead=0),
    dict(open_mic_id=16, status="going", when="upcoming", weeks_ahead=0),
    dict(open_mic_id=11, status="cancelled", when="upcoming", weeks_ahead=0),
    dict(open_mic_id=27, status="going", when="upcoming", weeks_ahead=1),
]

# Mic ratings (the owner's 0-10 opinion of each mic)
MIC_RATINGS = {27: 9.0, 16: 8.5, 19: 8.0, 21: 7.5, 11: 7.0, 8: 6.5, 1: 6.0, 4: 3.5}

# Pending scraper results for the Manage > Import screen
SCRAPED_MICS = [
    dict(id=101, source="badslava", name="Pete's Candy Store Mic", venue="Pete's Candy Store",
         day_of_week="Tuesday", address="709 Lorimer St", display_time="7:30 PM",
         start_time="19:30", cost="Free", signup_url=None,
         payload=dict(name="Pete's Candy Store Mic", venue="Pete's Candy Store",
                      address="709 Lorimer St", neighborhood="Williamsburg", borough="Brooklyn",
                      day_of_week="Tuesday", start_time="19:30", display_time="7:30 PM",
                      cost="Free", signup_method="in_person")),
    dict(id=102, source="badslava", name="Grisly Pear Early Mic", venue="The Grisly Pear",
         day_of_week="Sunday", address="107 MacDougal St", display_time="5:00 PM",
         start_time="17:00", cost="$5 + 1 drink", signup_url=None,
         payload=dict(name="Grisly Pear Early Mic", venue="The Grisly Pear",
                      address="107 MacDougal St", neighborhood="Greenwich Village",
                      borough="Manhattan", day_of_week="Sunday", start_time="17:00",
                      display_time="5:00 PM", cost="$5 + 1 drink", signup_method="in_person")),
    dict(id=103, source="firemics", name="QED Open Mic", venue="QED Astoria",
         day_of_week="Thursday", address="27-16 23rd Ave", display_time="6:00 PM",
         start_time="18:00", cost="$5", signup_url="https://www.firemics.com/qed",
         payload=dict(name="QED Open Mic", venue="QED Astoria", address="27-16 23rd Ave",
                      neighborhood="Astoria", borough="Queens", day_of_week="Thursday",
                      start_time="18:00", display_time="6:00 PM", cost="$5",
                      signup_method="online", signup_url="https://www.firemics.com/qed")),
]

# Scraper history, relative to today
SCRAPE_LOG = [
    dict(id=1, source="badslava", days_ago=0, hour=14, minute=7, status="success",
         notes="34 matched, 2 queued for review"),
    dict(id=2, source="firemics", days_ago=0, hour=14, minute=8, status="success",
         notes="18 matched, 1 queued for review"),
    dict(id=3, source="badslava", days_ago=1, hour=14, minute=7, status="success",
         notes="34 matched, 0 new"),
    dict(id=4, source="firemics", days_ago=1, hour=14, minute=8, status="error",
         notes="Timed out after 30s"),
]

SET_DEFAULTS = dict(new_material=False, got_feedback=False, would_return=True)


def load_manual_mics():
    """Read MANUAL_MICS without importing it (that would need a database)."""
    source = (ROOT / "scrapers" / "manual_mics.py").read_text()
    start = source.index("MANUAL_MICS = [") + len("MANUAL_MICS = ")
    end = source.index("\n]\n", start) + 2
    return ast.literal_eval(source[start:end])


def build():
    mics = []
    for index, mic in enumerate(load_manual_mics(), start=1):
        row = dict(mic)
        row["id"] = index
        row["is_active"] = True
        row.setdefault("is_biweekly", False)
        row.setdefault("urgency", "normal")
        row.setdefault("advance_days", 0)
        lat_lon = COORDINATES.get(row.get("address"))
        if lat_lon:
            row["latitude"], row["longitude"] = lat_lon
        if index in MIC_RATINGS:
            row["mic_rating"] = MIC_RATINGS[index]
        mics.append(row)

    mic_ids = {m["id"] for m in mics}
    sets = []
    next_id = 1
    for entry in SETS + PLANNED_SETS:
        assert entry["open_mic_id"] in mic_ids, entry
        row = {**SET_DEFAULTS, **entry, "id": next_id}
        next_id += 1
        sets.append(row)

    plans = []
    for plan_id, entry in enumerate(PLANS, start=1):
        assert entry["open_mic_id"] in mic_ids, entry
        plans.append({**entry, "id": plan_id})

    return {
        "_comment": "Generated by scripts/build_demo_data.py — edit that script, not this file.",
        "mics": mics,
        "sets": sets,
        "plans": plans,
        "scraped_mics": [{**s, "status": "pending"} for s in SCRAPED_MICS],
        "scrape_log": SCRAPE_LOG,
    }


def main():
    data = json.dumps(build(), indent=2, ensure_ascii=False) + "\n"
    for path in OUTPUTS:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(data)
        print(f"Wrote {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
