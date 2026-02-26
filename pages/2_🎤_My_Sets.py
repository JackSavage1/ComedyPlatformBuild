"""
My Sets Log — Page 2

Two sections:
A) Log a new set — A form to record a performance
B) Set history — A searchable/filterable list of all past performances

Key Streamlit concepts:
- st.form() — Groups inputs together so the app doesn't rerun on every
  keystroke. Only reruns when you hit Submit.
- st.tabs() — Creates tabbed sections (like browser tabs within the page)
- st.date_input() — A date picker widget
- st.slider() — A draggable slider for picking numbers
- st.balloons() — A fun confetti animation on success
"""

import streamlit as st
import pandas as pd
from datetime import datetime, date
from utils.database import get_all_mics, add_set, get_all_sets, update_set

# ---------------------------------------------------------------------------
# PAGE CONFIG
# ---------------------------------------------------------------------------
st.set_page_config(page_title="My Sets", page_icon="🎤", layout="wide")
st.title("🎤 My Sets")

# ---------------------------------------------------------------------------
# LOAD DATA
# ---------------------------------------------------------------------------
all_mics = get_all_mics()
all_sets = get_all_sets()

# ---------------------------------------------------------------------------
# TABS — Split the page into "Log a Set" and "Set History"
# ---------------------------------------------------------------------------
tab_log, tab_history = st.tabs(["📝 Log a New Set", "📋 Set History"])

