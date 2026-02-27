"""
Settings — Page 4

Manage your mics, run scrapers, export data, and access quick links.

Key Streamlit concepts:
- st.data_editor() — An editable table. Users can click cells to change values.
- st.download_button() — Creates a button that downloads a file to the user's computer.
- st.tabs() — Sections within the page.
- Session state — Streamlit's way of remembering things between reruns.
"""

import streamlit as st
import pandas as pd
import csv
import io
from datetime import datetime
from utils.database import (
    get_all_mics, add_mic, update_mic, deactivate_mic,
    get_all_sets, update_mic_rating, delete_mic_hard,
    log_scrape, get_scrape_log
)
from scrapers.badslava import scrape_badslava, compare_badslava_with_database
from scrapers.firemics import scrape_firemics, compare_firemics_with_database

# ---------------------------------------------------------------------------
# PAGE CONFIG
# ---------------------------------------------------------------------------
st.set_page_config(page_title="Settings", page_icon="⚙️", layout="wide")
st.title("⚙️ Settings")

# ---------------------------------------------------------------------------
# TABS
# ---------------------------------------------------------------------------
tab_mics, tab_scrapers, tab_export, tab_links = st.tabs([
    "🎤 Manage Mics", "🔄 Scraper Controls", "💾 Data Export", "🔗 Quick Links"
])

