# cloud-driver-api

This module defines the cloud-driver's public contract: interfaces, value objects/DTOs, exceptions, and the `CloudAPI` singleton. It has no concrete logic of its own — every implementation lives in **cloud-driver-plugin**, which depends on this module (never the other way around). This document explains and shows how to use three parts of that contract together: the **`security`** package, the **`database`** package, and **`CloudAPI`**, the facade that ties them into one entry point.

The design follows `security_requirements.txt` (bundled as a resource in this module) — envelope encryption with AES-256-GCM, KMS/HSM-backed key wrapping with rotation, authenticated-tag verification, Argon2id password hashing, and encryption-at-rest for stored data. Section references below (e.g. "section 9") point back to that document.

## Module layout

| Module | Contains |
|---|---|
| `cloud-driver-api` | Interfaces (`AeadEncryptionService`, `KeyEncryptionService`, `PasswordHasher`), value objects/records (`EncryptedPayload`, `WrappedKey`, `EnvelopeEncryptedPayload`, `EncryptedEntityRecord`), exceptions (`KeyWrapException`, `AuthenticationFailedException`, `DatabaseClientException`), and the abstract `CloudAPI`. |
| `cloud-driver-plugin` | Every concrete implementation: `AesGcmEncryptionService`, `InMemoryKeyEncryptionService`, `EnvelopeEncryptionService`, `SecureEntityChannel`, `Hasher`, `Argon2idPasswordHasher`, `SecretRedactor`, `EntityDatabaseClient`, `DefaultDataFactory`, `DefaultExtensionFactory`, and `DefaultCloudAPI`. |

A consuming extension depends on both, plus `database-driver-plugin` (for a concrete `DatabaseProvider`, e.g. the JSON file store or H2) and `bcprov-jdk18on` (Argon2id):

```xml
<dependency>
    <groupId>de.lino.cloud.api</groupId>
    <artifactId>cloud-driver-api</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>de.lino.cloud.plugin</groupId>
    <artifactId>cloud-driver-plugin</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## Quick start

`CloudAPI` is the single entry point: it envelope-encrypts a `de.lino.database.database.entity.Serialized` domain entity and stores it in a database, or reverses that on retrieval. Nothing but ciphertext ever reaches the database.

```java
// 1. Define an entity - any subclass of the database-driver-api base class works.
final class CustomerRecord extends Serialized {
    private final int id;
    private final String iban;

    CustomerRecord(int id, String iban) {
        this.id = id;
        this.iban = iban;
    }

    @Override
    public List<String> keysOf() {
        return List.of(String.valueOf(id)); // first element = primary key
    }

    @Override
    public String toString() { return "CustomerRecord{id=" + id + "}"; }
    // equals()/hashCode() as usual
}

// 2. Wire up a database (any database-driver-plugin DatabaseProvider - JSON file
//    store, H2, MySQL, PostgreSQL, MongoDB, ...). The JSON provider needs no
//    external server, so it is the easiest one to start with:
new DefaultFileProvider();
new DatabaseRepositoryRegistry(true);

Credentials credentials = new Credentials(
        Path.of("db/credentials.json"), Path.of("db/data")
);
DatabaseProvider provider = DatabaseRepository.getInstance()
        .registerDatabaseProvider(0, DatabaseType.JSON, credentials);
DatabaseSection customers = provider.createSection("customers");

// 3. Wire up envelope encryption (see the `security` section below for what
//    each piece does, and for a real KMS/HSM instead of the in-memory stand-in).
KeyEncryptionService kms = new InMemoryKeyEncryptionService();
EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(kms);

// 4. Initialize CloudAPI. This also installs the singleton returned by
//    CloudAPI.getInstance().
CloudAPI cloudAPI = DefaultCloudAPI.initialize(customers, envelopeEncryptionService);

// 5. Use it.
CustomerRecord customer = new CustomerRecord(42, "DE89370400440532013000");
cloudAPI.send(customer);                                     // encrypt + store
CustomerRecord recovered = cloudAPI.receive("42", CustomerRecord.class); // decrypt + return
```

A complete, runnable version of this (including every security-package feature below) lives at `cloud-driver-core/src/test/java/de/lino/cloud/core/sample/CloudAPIUsageSample.java`.

## The `security` package

Six sub-packages, each a thin layer around the previous one. Extension code normally only touches `CloudAPI`/`EntityDatabaseClient` directly (below) — this section is for understanding what happens underneath, or for using a piece standalone.

### `security.crypto` — authenticated encryption

`AeadEncryptionService` is the interface; `AesGcmEncryptionService` is its AES-256-GCM implementation. A fresh, random nonce is drawn for every call and never reused with the same key.

```java
AeadEncryptionService aead = new AesGcmEncryptionService(); // defaults to AES-256-GCM
SecretKey key = ...; // e.g. from a DataEncryptionKey, see below

