# AUTH_IMPLEMENTATION.md

## Ziel

Aktuell exponiert `DefaultRestFactory` (Javalin) optional einen statischen `X-API-Key`
(`ApiKey`-Entity) — geeignet für Server-zu-Server-Zugriffe, aber **nicht** für die drei
Endnutzer-Clients (iOS, Web, macOS). Ein Client soll sich mit **Username + Passwort**
einloggen und dafür ein zeitlich begrenztes **JWT** bekommen, das er danach bei jedem
Request im `Authorization: Bearer <token>`-Header mitschickt.

**Die Postgres-Credentials verlassen den Server dabei nie.** Login läuft ausschließlich
gegen eine eigene `User`-Entity (envelope-verschlüsselt über `DataFactory`, wie jede
andere Entity in diesem Codebase), nicht gegen die Datenbank direkt.

Diese Datei beschreibt **jede Datei, die neu angelegt oder geändert werden muss**,
vollständig und in der Reihenfolge der Umsetzung. Halte dich an die bestehenden
Konventionen dieses Codebase (siehe unten "Konventionen, die einzuhalten sind").

---

## Konventionen, die einzuhalten sind

- Entities sind `Serialized`-Subklassen, envelope-verschlüsselt, gehen ausschließlich
  über `DataFactory` (`register`/`fetch`/`findById`/...), nie über einen zweiten
  Persistenzpfad.
- `@NonNull`/`@NotNull` an Konstruktor- und öffentlichen Methodenparametern, wie in
  `ApiKey`, `DefaultRestFactory`.
- Passwort-Hashing läuft ausschließlich über `PasswordHasher`
  (`Argon2idPasswordHasher`), nie ein selbstgebautes Hashing.
- Zeitkritische Vergleiche (Passwort-Hash, API-Key) über `MessageDigest.isEqual`, nicht
  `String.equals`.
- Kein Secret (JWT-Signing-Key, DB-Credentials) hardcoded — ausschließlich über
  Umgebungsvariablen.
- `cloud-driver-api` enthält nur Interfaces/Contracts (keine Third-Party-Abhängigkeiten
  außer den bereits vorhandenen). Konkrete Implementierungen (Javalin, jjwt) leben in
  `cloud-driver-plugin`.

---

## Übersicht der Änderungen

| Datei | Modul | Aktion |
|---|---|---|
| `security/user/User.java` | `cloud-driver-api` | neu |
| `security/jwt/JwtSigner.java` | `cloud-driver-api` | neu |
| `security/jwt/InvalidJwtException.java` | `cloud-driver-api` | neu |
| `security/jwt/JjwtSigner.java` | `cloud-driver-plugin` | neu |
| `security/auth/AuthService.java` | `cloud-driver-plugin` | neu |
| `factory/DefaultRestFactory.java` | `cloud-driver-plugin` | ändern |
| `bootstrap/CloudBootstrap.java` | `cloud-driver-bootstrap` | ändern |
| `bootstrap/CreateUserCli.java` | `cloud-driver-bootstrap` | neu (einmaliges Anlegen von Usern) |
| `cloud-driver-plugin/pom.xml` | `cloud-driver-plugin` | ändern (jjwt-Dependency) |
| `.env` / systemd-Unit | Deployment | ändern (`JWT_SIGNING_KEY`) |

---

## 1. `cloud-driver-api/src/main/java/de/lino/cloud/api/security/user/User.java` (neu)

```java
package de.lino.cloud.api.security.user;

import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A persisted end-user account, envelope-encrypted like every other
 * {@link Serialized} entity. {@code passwordHash} is a PHC-style Argon2id
 * string produced by {@code PasswordHasher#hash} - never the raw password,
 * which this class never retains a field for at all (unlike {@code
 * de.lino.cloud.api.security.rest.ApiKey}, which does keep its raw value,
 * since a machine-generated API key must be handed back once; a
 * user-chosen password never needs to be).
 *
 * <p>{@code id} is the primary key this entity is stored/looked up under -
 * use the username itself if usernames should never change, or a
 * separately generated id (e.g. UUID) if they should; {@link AuthService}
 * (in {@code cloud-driver-plugin}) does not assume either choice.
 */
@Getter @ToString(exclude = {"passwordHash"})
@EqualsAndHashCode(callSuper = false)
public final class User extends Serialized {

    private final String id;
    private final String username;
    private final String passwordHash;

    public User(@NotNull final String id, @NotNull final String username, @NotNull final String passwordHash) {
        this.id = Objects.requireNonNull(id, "@User.init: id cannot be null");
        this.username = Objects.requireNonNull(username, "@User.init: username cannot be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "@User.init: passwordHash cannot be null");
    }

    @Override
    public List<String> keysOf() {
        return List.of(this.id);
    }
}
```

