"""cloud-driver-intelligence - semantic search over cloud-driver file content.

A standalone Python microservice that embeds uploaded file content and ranks it by meaning,
complementing (never replacing) ``cloud-driver-extensions-search``'s keyword index and
``cloud-driver-extensions-scan``'s malware scanning.

Two properties define this service and should be understood before changing anything in it:

**It never touches Postgres.** Its only data store is its own vector store. That is what keeps it
from becoming the "second persistence path" to the existing database that the project rules forbid.

**It never decides what a user may see.** It has no current knowledge of ownership or sharing - the
``ownerUserId`` it records at index time is a stale hint, invalidated by any share, revocation or
move that happens afterwards on the Java side. Every search is therefore restricted to a candidate
id set supplied per-request by ``cloud-driver``, resolved there from authoritative data, and every
id this service returns is re-checked there before it reaches a client. With both halves in place,
a compromised or simply out-of-date instance of this service can at worst return *nothing* - never
another account's files. See ``IntelligenceService``'s Javadoc on the Java side for the full
statement of that invariant.
"""

__version__ = "1.0.6"
