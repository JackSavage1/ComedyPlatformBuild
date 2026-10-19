"""
NYC Open Mic Tracker - Main App Entry Point

This is the first file Streamlit runs. It serves as our "home page."
On startup, it:
1. Initializes the database (creates tables if they don't exist)
2. Seeds the database with our known mics (only if it's empty)
3. Shows tonight's mics and tomorrow's mics

How Streamlit works:
- You write Python code, and Streamlit turns it into a web app
- Every time you save this file, the app auto-refreshes in your browser
- st.write(), st.title(), etc. are Streamlit functions that render UI elements
- Streamlit re-runs this ENTIRE file from top to bottom every time you interact
  with the app (click a button, change a slider, etc.)
"""

import streamlit as st
from datetime import datetime

# Import our custom modules
from utils.database import init_db, get_mics_today, get_mics_by_day, get_all_mics
from scrapers.manual_mics import seed_database


def safe_str(value, default=""):
    """Return value as a clean string, or default if it's nan/empty.

    Any database field can come back as nan (pandas' 'empty') which is a float,
    not a string. In Python `if nan:` is TRUE, so a plain `if mic["signup_url"]:`
    check passes and then st.link_button crashes on a float. This helper
    guarantees you always get a real string (or the default) to work with.
    """
    if isinstance(value, str) and value.strip():
        return value
    return default

# ---------------------------------------------------------------------------
# PAGE CONFIG — Must be the first Streamlit command
# ---------------------------------------------------------------------------
st.set_page_config(
    page_title="🎤 NYC Open Mic Tracker",
    page_icon="🎤",
    layout="wide"
)

# ---------------------------------------------------------------------------
# DATABASE INITIALIZATION
#
# st.cache_resource tells Streamlit: "Run this function ONCE and remember
# the result." Without this, the DB would get re-initialized every time
# you click something (because Streamlit re-runs the whole file).
# ---------------------------------------------------------------------------
@st.cache_resource
def setup_database():
    """Initialize DB and seed it. Only runs once per app session."""
    init_db()
    was_seeded = seed_database()
    return was_seeded

was_seeded = setup_database()

# ---------------------------------------------------------------------------
# HEADER
# ---------------------------------------------------------------------------
st.title("🎤 NYC Open Mic Tracker")

# Show today's date and day — strftime formats a date into a readable string
# %A = full day name (Monday), %B = full month (January), %d = day, %Y = year
today = datetime.now()
day_name = today.strftime("%A")
st.subheader(f"📆 {today.strftime('%A, %B %d, %Y')}")

if was_seeded:
    st.success("✅ Database initialized with 32 open mics!")

# ---------------------------------------------------------------------------
# TONIGHT'S MICS
# ---------------------------------------------------------------------------
st.markdown("---")
st.header(f"🌙 Tonight's Mics — {day_name}")

tonight_mics = get_mics_today()

if tonight_mics.empty:
    st.info(f"No open mics listed for {day_name}. Rest day! 😴")
else:
    st.write(f"**{len(tonight_mics)} mics tonight:**")

    for _, mic in tonight_mics.iterrows():
        # Build the urgency indicator
        urgency_dot = {"high": "🔴", "medium": "🟡", "normal": "🟢"}.get(
            mic["urgency"], "🟢"
        )

        # Build cost display
        cost_display = safe_str(mic["cost"], "Free/TBD")

        # Use an expander for each mic — click to see details
        with st.expander(
            f"{urgency_dot} {mic['display_time']} — **{mic['name']}** @ {mic['venue']} · {cost_display}"
        ):
            col1, col2 = st.columns(2)

            with col1:
                address = safe_str(mic["address"], "Address TBD")
                neighborhood = safe_str(mic["neighborhood"])
                borough = safe_str(mic["borough"])
                location_parts = [p for p in (neighborhood, borough) if p]
                location_suffix = f", {', '.join(location_parts)}" if location_parts else ""
                st.write(f"📍 **{address}**{location_suffix}")
                st.write(f"💰 {cost_display}")
                set_length = mic.get("set_length_min")
                # Handle None, empty, or NaN values robustly
                import math
                if set_length not in (None, "") and not (isinstance(set_length, float) and math.isnan(set_length)):
                    try:
                        st.write(f"⏱️ {int(float(set_length))} min sets")
                    except (ValueError, TypeError):
                        st.write("⏱️ Set length: N/A")
                else:
                    st.write("⏱️ Set length: N/A")
                signup_method = safe_str(mic["signup_method"])
                if signup_method:
                    st.write(f"📝 Signup: {signup_method}")
                signup_notes = safe_str(mic["signup_notes"])
                if signup_notes:
                    st.write(f"📌 {signup_notes}")

            with col2:
                urgency_note = safe_str(mic["urgency_note"])
                if mic["urgency"] == "high":
                    st.warning(f"🔴 HIGH URGENCY — {urgency_note or 'Signs up fast!'}")
                elif mic["urgency"] == "medium":
                    st.info(f"🟡 MEDIUM — {urgency_note or 'Book a few days early'}")

                instagram = safe_str(mic["instagram"])
                if instagram:
                    handle = instagram.replace("@", "")
                    st.write(f"📸 [{instagram}](https://instagram.com/{handle})")

                signup_url = safe_str(mic["signup_url"])
                if signup_url:
                    st.link_button("📝 Sign Up →", signup_url)

                notes = safe_str(mic["notes"])
                if notes:
                    st.caption(f"💡 {notes}")

# ---------------------------------------------------------------------------
# TOMORROW'S MICS (preview)
# ---------------------------------------------------------------------------
st.markdown("---")

# Get tomorrow's day name
days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
today_index = days.index(day_name)
tomorrow_name = days[(today_index + 1) % 7]  # % 7 wraps Sunday back to Monday

st.header(f"📅 Coming Up Tomorrow — {tomorrow_name}")

tomorrow_mics = get_mics_by_day(tomorrow_name)

if tomorrow_mics.empty:
    st.info(f"No open mics listed for {tomorrow_name}.")
else:
    st.write(f"**{len(tomorrow_mics)} mics:**")

    for _, mic in tomorrow_mics.iterrows():
        urgency_dot = {"high": "🔴", "medium": "🟡", "normal": "🟢"}.get(
            mic["urgency"], "🟢"
        )
        cost_display = safe_str(mic["cost"], "Free/TBD")
        neighborhood = safe_str(mic["neighborhood"])
        neighborhood_tag = f" ({neighborhood})" if neighborhood else ""

        st.write(
            f"{urgency_dot} **{mic['display_time']}** — {mic['name']} @ {mic['venue']}"
            f"{neighborhood_tag} · {cost_display}"
        )

# ---------------------------------------------------------------------------
# QUICK STATS
# ---------------------------------------------------------------------------
st.markdown("---")
all_mics = get_all_mics()
col1, col2, col3 = st.columns(3)
with col1:
    st.metric("Total Active Mics", len(all_mics))
with col2:
    st.metric("Neighborhoods", all_mics["neighborhood"].nunique())
with col3:
    borough_counts = all_mics["borough"].value_counts()
    top_borough = borough_counts.index[0] if len(borough_counts) > 0 else "N/A"
    st.metric("Most Mics In", top_borough)
