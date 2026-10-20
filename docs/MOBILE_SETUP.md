# Mic Tracker on your phone: setup guide

This guide gets the native **iOS** and **Android** apps running against your
existing Supabase database, and turns on the **nightly scraper** that feeds
the apps' "Import from Scrapers" screen.

Work through the steps in order. Steps 1–5 happen once in the Supabase
dashboard (about 10 minutes). After that you can do iOS (step 6), Android
(step 7), or both. Step 8 turns on the nightly scraper.

> Supabase moves its menus around now and then. If a label below doesn't
> match exactly, look for the closest one in the same area.

**Your Streamlit web app keeps working exactly as before.** It connects to
the database as the owner of the tables, and the new security rules don't
apply to the owner.

---

## 1. Why you need step 2 first: the database is currently unlocked

Supabase protects tables with **Row Level Security (RLS)**: rules that say
who may read or change which rows. RLS is currently **off** on all four
tables (`open_mics`, `my_sets`, `mic_plans`, `scrape_log`).

That didn't matter while only the Streamlit app used the database, because
it logs in with your private database password. The phone apps can't do
that. They use the project's public **anon key**, which ends up inside every
app you build, so you should treat it as public. With RLS off, **anyone who
has the anon key can read, change or delete all of your data**.

The migration in step 2 fixes that:

