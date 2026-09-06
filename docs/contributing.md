# Contributing

## Module boundaries

Dependency direction is one-way — see [architecture.md](architecture.md) for the full diagram.
Never introduce a dependency against that direction; if a type feels like it needs to cross the
boundary, it belongs at a lower layer instead.

## Code conventions (backend, Java)

| Convention | Detail |
|---|---|
| Instance-field access | Always qualified as `this.field`, never a bare `field` |
| Null-checking, concrete methods | Lombok's `@NonNull` on parameters (runtime-checked) |
| Null-checking, abstract/interface methods | `@NotNull` (documentation-only annotation, since there's no method body to inject a check into) |
| Hand-written null checks | Go through a shared assertion helper that names both the failing class/method and argument, rather than a bare null-check with a generic message |
| Javadoc | Every public/protected class, method, and field — a short summary sentence, then `@param`/`@return`/`@throws` as applicable |
| Thin pass-through methods | Point at the interface method they implement rather than repeating its documentation |
| Commit style | Prefer new commits over amending; never force-push a shared branch; never skip commit hooks |

## Keeping documentation current

Each module owns its own `README.md` — update it in the same change as the code it describes, not
as a separate follow-up. Cross-cutting concerns (architecture, configuration, the API contract,
deployment) live under `docs/` at the repository root; update the relevant page there too when a
change affects more than one module.

## Adding a new backend feature module

1. Create a new module under the feature-modules parent, declaring a concrete extension class and
   a small manifest file (name, version, dependencies on other feature modules).
2. Implement only the lifecycle hooks the extension framework requires — registration into the
   running process happens automatically once the module's jar is placed in the extensions folder.
3. Add the module's own `README.md`.
4. Add a row for it to the module map in this repository's root `README.md` and, if it's
   security- or config-relevant, to [security.md](security.md) / [configuration.md](configuration.md).

## Adding a new REST route

1. Add the route and its handler in the REST feature module.
2. Add the client-side call in the shared REST client library, then wire it into whichever app(s)
   need it (desktop, mobile, or both).
3. Add a row to [api-reference.md](api-reference.md) and to that module's own README.

## Pull requests

- Keep a change scoped to one concern — a bug fix should not also carry an unrelated refactor.
- Call out any known limitation or deliberately deferred follow-up directly in the PR description,
  rather than leaving it to be rediscovered later.