> Referenziert `AuthService` nur im Javadoc (Doku-Zweck) — `cloud-driver-api` bekommt
> dadurch keine Abhängigkeit auf `cloud-driver-plugin`; das ist nur ein Kommentar, kein
> Import.

---

## 2. `cloud-driver-api/src/main/java/de/lino/cloud/api/security/jwt/JwtSigner.java` (neu)

```java
package de.lino.cloud.api.security.jwt;

import org.jetbrains.annotations.NotNull;

/**
 * Signs and verifies stateless JWTs used to authenticate end-user clients
 * (iOS/web/macOS) after a successful login. Unlike {@code
 * de.lino.cloud.api.security.rest.ApiKey} (static, long-lived,
 * server-to-server), a JWT is short-lived and carries a user identity.
 */
public interface JwtSigner {

    /**
     * Issues a signed JWT asserting {@code subject}, expiring after {@code ttlSeconds}.
     *
     * @param subject the identity to embed (e.g. {@code User#getId()})
     * @param ttlSeconds how many seconds from now the token expires
     */
    @NotNull
    String sign(@NotNull String subject, long ttlSeconds);

    /**
     * Verifies {@code token}'s signature and expiry, returning its subject.
     *
     * @throws InvalidJwtException if the signature is invalid, malformed, or the token has expired
     */
    @NotNull
    String verify(@NotNull String token) throws InvalidJwtException;
}
```

## 3. `cloud-driver-api/src/main/java/de/lino/cloud/api/security/jwt/InvalidJwtException.java` (neu)

```java
package de.lino.cloud.api.security.jwt;

/**
 * Thrown by {@link JwtSigner#verify(String)} when a token's signature is
 * invalid, the token is malformed, or it has expired.
 */
public class InvalidJwtException extends RuntimeException {

    public InvalidJwtException(final String message) {
        super(message);
    }

    public InvalidJwtException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
```

---

## 4. `cloud-driver-plugin/pom.xml` (ändern)

Innerhalb von `<dependencies>` ergänzen (Version 0.12.6 zum Zeitpunkt dieser Anleitung —
vor dem Build kurz auf Maven Central prüfen, ob eine neuere Patch-Version vorliegt):

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

---

## 5. `cloud-driver-plugin/src/main/java/de/lino/cloud/plugin/security/jwt/JjwtSigner.java` (neu)

```java
package de.lino.cloud.plugin.security.jwt;

import de.lino.cloud.api.security.jwt.InvalidJwtException;
import de.lino.cloud.api.security.jwt.JwtSigner;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * {@link JwtSigner} backed by HMAC-SHA256 (jjwt). The signing key comes
 * from {@code JWT_SIGNING_KEY} (read by the caller, e.g. {@code
 * CloudBootstrap}) - never hardcoded, same requirement as the Postgres
 * credentials. Requires at least 32 bytes/256 bits of entropy; generate
 * e.g. via {@code openssl rand -base64 32}.
 */
public final class JjwtSigner implements JwtSigner {

    private final SecretKey key;

    public JjwtSigner(@NonNull final String signingKeySecret) {
        if (signingKeySecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("@JjwtSigner.init: signing key must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(signingKeySecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @NotNull
    public String sign(@NotNull final String subject, final long ttlSeconds) {
        final Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(this.key)
                .compact();
    }

    @Override
    @NotNull
    public String verify(@NotNull final String token) {
        try {
            return Jwts.parser()
                    .verifyWith(this.key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (final JwtException e) {
            throw new InvalidJwtException("@JjwtSigner.verify: invalid or expired token", e);
        }
    }
}
```

---

## 6. `cloud-driver-plugin/src/main/java/de/lino/cloud/plugin/security/auth/AuthService.java` (neu)

