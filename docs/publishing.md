# Publishing EntKt

The release version is defined once as `entktVersion` in the root
`gradle.properties`. All published modules and both Gradle plugin markers use
that version. The initial target is `0.1.0-alpha.1`: an experimental release
whose APIs may change in later alphas. Preparing this configuration does not
mean the version has been published.

## What is published

The publishing plugin is applied only to `schema`, `runtime`, `codegen`,
`gradle-plugin`, `postgres`, `jackson`, `migrations`, `flyway`,
`ent-viewer-core`, and `ent-viewer-spring`. Example applications and test
projects are excluded.

Library coordinates are `io.entkt:<module>:<version>`. The Gradle plugin
also publishes markers for `io.entkt` and `io.entkt.flyway`, each depending
on the matching `io.entkt:gradle-plugin` version. Publish the whole set
together; codegen and runtime must stay aligned.

[Vanniktech Maven Publish](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)
assembles publications and handles Central Portal uploads.
[Dokka](https://kotlinlang.org/docs/dokka-gradle.html) generates Kotlin API
documentation for each module's `-javadoc.jar`; each library also includes a
`-sources.jar`. The root build supplies shared POM metadata.

## Local preparation

Normal builds and local publishing require neither signing keys nor Central
credentials. Keep `centralPublishing` unset for this workflow:

```sh
./gradlew publishAllPublicationsToLocalStagingRepository
```

This writes an unsigned Maven repository under `build/publishing/local/`.
It does not upload anything. Use this repository in an independent consumer
build to verify dependency resolution, plugin resolution, code generation,
and compilation without falling back to project dependencies or Maven local.
Configure the staging repository in both `pluginManagement.repositories`
and the consumer's dependency repositories.

For ordinary local development, the existing command remains available:

```sh
./gradlew publishToMavenLocal
```

Before uploading a release:

1. Run the relevant unit, generated-code, Gradle plugin, and PostgreSQL
   integration test suites.
2. Inspect the staged POMs and source/documentation JARs. EntKt dependencies
   must use the same release version, with no `-SNAPSHOT` references.
3. Verify an independent consumer using only the staged EntKt artifacts,
   including code generation and a basic PostgreSQL CRUD/transaction flow.
4. Commit the reviewed release state and tag that commit with the version.

## Central account and signing setup

Before uploading, verify the `io.entkt` namespace by adding Sonatype's DNS
TXT record to `entkt.io`. See
[namespace verification](https://central.sonatype.org/register/namespace/).

Generate a [Central Portal user token](https://central.sonatype.org/publish/generate-portal-token/)
and a signing key whose public key is available as described in
[Sonatype's signing requirements](https://central.sonatype.org/publish/requirements/gpg/).
The token's username/password are different from the credentials used to
sign in to the Portal.

Supply secrets through your secret manager or environment:

- `ORG_GRADLE_PROJECT_mavenCentralUsername`: token username.
- `ORG_GRADLE_PROJECT_mavenCentralPassword`: token password.
- `ORG_GRADLE_PROJECT_signingInMemoryKey`: complete ASCII-armored private key.
- `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`: key passphrase, if set.
- `ORG_GRADLE_PROJECT_signingInMemoryKeyId`: optional signing key ID.

The corresponding unprefixed properties can instead live in your private
user-level `~/.gradle/gradle.properties`. Never put credentials or private
keys in this repository, command-line arguments, or build logs.

## Upload, then release manually

Only after the release checks pass and an upload is approved, run:

```sh
./gradlew -PcentralPublishing=true publishToMavenCentral
```

The flag enables Central publishing and required signing for this build.
It is not needed for local preparation. The configured
`automaticRelease = false` uploads a deployment without releasing it.
In the [Central Portal deployments page](https://central.sonatype.com/publishing/deployments),
wait for validation, review the complete module/marker set, and explicitly
click **Publish** when ready. Do not use the plugin's
`publishAndReleaseToMavenCentral` shortcut; that requests automatic release.

This configuration rejects `-SNAPSHOT` versions for Central uploads:
the plugin would publish snapshots directly to a separate repository,
without the same manual release step.

Released versions are [immutable](https://central.sonatype.org/publish/requirements/immutability/).
Publish a new alpha version for subsequent changes; do not reuse a released
version number.

## Consumer plugin resolution

EntKt's plugin markers are published to Maven Central alongside the
libraries. They are not automatically listed on the Gradle Plugin Portal.
Consumers need Maven Central in their plugin repositories:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Before the public release, use the local staging repository above instead.