EncryptedPayload payload = aead.encrypt(plaintext, key, associatedData); // associatedData may be null
byte[] recovered = aead.decrypt(payload, key); // throws AuthenticationFailedException if the tag doesn't verify
```

`EncryptedPayload` carries `algorithmId`, `nonce`, `ciphertext`, and `associatedData` - all defensively copied, so callers can't mutate shared state after the fact.

### `security.keys` — data-encryption and key-encryption keys

- `DataEncryptionKey` (DEK): a short-lived, random AES key protecting a single payload. Call `destroy()` once you're done with it to zero the raw material.
- `KeyEncryptionService` (KEK/KMS abstraction): wraps/unwraps DEKs, and supports rotation.
- `InMemoryKeyEncryptionService`: the only concrete `KeyEncryptionService` shipped here - **development/test only**. Swap in a real KMS/HSM client for production.

```java
DataEncryptionKeyGenerator dekGenerator = new DataEncryptionKeyGenerator();
DataEncryptionKey dek = dekGenerator.generate(); // AES-256 by default

KeyEncryptionService kms = new InMemoryKeyEncryptionService();
WrappedKey wrapped = kms.wrap(dek);              // encrypt the DEK under the active KEK
DataEncryptionKey unwrapped = kms.unwrap(wrapped); // decrypt it back, by whichever KEK version wrapped it

String previousKeyId = kms.activeKeyEncryptionKeyId();
String newKeyId = kms.rotate(); // activates a new KEK; data wrapped under the old one still unwraps
```

### `security.envelope` — tying crypto + keys together

`EnvelopeEncryptionService` is the facade most other code (`SecureEntityChannel`, `EntityDatabaseClient`) builds on: generate a DEK → encrypt with it → wrap the DEK with the active KEK → return both together as an `EnvelopeEncryptedPayload`.

```java
EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(kms); // AES-256-GCM by default

EnvelopeEncryptedPayload envelope = envelopeEncryptionService.encrypt(plaintext, associatedData);
byte[] recovered = envelopeEncryptionService.decrypt(envelope); // unwraps the DEK, then verifies + decrypts
```

### `security.hash` — general-purpose hashing

`HashAlgorithm` only offers SHA-256/384/512 - MD5 and SHA-1 are not representable, by design.

```java
byte[] digest = Hasher.digest(HashAlgorithm.SHA_256, data);
String hex = Hasher.hexDigest(HashAlgorithm.SHA_256, data);
```

### `security.password` — Argon2id

Only relevant if the extension itself must store a password (prefer OAuth 2.0 client credentials or mTLS for service-to-service auth instead).

```java
PasswordHasher passwordHasher = new Argon2idPasswordHasher(); // OWASP-baseline defaults
String encoded = passwordHasher.hash("correct horse battery staple".toCharArray());
boolean valid = passwordHasher.verify("correct horse battery staple".toCharArray(), encoded);
```

### `security.secrets` — redaction

A defense-in-depth safety net for text about to be logged or surfaced in an error - not a substitute for not logging secrets in the first place.

```java
String logLine = "Authorization: Bearer abc123... processing request for customer 42";
SecretRedactor.redact(logLine); // "Authorization: [REDACTED] processing request for customer 42"
```

### `security.entity` — `SecureEntityChannel`

Bridges `EnvelopeEncryptionService` and any `Serialized` domain entity: serializes the entity (via its own `toByteArray()`), encrypts it, and binds the entity's type name + primary key into the authenticated associated data so a payload can't be silently swapped for a different entity or record.

```java
SecureEntityChannel channel = new SecureEntityChannel(envelopeEncryptionService);

