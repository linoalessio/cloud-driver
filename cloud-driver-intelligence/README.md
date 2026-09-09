# cloud-driver-intelligence

Semantic search over `cloud-driver` file content: a standalone Python service that embeds each
uploaded file and ranks files by *meaning* rather than by literal text, so a query like
`Rechnung Autowerkstatt` can surface a file named `scan_0042.pdf`.

Complements, never replaces, `cloud-driver-extensions-search` (keyword matching) and
`cloud-driver-extensions-scan` (malware scanning). All three are independently optional.

This is one half of the feature. The other half is `cloud-driver-extensions-intelligence`, the Java
bridge inside `cloud-driver` that feeds this service and — critically — is the only component
permitted to decide who may see what.

## Two properties that define this service

**It never touches Postgres.** Its only data store is its own vector store. That is what keeps it
from becoming a second persistence path to the existing database.

**It never decides what a user may see.** See the security model below before changing `/search`.

## The security model

This service has no current knowledge of ownership or sharing. The `ownerUserId` it records at
index time is a *stale hint*, invalidated by any share, revocation, move or deletion that happens
afterwards on the Java side. It is recorded for maintenance purposes and is deliberately never used
to filter a search.

Every search is therefore two-staged:

1. **Pre-filter (Java).** `cloud-driver` resolves the complete set of file ids the searching account
   currently has access to, from authoritative ownership and sharing data, and passes exactly that
   set as `candidateFileIds`.
2. **Post-check (Java).** Every id this service returns is re-checked against that same
   authoritative access check before it can reach a client — treated as untrusted input, never as a
   result the pre-filter already vouched for.

With both halves in place, a compromised, buggy or simply out-of-date instance of this service can
at worst return **nothing** — never another account's files.

On this side, the candidate restriction is **structural rather than a filter**: `/search` loads only
the vectors fetched *by* the candidate ids, so there is nothing else in scope to rank or return. A
future change that ran a broad nearest-neighbour query and narrowed it afterwards would downgrade
that guarantee into a filter that can be got wrong. Don't.

## What is stored, and the trade-off worth knowing

The vector store holds embeddings derived from real file content, **unencrypted at rest**. Every
other persistence path in `cloud-driver` is envelope-encrypted; this one is not, and embeddings are
partially invertible — approximate source text can be reconstructed from them.

That is a deliberate trade-off of this design, not an oversight. Concretely it means:

- The store directory deserves the same filesystem protection as the database itself.
- It should not be backed up to somewhere the encrypted database would not be.
- `intelligence-max-bytes` (Java side) is the effective ceiling on how much of a file can end up
  represented in it.

## Endpoints

| Method   | Path                | Auth   | Purpose |
|----------|---------------------|--------|---------|
| `GET`    | `/health`           | none   | Liveness, plus whether the embedding backend and persistent store actually loaded |
| `POST`   | `/index`            | secret | Embed one file and store its vector (replaces any existing vector for that id) |
| `DELETE` | `/index/{file_id}`  | secret | Remove one file's vector (idempotent on absence) |
| `POST`   | `/search`           | secret | Rank `candidateFileIds` against `queryText` |

Authenticated endpoints require the shared secret in an `X-Internal-Secret` header. `/health` is
exempt: the Java bridge probes it at startup purely to log reachability, and it reveals nothing an
operator with network access to the port could not already infer from the port being open.

**The shared secret is a second line of defence, never the first.** Bind this service to loopback or
an internal container network only — exactly as `clamd` and Redis already are in this deployment.
The header exists to stop another process on the same host from reading or poisoning the vector
store; it does not make the service safe to expose publicly.

## Install and run

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[embeddings,store,dev]"