```java
package de.lino.cloud.plugin.security.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.security.jwt.InvalidJwtException;
import de.lino.cloud.api.security.jwt.JwtSigner;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.api.security.user.User;
import io.javalin.http.UnauthorizedResponse;
import lombok.NonNull;

import java.util.UUID;

/**
 * Verifies end-user login (username + password) against {@link User}
 * entities stored via {@link DataFactory}, and issues/validates the JWTs
 * that then authenticate every subsequent request from that client - see
 * {@link JwtSigner}. Deliberately separate from {@code
 * de.lino.cloud.api.security.rest.ApiKey}: an end user never sees, and
 * this class never needs, database credentials.
 *
 * <p>{@link #register} is deliberately not wired to any public HTTP route
 * by this class - see {@code CreateUserCli} in {@code cloud-driver-bootstrap}
 * for how new accounts are meant to be created (an operator-run one-off
 * command, not a self-service endpoint), unless the deployment explicitly
 * wants open self-registration.
 */
public final class AuthService {

    private static final long ACCESS_TOKEN_TTL_SECONDS = 60 * 60 * 12; // 12h

    private final DataFactory dataFactory;
    private final PasswordHasher hasher;
    private final JwtSigner signer;

    public AuthService(@NonNull final DataFactory dataFactory, @NonNull final PasswordHasher hasher, @NonNull final JwtSigner signer) {
        this.dataFactory = dataFactory;
        this.hasher = hasher;
        this.signer = signer;
    }

    /**
     * Creates and persists a new user account. Not exposed over HTTP by
     * this class - call it directly (e.g. from {@code CreateUserCli}).
     */
    public void register(@NonNull final String username, final char @NonNull [] rawPassword) {
        final User user = new User(UUID.randomUUID().toString(), username, this.hasher.hash(rawPassword));
        this.dataFactory.register(user);
    }

    /** Verifies {@code username}/{@code rawPassword}, returning a signed JWT on success. */
    @NonNull
    public String login(@NonNull final String username, final char @NonNull [] rawPassword) {
        final User user = this.dataFactory.getEntities(User.class).stream()
                .filter(u -> u.getUsername().equals(username))
                .findFirst()
                .orElseThrow(() -> new UnauthorizedResponse("invalid credentials"));

        if (!this.hasher.verify(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedResponse("invalid credentials");
        }
        return this.signer.sign(user.getId(), ACCESS_TOKEN_TTL_SECONDS);
    }

    /** Validates a JWT from the {@code Authorization} header, returning the user id. */
    @NonNull
    public String validate(@NonNull final String jwt) {
        try {
            return this.signer.verify(jwt);
        } catch (final InvalidJwtException e) {
            throw new UnauthorizedResponse("invalid or expired token");
        }
    }
}
```

---

## 7. `cloud-driver-plugin/src/main/java/de/lino/cloud/plugin/factory/DefaultRestFactory.java` (ändern)

`DefaultRestFactory` bekommt einen **dritten Konstruktor**, der statt eines statischen
`ApiKey` einen `AuthService` nimmt und Bearer-JWT-Auth durchsetzt. Der bestehende
`ApiKey`-Konstruktor bleibt unverändert erhalten (weiterhin sinnvoll für
Server-zu-Server-Zugriffe) — beide Mechanismen sind bewusst getrennt, nicht kombiniert.
Zusätzlich mountet dieser Konstruktor **immer** `POST /auth/login`, noch bevor
`start()` aufgerufen wird.

**Neue Felder** (zusätzlich zu den bestehenden `dataFactory`, `apiKey`, `gson`, ...):

```java
private final AuthService authService; // null, wenn kein JWT-Auth aktiv ist
```

**Neuer Konstruktor**, direkt unter dem bestehenden
`DefaultRestFactory(DataFactory, ApiKey)`:

```java
/**
 * Every route requires a valid {@code Authorization: Bearer <jwt>} header,
 * checked via {@code authService}, except {@code POST /auth/login} itself
 * - mounted automatically by this constructor - which issues that JWT in
 * the first place. Use this constructor (instead of the {@link ApiKey}
 * one) when the clients calling this API are end users authenticating
 * with a username/password, not another service holding a static key.
 *
 * @param dataFactory the {@link DataFactory} every registered resource is backed by
 * @param authService verifies login and issued JWTs; must not be {@code null}
 */
public DefaultRestFactory(@NonNull final DataFactory dataFactory, @NonNull final AuthService authService) {
    this.dataFactory = dataFactory;
    this.apiKey = null;
    this.authService = Objects.requireNonNull(authService, "@DefaultRestFactory.init: authService cannot be null");
}
```

