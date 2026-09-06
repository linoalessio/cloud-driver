# Deployment

## Backend

The backend deploys as a single jar to one server — there is no orchestration platform (Kubernetes,
etc.) involved. A handful of shell scripts (kept local to each operator's machine, not tracked in
version control since they hardcode server-specific connection details) handle the mechanics:

| Script | Runs where | Purpose |
|---|---|---|
| `deploy-cloud.sh` | Locally | Uploads the already-built, shaded bootstrap jar to the server, compressed and checksum-verified |
| `start-cloud.sh` | On the server | Starts the jar in a detached session with an explicit heap size, auto-restarting it if it ever exits |
| `test-bootstrap.sh` | Locally | Assembles a clean throwaway run directory for a manual smoke test |
| `release-and-package.sh` | Locally | One-shot release automation: bumps every version reference, builds, tags, pushes, and cuts a release |

None of these scripts build anything by themselves — always run `mvn clean install` (or the
targeted `-pl ... -am package` form) first.

**A rebuilt feature-module jar must always be redeployed together with a bootstrap jar built from
the same commit.** Feature-module jars resolve shared types off the running bootstrap jar's own
classpath at load time — mixing versions crashes the process at startup, and the auto-restart loop
will simply repeat that crash indefinitely rather than recovering.

## Continuous integration

Two GitHub Actions workflows run automatically; a third handles publishing:

| Workflow | Trigger | Does |
|---|---|---|
| Backend build check | Push / pull request | Runs `mvn package` across the whole reactor |
| Mobile build check | Push / pull request (mobile app changes only) | Builds the mobile app against the iOS Simulator SDK |
| Package publish | GitHub Release creation | Publishes every backend module to this repository's own package registry |

No workflow deploys to a live server automatically — that step is always run by hand via
`deploy-cloud.sh`, on purpose, since pushing to production is a separate decision from cutting a
release.

## Desktop app distribution

Native installers are built per-OS from the same Gradle project (see
[getting-started.md](getting-started.md)) and installed directly into the OS's normal application
location with a desktop shortcut — there is no app-store distribution for the desktop app today.

## Mobile app distribution

Built and signed through Xcode. There is no automated App Store submission pipeline configured —
distribution today is a manual Xcode archive/export step.
