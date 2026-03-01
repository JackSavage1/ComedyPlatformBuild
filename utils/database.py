"""
Database Module — All PostgreSQL Operations (Supabase)

This module connects to a Supabase-hosted PostgreSQL database so your data
persists across sessions and works from any computer.

Previously this used SQLite (a local file). Now it uses psycopg2 to talk to
PostgreSQL over the internet. The connection URL is stored in Streamlit secrets
(.streamlit/secrets.toml locally, or in the Streamlit Cloud dashboard).

Key concepts:
- A "connection" (conn) opens a network connection to Supabase's PostgreSQL server
- A "cursor" executes SQL commands on that connection
- "%s" placeholders in SQL prevent SQL injection (same idea as SQLite's "?")
- pd.read_sql_query() works identically with PostgreSQL — just pass the connection

pandas DataFrames:
- pandas is a library for working with tabular data (think: spreadsheets in Python)
- pd.read_sql_query() runs a SQL query and returns the results as a DataFrame
- DataFrames are what Streamlit uses to display tables, charts, etc.
"""

import psycopg2
import psycopg2.extras
import pandas as pd
import streamlit as st
from datetime import datetime
from geopy.geocoders import Nominatim
from geopy.exc import GeocoderTimedOut
import time


def get_connection():
    """
    Opens a connection to the Supabase PostgreSQL database.

    The connection parameters are stored in Streamlit secrets (never in code).
    Locally: .streamlit/secrets.toml
    On Streamlit Cloud: configured in the app's Secrets settings.

    We pass individual parameters instead of a single URL to avoid issues
    with special characters (like brackets) in the password.
    """
    db = st.secrets["database"]
    conn = psycopg2.connect(
        host=db["host"],
        port=db["port"],
        dbname=db["dbname"],
        user=db["user"],
        password=db["password"],
    )
    return conn


def init_db():
    """
    Creates all tables if they don't already exist.

    Safe to call every time the app starts — "IF NOT EXISTS" means it won't
    overwrite existing data. Think of it as "set up the filing cabinet
    only if it hasn't been set up yet."
    """
    conn = get_connection()
    cursor = conn.cursor()

    # -----------------------------------------------------------------------
    # Table: open_mics — The master list of all open mics
    # -----------------------------------------------------------------------
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS open_mics (
            id                SERIAL PRIMARY KEY,
            name              TEXT NOT NULL,
            venue             TEXT NOT NULL,
            address           TEXT,
            neighborhood      TEXT,
            borough           TEXT,
            day_of_week       TEXT NOT NULL,
            start_time        TEXT NOT NULL,
            display_time      TEXT,
            end_time          TEXT,
            cost              TEXT,
            set_length_min    INTEGER,
            signup_method     TEXT,
            signup_url        TEXT,
            signup_notes      TEXT,
            venue_url         TEXT,
            instagram         TEXT,
            urgency           TEXT DEFAULT 'normal',
            urgency_note      TEXT,
            advance_days      INTEGER DEFAULT 0,
            mic_rating        REAL,
            notes             TEXT,
            latitude          REAL,
            longitude         REAL,
            is_active         BOOLEAN DEFAULT TRUE,
            is_biweekly       BOOLEAN DEFAULT FALSE,
            created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)

    # -----------------------------------------------------------------------
    # Table: my_sets — Log of every time I perform
    # -----------------------------------------------------------------------
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS my_sets (
            id                SERIAL PRIMARY KEY,
            open_mic_id       INTEGER REFERENCES open_mics(id),
            date_performed    DATE NOT NULL,
            set_rating        INTEGER,
            crowd_rating      INTEGER,
            crowd_size        TEXT,
            set_list          TEXT,
            recording_url     TEXT,
            recording_type    TEXT,
            notes             TEXT,
            new_material      BOOLEAN DEFAULT FALSE,
            got_feedback      BOOLEAN DEFAULT FALSE,
            feedback_notes    TEXT,
            would_return      BOOLEAN DEFAULT TRUE,
            tags              TEXT,
            created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)

    # -----------------------------------------------------------------------
    # Table: mic_plans — Track which mics you're going to on specific dates
    #
    # This bridges recurring mics (open_mics) with specific calendar dates.
    # "Going" creates a plan + auto-creates a skeletal set entry.
    # "Cancelled" marks the mic as not happening that night.
    # -----------------------------------------------------------------------
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS mic_plans (
            id              SERIAL PRIMARY KEY,
            open_mic_id     INTEGER NOT NULL REFERENCES open_mics(id),
            plan_date       DATE NOT NULL,
            status          TEXT NOT NULL CHECK(status IN ('going', 'cancelled')),
            created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(open_mic_id, plan_date)
        )
    """)

    # -----------------------------------------------------------------------
    # Table: scrape_log — Track when scrapers last ran
    # -----------------------------------------------------------------------
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS scrape_log (
            id                SERIAL PRIMARY KEY,
            source            TEXT NOT NULL,
            last_scraped      TIMESTAMP,
            status            TEXT,
            notes             TEXT
        )
    """)

    conn.commit()
    conn.close()


