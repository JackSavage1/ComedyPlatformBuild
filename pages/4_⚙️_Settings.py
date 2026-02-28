"""
Settings & Data Management — Page 4

Three sections:
A) Add a Mic Manually — A form to add a new open mic to the database
B) Bulk Import from Scrapers — Pull mics from Bad Slava and FireMics
C) Manage Mics — View, edit, and deactivate existing mics

Key Streamlit concepts:
- st.form() — Groups inputs so the page doesn't rerun on every keystroke
- st.tabs() — Tabbed sections within the page
- st.session_state — Remembers values between reruns
"""

import streamlit as st
import pandas as pd
from datetime import datetime
from utils.database import (
    get_all_mics, add_mic, update_mic, deactivate_mic, get_mic_by_id,
    update_mic_rating, log_scrape, get_scrape_log
)
from scrapers.badslava import scrape_badslava, compare_badslava_with_database
from scrapers.firemics import scrape_firemics, compare_firemics_with_database

# ---------------------------------------------------------------------------
# PAGE CONFIG
# ---------------------------------------------------------------------------
st.set_page_config(page_title="Settings", page_icon="⚙️", layout="wide")
st.title("⚙️ Settings & Data Management")

# ---------------------------------------------------------------------------
# TABS
# ---------------------------------------------------------------------------
tab_add, tab_import, tab_manage = st.tabs([
    "➕ Add a Mic", "📥 Bulk Import", "🔧 Manage Mics"
])

# ===========================================================================
# TAB A: ADD A MIC MANUALLY
# ===========================================================================
with tab_add:
    st.subheader("Add a New Open Mic")
    st.caption("Manually add an open mic to your database.")

    with st.form("add_mic_form", clear_on_submit=True):
        st.markdown("#### Basic Info")
        col1, col2 = st.columns(2)
        with col1:
            name = st.text_input("Mic Name *", placeholder="e.g. Comedy Night")
            venue = st.text_input("Venue *", placeholder="e.g. The Comedy Club")
            address = st.text_input("Address", placeholder="e.g. 123 Main St")
        with col2:
            neighborhood = st.text_input("Neighborhood", placeholder="e.g. East Village")
            borough = st.selectbox(
                "Borough",
                options=["Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island"]
            )

        st.markdown("#### Schedule")
        col3, col4, col5 = st.columns(3)
        with col3:
            day_of_week = st.selectbox(
                "Day of Week *",
                options=["Monday", "Tuesday", "Wednesday", "Thursday",
                         "Friday", "Saturday", "Sunday"]
            )
        with col4:
            start_time = st.text_input("Start Time (24hr) *", placeholder="e.g. 19:00")
        with col5:
            display_time = st.text_input("Display Time", placeholder="e.g. 7:00 PM")

        st.markdown("#### Details")
        col6, col7 = st.columns(2)
        with col6:
            cost = st.text_input("Cost", placeholder="e.g. Free, $5, 1 drink min")
            set_length_min = st.number_input(
                "Set Length (minutes)", min_value=0, max_value=30, value=0,
                help="0 means unknown"
            )
            signup_method = st.selectbox(
                "Signup Method",
                options=["in_person", "online", "email", "instagram_dm"]
            )
        with col7:
            signup_url = st.text_input("Signup URL", placeholder="https://...")
            signup_notes = st.text_input("Signup Notes", placeholder="e.g. Arrive 30 min early")
            venue_url = st.text_input("Venue Website", placeholder="https://...")

        col8, col9 = st.columns(2)
        with col8:
            instagram = st.text_input("Instagram Handle", placeholder="e.g. @comedyclub")
            urgency = st.selectbox("Urgency", options=["normal", "medium", "high"])
        with col9:
            urgency_note = st.text_input("Urgency Note", placeholder="e.g. Signs up fast!")
            advance_days = st.number_input("Advance Days to Sign Up", min_value=0, value=0)

        is_biweekly = st.checkbox("Biweekly?")
        notes = st.text_area("Notes", placeholder="Any other details about this mic")

        submitted = st.form_submit_button("➕ Add Mic", use_container_width=True)

        if submitted:
            if not name or not venue or not day_of_week or not start_time:
                st.error("Name, Venue, Day of Week, and Start Time are required!")
            else:
                mic_data = {
                    "name": name,
                    "venue": venue,
                    "day_of_week": day_of_week,
                    "start_time": start_time,
                }
                # Only include optional fields if they have values
                if address:
                    mic_data["address"] = address
                if neighborhood:
                    mic_data["neighborhood"] = neighborhood
                if borough:
                    mic_data["borough"] = borough
                if display_time:
                    mic_data["display_time"] = display_time
                if cost:
                    mic_data["cost"] = cost
                if set_length_min > 0:
                    mic_data["set_length_min"] = set_length_min
                if signup_method:
                    mic_data["signup_method"] = signup_method
                if signup_url:
                    mic_data["signup_url"] = signup_url
                if signup_notes:
                    mic_data["signup_notes"] = signup_notes
                if venue_url:
                    mic_data["venue_url"] = venue_url
                if instagram:
                    mic_data["instagram"] = instagram
                if urgency != "normal":
                    mic_data["urgency"] = urgency
                if urgency_note:
                    mic_data["urgency_note"] = urgency_note
                if advance_days > 0:
                    mic_data["advance_days"] = advance_days
                if is_biweekly:
                    mic_data["is_biweekly"] = True
                if notes:
                    mic_data["notes"] = notes

                try:
                    add_mic(mic_data)
                    st.success(f"Added **{name}** @ {venue} ({day_of_week})!")
                    st.cache_data.clear()
                except Exception as e:
                    st.error(f"Error adding mic: {e}")


