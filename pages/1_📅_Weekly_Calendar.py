"""
Weekly Calendar View — Page 1

Shows ALL open mics organized into 7 columns (Mon–Sun).
Think of it like a weekly planner where you can see your entire week at a glance.

Key Streamlit concepts used here:
- st.columns() — Creates side-by-side columns for layout
- st.expander() — A collapsible section (click to reveal details)
- st.multiselect() — A dropdown where you can pick multiple options
- st.session_state — Streamlit's way of remembering values between reruns
  (normally, everything resets when you interact with the app)

Filters let you narrow down by neighborhood, cost, signup type, etc.
"""

import streamlit as st
from datetime import datetime, timedelta
from utils.database import (
    init_db, get_all_mics, get_all_sets, add_set,
    set_mic_plan, remove_mic_plan, get_plans_for_week,
    get_going_mic_ids_for_week, get_sets_for_mic_date, delete_mic_hard
)

# Ensure mic_plans table exists (in case app.py's cached init_db ran before
# the table was added to the code)
init_db()

# ---------------------------------------------------------------------------
# PAGE CONFIG
# ---------------------------------------------------------------------------
st.set_page_config(page_title="Weekly Calendar", page_icon="📅", layout="wide")
st.title("📅 Weekly Calendar")
st.caption("All NYC open mics at a glance — click any mic to see full details")

# ---------------------------------------------------------------------------
# WEEK NAVIGATION
#
# Allow users to navigate between weeks (like Google Calendar).
# Useful for mics that require booking several days/weeks in advance.
# ---------------------------------------------------------------------------
days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
today = datetime.now()
today_name = today.strftime("%A")
today_index = days.index(today_name)

# Initialize week offset in session state (0 = this week, 1 = next week, etc.)
if "week_offset" not in st.session_state:
    st.session_state.week_offset = 0

# Week navigation controls
nav_col1, nav_col2, nav_col3, nav_col4, nav_col5 = st.columns([1, 1, 2, 1, 1])

with nav_col1:
    if st.button("◀ Prev", use_container_width=True, disabled=st.session_state.week_offset <= -1):
        st.session_state.week_offset -= 1
        st.rerun()

with nav_col2:
    if st.button("Today", use_container_width=True, disabled=st.session_state.week_offset == 0):
        st.session_state.week_offset = 0
        st.rerun()

with nav_col4:
    if st.button("Next ▶", use_container_width=True, disabled=st.session_state.week_offset >= 3):
        st.session_state.week_offset += 1
        st.rerun()

with nav_col5:
    if st.button("+2 Wks ▶▶", use_container_width=True, disabled=st.session_state.week_offset >= 2):
        st.session_state.week_offset += 2
        st.rerun()

# Calculate the Monday of the selected week
monday_of_this_week = today - timedelta(days=today_index)
monday_of_week = monday_of_this_week + timedelta(weeks=st.session_state.week_offset)

# Week label
week_start_display = monday_of_week.strftime("%b %d")
week_end_display = (monday_of_week + timedelta(days=6)).strftime("%b %d, %Y")

with nav_col3:
    if st.session_state.week_offset == 0:
        st.markdown(f"<h3 style='text-align:center; margin:0;'>This Week</h3>", unsafe_allow_html=True)
    elif st.session_state.week_offset == 1:
        st.markdown(f"<h3 style='text-align:center; margin:0;'>Next Week</h3>", unsafe_allow_html=True)
    elif st.session_state.week_offset > 1:
        st.markdown(f"<h3 style='text-align:center; margin:0;'>{st.session_state.week_offset} Weeks Out</h3>", unsafe_allow_html=True)
    else:
        st.markdown(f"<h3 style='text-align:center; margin:0;'>Last Week</h3>", unsafe_allow_html=True)

st.caption(f"📆 {week_start_display} – {week_end_display}")

# ---------------------------------------------------------------------------
# CALCULATE DATES FOR SELECTED WEEK
# ---------------------------------------------------------------------------
week_dates = {}  # {"Monday": date(2026, 2, 23), ...}
for i, day in enumerate(days):
    week_dates[day] = (monday_of_week + timedelta(days=i)).date()

week_start = week_dates["Monday"].isoformat()
week_end = week_dates["Sunday"].isoformat()

# ---------------------------------------------------------------------------
# LOAD DATA
# ---------------------------------------------------------------------------
all_mics = get_all_mics()
all_sets = get_all_sets()

# Build a set of mic IDs I've visited (for the "haven't been" filter)
visited_mic_ids = set()
if not all_sets.empty:
    visited_mic_ids = set(all_sets["open_mic_id"].dropna().astype(int).tolist())

# Load plans for this week
week_plans = get_plans_for_week(week_start, week_end)
plan_lookup = {}  # {(mic_id, "2026-02-26"): "going" or "cancelled"}
if not week_plans.empty:
    for _, plan in week_plans.iterrows():
        key = (int(plan["open_mic_id"]), str(plan["plan_date"]))
        plan_lookup[key] = plan["status"]

