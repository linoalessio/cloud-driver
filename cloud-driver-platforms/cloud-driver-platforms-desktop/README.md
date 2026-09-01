# cloud-driver-platforms-desktop

A coroutine-friendly Kotlin facade over `cloud-driver-platforms-rest`'s Java `ApiClient` - the first Kotlin module in this repo.

## Project structure

Maven `packaging=jar`, `groupId=de.lino.cloud.platforms.desktop`, `artifactId=cloud-driver-platforms-desktop`, package root `de.lino.cloud.platform.desktop`. Its `<parent>` is the `cloud-driver-platforms` aggregator (`cloud-driver-platforms/pom.xml`), which lists it as a `<module>` alongside `cloud-driver-platforms-rest`.

Depends on `cloud-driver-platforms-rest` (this repo, same version) plus `kotlin-stdlib`, `kotlinx-coroutines-core`, and `kotlinx-coroutines-jdk8` (for `CompletableFuture<T>.await()`). Sources live under `src/main/kotlin`, compiled by `org.jetbrains.kotlin:kotlin-maven-plugin` (`jvmTarget=21`, matching the root `pom.xml`'s `maven.compiler.target`) rather than `maven-compiler-plugin`.

```
de.lino.cloud.platform.desktop
└── CloudDriverClient.kt   coroutine facade over ApiClient
```

## API surface

`CloudDriverClient` wraps one `ApiClient` instance (exposed as `apiClient`, for a caller that wants the raw blocking/`CompletableFuture` API instead) and adapts every network call to a `suspend` function via `await()`: `login`, `register`/`confirmRegistration`, `uploadFile`, `listFiles`, `downloadFile`, `deleteFile`, `moveFile`, `createFolder`/`listFolders`/`updateFolder`/`deleteFolder`. Each is a thin wrapper over the matching `ApiClient#*Async` method - no HTTP handling of its own. Implements `AutoCloseable` (`use { }` shuts down the wrapped `ApiClient`'s executor).

An `ApiClient.ApiException` thrown by the wrapped async call surfaces unwrapped from the `suspend` function, since `await()` rethrows a `CompletionException`'s cause rather than the wrapper itself.

## API usage + code sample

```kotlin
import de.lino.cloud.platform.desktop.CloudDriverClient
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun main() = runBlocking {
    CloudDriverClient("https://auth.cloud-driver.de", "https://api.cloud-driver.de").use { client ->
        client.login("you@example.com", "hunter2")

        client.uploadFile(Path.of("report.pdf"))

        for (file in client.listFiles()) {
            println("${file.fileName()} (${file.sizeBytes()} bytes)")
        }
    }
}
```
