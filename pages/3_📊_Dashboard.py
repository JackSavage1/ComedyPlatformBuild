"""
Performance Dashboard — Page 3

Your comedy analytics HQ. All charts use Plotly, which creates interactive
charts (hover for details, zoom, pan, etc.).

Key concepts:
- Plotly Express (px) — A high-level charting library. You give it a DataFrame
  and tell it which columns to use for X, Y, color, etc. It handles the rest.
- st.plotly_chart() — Renders a Plotly chart in Streamlit.
- st.metric() — Shows a big number with an optional trend arrow (delta).
"""

import streamlit as st
import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
from datetime import datetime, timedelta
from utils.database import get_all_sets, get_all_mics

# ---------------------------------------------------------------------------
# PAGE CONFIG
# ---------------------------------------------------------------------------
st.set_page_config(page_title="Dashboard", page_icon="📊", layout="wide")
st.title("📊 Performance Dashboard")

# ---------------------------------------------------------------------------
# LOAD DATA
# ---------------------------------------------------------------------------
all_sets = get_all_sets()
all_mics = get_all_mics()

# Filter out skeletal entries (auto-created by "Going" button with no ratings yet)
# These have NULL set_rating and would corrupt chart calculations.
all_sets = all_sets[all_sets["set_rating"].notna()]

# ---------------------------------------------------------------------------
# EMPTY STATE — Show a friendly message if there's no data
# ---------------------------------------------------------------------------
if all_sets.empty:
    st.markdown("---")
    st.markdown(
        """
        ### No sets logged yet!

        Hit a mic tonight and come back to log it.
        Your first data point is always the hardest. 🎤

        Once you start logging sets, this dashboard will show:
        - 📈 Your improvement trajectory over time
        - 📊 Which venues and neighborhoods you frequent
        - 🏷️ Tag analysis (are you killing more and bombing less?)
        - ⭐ Your mic ratings and visit patterns

        **Head to the "My Sets" page to log your first performance!**
        """
    )
    st.stop()  # Stop rendering the rest of the page

# ---------------------------------------------------------------------------
# DATA PREP
#
# Convert date strings to actual datetime objects so Plotly can work with them.
# Also extract useful columns for grouping/filtering.
# ---------------------------------------------------------------------------
all_sets["date_performed"] = pd.to_datetime(all_sets["date_performed"])
all_sets["month"] = all_sets["date_performed"].dt.to_period("M").astype(str)

# ===========================================================================
# ROW 1 — KEY METRICS (4 big numbers across the top)
# ===========================================================================
st.markdown("---")
metric_col1, metric_col2, metric_col3, metric_col4 = st.columns(4)

with metric_col1:
    st.metric("🎤 Total Sets", len(all_sets))

with metric_col2:
    avg_rating = all_sets["set_rating"].mean()
    # Calculate trend vs last month
    now = datetime.now()
    last_month_start = (now.replace(day=1) - timedelta(days=1)).replace(day=1)
    this_month_sets = all_sets[all_sets["date_performed"] >= now.replace(day=1)]
    last_month_sets = all_sets[
        (all_sets["date_performed"] >= last_month_start)
        & (all_sets["date_performed"] < now.replace(day=1))
    ]

    delta = None
    if not this_month_sets.empty and not last_month_sets.empty:
        delta = round(
            this_month_sets["set_rating"].mean() - last_month_sets["set_rating"].mean(),
            1
        )

    st.metric(
        "⭐ Avg Set Rating",
        f"{avg_rating:.1f}/10",
        delta=f"{delta:+.1f} vs last month" if delta is not None else None
    )

with metric_col3:
    # Week streak — consecutive weeks with at least 1 set
    all_sets["iso_year"] = all_sets["date_performed"].dt.isocalendar().year
    all_sets["iso_week"] = all_sets["date_performed"].dt.isocalendar().week
    all_sets["year_week"] = (
        all_sets["iso_year"].astype(str) + "-W"
        + all_sets["iso_week"].astype(str).str.zfill(2)
    )

    unique_weeks = sorted(all_sets["year_week"].unique(), reverse=True)
    streak = 0
    if unique_weeks:
        # Start from the most recent week and count backwards
        # For simplicity, just count consecutive entries in the sorted list
        current_year, current_week = datetime.now().isocalendar()[:2]
        check_week = current_week
        check_year = current_year

        for _ in range(52):  # Check up to a year back
            week_str = f"{check_year}-W{str(check_week).zfill(2)}"
            if week_str in unique_weeks:
                streak += 1
                check_week -= 1
                if check_week < 1:
                    check_year -= 1
                    check_week = 52
            else:
                break

    st.metric("🔥 Week Streak", f"{streak}")

