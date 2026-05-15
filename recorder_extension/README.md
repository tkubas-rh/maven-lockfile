# Maven Lockfile Recorder Extension

## What is this?

A Maven core extension that captures every artifact Maven downloads or resolves during a build. It writes the list to a JSON file that the `maven-lockfile` plugin reads to include those artifacts in the lockfile.

## Why do we need it?

Many Maven plugins download artifacts at runtime that don't appear in your `pom.xml`:

- **maven-surefire-plugin** downloads a test provider (e.g. `surefire-junit-platform`) based on which test framework you use
- **quarkus-maven-plugin** downloads deployment artifacts for each Quarkus extension
- **maven-compiler-plugin** downloads annotation processors listed in its configuration
- **protobuf-maven-plugin** downloads the `protoc` binary

These artifacts are invisible to Maven's dependency tree. Without them, an offline build fails.

Previously, we handled this with hand-written "special plugin resolvers" — Java classes that model each plugin's internal behavior to predict what it will download. This works but requires writing and maintaining a resolver for every plugin, and any plugin we haven't modeled is a gap.

The recorder extension solves this by observing the actual build. Instead of predicting what plugins need, it watches what Maven actually resolves and records it. No per-plugin code needed, no gaps.

## How it works

1. Maven loads the extension before any build phase starts
2. The extension attaches a listener to Maven's artifact resolution system
3. As the build runs (compile, test, package, etc.), every artifact resolution is captured
4. Before `lockfile:generate` runs, the captured list is written to `.mvn/build-recorded-artifacts.json`
5. The `maven-lockfile` plugin reads this file and adds the artifacts to the lockfile

The extension and the plugin are in separate modules because Maven loads extensions and plugins in different classloaders. The JSON file is the bridge between them.

## Setup

Add the extension to your project's `.mvn/extensions.xml`:

```xml
<extensions>
  <extension>
    <groupId>io.github.chains-project</groupId>
    <artifactId>maven-lockfile-recorder</artifactId>
    <version>5.15.1-SNAPSHOT</version>
  </extension>
</extensions>
```

## Usage

### Single command (recommended)

Run your build and generate the lockfile in one step:

```bash
mvn verify lockfile:generate
```

The extension captures everything during `verify`, flushes the data before `lockfile:generate` starts, and the plugin includes it in the lockfile.

### Two-pass

Run the build first, then generate the lockfile separately:

```bash
mvn verify
mvn lockfile:generate
```

The extension writes the JSON file at the end of the first build. The plugin reads it during the second.

## What it produces

The extension writes `.mvn/build-recorded-artifacts.json` — a JSON array of every artifact resolved during the build:

```json
[
  {
    "url": "https://repo.maven.apache.org/maven2/org/apache/maven/surefire/surefire-junit-platform/3.5.4/surefire-junit-platform-3.5.4.jar",
    "groupId": "org.apache.maven.surefire",
    "artifactId": "surefire-junit-platform",
    "version": "3.5.4",
    "classifier": "",
    "extension": "jar"
  }
]
```

This file is ephemeral and platform-specific. It is gitignored and should not be committed.

## Relationship to special plugin resolvers

The plugin still includes hand-written resolvers for Surefire, Quarkus, compiler annotation processors, and protobuf. These provide structured dependency trees and work without running the full build.

The extension is a complementary catch-all. It ensures that no artifact is missed, regardless of which plugins the project uses. Artifacts already captured by the special resolvers are deduplicated — they won't appear twice in the lockfile.

## Limitations

- Requires running the actual build — `mvn lockfile:generate` alone won't capture plugin runtime artifacts (use `mvn verify lockfile:generate` instead)
- Captures artifacts for the current platform only (OS, architecture, JDK) — a build on Linux won't capture macOS-specific artifacts
- Artifacts appear as flat entries in the lockfile — no dependency hierarchy or plugin attribution
- In multi-module projects, the recording spans all modules — a module's lockfile may include artifacts only needed by sibling modules
