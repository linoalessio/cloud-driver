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

Documentation lives in exactly two places: the root `README.md` (the map — modules, diagrams,
quick start) and `docs/` (architecture, security, configuration, the API contract, testing,
deployment, this page). Update the relevant page **in the same change as the code it describes**,
not as a separate follow-up — a new route belongs in [api-reference.md](api-reference.md), a new
config key in [configuration.md](configuration.md), a new module in the root README's module
table. Modules deliberately do not carry their own `README.md` files; module-level detail belongs
in source Javadoc.

## Adding a new backend feature module

1. Create a new module under the feature-modules parent, declaring a concrete extension class and
   a small manifest file (name, version, dependencies on other feature modules).
2. Implement only the lifecycle hooks the extension framework requires — registration into the
   running process happens automatically once the module's jar is placed in the extensions folder.
3. Add a row for it to the module table in this repository's root `README.md` and, if it's
   security- or config-relevant, to [security.md](security.md) / [configuration.md](configuration.md).

## Adding a new REST route

1. Add the route and its handler in the REST feature module.
2. Add the client-side call in the shared REST client library, then wire it into whichever app(s)
   need it (desktop, mobile, or both).
3. Add a row to [api-reference.md](api-reference.md).

## Pull requests

- Keep a change scoped to one concern — a bug fix should not also carry an unrelated refactor.
- Call out any known limitation or deliberately deferred follow-up directly in the PR description,
  rather than leaving it to be rediscovered later.