# ===========================================================================
# TAB 1: MANAGE MICS
# ===========================================================================
with tab_mics:
    st.subheader("🎤 Manage Open Mics")

    all_mics = get_all_mics()

    # -------------------------------------------------------------------
    # VIEW & EDIT EXISTING MICS
    # -------------------------------------------------------------------
    st.markdown("#### Current Mics")
    st.caption("Click on any cell to edit it. Changes save when you click outside the cell.")

    if not all_mics.empty:
        # Select which columns to show in the editor (skip internal ones)
        display_cols = [
            "id", "name", "venue", "day_of_week", "display_time", "start_time",
            "neighborhood", "borough", "cost", "signup_method",
            "urgency", "advance_days", "mic_rating", "notes", "is_active"
        ]

        # Only show columns that exist in the DataFrame
        display_cols = [c for c in display_cols if c in all_mics.columns]

        # st.data_editor returns the edited DataFrame
        edited_mics = st.data_editor(
            all_mics[display_cols],
            use_container_width=True,
            hide_index=True,
            disabled=["id"],  # Don't let them edit the ID
            column_config={
                "id": st.column_config.NumberColumn("ID", width="small"),
                "mic_rating": st.column_config.NumberColumn(
                    "My Rating", min_value=1, max_value=10, step=0.5
                ),
                "urgency": st.column_config.SelectboxColumn(
                    "Urgency", options=["normal", "medium", "high"]
                ),
                "day_of_week": st.column_config.SelectboxColumn(
                    "Day", options=["Monday", "Tuesday", "Wednesday",
                                    "Thursday", "Friday", "Saturday", "Sunday"]
                ),
                "signup_method": st.column_config.SelectboxColumn(
                    "Signup", options=["in_person", "online", "email", "instagram_dm"]
                ),
                "is_active": st.column_config.CheckboxColumn("Active"),
            },
            key="mic_editor",
        )

        # Save changes button
        if st.button("💾 Save All Changes", use_container_width=True):
            # Compare edited vs original and update changed rows
            changes_made = 0
            for idx in edited_mics.index:
                edited_row = edited_mics.loc[idx]
                original_row = all_mics[display_cols].loc[idx]

                # Check if anything changed in this row
                if not edited_row.equals(original_row):
                    mic_id = int(edited_row["id"])
                    # Build dict of changed fields (exclude 'id')
                    update_data = {}
                    for col in display_cols:
                        if col == "id":
                            continue
                        edited_val = edited_row[col]
                        orig_val = original_row[col]
                        # Handle NaN comparisons (NaN != NaN in Python)
                        if pd.isna(edited_val) and pd.isna(orig_val):
                            continue
                        if edited_val != orig_val:
                            update_data[col] = edited_val if not pd.isna(edited_val) else None

                    if update_data:
                        update_mic(mic_id, update_data)
                        changes_made += 1

            if changes_made > 0:
                st.success(f"✅ Updated {changes_made} mic(s)!")
                st.rerun()  # Refresh the page to show updated data
            else:
                st.info("No changes detected.")

    # -------------------------------------------------------------------
    # DELETE A MIC
    # -------------------------------------------------------------------
    st.markdown("---")
    st.markdown("#### 🗑 Delete a Mic")
    st.caption(
        "Remove a mic from your database. This soft-deletes it "
        "(your set history is preserved) and clears any future plans."
    )

    if not all_mics.empty:
        mic_options = {
            f"{row['name']} @ {row['venue']} ({row['day_of_week']})": int(row["id"])
            for _, row in all_mics.iterrows()
        }
        selected_mic_label = st.selectbox(
            "Select a mic to delete",
            options=[""] + list(mic_options.keys()),
            index=0,
            key="delete_mic_select",
        )

        if selected_mic_label:
            mic_id_to_delete = mic_options[selected_mic_label]

            if st.button("🗑 Delete this mic", key="settings_delete_btn"):
                st.session_state["confirm_settings_delete"] = mic_id_to_delete

            if st.session_state.get("confirm_settings_delete") == mic_id_to_delete:
                st.warning(f"Are you sure you want to remove **{selected_mic_label}**?")
                confirm_col1, confirm_col2 = st.columns(2)
                with confirm_col1:
                    if st.button("Yes, delete", key="settings_yd",
                                 type="primary", use_container_width=True):
                        delete_mic_hard(mic_id_to_delete)
                        st.session_state.pop("confirm_settings_delete", None)
                        st.success("Mic deleted!")
                        st.rerun()
                with confirm_col2:
                    if st.button("Cancel", key="settings_nd",
                                 use_container_width=True):
                        st.session_state.pop("confirm_settings_delete", None)
                        st.rerun()
    else:
        st.info("No mics in the database yet.")

    # -------------------------------------------------------------------
    # ADD A NEW MIC
    # -------------------------------------------------------------------
    st.markdown("---")
    st.markdown("#### ➕ Add a New Mic")

    with st.form("add_mic_form", clear_on_submit=True):
        add_col1, add_col2 = st.columns(2)

        with add_col1:
            new_name = st.text_input("Mic Name *", placeholder="e.g. My New Open Mic")
            new_venue = st.text_input("Venue *", placeholder="e.g. The Comedy Spot")
            new_address = st.text_input("Address", placeholder="123 Main St")
            new_neighborhood = st.text_input("Neighborhood", placeholder="e.g. Brooklyn, LES")
            new_borough = st.selectbox(
                "Borough",
                options=["Manhattan", "Brooklyn", "Bronx", "Queens", "Staten Island"]
            )
            new_day = st.selectbox(
                "Day of Week *",
                options=["Monday", "Tuesday", "Wednesday", "Thursday",
                         "Friday", "Saturday", "Sunday"]
            )

        with add_col2:
            new_time = st.time_input("Start Time *")
            new_display = st.text_input("Display Time", placeholder="e.g. 7:30 PM")
            new_cost = st.text_input("Cost", placeholder="e.g. $5, Free, 1 drink min")
            new_signup = st.selectbox(
                "Signup Method",
                options=["in_person", "online", "email", "instagram_dm"]
            )
            new_signup_url = st.text_input("Signup URL", placeholder="https://...")
            new_signup_notes = st.text_input("Signup Notes", placeholder="e.g. Arrive 30min early")
            new_instagram = st.text_input("Instagram", placeholder="@handle")
            new_urgency = st.selectbox("Urgency", options=["normal", "medium", "high"])

        add_submitted = st.form_submit_button("➕ Add Mic", use_container_width=True)

        if add_submitted:
            if not new_name or not new_venue:
                st.error("Name and Venue are required!")
            else:
                mic_data = {
                    "name": new_name,
                    "venue": new_venue,
                    "address": new_address if new_address else None,
                    "neighborhood": new_neighborhood if new_neighborhood else None,
                    "borough": new_borough,
                    "day_of_week": new_day,
                    "start_time": new_time.strftime("%H:%M"),
                    "display_time": new_display if new_display else new_time.strftime("%I:%M %p"),
                    "cost": new_cost if new_cost else None,
                    "signup_method": new_signup,
                    "signup_url": new_signup_url if new_signup_url else None,
                    "signup_notes": new_signup_notes if new_signup_notes else None,
                    "instagram": new_instagram if new_instagram else None,
                    "urgency": new_urgency,
                }
                add_mic(mic_data)
                st.success(f"✅ Added '{new_name}' on {new_day}!")
                st.rerun()