going_mic_ids = get_going_mic_ids_for_week(week_start, week_end)

# ---------------------------------------------------------------------------
# FILTERS
# ---------------------------------------------------------------------------
st.markdown("### 🔍 Filters")
filter_col1, filter_col2, filter_col3, filter_col4, filter_col5 = st.columns(5)

neighborhoods = sorted(all_mics["neighborhood"].dropna().unique().tolist())

with filter_col1:
    selected_neighborhoods = st.multiselect(
        "Neighborhood",
        options=neighborhoods,
        default=[],
        placeholder="All neighborhoods"
    )

with filter_col2:
    cost_filter = st.selectbox(
        "Cost",
        options=["All", "Free only", "Under $6", "Drink min only"],
        index=0
    )

with filter_col3:
    signup_filter = st.selectbox(
        "Signup Type",
        options=["All", "Online", "In-person", "Email", "Instagram DM"],
        index=0
    )

with filter_col4:
    show_unvisited = st.checkbox("Only unvisited", value=False)

with filter_col5:
    show_going_only = st.checkbox("Only going", value=False)

# ---------------------------------------------------------------------------
# APPLY FILTERS
# ---------------------------------------------------------------------------
filtered_mics = all_mics.copy()

if selected_neighborhoods:
    filtered_mics = filtered_mics[filtered_mics["neighborhood"].isin(selected_neighborhoods)]

if cost_filter == "Free only":
    filtered_mics = filtered_mics[
        filtered_mics["cost"].fillna("Free").str.lower().str.contains("free")
    ]
elif cost_filter == "Under $6":
    filtered_mics = filtered_mics[
        filtered_mics["cost"].fillna("Free").str.lower().str.contains("free|\\$[1-5]", regex=True)
    ]
elif cost_filter == "Drink min only":
    filtered_mics = filtered_mics[
        filtered_mics["cost"].fillna("").str.lower().str.contains("drink")
    ]

if signup_filter != "All":
    method_map = {
        "Online": "online",
        "In-person": "in_person",
        "Email": "email",
        "Instagram DM": "instagram_dm",
    }
    filtered_mics = filtered_mics[
        filtered_mics["signup_method"] == method_map.get(signup_filter, "")
    ]

if show_unvisited and visited_mic_ids:
    filtered_mics = filtered_mics[~filtered_mics["id"].isin(visited_mic_ids)]

if show_going_only and going_mic_ids:
    filtered_mics = filtered_mics[filtered_mics["id"].isin(going_mic_ids)]

st.caption(f"Showing {len(filtered_mics)} of {len(all_mics)} mics")

st.markdown("---")

# ---------------------------------------------------------------------------
# WEEKLY CALENDAR LAYOUT
# ---------------------------------------------------------------------------
day_columns = st.columns(7)

