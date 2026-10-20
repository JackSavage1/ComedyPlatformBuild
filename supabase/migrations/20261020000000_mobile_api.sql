-- ===========================================================================
-- Mobile API — security + RPC functions for the native iOS / Android apps
-- ===========================================================================
--
-- WHAT THIS DOES (in plain English)
--
-- The Streamlit web app talks to Postgres directly as the `postgres` user,
-- which OWNS the tables. Owners skip Row Level Security (RLS), so nothing in
-- this file changes how the web app behaves.
--
-- The phone apps are different: they talk to Supabase's REST API (PostgREST)
-- using the public "anon" key, and after signing in, as the `authenticated`
-- role. Until now RLS was OFF on every table, which means anyone holding the
-- anon key (it ships inside every app build!) could read AND change all of
-- your data. This file:
--
--   1. creates `app_admins` — the list of logins allowed to change data
--   2. creates `is_app_admin()` — "is the signed-in user on that list?"
--   3. creates `scraped_mics` — the review queue the nightly scraper fills
--   4. turns RLS ON for every table and adds the rules (policies)
--   5. adds the RPC functions the apps call (Going / Cancelled, import, ...)
--   6. locks down who may execute those functions and touch the tables
--
-- HOW TO APPLY: Supabase dashboard > SQL Editor > New query > paste this whole
-- file > Run. (See docs/MOBILE_SETUP.md.)
--
-- SAFE TO RE-RUN: every statement is idempotent (IF NOT EXISTS,
-- CREATE OR REPLACE, DROP POLICY IF EXISTS before CREATE POLICY), so running
-- it twice is harmless.
--
-- We deliberately do NOT use `FORCE ROW LEVEL SECURITY`: that would make RLS
-- apply to the table owner too, and the Streamlit app (connected as the
-- owner) would suddenly see empty tables.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 0. Existing tables (normally created by utils/database.py's init_db()).
--
-- On your live database these already exist, so these statements do nothing.
-- They are here so this file also works on a brand-new, empty project. The
-- definitions are copied verbatim from utils/database.py.
-- ---------------------------------------------------------------------------
create table if not exists public.open_mics (
    id                serial primary key,
    name              text not null,
    venue             text not null,
    address           text,
    neighborhood      text,
    borough           text,
    day_of_week       text not null,
    start_time        text not null,
    display_time      text,
    end_time          text,
    cost              text,
    set_length_min    integer,
    signup_method     text,
    signup_url        text,
    signup_notes      text,
    venue_url         text,
    instagram         text,
    urgency           text default 'normal',
    urgency_note      text,
    advance_days      integer default 0,
    mic_rating        real,
    notes             text,
    latitude          real,
    longitude         real,
    is_active         boolean default true,
    is_biweekly       boolean default false,
    created_at        timestamp default current_timestamp,
    updated_at        timestamp default current_timestamp
);

create table if not exists public.my_sets (
    id                serial primary key,
    open_mic_id       integer references public.open_mics(id),
    date_performed    date not null,
    set_rating        integer,
    crowd_rating      integer,
    crowd_size        text,
    set_list          text,
    recording_url     text,
    recording_type    text,
    notes             text,
    new_material      boolean default false,
    got_feedback      boolean default false,
    feedback_notes    text,
    would_return      boolean default true,
    tags              text,
    created_at        timestamp default current_timestamp
);

create table if not exists public.mic_plans (
    id              serial primary key,
    open_mic_id     integer not null references public.open_mics(id),
    plan_date       date not null,
    status          text not null check (status in ('going', 'cancelled')),
    created_at      timestamp default current_timestamp,
    unique (open_mic_id, plan_date)
);

create table if not exists public.scrape_log (
    id                serial primary key,
    source            text not null,
    last_scraped      timestamp,
    status            text,
    notes             text
);


-- ---------------------------------------------------------------------------
-- 1. app_admins — which Supabase logins may change data.
--
-- You add yourself once (docs/MOBILE_SETUP.md step 3):
--   insert into app_admins (user_id)
--   select id from auth.users where email = 'YOUR_EMAIL';
-- ---------------------------------------------------------------------------
create table if not exists public.app_admins (
    user_id     uuid primary key references auth.users(id) on delete cascade,
    created_at  timestamptz not null default now()
);


-- ---------------------------------------------------------------------------
-- 2. is_app_admin() — true when the signed-in user is in app_admins.
--
-- SECURITY DEFINER = "run with the privileges of the function's owner"
-- (postgres), so it can read app_admins no matter what the caller may see.
-- That avoids a chicken-and-egg problem inside the RLS policies below.
-- `set search_path = public` stops anyone from hijacking it with look-alike
-- tables in another schema. STABLE lets Postgres call it once per statement.
-- auth.uid() is Supabase's helper that reads the user id out of the JWT; it
-- is NULL for anonymous requests, so anon always gets false.
-- ---------------------------------------------------------------------------
create or replace function public.is_app_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
        from public.app_admins
        where user_id = auth.uid()
    );
