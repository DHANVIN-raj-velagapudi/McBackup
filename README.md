# McBackup

> **Why this exists.** We run a survival server (Arka SMP) and a lost world is the one thing that cannot be
> replaced. We wanted automatic backups that are cheap, tidy and verifiable. So we built McBackup as part of our
> all-in-one server setup, and it works well for us, so we are sharing it with all of you. It is free software (AGPL-3.0).

It is one of three plugins we built together as a suite, and it works fine on **any** Paper server on its own:

| Plugin | What it does |
|---|---|
| [ArkaSMPCore](https://github.com/DHANVIN-raj-velagapudi/ArkaSMPCore) | Homes, spawn, TPA, RTP, warps, claims, AFK, leaderboards, stats, cleaner |
| [ArkaSMPEconomy](https://github.com/DHANVIN-raj-velagapudi/ArkaSMPEconomy) | Rupee money, `/pay`, `/baltop`, `/trade`, item shop, `/sellgui` |
| **McBackup** (this repo) | Incremental hourly/daily/weekly backups to a cloud bucket |

Incremental Minecraft backups straight from a Paper server to a Google Cloud Storage bucket
(or any S3-compatible bucket). Built for this scheme:

- **Hourly:** only files that changed since the last backup are uploaded.
- **Midnight:** one final backup of the day is taken, then that day's hourly backups are deleted.
- **End of week (Sunday 00:30):** one weekly backup is taken, then the week's daily backups are deleted.
  The newest 4 weekly backups are kept.

Nothing is deleted until the new backup has been fully uploaded and committed.

## 1. Set up the bucket (Google Cloud)

1. Create a **private** bucket (or use your existing one).
2. Create a **service account** and give it the role **Storage Object Admin** on *this bucket only*.
3. Cloud Storage -> Settings -> **Interoperability** -> *Create a key for a service account*.
   Copy the **Access key** and **Secret** (shown once).

## 2. Install

1. Put `McBackup-<version>.jar` in the server's `plugins/` folder and start the server once.
2. Edit `plugins/McBackup/config.yml`: `bucket`, `prefix`, `access-key`, `secret-key`.
   (If your host supports environment variables, set `MCBACKUP_ACCESS_KEY` and `MCBACKUP_SECRET_KEY`
   instead; they take priority and keep the secret out of the file.)
3. Run `/mcbackup reload`, then `/mcbackup test`. It uploads, downloads and deletes a tiny file.
   It must say the bucket is OK before you rely on anything else.
4. Run `/mcbackup now hourly` once and watch the console. **The first backup uploads the whole world**
   and takes the longest; do it when few players are online. Autosave is paused while changed files are
   compressed (the console runs `save-all flush`, `save-off`, then `save-on`). Later backups only
   touch changed files and are quick.

Scheduled backups start 2 minutes after the server boots. The first scheduled job on a fresh install is a
"daily" (a full snapshot).

## Backup log

Every backup (and every failure) adds a row to `plugins/McBackup/backup-log.md`: time, type, how much was
uploaded, how many old backups were deleted, how much data the clean-up freed, and the total storage used
afterwards. Open it from the panel's File Manager.

## In-game notices

When a backup starts, staff get a detailed message (type and size of the changed data) and every other online
player gets a general one: "A backup is taking place. You may notice some lag. This is for your safety."
Staff = server OPs plus the names listed under `notify.admins` in `config.yml` (not case-sensitive). The
messages can be edited (`&` colour codes, `{type}` and `{size}` for staff), or turn them off with
`notify.enabled: false`. Only players who are online at that moment see it.

## 3. Commands (permission `mcbackup.admin`, OP by default)

| Command | What it does |
|---|---|
| `/mcbackup test` | Checks the bucket connection and credentials |
| `/mcbackup now [hourly\|daily\|weekly]` | Runs a backup now. `daily`/`weekly` also do their clean-up. |
| `/mcbackup status` | Shows whether a backup is running and the last result |
| `/mcbackup stats` | Backups in the bucket now, **storage used**, and running totals of backups taken / deleted / data uploaded |
| `/mcbackup list` | Lists snapshots (UTC), e.g. `hourly/20261124T053500Z` |
| `/mcbackup verify <id>` | Checks that everything a snapshot needs is still in the bucket |
| `/mcbackup restore <id>` | Downloads a snapshot into `plugins/McBackup/restore/...` (live files are NOT touched) |
| `/mcbackup cancel` | Stops a running backup between files |
| `/mcbackup reload` | Re-reads config.yml |

## 4. Restoring

**With the server running:** `/mcbackup restore <id>`, then stop the server, copy the files you need from
`plugins/McBackup/restore/<folder>` into the server folder (via the panel's file manager), and start it.
Every file is checked against its SHA-256 while it is restored; a corrupt backup file stops the restore.

**Without the server (e.g. after losing everything)**, from any computer with Java 21+:
```
export MCBACKUP_BUCKET=your-bucket MCBACKUP_ACCESS_KEY=... MCBACKUP_SECRET_KEY=... MCBACKUP_PREFIX=minecraft
java -jar McBackup-<version>.jar list
java -jar McBackup-<version>.jar restore daily/20261124T183000Z ./restored-world
```
Note: the plugin's own folder (`plugins/McBackup`, including your keys) is deliberately never backed up,
so you'll need to re-enter the keys after a full restore.

## 5. Config highlights

- `schedule.timezone`: default `Asia/Kolkata`. `hourly-minute`: backup at HH:05.
- `retention.keep-weekly`: how many weekly backups to keep.
- `retention.max-hourly` / `max-daily`: safety caps that only matter if daily/weekly jobs keep failing.
- `backup.exclude`: glob patterns skipped (logs, cache, libraries, versions, session.lock by default).
- `performance`: compression level, upload threads, optional bandwidth cap (`max-upload-mbps`).

## 6. Good to know / limits

- **Granularity is whole files.** If one chunk in a 5 MB region file changes, that region file is uploaded
  again (compressed). Unchanged files cost nothing.
- **Not encrypted by the plugin.** Data is encrypted at rest by Google, but the bucket owner can read it.
  Your backup contains `authme.db` (password hashes) and `config.yml` files with secrets, so keep the
  bucket private and the HMAC key limited to this bucket.
- One server per bucket prefix. Don't run two servers against the same `prefix`.
- Empty folders are not stored (Minecraft recreates them).
- If a scheduled job fails (e.g. network), it retries after `retry-minutes`, and nothing old is deleted.
- Paper only (not Folia).

## 7. Troubleshooting

- `HTTP 403 ... SignatureDoesNotMatch`: wrong access key/secret, or key pasted with spaces. Region should be `auto`.
- `HTTP 403 ... AccessDenied`: the service account lacks Storage Object Admin on the bucket.
- `HTTP 404 ... NoSuchBucket`: bucket name typo.
- Console says the plugin's `api-version` isn't supported: edit `plugin.yml` inside the jar's source and change
  `api-version` to your Paper's version line, then rebuild with `./build.sh`.
- "COULD NOT RE-ENABLE AUTOSAVE": run `/save-on` in the console.

## 8. Building from source

`./build.sh` (needs JDK 21+, no internet). It compiles against tiny stand-in classes for the handful of
Bukkit calls used (`src/stubs`); the stand-ins are NOT packaged, the real classes come from Paper at runtime.
Tests: see `src/test/java` (signing vs AWS examples, retention, schedule, full end-to-end run against a
signature-checking mock bucket, and the plugin commands).

**Tested here:** signing against Amazon's published examples and the official AWS Python client; retention;
schedule; end-to-end backup/daily/weekly/restore against a mock S3. **Not tested here:** a real Paper 26.3
server and the real Google Cloud Storage service (neither is reachable from the build environment).
Do the first run on a copy of your server or at a quiet time, and use `/mcbackup test` first.

## 9. Versions and updating

Every release gets a new version number (`MAJOR.MINOR.PATCH`). The history is in [CHANGELOG.md](CHANGELOG.md), and
the jar name carries the version (`./build.sh` reads it from `plugin.yml`).

**Your `config.yml` updates itself.** It stores the plugin version that last updated it
(`config-version: "1.1.0"`, **please do not change this line**). When you install a newer jar, McBackup saves a backup
of your config in `plugins/McBackup/config-backups/`, adds the options that are new (with comments), keeps every value
you set (including your keys), raises `config-version` and tells you in the console. A config with a YAML mistake is
left exactly as it is.

## 10. Status, contributing and support

McBackup is **young software**. Test it on a copy of your server first, run `/mcbackup test` and `/mcbackup verify`
on real snapshots, and practise a restore **before** you need one. Never rely on a backup you have not restored.

- **Found a bug or want a feature?** Open an [issue](../../issues) with your server version, what you did and the
  console output.
- **Want to improve it?** Fork it and send a pull request. Run the tests (section 8) before you do.
- Please keep the license: if you build on this and run it publicly, your changes must stay open too.

## License

Copyright (C) 2026 Velagapudi Dhanvin Raj

This program is free software: you can redistribute it and/or modify it under the terms of the
**GNU Affero General Public License, version 3** as published by the Free Software Foundation. It is distributed in
the hope that it will be useful, but **without any warranty**. See [LICENSE](LICENSE).
