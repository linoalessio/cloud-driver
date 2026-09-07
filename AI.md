# AI.md

This file documents how AI (Claude) was used in this project. It deliberately excludes any aspect where AI wrote or implemented code — only non-implementation work is listed below.

## How AI was used

| # | Aspect | What AI did |
|---|---|---|
| 1 | Javadoc | Re-read and rewrote Javadoc comments across the codebase. |
| 2 | Architecture review | Checked whether the project still follows its documented architecture pattern by inspecting actual module/dependency declarations, rather than assuming the pattern still holds from documentation alone. See finding below. |
| 3 | Repository/module reorganization | Planned and carried out moving and renaming modules, using tracked renames so change history stays attributable rather than appearing as delete-and-recreate. |
| 4 | Reference & documentation updates | Located and updated every reference to changed names/paths across build configuration, CI workflows, and documentation, so the repository stayed internally consistent after a change. |
| 5 | Build & test verification | Verified changes by actually running the project's build and test tooling afterward, rather than assuming a change was correct. |
| 6 | Scope clarification | Asked for explicit confirmation before decisions with wide-reaching or hard-to-reverse consequences, rather than guessing intent. |
| 7 | Anomaly investigation | Investigated unexpected/unexplained files or state encountered while working, and only removed or changed them after explicit confirmation. |

## Architecture review — finding

The project still follows its documented architecture pattern. In particular:

- **Dependency direction holds.** Every module depends only on the modules its layer is documented to depend on - no reverse or skip-layer dependency exists anywhere in the build.
- **The client/server boundary holds.** Every client-side module has zero dependency on any server-side module - a client cannot see the server's database credentials or internals, by construction, not just by convention.
- **The shared design pattern is applied consistently.** Every module that plays the same architectural role follows the same shape (e.g. the same "abstract primitive + generic async wrapper" contract), rather than each one improvising its own.
- **Documented exceptions remain documented exceptions.** Where a module deliberately breaks the general rule, that exception is explicitly called out in the project's own documentation - it reads as a considered decision, not an unnoticed violation.
- **Caveat.** The project has grown large in feature scope. The structural rules above are still mechanically enforced and verifiable, but "simple" is no longer an accurate description of the system as a whole - "consistent" is the more honest claim.
