# Changelog

All notable changes to McBackup. Versions follow `MAJOR.MINOR.PATCH`: a fix raises the last number, a new feature
the middle one, a breaking change the first. Every release raises the version.

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