with metric_col4:
    unique_venues = all_sets["venue"].dropna().nunique()
    total_venues = len(all_mics)
    st.metric("🏠 Venues Visited", f"{unique_venues} / {total_venues}")

# ===========================================================================
# ROW 2 — RATING CHARTS
# ===========================================================================
st.markdown("---")
st.subheader("📈 Rating Trends")

chart_col1, chart_col2 = st.columns(2)

with chart_col1:
    # Set Rating Over Time — Line chart with trend line
    fig_rating = px.scatter(
        all_sets,
        x="date_performed",
        y="set_rating",
        color="venue",
        title="Set Rating Over Time",
        labels={"date_performed": "Date", "set_rating": "Set Rating", "venue": "Venue"},
        trendline="ols",  # OLS = Ordinary Least Squares, draws a best-fit trend line
        hover_data=["mic_name", "crowd_size"],
    )
    fig_rating.update_layout(
        template="plotly_dark",
        yaxis_range=[0, 11],
        showlegend=True,
        legend=dict(orientation="h", yanchor="bottom", y=-0.4),
    )
    st.plotly_chart(fig_rating, use_container_width=True)

with chart_col2:
    # Crowd Rating vs Set Rating — Scatter plot
    # Map crowd sizes to numeric values for dot sizing
    size_map = {"empty": 10, "sparse": 20, "decent": 35, "packed": 50}
    all_sets["crowd_size_num"] = all_sets["crowd_size"].map(size_map).fillna(20)

    fig_scatter = px.scatter(
        all_sets,
        x="crowd_rating",
        y="set_rating",
        size="crowd_size_num",
        color="venue",
        title="Crowd Rating vs Set Rating",
        labels={
            "crowd_rating": "Crowd Rating",
            "set_rating": "Set Rating",
            "crowd_size_num": "Crowd Size",
        },
        hover_data=["mic_name", "date_performed", "crowd_size"],
    )
    fig_scatter.update_layout(
        template="plotly_dark",
        xaxis_range=[0, 11],
        yaxis_range=[0, 11],
        showlegend=True,
        legend=dict(orientation="h", yanchor="bottom", y=-0.4),
    )
    st.plotly_chart(fig_scatter, use_container_width=True)

# ===========================================================================
# ROW 3 — BREAKDOWNS (bar charts)
# ===========================================================================
st.markdown("---")
st.subheader("📊 Breakdowns")

break_col1, break_col2, break_col3 = st.columns(3)

with break_col1:
    # Sets by Day of Week
    day_order = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    day_counts = all_sets["day_of_week"].value_counts().reindex(day_order, fill_value=0)

    fig_days = px.bar(
        x=day_counts.index,
        y=day_counts.values,
        title="Sets by Day of Week",
        labels={"x": "Day", "y": "Sets"},
        color=day_counts.values,
        color_continuous_scale="Reds",
    )
    fig_days.update_layout(template="plotly_dark", showlegend=False, coloraxis_showscale=False)
    st.plotly_chart(fig_days, use_container_width=True)

with break_col2:
    # Sets by Neighborhood
    hood_counts = all_sets["neighborhood"].dropna().value_counts().head(10)

    fig_hoods = px.bar(
        x=hood_counts.values,
        y=hood_counts.index,
        title="Sets by Neighborhood",
        labels={"x": "Sets", "y": "Neighborhood"},
        orientation="h",
        color=hood_counts.values,
        color_continuous_scale="Blues",
    )
    fig_hoods.update_layout(template="plotly_dark", showlegend=False, coloraxis_showscale=False)
    st.plotly_chart(fig_hoods, use_container_width=True)

with break_col3:
    # Top 5 Mics
    mic_counts = all_sets["mic_name"].dropna().value_counts().head(5)

    fig_mics = px.bar(
        x=mic_counts.values,
        y=mic_counts.index,
        title="Top 5 Mics",
        labels={"x": "Visits", "y": "Mic"},
        orientation="h",
        color=mic_counts.values,
        color_continuous_scale="Greens",
    )
    fig_mics.update_layout(template="plotly_dark", showlegend=False, coloraxis_showscale=False)
    st.plotly_chart(fig_mics, use_container_width=True)