- Everyone can **read** the mic list (it's public info anyway).
- Only **your login** can add, edit or delete mics, and only your login can
  see or change your sets, plans, scrape log and scraper queue.
- It also adds the functions the apps call for things like the Going /
  Cancelled buttons and importing scraped mics.

---

## 2. Apply the security migration

1. Open your project in the [Supabase dashboard](https://supabase.com/dashboard).
2. In the left sidebar, click **SQL Editor**, then **New query** (or the **+** button).
3. In this repo, open
   [`supabase/migrations/20261020000000_mobile_api.sql`](../supabase/migrations/20261020000000_mobile_api.sql),
   select everything, and copy it.
4. Paste it into the SQL editor and click **Run**.
   - Supabase may warn about a *"destructive operation"*. That's because the
     file drops and re-creates its own security rules (policies) so it can
     be run more than once. It doesn't delete any data. Confirm and run it.
5. You should see **Success. No rows returned**.

It's safe to run this file again later, for example if you're not sure it
worked.

**Optional check:** run this in a new query. Every row should say `true`:

```sql
select tablename, rowsecurity
from pg_tables
where schemaname = 'public'
order by tablename;
```

---

## 3. Create your login and make it an admin

The apps have no sign-up screen. You create your one account here.

1. In the left sidebar, go to **Authentication → Users**.
2. Click **Add user → Create new user**.
3. Enter your email and a password you'll remember, and **tick "Auto Confirm User"**.
   (Without it, Supabase waits for you to click a confirmation email first.)
4. Click **Create user**.
5. Go back to the **SQL Editor**, open a new query, paste the following,
   and **replace `YOUR_EMAIL`** with the email you just used (keep the quotes):

   ```sql
   insert into app_admins (user_id)
   select id from auth.users where email = 'YOUR_EMAIL';
   ```

6. Click **Run**. It should report **1 row** affected. If it says 0 rows,
   the email doesn't match exactly. Check it with
   `select email from auth.users;` and try again.

Only users in `app_admins` can change data. Anyone else who signs in sees the
app's **"Account not authorized"** screen, which shows the same SQL.

---

## 4. (Optional) Turn off public sign-ups

Strangers who sign up can't change anything because they aren't admins, but
you can stop them creating accounts at all:

1. Go to **Authentication → Sign In / Providers** (on older dashboards:
   **Authentication → Providers → Email**, or **Authentication → Settings**).
2. Turn **off** "Allow new users to sign up".
3. Click **Save**.

You can still add users yourself as in step 3.

---

## 5. Find your project URL and anon key

Both apps need two values:

| Value | What it looks like |
|---|---|
| **Project URL** | `https://<project-ref>.supabase.co` |
| **anon key** | a long string starting with `eyJ...` |

Where to find them: **Project Settings** (gear icon at the bottom of the
sidebar) **→ API**. On newer dashboards they're split across two pages:

- **Project Settings → Data API**: the **Project URL**.
- **Project Settings → API Keys**: the **`anon` `public`** key. If you only
  see new-style keys, open the **Legacy API Keys** tab and copy **anon public**.
  The new **publishable** key (`sb_publishable_...`) should also work, but the
  `anon` key is the safest choice.

> **Never** put the `service_role` / secret key or your database password in
> the apps. Those keys skip all the security rules from step 2.

---

## 6. Run the iOS app

You need a Mac with **Xcode** (from the Mac App Store; open it once after
installing).

**Quick way — one command.** In Terminal, in your copy of this repo:

```bash
bash ios/setup.sh
```

It installs XcodeGen if needed, asks you to paste your anon key, writes
`ios/Config/Secrets.xcconfig` (with a bundle ID unique to you, which a free
Apple ID needs), generates the project and opens Xcode. Then continue at
**step 5** below. The manual steps 1–4 are what the script does for you.

1. Install XcodeGen. This tool generates the Xcode project from
   `ios/project.yml`.

   ```bash
   brew install xcodegen
   ```

2. In Terminal, go to your copy of this repo and create your secrets file
   from the template:

   ```bash
   cp ios/Config/Secrets.example.xcconfig ios/Config/Secrets.xcconfig
   ```

3. Open `ios/Config/Secrets.xcconfig` in any text editor and fill in your
   values from step 5:

   ```
   SUPABASE_URL = https:/$()/YOUR-PROJECT-REF.supabase.co
   SUPABASE_ANON_KEY = eyJ...your anon key...
   ```

   **Yes, type `https:/$()/` exactly like that.** In `.xcconfig` files, `//`
   starts a comment, so a plain `https://...` would get cut off after
   `https:`. The `$()` is an empty placeholder that keeps the two slashes
   apart. The app still receives a normal `https://` address. Don't add
   quotes.

4. Generate the Xcode project and open it:

   ```bash
   cd ios
   xcodegen
   open MicTracker.xcodeproj
   ```

5. In Xcode, click the **MicTracker** project in the left sidebar, select
   the **MicTracker** target, open the **Signing & Capabilities** tab, and
   pick your **Team**.
   - No team listed? Go to **Xcode → Settings → Accounts**, click **+**, and
     sign in with your Apple ID. A free "Personal Team" is enough to run the
     app on your own phone.
   - If Xcode says the bundle identifier isn't available, change it to
     something unique such as `com.yourname.mictracker`.

6. Choose a simulator (for example "iPhone 15") or your plugged-in iPhone
   from the device menu at the top, then press **⌘R** (Run).
   - Running on a real iPhone for the first time: on the phone, enable
     **Settings → Privacy & Security → Developer Mode**, and trust your
     developer certificate under **Settings → General → VPN & Device
     Management**.

7. Sign in with the email and password from step 3.

Good to know:

- `Secrets.xcconfig` is ignored by git, so your key stays on your computer.
  `MicTracker.xcodeproj` is generated and not committed. Re-run `xcodegen`
  whenever you pull changes that add or remove files.
- Seeing the **"Not configured"** screen? The URL or key didn't make it into
  the build. Check step 3 above, then **Product → Clean Build Folder**
  (⇧⌘K) and run again.

---

## 7. Run the Android app

You need [Android Studio](https://developer.android.com/studio) (latest
stable version).

1. Create your secrets file from the template:

   ```bash
   cp android/secrets.example.properties android/secrets.properties
   ```

2. Open `android/secrets.properties` and fill in your values from step 5.
   Plain `https://` is fine here because the `.xcconfig` problem doesn't
   apply to this file:

   ```
   SUPABASE_URL=https://YOUR-PROJECT-REF.supabase.co
   SUPABASE_ANON_KEY=eyJ...your anon key...
   ```

3. Start Android Studio → **Open** → select the **`android`** folder inside
   this repo (not the repo itself). Wait for the Gradle sync to finish
   (the first time it downloads a lot and can take several minutes).
4. Pick a device:
   - **Emulator:** **Device Manager** (phone icon on the right) → **+** →
     choose a phone → finish the wizard.
   - **Your phone:** enable Developer options and USB debugging
     (Settings → About phone → tap **Build number** 7 times, then
     Settings → System → Developer options → **USB debugging**) and plug it in.
5. Make sure **app** is selected in the toolbar and press the green **Run ▶**
   button.
6. Sign in with the email and password from step 3.

`secrets.properties` is ignored by git. If you change it, just run again.

---

## 8. Turn on the nightly scraper (GitHub Actions)

The workflow `.github/workflows/scrape.yml` runs
`scripts/run_scrapers.py` every day at 14:07 UTC (10:07 AM New York time in
summer, 9:07 AM in winter). It scrapes Bad Slava and FireMics, queues mics
you don't have yet for review, logs each run, and fills in missing map
coordinates. In the app you review the queue under **Manage → Import from
Scrapers**.

The job needs your database login. Use **the same values as the
`[database]` section of your `.streamlit/secrets.toml`**.

1. On GitHub, open this repository → **Settings → Secrets and variables →
   Actions → New repository secret**.
2. Add these five secrets one at a time. The name goes on the left and the
   value from `secrets.toml` goes on the right:

   | Secret name | Value from `.streamlit/secrets.toml` |
   |---|---|
   | `SUPABASE_DB_HOST` | `host` |
   | `SUPABASE_DB_PORT` | `port` (usually `5432`) |
   | `SUPABASE_DB_NAME` | `dbname` (usually `postgres`) |
   | `SUPABASE_DB_USER` | `user` |
   | `SUPABASE_DB_PASSWORD` | `password` |

3. Try it: open the repo's **Actions** tab → **Nightly scrape** → **Run
   workflow**. Tick **Dry run** the first time. That run scrapes and prints
   what it *would* queue without writing anything. Then run it again without
   Dry run.
4. Open the app → **Manage → Import from Scrapers** to review the results,
   and **Manage → Scrape History** to see each run.

Good to know:

- Until `SUPABASE_DB_HOST` is set, the workflow skips itself and shows a
  notice instead of failing.
- **"Network is unreachable" or "could not translate host name" in the
  log?** Supabase's *direct* connection host (`db.<project-ref>.supabase.co`)
  only works over IPv6, which GitHub's machines don't have. In the Supabase
  dashboard click **Connect** (top of the page) → **Session pooler**, and
  use that host (`aws-0-<region>.pooler.supabase.com`), port `5432`, and user
  (`postgres.<project-ref>`) in the GitHub secrets instead.
- GitHub pauses scheduled workflows in repositories with no activity for
  60 days. If the scrape stops, re-enable it from the **Actions** tab.
- You can run the same job on your own computer from the repo root:
  `python scripts/run_scrapers.py --dry-run`. It uses your
  `.streamlit/secrets.toml` when one is present.

---

## Troubleshooting

| What you see | What to do |
|---|---|
| **"Account not authorized"** in the app | Step 3's SQL hasn't been run for this email. Run it (with the exact email shown on that screen), then tap **Retry**. |
| **"Not configured"** screen | The secrets file is missing or empty. Redo step 6.2–6.3 (iOS) or 7.1–7.2 (Android), then rebuild. |
| **"Invalid login credentials"** | Wrong email or password. Reset the password under Authentication → Users → (your user) → ⋯. |
| **"Email not confirmed"** | You missed "Auto Confirm User". Delete the user, create it again with the box ticked, then **re-run step 3's SQL** (the new user has a new id). |
| **"Invalid API key"** | The anon key was copied wrong. Copy it again, with no quotes or spaces at either end. |
| The **Streamlit app** shows no data after step 2 | It's logging in as a user other than the table owner. Make sure `user` in `.streamlit/secrets.toml` is `postgres` (or `postgres.<project-ref>` for the pooler). |