for i, day in enumerate(days):
    with day_columns[i]:
        day_date = week_dates[day]
        date_label = day_date.strftime("%m/%d")

        # Only highlight today if we're viewing the current week
        is_today = (day == today_name and st.session_state.week_offset == 0)

        # Check if this day is in the past
        is_past = day_date < today.date()

        if is_today:
            st.markdown(
                f"<h4 style='text-align:center; color:#FF4B4B; "
                f"border-bottom: 3px solid #FF4B4B; padding-bottom: 5px;'>"
                f"✨ {day[:3]} {date_label}</h4>",
                unsafe_allow_html=True
            )
        elif is_past:
            st.markdown(
                f"<h4 style='text-align:center; color:#666; border-bottom: 1px solid #333; "
                f"padding-bottom: 5px;'>{day[:3]} {date_label}</h4>",
                unsafe_allow_html=True
            )
        else:
            st.markdown(
                f"<h4 style='text-align:center; border-bottom: 1px solid #333; "
                f"padding-bottom: 5px;'>{day[:3]} {date_label}</h4>",
                unsafe_allow_html=True
            )

        # Filter mics for this day
        day_mics = filtered_mics[filtered_mics["day_of_week"] == day].sort_values("start_time")

        if day_mics.empty:
            st.caption("No mics")
            continue

        # Display each mic as a compact card
        for _, mic in day_mics.iterrows():
            mic_id = int(mic["id"])
            plan_key = (mic_id, day_date.isoformat())
            plan_status = plan_lookup.get(plan_key, None)

            # Urgency dot
            urgency_dot = {"high": "🔴", "medium": "🟡", "normal": "🟢"}.get(
                mic["urgency"], "🟢"
            )

            # Cost badge
            cost = mic["cost"] if mic["cost"] else "Free"
            if "free" in cost.lower():
                cost_badge = "🆓"
            elif "drink" in cost.lower():
                cost_badge = "🍺"
            else:
                cost_badge = "💵"

            # Biweekly indicator
            biweekly_tag = " 🔄" if mic["is_biweekly"] else ""

            # Status prefix for the expander label
            if plan_status == "going":
                status_prefix = "✅ "
            elif plan_status == "cancelled":
                status_prefix = "❌ "
            else:
                status_prefix = ""

            # Build signup deadline info based on the actual mic date
            signup_deadline = ""
            if mic["advance_days"] and int(mic["advance_days"]) > 0:
                mic_date = datetime.combine(day_date, datetime.min.time())
                signup_by = mic_date - timedelta(days=int(mic["advance_days"]))

                # Calculate days until signup deadline
                days_until_signup = (signup_by.date() - today.date()).days

                if signup_by.date() < today.date():
                    signup_deadline = "⚠️ Signup deadline passed!"
                elif days_until_signup == 0:
                    signup_deadline = "🔴 Sign up TODAY!"
                elif days_until_signup == 1:
                    signup_deadline = "🟡 Sign up by TOMORROW!"
                else:
                    signup_deadline = f"📅 Sign up by {signup_by.strftime('%a %m/%d')} ({days_until_signup} days)"

            # Compact card using an expander
            with st.expander(
                f"{status_prefix}{urgency_dot} {mic['display_time'] or mic['start_time']}\n\n"
                f"**{mic['name']}**\n\n"
                f"{cost_badge} {mic['neighborhood']}{biweekly_tag}"
            ):
                # Status banner at top of card
                if plan_status == "going":
                    st.success("You're going to this mic!")
                elif plan_status == "cancelled":
                    st.error("This mic is marked as cancelled")

                st.write(f"🏠 **{mic['venue']}**")
                st.write(f"📍 {mic['address']}")
                st.write(f"💰 {cost}")

                if mic["signup_method"]:
                    st.write(f"📝 {mic['signup_method'].replace('_', ' ').title()}")

                if mic["signup_notes"]:
                    st.caption(f"📌 {mic['signup_notes']}")

                if signup_deadline:
                    st.info(signup_deadline)

                if mic["urgency"] == "high":
                    st.warning(mic["urgency_note"] or "Signs up fast — book ahead!")
                elif mic["urgency"] == "medium":
                    st.info(mic["urgency_note"] or "Book a few days early")

                if mic["notes"]:
                    st.caption(f"💡 {mic['notes']}")

                if mic["instagram"]:
                    handle = mic["instagram"].replace("@", "")
                    st.markdown(
                        f"📸 [{mic['instagram']}](https://instagram.com/{handle})"
                    )

                if mic["signup_url"]:
                    st.link_button("📝 Sign Up →", mic["signup_url"])

                # Show visit count
                if mic_id in visited_mic_ids:
                    visit_count = len(all_sets[all_sets["open_mic_id"] == mic_id])
                    st.write(f"✅ Been here {visit_count}x")

                if mic["mic_rating"]:
                    st.write(f"⭐ My rating: {mic['mic_rating']}/10")

                # -----------------------------------------------------------
                # ACTION BUTTONS: Going / Cancelled / Clear
                # -----------------------------------------------------------
                st.markdown("---")
                btn_col1, btn_col2, btn_col3 = st.columns(3)

                with btn_col1:
                    if plan_status != "going":
                        if st.button("Going", key=f"go_{mic_id}_{day_date}",
                                     type="primary", use_container_width=True):
                            set_mic_plan(mic_id, day_date.isoformat(), "going")
                            # Auto-create a skeletal set entry if one doesn't exist
                            if not get_sets_for_mic_date(mic_id, day_date.isoformat()):
                                add_set({
                                    "open_mic_id": mic_id,
                                    "date_performed": day_date.isoformat(),
                                })
                            st.rerun()

                with btn_col2:
                    if plan_status != "cancelled":
                        if st.button("Cancelled", key=f"cx_{mic_id}_{day_date}",
                                     use_container_width=True):
                            set_mic_plan(mic_id, day_date.isoformat(), "cancelled")
                            st.rerun()

                with btn_col3:
                    if plan_status is not None:
                        if st.button("Clear", key=f"cl_{mic_id}_{day_date}",
                                     use_container_width=True):
                            remove_mic_plan(mic_id, day_date.isoformat())
                            st.rerun()

                # -----------------------------------------------------------
                # DELETE BUTTON
                # -----------------------------------------------------------
                st.markdown("---")
                if st.button("🗑 Delete this mic", key=f"del_{mic_id}_{day_date}"):
                    st.session_state[f"confirm_delete_{mic_id}"] = True

                if st.session_state.get(f"confirm_delete_{mic_id}", False):
                    st.warning(f"Remove **{mic['name']}** from your database?")
                    del_col1, del_col2 = st.columns(2)
                    with del_col1:
                        if st.button("Yes, delete", key=f"yd_{mic_id}_{day_date}",
                                     type="primary", use_container_width=True):
                            delete_mic_hard(mic_id)
                            if f"confirm_delete_{mic_id}" in st.session_state:
                                del st.session_state[f"confirm_delete_{mic_id}"]
                            st.rerun()
                    with del_col2:
                        if st.button("Cancel", key=f"nd_{mic_id}_{day_date}",
                                     use_container_width=True):
                            del st.session_state[f"confirm_delete_{mic_id}"]
                            st.rerun()