Der bestehende Konstruktor `DefaultRestFactory(DataFactory, ApiKey)` muss `this.authService = null;`
ergänzen, und `DefaultRestFactory(DataFactory)` (der No-Auth-Konstruktor für lokale
Entwicklung) ebenfalls.

**`start(int port)` anpassen** — der bisherige Block sieht so aus:

```java
this.app = Javalin.create(config -> {

    if (this.apiKey != null) {
        config.routes.before(this::requireValidApiKey);
    }

    this.registerResources.forEach((path, type) -> this.bindRegister(config, path, type));
    this.fetchResources.forEach((path, type) -> this.bindFetch(config, path, type));
    this.updateResources.forEach((path, type) -> this.bindUpdate(config, path, type));
    this.deleteResources.forEach((path, type) -> this.bindDelete(config, path, type));
});
```

Ersetzen durch:

```java
this.app = Javalin.create(config -> {

    if (this.apiKey != null) {
        config.routes.before(this::requireValidApiKey);
    }

    if (this.authService != null) {
        config.routes.post("/auth/login", this::handleLogin);
        config.routes.before(this::requireValidBearerToken);
    }

    this.registerResources.forEach((path, type) -> this.bindRegister(config, path, type));
    this.fetchResources.forEach((path, type) -> this.bindFetch(config, path, type));
    this.updateResources.forEach((path, type) -> this.bindUpdate(config, path, type));
    this.deleteResources.forEach((path, type) -> this.bindDelete(config, path, type));
});
```

**Neue private Methoden**, direkt unter dem bestehenden `requireValidApiKey`:

```java
private static final String LOGIN_PATH = "/auth/login";
private static final String AUTHORIZATION_HEADER = "Authorization";
private static final String BEARER_PREFIX = "Bearer ";

/**
 * Gates every route behind a valid {@code Authorization: Bearer <jwt>}
 * header, except {@link #LOGIN_PATH} itself - that route is how a client
 * obtains the JWT this filter checks for in the first place, so it must
 * stay reachable without one.
 */
private void requireValidBearerToken(@NotNull final Context ctx) {
    if (LOGIN_PATH.equals(ctx.path())) {
        return;
    }
    final String header = ctx.header(AUTHORIZATION_HEADER);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
        throw new UnauthorizedResponse("Missing " + AUTHORIZATION_HEADER + " header");
    }
    final String userId = this.authService.validate(header.substring(BEARER_PREFIX.length()));
    ctx.attribute("userId", userId);
}

/**
 * {@code POST /auth/login}: reads {@code {"username": ..., "password": ...}}
 * from the request body, dispatched off the Jetty worker thread since
 * {@link AuthService#login} runs Argon2id (deliberately slow) plus a
 * {@code DataFactory} lookup.
 */
private void handleLogin(@NotNull final Context ctx) {
    final LoginRequest request = this.gson.fromJson(ctx.body(), LoginRequest.class);
    ctx.future(() -> MultiTaskingFactory.getInstance().supplyAsync(() ->
            this.authService.login(request.username(), request.password().toCharArray())
    ).thenAccept(token ->
            ctx.contentType("application/json").result(this.gson.toJson(new LoginResponse(token)))));
}

private record LoginRequest(String username, String password) {
}

private record LoginResponse(String token) {
}
```

**Neue Imports** oben in der Datei ergänzen:

```java
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.plugin.security.auth.AuthService;
import java.util.Objects;
```

(`MultiTaskingFactory` ist evtl. schon indirekt über eine andere Klasse im Classpath,
aber nicht zwingend bereits importiert — prüfen und ggf. ergänzen.)

---

## 8. `cloud-driver-bootstrap/src/main/java/de/lino/cloud/bootstrap/CloudBootstrap.java` (ändern)