$$;


-- ---------------------------------------------------------------------------
-- 3. scraped_mics — the review queue filled nightly by scripts/run_scrapers.py
--
-- The phone's "Import from Scrapers" screen lists the `pending` rows; Add
-- calls import_scraped_mic(), Dismiss calls dismiss_scraped_mic().
-- name / venue / day_of_week are NOT NULL (the scraper stores '' when a
-- listing has no value) because the UNIQUE constraint ignores NULLs, and we
-- need it to catch every duplicate.
-- scripts/run_scrapers.py contains the same CREATE TABLE — keep them in sync.
-- ---------------------------------------------------------------------------
create table if not exists public.scraped_mics (
    id             bigint generated by default as identity primary key,
    source         text not null,
    name           text not null,
    venue          text not null,
    day_of_week    text not null,
    address        text,
    display_time   text,
    start_time     text,
    cost           text,
    signup_url     text,
    payload        jsonb not null default '{}'::jsonb,
    status         text not null default 'pending'
                   check (status in ('pending', 'added', 'dismissed')),
    first_seen_at  timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint scraped_mics_source_name_venue_day_key
        unique (source, name, venue, day_of_week)
);

-- If the table was created some other way without the unique constraint,
-- add it now (ALTER TABLE ... ADD CONSTRAINT has no IF NOT EXISTS).
do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.scraped_mics'::regclass
          and conname = 'scraped_mics_source_name_venue_day_key'
    ) then
        alter table public.scraped_mics
            add constraint scraped_mics_source_name_venue_day_key
            unique (source, name, venue, day_of_week);
    end if;
end
$$;

-- The app always asks for "status = pending", so index it.
create index if not exists scraped_mics_status_idx
    on public.scraped_mics (status);


-- ---------------------------------------------------------------------------
-- 4. Row Level Security — ON for every table the API can see.
--
-- With RLS on and no matching policy, the answer is "no rows". Policies
-- below open things back up for exactly who should have access.
-- ---------------------------------------------------------------------------
alter table public.open_mics    enable row level security;
alter table public.my_sets      enable row level security;
alter table public.mic_plans    enable row level security;
alter table public.scrape_log   enable row level security;
alter table public.scraped_mics enable row level security;
alter table public.app_admins   enable row level security;