export CLOUD_DRIVER_INTELLIGENCE_SECRET="$(openssl rand -base64 32)"
uvicorn cloud_driver_intelligence.app:app --host 127.0.0.1 --port 8600
```

The same secret must be set as `intelligence-shared-secret` in `cloud-driver`'s own
`configuration.json`.

### Optional extras, and what happens without them

Both heavy dependencies are optional so the service can be installed, started and tested without
pulling in PyTorch (~2 GB). Without them it stays up and degrades rather than failing:

| Extra        | Provides                          | Without it |
|--------------|-----------------------------------|------------|
| `embeddings` | `sentence-transformers`            | `/index` and `/search` succeed but do nothing; searches return no results |
| `store`      | `chromadb` (persistent vectors)    | Falls back to an in-memory store — functionally complete, lost on restart |

`GET /health` reports both directly (`embeddingsAvailable`, `persistentStore`), so a half-configured
deployment is visible immediately rather than inferred from silently empty search results.

## Configuration

All environment variables, read once at import.

| Variable | Default | Purpose |
|---|---|---|
| `CLOUD_DRIVER_INTELLIGENCE_SECRET` | *(none)* | Shared secret. **No default by design** — unset means every authenticated request is rejected with `503`, because a service authenticating with a well-known constant is worse than one that refuses to start. |
| `CLOUD_DRIVER_INTELLIGENCE_STORE_PATH` | `./chroma` | Where Chroma persists. |
| `CLOUD_DRIVER_INTELLIGENCE_MODEL` | `all-MiniLM-L6-v2` | sentence-transformers model id. |
| `CLOUD_DRIVER_INTELLIGENCE_MAX_CHARS` | `20000` | Cap on decoded text embedded per file. |

Settings are environment-driven rather than read from `cloud-driver`'s `configuration.json`
deliberately: this is a separate process with a separate lifecycle, possibly on a separate host.
The shared secret is the one value that must match on both sides.

## What is embedded

The file name is **always** embedded, even when full text is available — a name is frequently the
most human-meaningful signal a file has, and for the many formats nothing can currently be extracted
from (PDF, images, archives, office documents) it is the only signal at all. That name-only fallback
is what makes this service useful without OCR or per-format extractors, and is why an unextractable
file is still indexed rather than skipped.

Text content is additionally embedded for `text/*` plus `application/json` / `xml` / `yaml` / `toml`
— deliberately the same set the Java side's keyword indexing uses, so both search kinds agree on
what "has text".

Deciding what is embeddable lives entirely here, never in Java. Adding PDF text extraction, OCR or a
multimodal CLIP model is a change to `embeddings.py` alone: no Java rebuild, no wire-format change.

## Tests

```bash
pytest
```

No network, no model download, no Chroma: a deterministic stub embedding model stands in for the
real one, so the tests exercise this service's own logic — auth, decoding, the candidate
restriction, ranking, idempotency, and degradation when the optional extras are absent.

The candidate restriction has its own dedicated tests, including one that indexes a document owned
by a *different* account and asserts it stays invisible when not offered as a candidate.

### If `pytest` reports `ModuleNotFoundError: No module named 'cloud_driver_intelligence'`

Delete `.venv` and reinstall. A `pip install -e` that fails part-way — the most likely cause being a
missing `README.md`, which hatchling validates *after* it has already written the editable install's
`.pth` and `dist-info` — leaves those two behind in a state a subsequent successful install does not
repair, so the package is registered but never actually lands on `sys.path`. The `.pth` file looks
entirely correct when inspected, which makes this misleading to debug. A clean venv fixes it, and CI
never hits it because every run installs fresh.

## Deployment

Run as its own process, started and stopped independently of `cloud-driver-bootstrap` — the same
operational shape as `clamd` and Redis, which on this deployment are both real systemd services.

`deploy/` holds everything needed:

```bash
./deploy/install-on-server.sh
```

Idempotent — safe to re-run to ship updated source. It uploads the source, builds a venv with the
`embeddings` and `store` extras, writes the shared secret (read from the local, gitignored
`cloud-driver/configuration.json`, so the two halves cannot drift apart) to a root-owned `0600` env
file, installs `deploy/cloud-driver-intelligence.service`, starts it, and waits for `/health`.

The vector store and the downloaded model live in `/var/lib/cloud-driver-intelligence`
(systemd `StateDirectory`) and are never touched by a re-install, so re-running does not lose the
index.

The service runs as a `DynamicUser` rather than root — nothing here needs privilege, and the store
holds embeddings derived from real file content. Note this differs from the JVM beside it, which
does run as root on this box.

**The Python half does nothing on its own.** Semantic search only works once the Java side is
deployed too:

```bash
mvn clean install            # builds cloud-driver-extensions-intelligence
./shell/deploy-cloud.sh      # bootstrap jar + ALL extension jars + configuration.json
ssh strato 'screen -S cloud_driver -X quit'
ssh strato 'cd /home/cloud && ./start-cloud.sh'
```

That last pair restarts the JVM, which is a brief interruption of the whole API — the extension jar
and the bootstrap jar must always be deployed together from the same commit, or the process crashes
at startup on a `NoClassDefFoundError` and `start-cloud.sh`'s restart loop repeats it indefinitely.

Useful afterwards:

```bash
ssh strato 'systemctl status cloud-driver-intelligence'
ssh strato 'journalctl -u cloud-driver-intelligence -n 50 --no-pager'
ssh strato 'curl -s http://127.0.0.1:8600/health'
```

`cloud-driver` does **not** require this service to be up in order to boot.
 If it is unreachable the Java bridge retries an
index call three times (3s/15s), then gives up and leaves that file un-indexed. Nothing about
access, quota or integrity depends on it; the only cost is that the file is not semantically
findable until it is next re-uploaded or its content replaced.

There is no re-index-everything command yet — see "Known gaps" below.

## Known gaps

- **No backfill.** Files uploaded before this service was first started are not indexed. Only new
  uploads, content replacements and restores are. A terminal command in the mould of
  `CloudSearchExtension`'s own startup backfill would close this.
- **Text only.** No PDF text extraction, no OCR, no image (CLIP) embeddings. A PDF or photo is
  currently embedded by file name alone.
- **No share-revocation hook.** A revoked share leaves its vector in place. Harmless — the pre-filter
  would never offer that id and the post-check would reject it anyway — but the entry lingers until
  the file itself is deleted.
- **Single collection, no sharding.** Fine at this deployment's scale; every candidate lookup is by
  id, so cost scales with the candidate set rather than the store.