def migrate_add_coordinates():
    """
    Adds latitude and longitude columns to open_mics table if they don't exist.
    Safe to call multiple times - only adds columns if missing.
    """
    conn = get_connection()
    cursor = conn.cursor()

    # Check if columns exist
    cursor.execute("""
        SELECT column_name FROM information_schema.columns
        WHERE table_name = 'open_mics' AND column_name IN ('latitude', 'longitude')
    """)
    existing = {row[0] for row in cursor.fetchall()}

    if 'latitude' not in existing:
        cursor.execute("ALTER TABLE open_mics ADD COLUMN latitude REAL")
    if 'longitude' not in existing:
        cursor.execute("ALTER TABLE open_mics ADD COLUMN longitude REAL")

    conn.commit()
    conn.close()


# ===========================================================================
# GEOCODING — Convert addresses to lat/lon coordinates
# ===========================================================================

def geocode_address(address, borough="New York"):
    """
    Converts a street address to latitude/longitude coordinates.

    Uses OpenStreetMap's Nominatim geocoder (free, no API key needed).
    Appends ", New York, NY" to improve accuracy for NYC addresses.

    Returns:
        tuple: (latitude, longitude) or (None, None) if geocoding fails
    """
    if not address:
        return None, None

    geolocator = Nominatim(user_agent="comedy_mic_tracker")
    full_address = f"{address}, {borough}, New York, NY"

    try:
        location = geolocator.geocode(full_address, timeout=10)
        if location:
            return location.latitude, location.longitude
    except GeocoderTimedOut:
        pass
    except Exception:
        pass

    return None, None


def geocode_all_mics():
    """
    Geocodes all mics that don't have coordinates yet.

    Adds a small delay between requests to respect Nominatim's rate limits.
    Returns the count of mics successfully geocoded.
    """
    conn = get_connection()
    cursor = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    # Find mics without coordinates
    cursor.execute("""
        SELECT id, address, borough FROM open_mics
        WHERE is_active = TRUE AND (latitude IS NULL OR longitude IS NULL) AND address IS NOT NULL
    """)
    mics_to_geocode = cursor.fetchall()

    geocoded_count = 0
    for mic in mics_to_geocode:
        lat, lon = geocode_address(mic['address'], mic.get('borough', 'New York'))
        if lat and lon:
            cursor.execute(
                "UPDATE open_mics SET latitude = %s, longitude = %s WHERE id = %s",
                (lat, lon, mic['id'])
            )
            geocoded_count += 1
        time.sleep(1)  # Respect Nominatim rate limit (1 request/second)

    conn.commit()
    conn.close()
    return geocoded_count


def get_mics_with_coordinates():
    """
    Returns all active mics that have valid coordinates for mapping.
    """
    conn = get_connection()
    df = pd.read_sql_query("""
        SELECT * FROM open_mics
        WHERE is_active = TRUE AND latitude IS NOT NULL AND longitude IS NOT NULL
        ORDER BY day_of_week, start_time
    """, conn)
    conn.close()
    return df


# ===========================================================================
# HELPER FUNCTIONS — These are the "API" other parts of the app use
# ===========================================================================

def get_all_mics():
    """
    Returns a pandas DataFrame of ALL active mics.

    We join nothing here — just the raw mic list.
    is_active = TRUE filters out mics you've "soft deleted" (deactivated).
    """
    conn = get_connection()
    df = pd.read_sql_query(
        "SELECT * FROM open_mics WHERE is_active = TRUE ORDER BY day_of_week, start_time",
        conn
    )
    conn.close()
    return df


def get_mics_by_day(day):
    """
    Returns all active mics for a specific day of the week.

    Args:
        day: A string like "Monday", "Tuesday", etc.
    """
    conn = get_connection()
    df = pd.read_sql_query(
        "SELECT * FROM open_mics WHERE is_active = TRUE AND day_of_week = %s ORDER BY start_time",
        conn,
        params=(day,)
    )
    conn.close()
    return df


def get_mics_today():
    """
    Returns all active mics for TODAY's day of the week.

    datetime.now().strftime("%A") gives us the full day name:
    Monday, Tuesday, etc. — matching what we store in the database.
    """
    today = datetime.now().strftime("%A")
    return get_mics_by_day(today)


