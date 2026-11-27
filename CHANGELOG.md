# Changelog

All notable changes to McBackup. Versions follow `MAJOR.MINOR.PATCH`: a fix raises the last number, a new feature
the middle one, a breaking change the first. Every release raises the version.

## 1.4.0

### Changed (a backup now uses much less CPU; restores work exactly as before)
- **World region files, jars and images are no longer compressed at full effort.** `.mca` files (and `.mcc`, `.jar`, `.zip`, `.gz`,
  `.png`, `.ogg`...) are already compressed inside, so gzip at level 3 spent most of a backup's CPU for almost no gain. They now use
  `performance.precompressed-level` (default 1, the cheapest). Any gzip level reads back the same, so old backups restore normally.
- **The compress step may use only half of one CPU core** (`performance.cpu-limit-percent`, default 50; 100 = no limit). It sleeps
  between slices of work, so a backup takes a little longer but the game keeps its CPU. Only the quick raw copy (while autosave is
  paused) and the upload are not limited.

### Added
- New options `performance.cpu-limit-percent`, `performance.precompressed-level` and `performance.precompressed-extensions`. They are
  added to your `config.yml` automatically; your own values are kept.

## 1.3.0

### Added
- **Error log.** When something goes wrong McBackup writes a readable entry to `plugins/McBackup/logs/errors.log` and a
  short line to the console. Each entry has a number (like `E-1A2`), the time, the plugin version, which part failed
  (scheduled backup, manual backup, autosave, config, notices...) and the full stack trace. Send that one file to get
  a problem diagnosed.
- A failed scheduled backup, a failed `/mcbackup now`, a failed autosave re-enable (`/save-on`), a config problem and
  every warning McBackup logs are all recorded. A crash inside `/mcbackup` shows an error number instead of a stack
  trace.
- `/mcbackup errors` shows the last problems in game (it works even when the backup is not configured yet) and
  `/mcbackup errors test` writes a test entry to check the file works.
- The same problem is written at most 5 times a minute, the file rotates at 2 MB (3 old files are kept), and writing
  happens on its own thread so it cannot slow a backup or the game down.

## 1.2.1

### Fixed
- **The plugin failed to start on Paper** (`NoSuchMethodError: YamlConfiguration.options()` while enabling, shown red in
  `/plugins`). The config updater was compiled against a stand-in class whose `options()` method had a different return
  type than the real Paper class. The stand-ins now have the same signatures as Paper, and a check confirmed that every
  call into Paper in the built jar is identical to a build made against the real Paper API.
- Nothing to change on your side: update the jar and restart. Your `config.yml` and backups are untouched.

## 1.2.0

### Added
- **Safe upgrades and downgrades.** A snapshot of the plugin folder is saved to `data-backups/` on every version
  change (last 5 kept). An older jar that finds data from a newer format stays idle instead of touching it.

## 1.1.0

### Added
- **Automatic config updates.** `config.yml` stores the plugin version in `config-version`. On a new version the
  plugin backs up the old file (`config-backups/`), adds the new options with their comments, keeps all your
  values (including your keys), and raises the version. Do not edit `config-version` by hand.
- `build.sh` takes the version from `plugin.yml`.

### Fixed
- `/mcbackup restore` builds its target folder from the verified snapshot id, never from raw input.

## 1.0.0
- First release: incremental hourly, daily and weekly backups to an S3-compatible bucket, retention, verify and
  restore, a standalone restore tool.