# ===========================================================================
# TAB B: BULK IMPORT FROM SCRAPERS
# ===========================================================================
with tab_import:
    st.subheader("Bulk Import from Scrapers")
    st.caption(
        "Pull open mic listings from external sites and add new ones "
        "to your database. Duplicates are detected automatically."
    )

    all_mics = get_all_mics()

    # --- Scrape Log ---
    with st.expander("📋 Scrape History"):
        scrape_log = get_scrape_log()
        if scrape_log.empty:
            st.info("No scrapes logged yet.")
        else:
            st.dataframe(scrape_log, use_container_width=True, hide_index=True)

    st.markdown("---")

    # -------------------------------------------------------------------
    # BAD SLAVA
    # -------------------------------------------------------------------
    st.markdown("### Bad Slava")
    st.caption("Scrapes badslava.com for NYC comedy open mics.")

    if st.button("🔍 Scrape Bad Slava", key="scrape_badslava"):
        with st.spinner("Scraping badslava.com..."):
            result = scrape_badslava()

        if result["errors"]:
            for err in result["errors"]:
                st.error(err)
            log_scrape("badslava", "error", "; ".join(result["errors"]))

        if result["mics"]:
            st.info(
                f"Found **{result['nyc_count']}** NYC mics "
                f"(out of {result['all_count']} total NY state entries)"
            )

            comparison = compare_badslava_with_database(result["mics"], all_mics)
            new_mics = comparison["new_mics"]
            matched = comparison["matched_mics"]

            st.write(f"Already in your database: **{matched}**")
            st.write(f"New mics found: **{len(new_mics)}**")

            if new_mics:
                st.session_state["badslava_new_mics"] = new_mics
            else:
                st.success("Your database is up to date with Bad Slava!")
                log_scrape("badslava", "success", f"{matched} matched, 0 new")

    # Show new mics from Bad Slava if available
    if st.session_state.get("badslava_new_mics"):
        new_mics = st.session_state["badslava_new_mics"]
        st.markdown(f"#### {len(new_mics)} New Mics from Bad Slava")

        for i, mic in enumerate(new_mics):
            with st.expander(
                f"{mic['day_of_week']} — {mic['name']} @ {mic['venue']}"
            ):
                st.write(f"📍 {mic.get('address', 'N/A')}")
                st.write(f"🕐 {mic.get('display_time', mic.get('start_time', 'N/A'))}")
                st.write(f"💰 {mic.get('cost', 'N/A')}")

                if st.button(f"➕ Add this mic", key=f"add_bs_{i}"):
                    try:
                        add_mic(mic)
                        st.success(f"Added **{mic['name']}**!")
                        st.cache_data.clear()
                    except Exception as e:
                        st.error(f"Error: {e}")

        if st.button("➕ Add ALL new Bad Slava mics", key="add_all_bs"):
            added = 0
            errors = []
            for mic in new_mics:
                try:
                    add_mic(mic)
                    added += 1
                except Exception as e:
                    errors.append(f"{mic['name']}: {e}")

            st.success(f"Added {added} mics!")
            if errors:
                for err in errors:
                    st.error(err)

            log_scrape("badslava", "success", f"Added {added} new mics")
            st.session_state.pop("badslava_new_mics", None)
            st.cache_data.clear()
            st.rerun()

    st.markdown("---")

    # -------------------------------------------------------------------
    # FIREMICS
    # -------------------------------------------------------------------
    st.markdown("### FireMics")
    st.caption("Scrapes firemics.com for NYC comedy open mics.")

    if st.button("🔍 Scrape FireMics", key="scrape_firemics"):
        with st.spinner("Scraping firemics.com..."):
            result = scrape_firemics()

        if result["errors"]:
            for err in result["errors"]:
                st.error(err)
            log_scrape("firemics", "error", "; ".join(result["errors"]))

        if result["mics"]:
            st.info(f"Found **{len(result['mics'])}** mics (from {result['all_count']} event instances)")

            comparison = compare_firemics_with_database(result["mics"], all_mics)
            new_mics = comparison["new_mics"]
            matched = comparison["matched_mics"]

            st.write(f"Already in your database: **{matched}**")
            st.write(f"New mics found: **{len(new_mics)}**")

            if new_mics:
                st.session_state["firemics_new_mics"] = new_mics
            else:
                st.success("Your database is up to date with FireMics!")
                log_scrape("firemics", "success", f"{matched} matched, 0 new")

    # Show new mics from FireMics if available
    if st.session_state.get("firemics_new_mics"):
        new_mics = st.session_state["firemics_new_mics"]
        st.markdown(f"#### {len(new_mics)} New Mics from FireMics")

        for i, mic in enumerate(new_mics):
            with st.expander(
                f"{mic['day_of_week']} — {mic['name']} @ {mic['venue']}"
            ):
                st.write(f"📍 {mic.get('address', 'N/A')}")
                st.write(f"🕐 {mic.get('display_time', mic.get('start_time', 'N/A'))}")
                st.write(f"💰 {mic.get('cost', 'N/A')}")
                if mic.get("signup_url"):
                    st.write(f"📝 {mic['signup_url']}")

                if st.button(f"➕ Add this mic", key=f"add_fm_{i}"):
                    try:
                        add_mic(mic)
                        st.success(f"Added **{mic['name']}**!")
                        st.cache_data.clear()
                    except Exception as e:
                        st.error(f"Error: {e}")

        if st.button("➕ Add ALL new FireMics mics", key="add_all_fm"):
            added = 0
            errors = []
            for mic in new_mics:
                try:
                    add_mic(mic)
                    added += 1
                except Exception as e:
                    errors.append(f"{mic['name']}: {e}")

            st.success(f"Added {added} mics!")
            if errors:
                for err in errors:
                    st.error(err)

            log_scrape("firemics", "success", f"Added {added} new mics")
            st.session_state.pop("firemics_new_mics", None)
            st.cache_data.clear()
            st.rerun()