# ===========================================================================
# TAB A: LOG A NEW SET
# ===========================================================================
with tab_log:
    st.subheader("Log a Performance")
    st.caption("Record your set details — the more you log, the better your Dashboard analytics get!")

    # -------------------------------------------------------------------
    # PLANNED SETS — Quick-log section for mics marked "Going"
    #
    # When you click "Going" on the calendar, a skeletal set entry is
    # auto-created (just mic_id + date, no ratings). This section shows
    # those entries so you can quickly fill in the details after performing.
    # -------------------------------------------------------------------
    planned_sets = all_sets[all_sets["set_rating"].isna()]
    if not planned_sets.empty:
        st.markdown("#### 📋 Planned Sets — Ready to Log")
        st.caption(
            "You marked these as 'Going' on the calendar. "
            "Click one to fill in your set details."
        )
        for _, ps in planned_sets.iterrows():
            mic_name = ps.get("mic_name", "Unknown")
            venue = ps.get("venue", "")
            date_str = str(ps["date_performed"])[:10]
            if st.button(
                f"📝 Log: **{mic_name}** @ {venue} — {date_str}",
                key=f"log_planned_{ps['id']}",
                use_container_width=True,
            ):
                st.session_state["prefill_set_id"] = int(ps["id"])
                st.session_state["prefill_mic_id"] = int(ps["open_mic_id"])
                st.session_state["prefill_date"] = date_str
                st.rerun()
        st.markdown("---")

    # Check if we're filling in a planned set
    prefill_set_id = st.session_state.get("prefill_set_id")
    prefill_mic_id = st.session_state.get("prefill_mic_id")
    prefill_date = st.session_state.get("prefill_date")

    if prefill_set_id:
        st.info(f"Filling in details for your planned set. Clear the form to log a different set.")
        if st.button("Clear — log a different set instead"):
            for key in ["prefill_set_id", "prefill_mic_id", "prefill_date"]:
                if key in st.session_state:
                    del st.session_state[key]
            st.rerun()

    # Build the mic dropdown options, grouped by day
    days_order = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

    mic_options = {}  # {"display string": mic_id}
    mic_id_to_label = {}  # {mic_id: "display string"} — reverse lookup for prefill
    for day in days_order:
        day_mics = all_mics[all_mics["day_of_week"] == day].sort_values("start_time")
        for _, mic in day_mics.iterrows():
            label = f"{day[:3]} — {mic['display_time'] or mic['start_time']} — {mic['name']} @ {mic['venue']}"
            mic_options[label] = int(mic["id"])
            mic_id_to_label[int(mic["id"])] = label

    # Pre-select the mic and date if filling in a planned set
    default_mic_index = None
    default_date = date.today()
    if prefill_mic_id and prefill_mic_id in mic_id_to_label:
        prefill_label = mic_id_to_label[prefill_mic_id]
        option_list = list(mic_options.keys())
        if prefill_label in option_list:
            default_mic_index = option_list.index(prefill_label)
    if prefill_date:
        try:
            default_date = datetime.strptime(prefill_date[:10], "%Y-%m-%d").date()
            # Clamp to today — the date picker's max_value is today,
            # so a future planned date would crash the widget
            if default_date > date.today():
                default_date = date.today()
        except ValueError:
            pass

    # -----------------------------------------------------------------------
    # THE FORM
    #
    # st.form() groups all these inputs together. Nothing happens until you
    # click "Submit." Without a form, Streamlit would rerun the entire page
    # every time you move a slider or type a character — very annoying!
    # -----------------------------------------------------------------------
    with st.form("log_set_form", clear_on_submit=True):
        st.markdown("#### Which mic?")
        selected_mic_label = st.selectbox(
            "Select the open mic",
            options=list(mic_options.keys()),
            index=default_mic_index,
            placeholder="Choose a mic..."
        )

        st.markdown("#### When?")
        date_performed = st.date_input(
            "Date performed",
            value=default_date,
            max_value=date.today()
        )

        st.markdown("#### How'd it go?")
        col1, col2 = st.columns(2)
        with col1:
            set_rating = st.slider(
                "Set rating (how did YOU do?)",
                min_value=1, max_value=10, value=5,
                help="1 = total bomb, 10 = absolute killer set"
            )
        with col2:
            crowd_rating = st.slider(
                "Crowd rating (how was the audience?)",
                min_value=1, max_value=10, value=5,
                help="1 = dead silent / hostile, 10 = engaged and laughing at everything"
            )

        crowd_size = st.radio(
            "Crowd size",
            options=["empty", "sparse", "decent", "packed"],
            horizontal=True,  # Display options side by side
            index=2  # Default to "decent"
        )

        st.markdown("#### Your material")
        set_list = st.text_area(
            "Set list (bit names, order)",
            placeholder="e.g.\n1. Subway bit\n2. Dating app opener\n3. New bit about coffee\n4. Crowd work closer",
            height=120
        )

        new_material = st.checkbox("Trying new material?", value=False)

        st.markdown("#### Recording")
        rec_col1, rec_col2 = st.columns(2)
        with rec_col1:
            recording_url = st.text_input(
                "Recording link",
                placeholder="YouTube URL, Google Drive link, etc."
            )
        with rec_col2:
            recording_type = st.radio(
                "Recording type",
                options=["none", "video", "audio"],
                horizontal=True
            )

        st.markdown("#### Reflections")
        notes = st.text_area(
            "Notes — what worked, what didn't, observations",
            placeholder="e.g. The subway bit killed. The coffee bit needs a better tag. "
                        "Host was cool, gave me an extra minute. Crowd was mostly comics.",
            height=100
        )

        got_feedback = st.checkbox("Got feedback from host or other comics?")
        feedback_notes = ""
        if got_feedback:
            feedback_notes = st.text_area(
                "What feedback did you get?",
                placeholder="e.g. Host said my crowd work was strong. "
                            "Another comic suggested tightening the dating bit."
            )

        would_return = st.checkbox("Would return to this mic?", value=True)

        tags = st.multiselect(
            "Tags (pick all that apply)",
            options=[
                "killed", "bombed", "new_bit", "crowd_work",
                "got_laughs", "nervous", "confident", "short_set",
                "long_set", "got_spotted"
            ],
            default=[],
            placeholder="Select tags..."
        )

        # -------------------------------------------------------------------
        # SUBMIT BUTTON
        # -------------------------------------------------------------------
        submitted = st.form_submit_button("💾 Save Set", use_container_width=True)

        if submitted:
            if not selected_mic_label:
                st.error("Please select a mic!")
            else:
                # Build the data dictionary
                set_data = {
                    "open_mic_id": mic_options[selected_mic_label],
                    "date_performed": date_performed.isoformat(),
                    "set_rating": set_rating,
                    "crowd_rating": crowd_rating,
                    "crowd_size": crowd_size,
                    "set_list": set_list if set_list else None,
                    "recording_url": recording_url if recording_url else None,
                    "recording_type": recording_type,
                    "notes": notes if notes else None,
                    "new_material": 1 if new_material else 0,
                    "got_feedback": 1 if got_feedback else 0,
                    "feedback_notes": feedback_notes if feedback_notes else None,
                    "would_return": 1 if would_return else 0,
                    "tags": ",".join(tags) if tags else None,
                }

                # If filling in a planned set, UPDATE instead of INSERT
                if prefill_set_id:
                    update_set(prefill_set_id, set_data)
                    # Clear the prefill state
                    for key in ["prefill_set_id", "prefill_mic_id", "prefill_date"]:
                        if key in st.session_state:
                            del st.session_state[key]
                else:
                    add_set(set_data)

                st.success("✅ Set logged! Keep grinding! 🎤")
                st.balloons()