EnvelopeEncryptedPayload envelope = channel.send(customer);
CustomerRecord recovered = channel.receive(envelope, CustomerRecord.class); // rejects a type/record mismatch
```

This is what `EntityDatabaseClient` uses internally - most extensions never call it directly.

## The `database` package

### `EncryptedEntityRecord` / `DatabaseClientException` (api)

`EncryptedEntityRecord` is the on-disk/on-wire JSON shape of an `EnvelopeEncryptedPayload`: every binary field base64-encoded. `EntityDatabaseClient` stores one of these under the `"data"` key of a `database-driver-api` `DatabaseEntry` - the database never sees anything else.

`DatabaseClientException` signals a failed persistence operation (not found, id collision, corrupted record).

### `EntityDatabaseClient` (core)

The class that actually talks to the database. `CloudAPI`/`DefaultCloudAPI` is a thin pass-through to it.

```java
EntityDatabaseClient client = new EntityDatabaseClient(customers, envelopeEncryptionService);

client.store(customer);                                    // insert, or update if the id already exists
CustomerRecord recovered = client.retrieve("42", CustomerRecord.class);

client.storeAll(List.of(customerA, customerB, customerC));  // concurrent batch write
List<CustomerRecord> many = client.retrieveAll(
        List.of("1", "2", "3"), CustomerRecord.class);       // concurrent batch read, order preserved
```

Three things worth knowing about how it behaves:

- **Concurrency.** `store()` always tries `insert()` first and only falls back to `update()` on collision - never a `exists()`-then-branch check, which would leave a race window under concurrent writes to the same id. `storeAll`/`retrieveAll` dispatch each entity/id as its own task on `MultiTaskingFactory`'s shared virtual-thread executor rather than looping sequentially, which is what actually matters for large ("big data") batches, since the dominant cost per item is waiting on the database/KMS, not CPU.
- **Caching.** Each entity type gets its own read-through, write-through cache (backed by `database-driver-api`'s `Cache`, TTL/size-bounded), created lazily. `store()` populates it immediately with the plaintext already in hand; `retrieve()` checks it before touching the database. Because it holds decrypted plaintext in memory, it is deliberately short-lived and bounded by default (30s / 1000 entries per type) - tune or widen via the second constructor if that trade-off doesn't fit a given deployment:

  ```java
  new EntityDatabaseClient(customers, envelopeEncryptionService, Duration.ofMinutes(5), 10_000);
  ```

- **Failure semantics.** `storeAll`/`retrieveAll` throw the first failure encountered once every item in the batch has been attempted - unlike a sequential loop, a failure doesn't stop the rest of the batch from running, since they're already dispatched concurrently by the time any one of them fails.

## `CloudAPI`

The abstract facade (`cloud-driver-api`) plus its concrete implementation (`DefaultCloudAPI`, `cloud-driver-core`).

```java
CloudAPI cloudAPI = DefaultCloudAPI.initialize(databaseSection, envelopeEncryptionService);

// Single entity
cloudAPI.send(customer);
CustomerRecord one = cloudAPI.receive("42", CustomerRecord.class);

// Batch
cloudAPI.send(customerA, customerB, customerC);
List<CustomerRecord> many = cloudAPI.receive(new String[]{"1", "2", "3"}, CustomerRecord.class);

// Async (CompletableFuture, backed by MultiTaskingFactory's virtual-thread executor)
cloudAPI.sendAsync(customer).get();
CompletableFuture<CustomerRecord> future = cloudAPI.receiveAsync("42", CustomerRecord.class);

// Access the singleton from anywhere once initialized
CloudAPI.getInstance().receive("42", CustomerRecord.class);
```

`sendAsync`/`receiveAsync` need no override in `DefaultCloudAPI` - they're implemented once, generically, directly on `CloudAPI` in terms of the abstract sync methods. On failure, the returned future completes exceptionally with a `CompletionException` wrapping whatever checked exception the synchronous call would have thrown (`DatabaseClientException`, `KeyWrapException`, or `AuthenticationFailedException`) - the standard idiom for surfacing checked exceptions through `CompletableFuture`.

| Method | Throws |
|---|---|
| `send(T entity)` | `DatabaseClientException`, `KeyWrapException` |
| `send(T... entities)` | `DatabaseClientException`, `KeyWrapException` |
| `receive(String objectId, Class<T> type)` | `DatabaseClientException`, `KeyWrapException`, `AuthenticationFailedException` |
| `receive(String[] objectIds, Class<T> type)` | same, plus preserves request order |
| `sendAsync`/`receiveAsync` variants | never throw synchronously; failures surface via the returned `CompletableFuture` |