# ===========================================================================
# TAB 2: DISCOVER & IMPORT MICS
# ===========================================================================
with tab_scrapers:
    st.subheader("🔍 Discover & Import Mics")

    # -------------------------------------------------------------------
    # SECTION 1: CSV IMPORT
    # -------------------------------------------------------------------
    st.markdown("#### 📄 Import Mics from CSV")
    st.caption(
        "The fastest way to add multiple mics at once. Create a spreadsheet "
        "with mic info, export it as CSV, and upload it here."
    )

    with st.expander("📋 How to format your CSV"):
        st.markdown("""
**Required columns:** `name`, `venue`, `day_of_week`, `start_time`

**Optional columns:** `address`, `neighborhood`, `borough`, `display_time`,
`cost`, `signup_method`, `signup_url`, `signup_notes`, `instagram`,
`urgency`, `advance_days`, `notes`

**Example CSV:**
```
name,venue,day_of_week,start_time,display_time,cost,neighborhood,borough,signup_method
My Cool Mic,The Venue,Monday,19:30,7:30 PM,$5,LES,Manhattan,in_person
Another Mic,Bar Name,Friday,21:00,9:00 PM,Free,Brooklyn,Brooklyn,online
```

**Tips:**
- `day_of_week` must be the full name: Monday, Tuesday, etc.
- `start_time` should be 24hr format: 19:30, 21:00, etc.
- `signup_method` options: in_person, online, email, instagram_dm
- `urgency` options: normal, medium, high
        """)

    uploaded_csv = st.file_uploader("Upload a CSV file", type=["csv"])

    if uploaded_csv is not None:
        try:
            # Read the CSV file
            csv_df = pd.read_csv(uploaded_csv)
            st.write(f"Found **{len(csv_df)} mics** in the CSV:")
            st.dataframe(csv_df, use_container_width=True, hide_index=True)

            # Validate required columns
            required = {"name", "venue", "day_of_week", "start_time"}
            missing = required - set(csv_df.columns)

            if missing:
                st.error(f"Missing required columns: {', '.join(missing)}")
            else:
                if st.button("➕ Import All Mics from CSV", use_container_width=True):
                    imported = 0
                    for _, row in csv_df.iterrows():
                        mic_data = {k: v for k, v in row.to_dict().items()
                                    if pd.notna(v) and v != ""}
                        add_mic(mic_data)
                        imported += 1
                    st.success(f"Imported {imported} mics!")
                    st.rerun()
        except Exception as e:
            st.error(f"Error reading CSV: {str(e)}")

    # -------------------------------------------------------------------
    # SECTION 2: BAD SLAVA SCRAPER (the one that works!)
    # -------------------------------------------------------------------
    st.markdown("---")
    st.markdown("#### 🌐 Scrape Bad Slava (Recommended)")
    st.caption(
        "Bad Slava is a nationwide open mic directory. Unlike most comedy sites, "
        "their data is embedded directly in the page source — so our scraper "
        "can actually read it! It pulls all NYC comedy open mics and compares "
        "them against your database."
    )

    # Check scrape log
    scrape_log_all = get_scrape_log()

    bs_log = scrape_log_all[scrape_log_all["source"] == "badslava"]
    if not bs_log.empty:
        last = bs_log.iloc[0]
        st.caption(f"Last scraped: {str(last['last_scraped'])[:16]} — {last['status']} — {last['notes']}")

    if st.button("🔍 Scrape Bad Slava for NYC Mics", use_container_width=True, type="primary"):
        with st.spinner("Scraping badslava.com for NYC comedy open mics..."):
            result = scrape_badslava()

        # Log the attempt
        status = "success" if result["mics"] else "error"
        notes = (
            f"Found {result['nyc_count']} NYC mics out of "
            f"{result['all_count']} total NY state entries. "
            f"{len(result['errors'])} errors."
        )
        log_scrape("badslava", status, notes)

        # Save results to session_state so they persist across reruns
        # (Without this, clicking "Add All" would rerun the page and lose the results)
        st.session_state["bs_result"] = result
        if result["mics"]:
            comparison = compare_badslava_with_database(result["mics"], get_all_mics())
            st.session_state["bs_comparison"] = comparison
        else:
            st.session_state["bs_comparison"] = None

    # Display results from session_state (persists across button clicks)
    if "bs_result" in st.session_state:
        result = st.session_state["bs_result"]

        if result.get("errors"):
            for err in result["errors"]:
                st.warning(err)

        if result["mics"]:
            st.success(
                f"Found **{result['nyc_count']} NYC comedy mics** "
                f"(filtered from {result['all_count']} total NY state listings)"
            )

            comparison = st.session_state.get("bs_comparison")
            if comparison:
                st.write(f"✅ **{comparison['matched_mics']}** already in your database")

                if comparison["new_mics"]:
                    st.markdown(f"### 🆕 {len(comparison['new_mics'])} New Mics Not In Your Database")

                    # BULK ADD BUTTON
                    if st.button(
                        f"➕ Add All {len(comparison['new_mics'])} New Mics",
                        use_container_width=True,
                        type="primary"
                    ):
                        added = 0
                        for mic in comparison["new_mics"]:
                            insert_data = {k: v for k, v in mic.items()
                                           if k != "source" and v is not None}
                            add_mic(insert_data)
                            added += 1
                        st.success(f"Added {added} new mics to your database!")
                        st.balloons()
                        # Clear the scrape results so they don't show stale data
                        del st.session_state["bs_result"]
                        del st.session_state["bs_comparison"]
                        st.rerun()

                    st.caption("Or expand each one below to review before adding individually.")

                    for i, mic in enumerate(comparison["new_mics"]):
                        cost_display = mic.get("cost", "?")
                        with st.expander(
                            f"🆕 **{mic['day_of_week']}** — {mic.get('display_time', '?')} — "
                            f"{mic['name']} @ {mic['venue']} — {cost_display}"
                        ):
                            detail_col1, detail_col2 = st.columns(2)
                            with detail_col1:
                                st.write(f"**Mic:** {mic['name']}")
                                st.write(f"**Venue:** {mic['venue']}")
                                st.write(f"**Address:** {mic.get('address', 'N/A')}")
                                st.write(f"**Neighborhood:** {mic.get('neighborhood', 'Unknown')}")
                                st.write(f"**Borough:** {mic.get('borough', 'Unknown')}")
                            with detail_col2:
                                st.write(f"**Day:** {mic['day_of_week']}")
                                st.write(f"**Time:** {mic.get('display_time', mic.get('start_time'))}")
                                st.write(f"**Cost:** {cost_display}")
                                if mic.get("notes"):
                                    st.write(f"**Notes:** {mic['notes']}")
                                if mic.get("is_biweekly"):
                                    st.write("**Frequency:** Biweekly")

                            if st.button(f"➕ Add to my database", key=f"add_bs_{i}"):
                                insert_data = {k: v for k, v in mic.items()
                                               if k != "source" and v is not None}
                                add_mic(insert_data)
                                st.success(f"Added '{mic['name']}'!")
                                # Re-compare so the added mic disappears from the list
                                fresh_comparison = compare_badslava_with_database(
                                    result["mics"], get_all_mics()
                                )
                                st.session_state["bs_comparison"] = fresh_comparison
                                st.rerun()
                else:
                    st.success("No new mics found — your database already has everything Bad Slava lists for NYC!")
        else:
            st.warning("No NYC mics found. This is unexpected — the site may have changed.")

    # -------------------------------------------------------------------
    # SECTION 3: FIREMICS SCRAPER
    # -------------------------------------------------------------------
    st.markdown("---")
    st.markdown("#### 🔥 Scrape FireMics")
    st.caption(
        "FireMics is an open mic discovery platform built with Next.js. "
        "Like Bad Slava, the event data is embedded directly in the page "
        "as structured JSON — so our scraper can read it without running JavaScript."
    )

    # Check scrape log for FireMics
    fm_log = scrape_log_all[scrape_log_all["source"] == "firemics"]
    if not fm_log.empty:
        last = fm_log.iloc[0]
        st.caption(f"Last scraped: {last['last_scraped'][:16]} — {last['status']} — {last['notes']}")

    if st.button("🔍 Scrape FireMics for NYC Mics", use_container_width=True, type="primary"):
        with st.spinner("Scraping firemics.com for NYC comedy open mics..."):
            fm_result = scrape_firemics()

        # Log the attempt
        status = "success" if fm_result["mics"] else "error"
        notes = (
            f"Found {len(fm_result['mics'])} unique mics from "
            f"{fm_result['all_count']} total event instances. "
            f"{len(fm_result['errors'])} errors."
        )
        log_scrape("firemics", status, notes)

        # Save results to session_state so they persist across reruns
        st.session_state["fm_result"] = fm_result
        if fm_result["mics"]:
            fm_comparison = compare_firemics_with_database(fm_result["mics"], get_all_mics())
            st.session_state["fm_comparison"] = fm_comparison
        else:
            st.session_state["fm_comparison"] = None

    # Display FireMics results from session_state
    if "fm_result" in st.session_state:
        fm_result = st.session_state["fm_result"]

        if fm_result.get("errors"):
            for err in fm_result["errors"]:
                st.warning(err)

        if fm_result["mics"]:
            st.success(
                f"Found **{len(fm_result['mics'])} unique NYC comedy mics** "
                f"(from {fm_result['all_count']} total event instances)"
            )

            fm_comparison = st.session_state.get("fm_comparison")
            if fm_comparison:
                st.write(f"✅ **{fm_comparison['matched_mics']}** already in your database")

                if fm_comparison["new_mics"]:
                    st.markdown(f"### 🆕 {len(fm_comparison['new_mics'])} New Mics Not In Your Database")

                    # BULK ADD BUTTON
                    if st.button(
                        f"➕ Add All {len(fm_comparison['new_mics'])} New FireMics",
                        use_container_width=True,
                        type="primary"
                    ):
                        added = 0
                        for mic in fm_comparison["new_mics"]:
                            insert_data = {k: v for k, v in mic.items()
                                           if k != "source" and v is not None}
                            add_mic(insert_data)
                            added += 1
                        st.success(f"Added {added} new mics to your database!")
                        st.balloons()
                        del st.session_state["fm_result"]
                        del st.session_state["fm_comparison"]
                        st.rerun()

                    st.caption("Or expand each one below to review before adding individually.")

                    for i, mic in enumerate(fm_comparison["new_mics"]):
                        cost_display = mic.get("cost", "?")
                        with st.expander(
                            f"🆕 **{mic['day_of_week']}** — {mic.get('display_time', '?')} — "
                            f"{mic['name']} @ {mic['venue']} — {cost_display}"
                        ):
                            detail_col1, detail_col2 = st.columns(2)
                            with detail_col1:
                                st.write(f"**Mic:** {mic['name']}")
                                st.write(f"**Venue:** {mic['venue']}")
                                st.write(f"**Address:** {mic.get('address', 'N/A')}")
                                st.write(f"**Neighborhood:** {mic.get('neighborhood', 'Unknown')}")
                                st.write(f"**Borough:** {mic.get('borough', 'Unknown')}")
                            with detail_col2:
                                st.write(f"**Day:** {mic['day_of_week']}")
                                st.write(f"**Time:** {mic.get('display_time', mic.get('start_time'))}")
                                st.write(f"**Cost:** {cost_display}")
                                if mic.get("signup_url"):
                                    st.write(f"**Signup:** [Link]({mic['signup_url']})")
                                if mic.get("venue_url"):
                                    st.write(f"**Website:** [Link]({mic['venue_url']})")
                                if mic.get("is_biweekly"):
                                    st.write("**Frequency:** Biweekly")

                            if st.button(f"➕ Add to my database", key=f"add_fm_{i}"):
                                insert_data = {k: v for k, v in mic.items()
                                               if k != "source" and v is not None}
                                add_mic(insert_data)
                                st.success(f"Added '{mic['name']}'!")
                                fresh_comparison = compare_firemics_with_database(
                                    fm_result["mics"], get_all_mics()
                                )
                                st.session_state["fm_comparison"] = fresh_comparison
                                st.rerun()
                else:
                    st.success("No new mics found — your database already has everything FireMics lists for NYC!")
        else:
            st.warning("No NYC mics found. FireMics may have changed their data structure.")

    # -------------------------------------------------------------------
    # SECTION 4: MANUAL DISCOVERY LINKS
    # -------------------------------------------------------------------
    st.markdown("---")
    st.markdown("#### 🌐 Find New Mics Manually")
    st.caption(
        "Since most comedy sites need JavaScript, the best way to discover "
        "new mics is to check these sites in your browser, then add them "
        "here using the Add Mic form or CSV import."
    )

    disc_col1, disc_col2 = st.columns(2)
    with disc_col1:
        st.markdown("""
- [comedylistings.com](https://www.comedylistings.com) — Best NYC open mic aggregator
- [badslava.com](https://www.badslava.com) — Nationwide open mic listings
- [freemics.com](https://www.freemics.com) — Free open mic finder
- [firemics.com](https://www.firemics.com) — Open mic discovery
        """)
    with disc_col2:
        st.markdown("""
- [comediansontheloose.com](https://www.comediansontheloose.com/open-mics) — Black Cat LES
- [laughingbuddhacomedy.com/mics](https://www.laughingbuddhacomedy.com/mics) — Laughing Buddha
- [comedymob.com](https://www.comedymob.com) — Comedy Mob signups
- [eastvillecomedy.com/calendar](https://www.eastvillecomedy.com/calendar) — EastVille
        """)