`initiateCloudDriver()` konstruiert aktuell `DefaultCloudDriver.setInstance(...)`, welches
intern bereits einen unauthentifizierten `DefaultRestFactory(dataFactory)` anlegt (siehe
`DefaultCloudDriver.setInstance`). Für Auth brauchen wir stattdessen einen eigenen,
JWT-gesicherten `RestFactory` — der über `CloudDriver.getRestFactory()` bereitgestellte ist
laut dessen eigenem Javadoc bewusst unauthentifiziert und nicht für den Einsatz außerhalb
von `localhost` gedacht.

**Neue Methode** in `CloudBootstrap`, die den Server tatsächlich startet (bisher scheint
noch nichts `RestFactory#start` aufzurufen — falls doch, diese Stelle entsprechend
ersetzen statt neu hinzuzufügen):

```java
/**
 * Builds a JWT-authenticated {@link DefaultRestFactory} (backed by the
 * same {@code dataFactory} as everything else {@code CLOUD_API} exposes),
 * registers every resource this deployment exposes over HTTP, and starts
 * it listening on {@code port}. Deliberately does not use {@code
 * CLOUD_API.getRestFactory()} - that facet is unauthenticated by design
 * (see its Javadoc) - and constructs its own instead, gated by {@link
 * AuthService}. Returns {@link RestFactory#stop} as the shutdown action.
 */
private static Runnable startRestApi(@NonNull final int port) {

    final String signingKey = System.getenv("JWT_SIGNING_KEY");
    if (signingKey == null || signingKey.isBlank()) {
        throw new IllegalStateException(
                "@CloudBootstrap.startRestApi: JWT_SIGNING_KEY environment variable is not set. "
                        + "Generate one via: openssl rand -base64 32");
    }

    final DataFactory dataFactory = CLOUD_API.getDataFactory();
    final PasswordHasher passwordHasher = new Argon2idPasswordHasher();
    final JwtSigner jwtSigner = new JjwtSigner(signingKey);
    final AuthService authService = new AuthService(dataFactory, passwordHasher, jwtSigner);

    final RestFactory restFactory = new DefaultRestFactory(dataFactory, authService);

    // Hier jede Entity mounten, die über HTTP erreichbar sein soll, z.B.:
    // restFactory.register("/notes", Note.class);
    // restFactory.fetch("/notes", Note.class);
    // restFactory.update("/notes", Note.class);
    // restFactory.delete("/notes", Note.class);

    restFactory.start(port);

    return restFactory::stop;
}
```

**Neue Imports** in `CloudBootstrap.java`:

```java
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.security.jwt.JwtSigner;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.plugin.factory.DefaultRestFactory;
import de.lino.cloud.plugin.security.auth.AuthService;
import de.lino.cloud.plugin.security.jwt.JjwtSigner;
import de.lino.cloud.plugin.security.password.Argon2idPasswordHasher;
```

**Im `runnable`-Array in `main`** (dort, wo aktuell
`startPendingUploadScheduler()`, `startEventScheduler(...)`,
`startExtensionsBootstrapScheduler(args)` stehen) ergänzen:

```java
final Runnable[] runnable = new Runnable[] {

        startPendingUploadScheduler()

        , startEventScheduler(DatabaseWatchEvent.class, ExtensionRegisterEvent.class)

        , startExtensionsBootstrapScheduler(args)

        , startRestApi(readRestPort())

};
```

Kleine Hilfsmethode für den Port, ebenfalls aus einer Env-Variable, mit Fallback:

```java
private static int readRestPort() {
    final String raw = System.getenv("CLOUD_REST_PORT");
    return raw == null || raw.isBlank() ? 8080 : Integer.parseInt(raw);
}
```

---

## 9. `cloud-driver-bootstrap/src/main/java/de/lino/cloud/bootstrap/CreateUserCli.java` (neu)

Ein bewusst separates, manuell auszuführendes Kommando, um den ersten (und weitere)
User anzulegen — **kein** öffentlicher Self-Registrierungs-Endpunkt, damit sich nicht
irgendjemand mit Zugriff auf Port 8080 selbst einen Account erstellen kann.