def add_set(data_dict):
    """
    Inserts a new set log into the my_sets table.

    Args:
        data_dict: A dictionary with keys matching my_sets columns, e.g.:
            {"open_mic_id": 1, "date_performed": "2025-01-15", "set_rating": 7, ...}

    How this works:
    - We build the column names and placeholders dynamically from the dict keys
    - This way we don't have to list every column — just pass what you have
    """
    conn = get_connection()
    cursor = conn.cursor()

    columns = ", ".join(data_dict.keys())
    placeholders = ", ".join(["%s"] * len(data_dict))
    values = tuple(data_dict.values())

    cursor.execute(
        f"INSERT INTO my_sets ({columns}) VALUES ({placeholders})",
        values
    )
    conn.commit()
    conn.close()


def get_all_sets():
    """
    Returns a DataFrame of all my sets, joined with mic info.

    A JOIN combines two tables — here we connect each set with its mic's name,
    venue, and neighborhood so we don't just see mic IDs.

    "LEFT JOIN" means: show all sets even if somehow the mic was deleted.
    """
    conn = get_connection()
    df = pd.read_sql_query("""
        SELECT
            s.*,
            m.name AS mic_name,
            m.venue,
            m.neighborhood,
            m.borough,
            m.day_of_week
        FROM my_sets s
        LEFT JOIN open_mics m ON s.open_mic_id = m.id
        ORDER BY s.date_performed DESC
    """, conn)
    conn.close()
    return df


def get_sets_for_mic(mic_id):
    """
    Returns all my sets at a specific mic.
    Useful for the mic detail view — "How have I done here?"
    """
    conn = get_connection()
    df = pd.read_sql_query(
        "SELECT * FROM my_sets WHERE open_mic_id = %s ORDER BY date_performed DESC",
        conn,
        params=(mic_id,)
    )
    conn.close()
    return df


def get_set_count_for_mic(mic_id):
    """Returns how many times I've performed at a specific mic."""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT COUNT(*) FROM my_sets WHERE open_mic_id = %s", (mic_id,))
    count = cursor.fetchone()[0]
    conn.close()
    return count


def update_mic_rating(mic_id, rating):
    """
    Updates the overall mic rating for a specific mic.
    This is MY opinion of the mic itself (not how I performed there).
    """
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "UPDATE open_mics SET mic_rating = %s, updated_at = CURRENT_TIMESTAMP WHERE id = %s",
        (rating, mic_id)
    )
    conn.commit()
    conn.close()


def seed_mics(mic_list):
    """
    Bulk inserts mics from a list of dictionaries.

    Used for the initial data load (Step 3). Each dict should have keys
    matching the open_mics table columns.

    Args:
        mic_list: A list of dicts, e.g.:
            [{"name": "Try New Sh*t", "venue": "EastVille", ...}, ...]
    """
    conn = get_connection()
    cursor = conn.cursor()

    for mic in mic_list:
        columns = ", ".join(mic.keys())
        placeholders = ", ".join(["%s"] * len(mic))
        values = tuple(mic.values())

        cursor.execute(
            f"INSERT INTO open_mics ({columns}) VALUES ({placeholders})",
            values
        )

    conn.commit()
    conn.close()


def is_db_empty():
    """
    Checks if the open_mics table has any rows.
    Used to decide whether to run the seed function.
    """
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT COUNT(*) FROM open_mics")
    count = cursor.fetchone()[0]
    conn.close()
    return count == 0


def get_mic_by_id(mic_id):
    """Returns a single mic's data as a dictionary."""
    conn = get_connection()
    cursor = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cursor.execute("SELECT * FROM open_mics WHERE id = %s", (mic_id,))
    row = cursor.fetchone()
    conn.close()
    if row:
        return dict(row)
    return None


def update_mic(mic_id, data_dict):
    """
    Updates a mic's fields from a dictionary.
    Only updates the fields you pass in.
    """
    conn = get_connection()
    cursor = conn.cursor()

    set_clause = ", ".join([f"{k} = %s" for k in data_dict.keys()])
    values = tuple(data_dict.values()) + (mic_id,)

    cursor.execute(
        f"UPDATE open_mics SET {set_clause}, updated_at = CURRENT_TIMESTAMP WHERE id = %s",
        values
    )
    conn.commit()
    conn.close()


def deactivate_mic(mic_id):
    """Soft-deletes a mic by setting is_active = FALSE."""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "UPDATE open_mics SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = %s",
        (mic_id,)
    )
    conn.commit()
    conn.close()