# ===========================================================================
# TAB 3: DATA EXPORT
# ===========================================================================
with tab_export:
    st.subheader("💾 Data Export")
    st.caption("Download your data for backup or analysis in Excel/Google Sheets.")

    export_col1, export_col2, export_col3 = st.columns(3)

    with export_col1:
        st.markdown("#### My Sets")
        sets_df = get_all_sets()
        if not sets_df.empty:
            csv_sets = sets_df.to_csv(index=False)
            st.download_button(
                label="📥 Download Sets CSV",
                data=csv_sets,
                file_name=f"my_sets_{datetime.now().strftime('%Y%m%d')}.csv",
                mime="text/csv",
                use_container_width=True,
            )
            st.caption(f"{len(sets_df)} sets")
        else:
            st.info("No sets to export yet.")

    with export_col2:
        st.markdown("#### All Mics")
        mics_df = get_all_mics()
        if not mics_df.empty:
            csv_mics = mics_df.to_csv(index=False)
            st.download_button(
                label="📥 Download Mics CSV",
                data=csv_mics,
                file_name=f"open_mics_{datetime.now().strftime('%Y%m%d')}.csv",
                mime="text/csv",
                use_container_width=True,
            )
            st.caption(f"{len(mics_df)} mics")
        else:
            st.info("No mics to export.")

    with export_col3:
        st.markdown("#### Full Database Backup")
        st.info(
            "Your database is now hosted on Supabase. "
            "You can back it up from the Supabase dashboard, "
            "or use the CSV exports on the left."
        )


