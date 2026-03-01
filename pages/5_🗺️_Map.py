"""
Map Page — Visualize all open mics on an interactive map

Uses pydeck (Mapbox-based) to display all comedy open mics in NYC.
Each mic appears as a colored dot you can hover over for details.
"""

import streamlit as st
import pydeck as pdk
import pandas as pd
from utils.database import (
    get_mics_with_coordinates,
    geocode_all_mics,
    migrate_add_coordinates,
    get_all_mics
)

st.set_page_config(page_title="Mic Map", page_icon="🗺️", layout="wide")

st.title("🗺️ Open Mic Map")
st.caption("All NYC comedy open mics plotted on a map")

# Ensure database has coordinate columns
migrate_add_coordinates()

# Sidebar controls
with st.sidebar:
    st.header("Map Controls")

    # Day filter
    day_filter = st.multiselect(
        "Filter by Day",
        ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
        default=[]
    )

    # Borough filter
    borough_filter = st.multiselect(
        "Filter by Borough",
        ["Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island"],
        default=[]
    )

    st.divider()

    # Geocoding section
    st.subheader("Geocoding")
    all_mics = get_all_mics()
    mics_with_coords = get_mics_with_coordinates()

    total_mics = len(all_mics)
    geocoded_mics = len(mics_with_coords)
    missing_coords = total_mics - geocoded_mics

    st.metric("Mics with coordinates", f"{geocoded_mics}/{total_mics}")

    if missing_coords > 0:
        st.warning(f"{missing_coords} mics need geocoding")
        if st.button("🌍 Geocode Missing Mics"):
            with st.spinner(f"Geocoding {missing_coords} addresses... (this may take a minute)"):
                count = geocode_all_mics()
                st.success(f"Geocoded {count} mics!")
                st.rerun()

# Get mics with coordinates
df = get_mics_with_coordinates()

if df.empty:
    st.warning("No mics have coordinates yet. Click 'Geocode Missing Mics' in the sidebar to add them.")
    st.stop()

# Apply filters
if day_filter:
    df = df[df['day_of_week'].isin(day_filter)]

if borough_filter:
    df = df[df['borough'].isin(borough_filter)]

if df.empty:
    st.info("No mics match your filters. Try adjusting the filters in the sidebar.")
    st.stop()

# Color mapping by borough
borough_colors = {
    "Manhattan": [255, 99, 71],    # Tomato red
    "Brooklyn": [30, 144, 255],    # Dodger blue
    "Queens": [50, 205, 50],       # Lime green
    "Bronx": [255, 165, 0],        # Orange
    "Staten Island": [147, 112, 219],  # Purple
}

# Add color column based on borough
df['color'] = df['borough'].apply(lambda b: borough_colors.get(b, [128, 128, 128]))

# Create tooltip text
df['tooltip'] = df.apply(
    lambda row: f"<b>{row['name']}</b><br/>"
                f"📍 {row['venue']}<br/>"
                f"📅 {row['day_of_week']}s at {row['display_time'] or row['start_time']}<br/>"
                f"💰 {row['cost'] or 'Unknown'}<br/>"
                f"🏘️ {row['neighborhood'] or row['borough']}",
    axis=1
)

# Map stats
col1, col2, col3 = st.columns(3)
with col1:
    st.metric("Mics on Map", len(df))
with col2:
    st.metric("Neighborhoods", df['neighborhood'].nunique())
with col3:
    st.metric("Boroughs", df['borough'].nunique())

# Create the map layer
layer = pdk.Layer(
    "ScatterplotLayer",
    data=df,
    get_position=["longitude", "latitude"],
    get_color="color",
    get_radius=150,
    pickable=True,
    auto_highlight=True,
)

# Set initial view to NYC center
view_state = pdk.ViewState(
    latitude=40.7580,
    longitude=-73.9855,
    zoom=11,
    pitch=0,
)

# Render the map
st.pydeck_chart(
    pdk.Deck(
        layers=[layer],
        initial_view_state=view_state,
        tooltip={"html": "{tooltip}", "style": {"backgroundColor": "#1e1e1e", "color": "white"}},
        map_style="mapbox://styles/mapbox/dark-v10",
    )
)

# Legend
st.markdown("### Legend")
legend_cols = st.columns(5)
for i, (borough, color) in enumerate(borough_colors.items()):
    with legend_cols[i]:
        hex_color = f"#{color[0]:02x}{color[1]:02x}{color[2]:02x}"
        st.markdown(f"<span style='color:{hex_color};font-size:24px;'>●</span> {borough}", unsafe_allow_html=True)

# Mic list below map
st.divider()
st.subheader("Mics on Map")

# Display as expandable cards grouped by day
for day in ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]:
    day_mics = df[df['day_of_week'] == day]
    if not day_mics.empty:
        with st.expander(f"**{day}** ({len(day_mics)} mics)", expanded=False):
            for _, mic in day_mics.iterrows():
                st.markdown(f"""
                **{mic['name']}** @ {mic['venue']}
                - 📍 {mic['address']}
                - ⏰ {mic['display_time'] or mic['start_time']}
                - 💰 {mic['cost'] or 'Unknown'}
                """)
