# Changelog

All notable changes to McBackup. Versions follow `MAJOR.MINOR.PATCH`: a fix raises the last number, a new feature
the middle one, a breaking change the first. Every release raises the version.

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