# ===========================================================================
# ROW 4 — TAG ANALYSIS
# ===========================================================================
st.markdown("---")
st.subheader("🏷️ Tag Analysis")

# Parse all tags from comma-separated strings into individual counts
all_tags_list = []
for _, row in all_sets.iterrows():
    if row.get("tags") and pd.notna(row["tags"]):
        date_val = row["date_performed"]
        for tag in row["tags"].split(","):
            tag = tag.strip()
            if tag:
                all_tags_list.append({"tag": tag, "date": date_val})

if all_tags_list:
    tags_df = pd.DataFrame(all_tags_list)

    tag_col1, tag_col2 = st.columns(2)

    with tag_col1:
        # Tag frequency bar chart
        tag_counts = tags_df["tag"].value_counts()

        # Color-code: green for positive tags, red for negative
        positive_tags = {"killed", "got_laughs", "confident", "crowd_work", "got_spotted"}
        negative_tags = {"bombed", "nervous"}
        colors = [
            "#2ecc71" if t in positive_tags
            else "#e74c3c" if t in negative_tags
            else "#3498db"
            for t in tag_counts.index
        ]

        fig_tags = go.Figure(data=[
            go.Bar(x=tag_counts.index, y=tag_counts.values, marker_color=colors)
        ])
        fig_tags.update_layout(
            title="Tag Frequency",
            template="plotly_dark",
            xaxis_title="Tag",
            yaxis_title="Count",
        )
        st.plotly_chart(fig_tags, use_container_width=True)

    with tag_col2:
        # Tag distribution over time — stacked area
        tags_df["month"] = tags_df["date"].dt.to_period("M").astype(str)
        tag_monthly = tags_df.groupby(["month", "tag"]).size().reset_index(name="count")

        fig_tag_time = px.area(
            tag_monthly,
            x="month",
            y="count",
            color="tag",
            title="Tags Over Time",
            labels={"month": "Month", "count": "Count", "tag": "Tag"},
        )
        fig_tag_time.update_layout(
            template="plotly_dark",
            legend=dict(orientation="h", yanchor="bottom", y=-0.4),
        )
        st.plotly_chart(fig_tag_time, use_container_width=True)
else:
    st.info("No tags recorded yet. Start tagging your sets to see patterns emerge!")

# ===========================================================================
# ROW 5 — MIC RATINGS TABLE
# ===========================================================================
st.markdown("---")
st.subheader("⭐ My Mic Ratings")

# Build a summary table: for each mic I've visited, show stats
if not all_sets.empty:
    mic_summary = all_sets.groupby(["mic_name", "venue"]).agg(
        times_visited=("id", "count"),
        avg_set_rating=("set_rating", "mean"),
        avg_crowd_rating=("crowd_rating", "mean"),
        last_visited=("date_performed", "max"),
    ).reset_index()

    # Merge in the mic_rating from the open_mics table
    mic_ratings = all_mics[["name", "mic_rating"]].rename(columns={"name": "mic_name"})
    mic_summary = mic_summary.merge(mic_ratings, on="mic_name", how="left")

    # Round averages
    mic_summary["avg_set_rating"] = mic_summary["avg_set_rating"].round(1)
    mic_summary["avg_crowd_rating"] = mic_summary["avg_crowd_rating"].round(1)
    mic_summary["last_visited"] = mic_summary["last_visited"].dt.strftime("%m/%d/%Y")

    # Sort by times visited
    mic_summary = mic_summary.sort_values("times_visited", ascending=False)

    # Rename columns for display
    display_summary = mic_summary.rename(columns={
        "mic_name": "Mic",
        "venue": "Venue",
        "mic_rating": "My Rating",
        "times_visited": "Visits",
        "avg_set_rating": "Avg Set Rating",
        "avg_crowd_rating": "Avg Crowd Rating",
        "last_visited": "Last Visit",
    })

    st.dataframe(
        display_summary,
        use_container_width=True,
        hide_index=True,
    )

    # Highlight highly rated but not recently visited
    if mic_summary["mic_rating"].notna().any():
        high_rated = mic_summary[
            (mic_summary["mic_rating"] >= 7)
            & (pd.to_datetime(mic_summary["last_visited"], format="%m/%d/%Y")
               < datetime.now() - timedelta(days=30))
        ]
        if not high_rated.empty:
            st.info(
                "💡 **Mics you rated highly but haven't visited in 30+ days:** "
                + ", ".join(high_rated["mic_name"].tolist())
            )