-- open_mics: anyone may READ the mic list (it's public info anyway);
-- only an app admin may add, edit, or delete.
drop policy if exists open_mics_select_all   on public.open_mics;
drop policy if exists open_mics_insert_admin on public.open_mics;
drop policy if exists open_mics_update_admin on public.open_mics;
drop policy if exists open_mics_delete_admin on public.open_mics;

create policy open_mics_select_all on public.open_mics
    for select to anon, authenticated
    using (true);

create policy open_mics_insert_admin on public.open_mics
    for insert to authenticated
    with check (public.is_app_admin());

create policy open_mics_update_admin on public.open_mics
    for update to authenticated
    using (public.is_app_admin())
    with check (public.is_app_admin());

create policy open_mics_delete_admin on public.open_mics
    for delete to authenticated
    using (public.is_app_admin());


-- my_sets, mic_plans, scrape_log, scraped_mics: your private data —
-- every operation requires an app admin.
drop policy if exists my_sets_admin_all on public.my_sets;
create policy my_sets_admin_all on public.my_sets
    for all to authenticated
    using (public.is_app_admin())
    with check (public.is_app_admin());

drop policy if exists mic_plans_admin_all on public.mic_plans;
create policy mic_plans_admin_all on public.mic_plans
    for all to authenticated
    using (public.is_app_admin())
    with check (public.is_app_admin());

drop policy if exists scrape_log_admin_all on public.scrape_log;
create policy scrape_log_admin_all on public.scrape_log
    for all to authenticated
    using (public.is_app_admin())
    with check (public.is_app_admin());

drop policy if exists scraped_mics_admin_all on public.scraped_mics;
create policy scraped_mics_admin_all on public.scraped_mics
    for all to authenticated
    using (public.is_app_admin())
    with check (public.is_app_admin());


-- app_admins: a signed-in user may see only their own row (enough to answer
-- "am I an admin?"). Nobody can add admins through the API — you do that in
-- the SQL editor.
drop policy if exists app_admins_select_own on public.app_admins;
create policy app_admins_select_own on public.app_admins
    for select to authenticated
    using (user_id = auth.uid());


-- ---------------------------------------------------------------------------
-- 5. RPC functions the apps call: POST /rest/v1/rpc/<name> {named args}
--
-- All are SECURITY INVOKER ("run as the caller"), so the RLS policies above
-- still apply inside them. Each one also checks is_app_admin() up front so
-- a non-admin gets a clear "Not authorized" error (HTTP 403) instead of a
-- silent no-op.
-- ---------------------------------------------------------------------------

-- set_plan_status — the Going / Cancelled buttons.
-- Same as the web app: upsert the plan, and when "going", make sure a
-- skeletal my_sets row exists so My Sets can prompt you to log it later.
create or replace function public.set_plan_status(
    p_mic_id    integer,
    p_plan_date date,
    p_status    text
)
returns void
language plpgsql
volatile
security invoker
set search_path = public
as $$
begin
    if not public.is_app_admin() then
        raise exception 'Not authorized: this account is not an app admin.'
            using errcode = '42501';
    end if;

    if p_status is null or p_status not in ('going', 'cancelled') then
        raise exception 'Invalid plan status "%" (expected going or cancelled).', p_status
            using errcode = '22023';
    end if;

    insert into public.mic_plans (open_mic_id, plan_date, status)
    values (p_mic_id, p_plan_date, p_status)
    on conflict (open_mic_id, plan_date) do update
        set status     = excluded.status,
            created_at = current_timestamp;

    if p_status = 'going' and not exists (
        select 1
        from public.my_sets
        where open_mic_id = p_mic_id
          and date_performed = p_plan_date
    ) then
        insert into public.my_sets (open_mic_id, date_performed)
        values (p_mic_id, p_plan_date);
    end if;
end;
$$;


-- clear_plan — the Clear button. Only the plan goes; a skeletal set row
-- created by "Going" is left alone (same as the web app).
create or replace function public.clear_plan(
    p_mic_id    integer,
    p_plan_date date
)
returns void
language plpgsql
volatile
security invoker
set search_path = public
as $$
begin
    if not public.is_app_admin() then
        raise exception 'Not authorized: this account is not an app admin.'
            using errcode = '42501';
    end if;

    delete from public.mic_plans
    where open_mic_id = p_mic_id
      and plan_date = p_plan_date;
end;
$$;


-- delete_planned_set — the ✕ on a "Planned Sets" row.
-- Safety: only a row with no set_rating (a plan, not a logged set) is ever
-- deleted. Also removes the matching calendar plan. Returns true if it
-- deleted something.
create or replace function public.delete_planned_set(
    p_set_id integer
)
returns boolean
language plpgsql
volatile
security invoker
set search_path = public
as $$
declare
    v_mic_id  integer;
    v_date    date;
begin
    if not public.is_app_admin() then
        raise exception 'Not authorized: this account is not an app admin.'
            using errcode = '42501';
    end if;

    select open_mic_id, date_performed
      into v_mic_id, v_date
    from public.my_sets
    where id = p_set_id
      and set_rating is null;

    if not found then
        return false;
    end if;

    delete from public.my_sets where id = p_set_id;

    delete from public.mic_plans
    where open_mic_id = v_mic_id
      and plan_date = v_date;

    return true;
end;
$$;


-- delete_mic — soft delete (is_active = false) so set history keeps the
-- mic's name, plus remove all of its plans. Same as delete_mic_hard() in
-- utils/database.py.
create or replace function public.delete_mic(
    p_mic_id integer
)
returns void
language plpgsql
volatile
security invoker
set search_path = public
as $$
begin
    if not public.is_app_admin() then
        raise exception 'Not authorized: this account is not an app admin.'
            using errcode = '42501';
    end if;

    update public.open_mics
       set is_active  = false,
           updated_at = current_timestamp
     where id = p_mic_id;

    delete from public.mic_plans where open_mic_id = p_mic_id;
end;
$$;


-- import_scraped_mic — "Add" on the Import screen.
--
-- Copies the scraper's payload into open_mics and returns the new mic id.
-- Works like _clean_open_mic_data() in utils/database.py:
--   * only keys that are real open_mics columns are used (scraper extras
--     like "source" are ignored), and id / is_active / created_at /
--     updated_at always come from us, never from the payload
--   * booleans are coerced: true/false, 1/0, "1"/"0", "true"/"false", "yes"...
--   * numbers sent as text or as 30.0 are coerced for integer columns
--   * null / missing / blank values are left out, so column DEFAULTs apply
--     (urgency = 'normal', advance_days = 0, ...)
--   * is_active is always true
-- Then the queue row is marked 'added'.
create or replace function public.import_scraped_mic(
    p_id bigint
)
returns integer
language plpgsql
volatile
security invoker
set search_path = public
as $$
declare
    v_row      public.scraped_mics%rowtype;
    v_source   jsonb;                  -- raw values, keyed by column name
    v_clean    jsonb := '{}'::jsonb;   -- values coerced to each column's type
    v_col      record;
    v_val      jsonb;
    v_txt      text;
    v_columns  text;
    v_new_id   integer;
begin
    if not public.is_app_admin() then
        raise exception 'Not authorized: this account is not an app admin.'
            using errcode = '42501';
    end if;

    -- FOR UPDATE locks the row so a double-tap can't import it twice.
    select * into v_row
    from public.scraped_mics
    where id = p_id
    for update;

    if not found then
        raise exception 'Scraped mic % was not found.', p_id
            using errcode = 'P0002';
    end if;

    if v_row.status = 'added' then
        raise exception '"%" was already added.', v_row.name;
    end if;

    -- Start from the denormalized columns, then let the full payload win.
    -- jsonb_strip_nulls drops {"key": null} entries so they can't overwrite.
    v_source := jsonb_strip_nulls(jsonb_build_object(
                    'name',         v_row.name,
                    'venue',        v_row.venue,
                    'day_of_week',  v_row.day_of_week,
                    'address',      v_row.address,
                    'display_time', v_row.display_time,
                    'start_time',   v_row.start_time,
                    'cost',         v_row.cost,
                    'signup_url',   v_row.signup_url))
             || case
                    when jsonb_typeof(v_row.payload) = 'object'
                        then jsonb_strip_nulls(v_row.payload)
                    else '{}'::jsonb
                end;

    -- Walk the REAL columns of open_mics and pick up matching payload keys.
    for v_col in
        select a.attname::text                      as col,
               format_type(a.atttypid, a.atttypmod) as typ,
               a.attnotnull                         as notnull
        from pg_catalog.pg_attribute a
        where a.attrelid = 'public.open_mics'::regclass
          and a.attnum > 0
          and not a.attisdropped
          and a.attname not in ('id', 'is_active', 'created_at', 'updated_at')
    loop
        v_val := v_source -> v_col.col;
        continue when v_val is null or jsonb_typeof(v_val) = 'null';

        v_txt := btrim(v_val #>> '{}');   -- the value as plain text

        -- Blank text counts as "not provided" so the default applies.
        -- (A required text column keeps '' — same as the web importer.)
        if jsonb_typeof(v_val) = 'string' and v_txt = '' then
            if v_col.notnull and v_col.typ = 'text' then
                v_clean := v_clean || jsonb_build_object(v_col.col, ''::text);
            end if;
            continue;
        end if;

        if v_col.typ = 'boolean' then
            if jsonb_typeof(v_val) = 'boolean' then
                v_clean := v_clean || jsonb_build_object(v_col.col, v_val);
            elsif jsonb_typeof(v_val) = 'number' then
                v_clean := v_clean || jsonb_build_object(v_col.col, v_txt::numeric <> 0);
            else
                v_clean := v_clean || jsonb_build_object(
                    v_col.col, lower(v_txt) in ('1', 'true', 't', 'yes', 'y', 'on'));
            end if;

        elsif v_col.typ in ('integer', 'bigint', 'smallint') then
            -- 30, 30.0 and "30" all become 30; anything else is skipped.
            if jsonb_typeof(v_val) in ('number', 'string')
               and v_txt ~ '^[-+]?[0-9]+(\.[0-9]+)?$' then
                v_clean := v_clean || jsonb_build_object(
                    v_col.col, round(v_txt::numeric)::bigint);
            end if;

        elsif v_col.typ in ('real', 'double precision') or v_col.typ like 'numeric%' then
            if jsonb_typeof(v_val) in ('number', 'string')
               and v_txt ~ '^[-+]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][-+]?[0-9]+)?$' then
                v_clean := v_clean || jsonb_build_object(v_col.col, v_txt::numeric);
            end if;

        else
            -- Text columns: keep the scraper's string exactly as it was.
            v_clean := v_clean || jsonb_build_object(v_col.col, v_val #>> '{}');
        end if;
    end loop;

    v_clean := v_clean || jsonb_build_object('is_active', true);

    -- Friendly message instead of a raw NOT NULL violation.
    for v_col in
        select a.attname::text as col
        from pg_catalog.pg_attribute a
        where a.attrelid = 'public.open_mics'::regclass
          and a.attnum > 0
          and not a.attisdropped
          and a.attnotnull
          and not a.atthasdef
    loop
        if not (v_clean ? v_col.col) then
            raise exception 'Can''t import "%": the listing has no %.', v_row.name, v_col.col
                using errcode = '23502';
        end if;
    end loop;

    -- Insert only the columns we collected. jsonb_populate_record turns the
    -- JSON object into an open_mics-shaped row, converting each value to the
    -- column's type; listing the columns explicitly lets every other column
    -- fall back to its DEFAULT.
    select string_agg(quote_ident(k), ', ' order by k)
      into v_columns
    from jsonb_object_keys(v_clean) as k;

    execute format(
        'insert into public.open_mics (%1$s) '
        'select %1$s from jsonb_populate_record(null::public.open_mics, $1) '
        'returning id',
        v_columns)
      into v_new_id
      using v_clean;

    update public.scraped_mics
       set status     = 'added',
           updated_at = now()
     where id = p_id;

    return v_new_id;
end;
$$;


-- dismiss_scraped_mic — "Dismiss" on the Import screen. The row stays in
-- the table as 'dismissed' so tomorrow's scrape doesn't re-queue it.
create or replace function public.dismiss_scraped_mic(
    p_id bigint
)
returns void
language plpgsql
volatile
security invoker
set search_path = public
as $$
begin
    if not public.is_app_admin() then
        raise exception 'Not authorized: this account is not an app admin.'
            using errcode = '42501';
    end if;

    update public.scraped_mics
       set status     = 'dismissed',
           updated_at = now()
     where id = p_id;
end;
$$;


-- ---------------------------------------------------------------------------
-- 6. Who may execute the functions.
--
-- Postgres lets EVERYONE (the PUBLIC pseudo-role) execute new functions, and
-- Supabase additionally grants them to anon. Take that away, then allow
-- only signed-in users. (Non-admins still get "Not authorized" inside.)
-- ---------------------------------------------------------------------------
revoke execute on function public.is_app_admin()                          from public, anon;
revoke execute on function public.set_plan_status(integer, date, text)    from public, anon;
revoke execute on function public.clear_plan(integer, date)               from public, anon;
revoke execute on function public.delete_planned_set(integer)             from public, anon;
revoke execute on function public.delete_mic(integer)                     from public, anon;
revoke execute on function public.import_scraped_mic(bigint)              from public, anon;
revoke execute on function public.dismiss_scraped_mic(bigint)             from public, anon;

grant execute on function public.is_app_admin()                           to authenticated;
grant execute on function public.set_plan_status(integer, date, text)     to authenticated;
grant execute on function public.clear_plan(integer, date)                to authenticated;
grant execute on function public.delete_planned_set(integer)              to authenticated;
grant execute on function public.delete_mic(integer)                      to authenticated;
grant execute on function public.import_scraped_mic(bigint)               to authenticated;
grant execute on function public.dismiss_scraped_mic(bigint)              to authenticated;


-- ---------------------------------------------------------------------------
-- 7. Table privileges.
--
-- Privileges decide which COMMANDS a role may run at all; RLS policies then
-- decide which ROWS. Supabase grants everything to anon/authenticated by
-- default, so start from nothing and grant back only what the apps need:
--   anon          → read open_mics
--   authenticated → read/write the data tables (policies still require admin)
--                   and read app_admins (policy: own row only)
-- (TRUNCATE ignores RLS, which is one more reason to revoke ALL first.)
-- ---------------------------------------------------------------------------
revoke all on table
    public.open_mics, public.my_sets, public.mic_plans,
    public.scrape_log, public.scraped_mics, public.app_admins
from anon, authenticated;

grant select on table public.open_mics to anon;

grant select, insert, update, delete on table
    public.open_mics, public.my_sets, public.mic_plans,
    public.scrape_log, public.scraped_mics
to authenticated;

grant select on table public.app_admins to authenticated;

-- Inserts pull the next id from each table's sequence, so the inserting
-- role needs USAGE on it. pg_get_serial_sequence finds the sequence behind
-- both SERIAL and IDENTITY columns, whatever it happens to be named.
do $$
declare
    v_table text;
    v_seq   text;
begin
    foreach v_table in array array['open_mics', 'my_sets', 'mic_plans', 'scrape_log', 'scraped_mics']
    loop
        v_seq := pg_get_serial_sequence('public.' || v_table, 'id');
        if v_seq is not null then
            execute format('revoke all on sequence %s from anon', v_seq);
            execute format('grant usage, select on sequence %s to authenticated', v_seq);
        end if;
    end loop;
end
$$;


-- ---------------------------------------------------------------------------
-- 8. Ask the REST API to pick up the new functions right away.
-- ---------------------------------------------------------------------------
notify pgrst, 'reload schema';