# ===========================================================================
# TAB C: MANAGE EXISTING MICS
# ===========================================================================
with tab_manage:
    st.subheader("Manage Existing Mics")

    all_mics = get_all_mics()

    if all_mics.empty:
        st.info("No mics in the database yet. Add some using the other tabs!")
    else:
        st.caption(f"{len(all_mics)} active mics in your database")

        # Build a searchable dropdown
        mic_options = {}
        for _, mic in all_mics.iterrows():
            label = f"{mic['day_of_week'][:3]} — {mic['name']} @ {mic['venue']}"
            mic_options[label] = int(mic["id"])

        selected_label = st.selectbox(
            "Select a mic to edit",
            options=list(mic_options.keys()),
            placeholder="Choose a mic..."
        )

        if selected_label:
            mic_id = mic_options[selected_label]
            mic = get_mic_by_id(mic_id)

            if mic:
                st.markdown("---")
                st.markdown(f"### Editing: {mic['name']} @ {mic['venue']}")

                with st.form(f"edit_mic_{mic_id}"):
                    col1, col2 = st.columns(2)
                    with col1:
                        edit_name = st.text_input("Mic Name", value=mic.get("name", ""))
                        edit_venue = st.text_input("Venue", value=mic.get("venue", ""))
                        edit_address = st.text_input("Address", value=mic.get("address") or "")
                        edit_neighborhood = st.text_input(
                            "Neighborhood", value=mic.get("neighborhood") or ""
                        )
                        edit_borough = st.selectbox(
                            "Borough",
                            options=["Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island"],
                            index=["Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island"].index(
                                mic.get("borough", "Manhattan")
                            ) if mic.get("borough") in ["Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island"] else 0
                        )

                    with col2:
                        edit_day = st.selectbox(
                            "Day of Week",
                            options=["Monday", "Tuesday", "Wednesday", "Thursday",
                                     "Friday", "Saturday", "Sunday"],
                            index=["Monday", "Tuesday", "Wednesday", "Thursday",
                                   "Friday", "Saturday", "Sunday"].index(
                                mic.get("day_of_week", "Monday")
                            )
                        )
                        edit_start = st.text_input("Start Time (24hr)", value=mic.get("start_time", ""))
                        edit_display = st.text_input(
                            "Display Time", value=mic.get("display_time") or ""
                        )
                        edit_cost = st.text_input("Cost", value=mic.get("cost") or "")
                        edit_signup = st.selectbox(
                            "Signup Method",
                            options=["in_person", "online", "email", "instagram_dm"],
                            index=["in_person", "online", "email", "instagram_dm"].index(
                                mic.get("signup_method", "in_person")
                            ) if mic.get("signup_method") in ["in_person", "online", "email", "instagram_dm"] else 0
                        )

                    edit_signup_url = st.text_input("Signup URL", value=mic.get("signup_url") or "")
                    edit_signup_notes = st.text_input("Signup Notes", value=mic.get("signup_notes") or "")
                    edit_instagram = st.text_input("Instagram", value=mic.get("instagram") or "")
                    edit_notes = st.text_area("Notes", value=mic.get("notes") or "")

                    save_btn = st.form_submit_button("💾 Save Changes", use_container_width=True)

                    if save_btn:
                        updates = {
                            "name": edit_name,
                            "venue": edit_venue,
                            "address": edit_address or None,
                            "neighborhood": edit_neighborhood or None,
                            "borough": edit_borough,
                            "day_of_week": edit_day,
                            "start_time": edit_start,
                            "display_time": edit_display or None,
                            "cost": edit_cost or None,
                            "signup_method": edit_signup,
                            "signup_url": edit_signup_url or None,
                            "signup_notes": edit_signup_notes or None,
                            "instagram": edit_instagram or None,
                            "notes": edit_notes or None,
                        }
                        try:
                            update_mic(mic_id, updates)
                            st.success(f"Updated **{edit_name}**!")
                            st.cache_data.clear()
                        except Exception as e:
                            st.error(f"Error updating mic: {e}")

                # Mic Rating (outside the form so it can update independently)
                st.markdown("---")
                current_rating = mic.get("mic_rating") or 0.0
                new_rating = st.slider(
                    "My Rating for this mic",
                    min_value=0.0, max_value=10.0,
                    value=float(current_rating), step=0.5,
                    key=f"rating_{mic_id}"
                )
                if st.button("⭐ Update Rating", key=f"rate_{mic_id}"):
                    update_mic_rating(mic_id, new_rating)
                    st.success(f"Rating updated to {new_rating}/10")

                # Deactivate
                st.markdown("---")
                if st.button("🗑 Deactivate this mic", key=f"deactivate_{mic_id}"):
                    st.session_state[f"confirm_deactivate_{mic_id}"] = True

                if st.session_state.get(f"confirm_deactivate_{mic_id}"):
                    st.warning(
                        f"This will hide **{mic['name']}** from all views. "
                        "Your set history for this mic will be preserved."
                    )
                    dc1, dc2 = st.columns(2)
                    with dc1:
                        if st.button("Yes, deactivate", key=f"yd_{mic_id}", type="primary"):
                            deactivate_mic(mic_id)
                            st.session_state.pop(f"confirm_deactivate_{mic_id}", None)
                            st.cache_data.clear()
                            st.rerun()
                    with dc2:
                        if st.button("Cancel", key=f"nd_{mic_id}"):
                            st.session_state.pop(f"confirm_deactivate_{mic_id}", None)
                            st.rerun()