```java
package de.lino.cloud.bootstrap;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.plugin.security.auth.AuthService;
import de.lino.cloud.plugin.security.jwt.JjwtSigner;
import de.lino.cloud.plugin.security.password.Argon2idPasswordHasher;

import java.io.Console;
import java.util.Arrays;

/**
 * One-off CLI to create a new {@code User} account. Run manually on the
 * server, never exposed over HTTP - see {@code
 * DefaultRestFactory}'s {@code /auth/login} route for how that same
 * account then authenticates.
 *
 * <p>Usage: {@code java -cp cloud-driver-bootstrap.jar
 * de.lino.cloud.bootstrap.CreateUserCli <username>} — prompts for the
 * password on the terminal (not as a CLI argument, so it never ends up in
 * shell history or {@code ps}).
 */
public final class CreateUserCli {

    public static void main(final String[] args) throws Exception {

        if (args.length != 1) {
            System.err.println("Usage: CreateUserCli <username>");
            System.exit(1);
        }

        final CloudDriver cloudDriver = CloudBootstrap.initiateCloudDriverForCli();
        final DataFactory dataFactory = cloudDriver.getDataFactory();

        final String signingKey = System.getenv("JWT_SIGNING_KEY");
        if (signingKey == null || signingKey.isBlank()) {
            throw new IllegalStateException("JWT_SIGNING_KEY environment variable is not set");
        }

        final AuthService authService = new AuthService(
                dataFactory, new Argon2idPasswordHasher(), new JjwtSigner(signingKey));

        final Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("No console available - run this interactively, not piped");
        }
        final char[] password = console.readPassword("Password for '%s': ", args[0]);

        try {
            authService.register(args[0], password);
            System.out.println("User '" + args[0] + "' created.");
        } finally {
            Arrays.fill(password, ' ');
        }
    }
}
```

Dafür muss `initiateCloudDriver()` in `CloudBootstrap` in eine wiederverwendbare,
paket-sichtbare Methode `initiateCloudDriverForCli()` umbenannt/extrahiert werden (gleicher
Inhalt wie die bisherige `private static Optional<CloudDriver> initiateCloudDriver()`, nur
`CloudDriver` statt `Optional<CloudDriver>` zurückgebend, `orElseThrow()` intern), damit sowohl
`main` als auch `CreateUserCli` dieselbe Bootstrap-Logik nutzen, ohne den vollen
Extension-/Scheduler-Start mitzuziehen.

---

## 10. Deployment: `JWT_SIGNING_KEY` setzen

Auf der VPS, z.B. in der systemd-Unit (`Environment=`-Zeile) oder einer geladenen
`.env`-Datei — **niemals im Repo**:

```bash
openssl rand -base64 32
# -> Ausgabe als JWT_SIGNING_KEY setzen, z.B.:
export JWT_SIGNING_KEY="<ausgabe von oben>"
```

---

## Testen nach der Implementierung

```bash
# 1. User anlegen (einmalig, auf dem Server)
java -cp cloud-driver-bootstrap.jar de.lino.cloud.bootstrap.CreateUserCli lino
# Password for 'lino': ********

# 2. Login vom Client (z.B. Handy / curl zum Testen)
curl -X POST https://deine-domain:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"lino","password":"dein-passwort"}'
# -> {"token":"eyJhbGciOiJIUzI1NiJ9..."}

# 3. Authentifizierter Request mit dem Token
curl https://deine-domain:8080/notes \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."

# 4. Ohne/mit falschem Token -> 401
curl https://deine-domain:8080/notes
curl https://deine-domain:8080/notes -H "Authorization: Bearer invalid"
```

---

## Nicht-Ziele / bewusst ausgelassen

- Kein Refresh-Token-Mechanismus in dieser ersten Version — das JWT läuft nach 12h ab,
  der Client muss sich dann erneut einloggen. Ausbaufähig über eine zweite,
  widerrufbare Token-Art (siehe frühere Skizze mit `RefreshTokenStore`), aber für den
  Start bewusst einfach gehalten.
- Kein öffentlicher Self-Registrierungs-Endpunkt — neue Accounts entstehen nur über
  `CreateUserCli` auf dem Server selbst.
- `ApiKey`- und JWT-Auth werden nicht kombiniert (ein `DefaultRestFactory` nutzt genau
  einen der beiden Mechanismen). Falls beides gleichzeitig gebraucht wird (z.B. ein
  interner Cronjob mit API-Key, echte Nutzer mit JWT), zwei separate `RestFactory`-
  Instanzen auf unterschiedlichen Ports/Pfaden einsetzen.