# ===========================================================================
# TAB 4: QUICK LINKS
# ===========================================================================
with tab_links:
    st.subheader("🔗 Quick Links")

    st.markdown("#### 📸 Instagram Accounts to Follow")
    ig_accounts = [
        ("@eastvillecomedy", "EastVille Comedy Club"),
        ("@comedyinharlem", "Comedy In Harlem"),
        ("@cotlcomedy", "Comedians on the Loose"),
        ("@laughingbuddhacomedy", "Laughing Buddha Comedy"),
        ("@comedybrooklyn", "Comedy Brooklyn"),
        ("@stmarkscomedyclub", "St. Marks Comedy Club"),
        ("@standupny", "Stand Up NY"),
        ("@thecomicstriplive", "Comic Strip Live"),
        ("@sohoplayhousenyc", "SoHo Playhouse"),
        ("@cuckoos_openmic", "Cuckoos Open Mic"),
        ("@matt_scribble", "Matt Scribble (SoHo Thu host)"),
        ("@thetinycupboard", "The Tiny Cupboard"),
        ("@comedymobnyc", "Comedy Mob NYC"),
        ("@comedianJamie", "Comedian Jamie"),
    ]

    # Display in 2 columns
    ig_col1, ig_col2 = st.columns(2)
    for i, (handle, desc) in enumerate(ig_accounts):
        clean_handle = handle.replace("@", "")
        with ig_col1 if i % 2 == 0 else ig_col2:
            st.markdown(f"- [{handle}](https://instagram.com/{clean_handle}) — {desc}")

    st.markdown("---")
    st.markdown("#### 🌐 Key Websites")
    websites = [
        ("comedylistings.com", "https://www.comedylistings.com", "NYC open mic aggregator"),
        ("comediansontheloose.com", "https://www.comediansontheloose.com/open-mics", "Black Cat LES signups"),
        ("laughingbuddhacomedy.com", "https://www.laughingbuddhacomedy.com/mics", "Laughing Buddha signups"),
        ("comedymob.com", "https://www.comedymob.com", "Comedy Mob signups"),
        ("badslava.com", "https://www.badslava.com", "Nationwide open mic listings"),
        ("freemics.com", "https://www.freemics.com", "Free open mic finder"),
        ("firemics.com", "https://www.firemics.com", "Open mic discovery"),
    ]

    for name, url, desc in websites:
        st.markdown(f"- [{name}]({url}) — {desc}")