# ===========================================================================
# TAB B: SET HISTORY
# ===========================================================================
with tab_history:
    st.subheader("📋 My Set History")

    # Reload sets to include any just-submitted set
    all_sets = get_all_sets()

    if all_sets.empty:
        st.markdown("---")
        st.markdown(
            """
            ### No sets logged yet!

            Hit a mic tonight and come back to log it.

            Your first data point is always the hardest. 🎤

            **Go to the "Log a New Set" tab above to record your first performance.**
            """
        )
    else:
        # -------------------------------------------------------------------
        # SUMMARY STATS
        # Exclude skeletal (planned but not yet logged) entries from stats
        # -------------------------------------------------------------------
        completed_sets = all_sets[all_sets["set_rating"].notna()]

        stat_col1, stat_col2, stat_col3, stat_col4 = st.columns(4)

        with stat_col1:
            planned_count = len(all_sets) - len(completed_sets)
            label = f"{len(completed_sets)}"
            if planned_count > 0:
                label += f" (+{planned_count} planned)"
            st.metric("Total Sets", label)

        with stat_col2:
            avg_rating = completed_sets["set_rating"].mean() if not completed_sets.empty else 0
            st.metric("Avg Set Rating", f"{avg_rating:.1f}/10")

        with stat_col3:
            # Most visited mic
            if "mic_name" in all_sets.columns and all_sets["mic_name"].notna().any():
                top_mic = all_sets["mic_name"].value_counts().index[0]
                top_count = all_sets["mic_name"].value_counts().values[0]
                st.metric("Most Visited", f"{top_mic} ({top_count}x)")
            else:
                st.metric("Most Visited", "N/A")

        with stat_col4:
            # Current streak: consecutive weeks with at least 1 set
            # Convert dates and group by ISO week
            all_sets["date_performed"] = pd.to_datetime(all_sets["date_performed"])
            all_sets["year_week"] = (
                all_sets["date_performed"].dt.isocalendar().year.astype(str)
                + "-W"
                + all_sets["date_performed"].dt.isocalendar().week.astype(str).str.zfill(2)
            )
            unique_weeks = sorted(all_sets["year_week"].unique(), reverse=True)

            # Count consecutive weeks from the most recent
            streak = 0
            current_dt = datetime.now()
            current_year_week = (
                str(current_dt.isocalendar()[0])
                + "-W"
                + str(current_dt.isocalendar()[1]).zfill(2)
            )

            # Check if current or last week has a set, then count backwards
            for week in unique_weeks:
                if streak == 0:
                    # Start counting from the most recent logged week
                    streak = 1
                else:
                    # Check if this week is consecutive with the previous
                    # Simple approach: just count unique weeks from most recent
                    streak += 1

            st.metric("Week Streak", f"{streak} weeks")

        st.markdown("---")

        # -------------------------------------------------------------------
        # FILTERS FOR HISTORY
        # -------------------------------------------------------------------
        filter_col1, filter_col2, filter_col3 = st.columns(3)

        with filter_col1:
            # Filter by mic name
            mic_names = ["All"] + sorted(
                all_sets["mic_name"].dropna().unique().tolist()
            )
            filter_mic = st.selectbox("Filter by mic", mic_names)

        with filter_col2:
            # Filter by rating range
            filter_rating = st.slider(
                "Min set rating",
                min_value=1, max_value=10, value=1
            )

        with filter_col3:
            # Filter by tags
            all_tags = set()
            for tags_str in all_sets["tags"].dropna():
                for tag in tags_str.split(","):
                    tag = tag.strip()
                    if tag:
                        all_tags.add(tag)
            filter_tags = st.multiselect(
                "Filter by tags",
                options=sorted(all_tags),
                default=[]
            )

        # Apply filters
        display_sets = all_sets.copy()

        if filter_mic != "All":
            display_sets = display_sets[display_sets["mic_name"] == filter_mic]

        # Filter by rating (keep planned/skeletal entries too — they have no rating yet)
        display_sets = display_sets[
            (display_sets["set_rating"] >= filter_rating) | (display_sets["set_rating"].isna())
        ]

        if filter_tags:
            # Keep rows where ANY of the selected tags appear
            def has_any_tag(tags_str, target_tags):
                if pd.isna(tags_str):
                    return False
                row_tags = [t.strip() for t in tags_str.split(",")]
                return any(t in row_tags for t in target_tags)

            display_sets = display_sets[
                display_sets["tags"].apply(lambda x: has_any_tag(x, filter_tags))
            ]

        st.caption(f"Showing {len(display_sets)} sets")

        # -------------------------------------------------------------------
        # SET LIST DISPLAY
        # -------------------------------------------------------------------
        for _, s in display_sets.iterrows():
            # Build a compact header line
            date_str = s["date_performed"].strftime("%m/%d/%Y") if isinstance(
                s["date_performed"], (datetime, pd.Timestamp)
            ) else str(s["date_performed"])

            mic_name = s.get("mic_name", "Unknown Mic")
            venue = s.get("venue", "")
            set_r = s.get("set_rating")
            crowd_r = s.get("crowd_rating")

            # Detect skeletal (planned but not yet logged) entries
            is_skeletal = pd.isna(set_r)

            if is_skeletal:
                rating_emoji = "📋"
                header_suffix = "PLANNED — tap to log details"
            else:
                set_r = int(set_r)
                crowd_r = int(crowd_r) if pd.notna(crowd_r) else "?"
                if set_r >= 8:
                    rating_emoji = "🔥"
                elif set_r >= 5:
                    rating_emoji = "👍"
                else:
                    rating_emoji = "😬"
                header_suffix = f"Set: {set_r}/10 · Crowd: {crowd_r}/10"

            # Tags as pills
            tags_display = ""
            if s.get("tags") and pd.notna(s["tags"]):
                tag_list = [t.strip() for t in s["tags"].split(",") if t.strip()]
                tags_display = " ".join([f"`{t}`" for t in tag_list])

            with st.expander(
                f"{rating_emoji} **{date_str}** — {mic_name} @ {venue} — "
                f"{header_suffix} {tags_display}"
            ):
                if is_skeletal:
                    st.info(
                        "You marked yourself as going to this mic. "
                        "Head to the **Log a New Set** tab to fill in your ratings and notes!"
                    )
                    st.write(f"📅 **Date:** {date_str}")
                    st.write(f"🎤 **Mic:** {mic_name} @ {venue}")
                else:
                    detail_col1, detail_col2 = st.columns(2)

                    with detail_col1:
                        st.write(f"📅 **Date:** {date_str}")
                        st.write(f"🎤 **Mic:** {mic_name} @ {venue}")
                        st.write(f"📊 **Set Rating:** {set_r}/10")
                        st.write(f"👥 **Crowd Rating:** {crowd_r}/10")
                        st.write(f"👥 **Crowd Size:** {s.get('crowd_size', 'N/A')}")

                        if s.get("new_material"):
                            st.write("🆕 **New material**")
                        if s.get("would_return") == 0:
                            st.write("❌ **Would NOT return**")

                    with detail_col2:
                        if s.get("set_list") and pd.notna(s["set_list"]):
                            st.write("**Set List:**")
                            st.text(s["set_list"])

                        if s.get("notes") and pd.notna(s["notes"]):
                            st.write(f"**Notes:** {s['notes']}")

                        if s.get("got_feedback") and s.get("feedback_notes") and pd.notna(s["feedback_notes"]):
                            st.write(f"💬 **Feedback:** {s['feedback_notes']}")

                        if s.get("recording_url") and pd.notna(s["recording_url"]):
                            rec_type = s.get("recording_type", "recording")
                            st.link_button(
                                f"▶️ Watch/Listen ({rec_type})",
                                s["recording_url"]
                            )