def add_mic(data_dict):
    """
    Inserts a new open mic into the database.
    Automatically removes 'id' if present to allow Postgres to auto-generate it.
    """
    conn = get_connection()
    try:
        with conn.cursor() as cursor:
            # Remove 'id' (Postgres auto-generates it) and 'source' (not a table column)
            clean_data = {k: v for k, v in data_dict.items() if k not in ['id', 'source']}

            columns = ", ".join(clean_data.keys())
            placeholders = ", ".join(["%s"] * len(clean_data))
            values = tuple(clean_data.values())

            query = f"INSERT INTO open_mics ({columns}) VALUES ({placeholders})"
            cursor.execute(query, values)
            conn.commit()
    finally:
        conn.close()


# ===========================================================================
# MIC PLANS — Going / Cancelled tracking for specific dates
# ===========================================================================

def set_mic_plan(open_mic_id, plan_date, status):
    """
    Creates or updates a plan for a mic on a specific date.

    Uses "ON CONFLICT ... DO UPDATE" so if you click "Going" and then
    "Cancelled" for the same mic on the same date, it just updates the row
    instead of creating a duplicate (thanks to the UNIQUE constraint).
    """
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT INTO mic_plans (open_mic_id, plan_date, status)
        VALUES (%s, %s, %s)
        ON CONFLICT(open_mic_id, plan_date) DO UPDATE SET
            status = excluded.status,
            created_at = CURRENT_TIMESTAMP
    """, (open_mic_id, plan_date, status))
    conn.commit()
    conn.close()


def remove_mic_plan(open_mic_id, plan_date):
    """Removes a plan entirely (user clicks 'Clear')."""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "DELETE FROM mic_plans WHERE open_mic_id = %s AND plan_date = %s",
        (open_mic_id, plan_date)
    )
    conn.commit()
    conn.close()


def get_plans_for_week(week_start, week_end):
    """
    Returns a DataFrame of all plans within a date range.

    Used by the calendar to know which mics are marked going/cancelled
    for the current week.
    """
    conn = get_connection()
    df = pd.read_sql_query("""
        SELECT p.*, m.name AS mic_name, m.venue, m.day_of_week
        FROM mic_plans p
        JOIN open_mics m ON p.open_mic_id = m.id
        WHERE p.plan_date BETWEEN %s AND %s
        ORDER BY p.plan_date
    """, conn, params=(week_start, week_end))
    conn.close()
    return df


def get_going_mic_ids_for_week(week_start, week_end):
    """Returns a set of open_mic_ids marked 'going' within a date range."""
    conn = get_connection()
    cursor = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cursor.execute("""
        SELECT DISTINCT open_mic_id FROM mic_plans
        WHERE plan_date BETWEEN %s AND %s AND status = 'going'
    """, (week_start, week_end))
    ids = {row["open_mic_id"] for row in cursor.fetchall()}
    conn.close()
    return ids


def get_sets_for_mic_date(mic_id, date_str):
    """
    Checks if a set entry already exists for this mic on this date.
    Prevents duplicate skeletal entries when clicking 'Going' multiple times.
    """
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "SELECT id FROM my_sets WHERE open_mic_id = %s AND date_performed = %s",
        (mic_id, date_str)
    )
    row = cursor.fetchone()
    conn.close()
    return row is not None


def delete_mic_hard(mic_id):
    """
    Soft-deletes a mic AND cleans up its future plans.

    We use soft delete (is_active = FALSE) instead of actually deleting the row
    because my_sets entries reference open_mic_id — if we deleted the mic,
    your set history would lose the mic name/venue info.
    """
    deactivate_mic(mic_id)
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("DELETE FROM mic_plans WHERE open_mic_id = %s", (mic_id,))
    conn.commit()
    conn.close()


def update_set(set_id, data_dict):
    """
    Updates an existing set entry with new data.

    Used when filling in details for a skeletal set entry that was
    auto-created by the 'Going' button.
    """
    conn = get_connection()
    cursor = conn.cursor()
    set_clause = ", ".join([f"{k} = %s" for k in data_dict.keys()])
    values = tuple(data_dict.values()) + (set_id,)
    cursor.execute(
        f"UPDATE my_sets SET {set_clause} WHERE id = %s",
        values
    )
    conn.commit()
    conn.close()


# ===========================================================================
# SCRAPE LOG — Helper functions for logging scraper runs
# ===========================================================================

def log_scrape(source, status, notes):
    """Logs a scraper run to the scrape_log table."""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute(
        "INSERT INTO scrape_log (source, last_scraped, status, notes) VALUES (%s, %s, %s, %s)",
        (source, datetime.now().isoformat(), status, notes)
    )
    conn.commit()
    conn.close()


def get_scrape_log():
    """Returns the full scrape log as a DataFrame."""
    conn = get_connection()
    df = pd.read_sql_query("SELECT * FROM scrape_log ORDER BY last_scraped DESC", conn)
    conn.close()
    return df
