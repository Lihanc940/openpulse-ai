# Desktop development dependencies

This module uses the following unmodified third-party dependencies. Versions are pinned in the desktop/client POMs; Maven resolves their transitive dependencies. The table includes development/test dependencies; the portable build generates a separate notice from its exact shipping allowlist in `distribution/portable-inputs.json`.

| Component | Version | Source / artifact | License |
| --- | --- | --- | --- |
| OpenJFX base, graphics, controls (Windows x64) | 21.0.13 | [OpenJFX](https://openjfx.io/), [release notes](https://gluonhq.com/products/javafx/openjfx-21-release-notes/), Maven `org.openjfx` | GPL version 2 with Classpath Exception; [upstream license and exception](https://github.com/openjdk/jfx/blob/jfx21/LICENSE), [Maven parent metadata](https://repo.maven.apache.org/maven2/org/openjfx/javafx/21.0.13/javafx-21.0.13.pom) |
| networknt JSON Schema Validator | 3.0.6 | [source](https://github.com/networknt/json-schema-validator), Maven `com.networknt:json-schema-validator` | Apache-2.0 |
| Jackson core, databind, YAML | 3.1.4 | [Jackson](https://github.com/FasterXML/jackson), Maven `tools.jackson` | Apache-2.0 |
| Jackson annotations | 2.21 | [source](https://github.com/FasterXML/jackson-annotations) | Apache-2.0 |
| itu | 1.14.0 | [source](https://github.com/ethlo/itu), Maven `com.ethlo.time:itu` | Apache-2.0 |
| SnakeYAML Engine | 3.0.1 | [source](https://bitbucket.org/snakeyaml/snakeyaml-engine), Maven `org.snakeyaml:snakeyaml-engine` | Apache-2.0 |
| SLF4J API | 2.0.17 | [source and license](https://www.slf4j.org/license.html), Maven `org.slf4j:slf4j-api` | MIT |
| JUnit Jupiter (test only) | 6.0.3 | [source](https://github.com/junit-team/junit-framework), Maven `org.junit.jupiter:junit-jupiter` | EPL-2.0 |
| AssertJ Core (test only) | 3.27.7 | [source](https://github.com/assertj/assertj), Maven `org.assertj:assertj-core` | Apache-2.0 |

The shared OpenPulse client and desktop code are part of this repository under its MIT license. The analyzer has its own [third-party notices](../docs/THIRD_PARTY_NOTICES.md).

Development JavaFX JARs come from Maven Central, not Oracle JavaFX binary downloads. The Windows x64 candidate instead links official Gluon OpenJFX 21.0.13 base/graphics/controls JMODs (same JavaFX version), retaining the runtime `legal/javafx.*` trees, GPLv2, Classpath Exception and native jpeg/mesa notices. Source: [OpenJFX jfx21](https://github.com/openjdk/jfx/tree/jfx21); binaries: [official Windows x64 jmods](https://download2.gluonhq.com/openjfx/21.0.13/openjfx-21.0.13_windows-x64_bin-jmods.zip). Module hashes are pinned in the distribution inputs.

Stage 3 uses Eclipse Temurin 21 (locally tested 21.0.11+10, Windows x64), GPLv2 + Classpath Exception; [source and binaries](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.11%2B10), [build source](https://github.com/adoptium/jdk21u). The linked runtime keeps its complete per-module `legal/` directory and Temurin NOTICE, including the shipped native dependencies' notices. The JDK vendor check deliberately rejects an unreviewed vendor notice inventory.

The candidate contains the desktop JAR and nine allowlisted runtime JARs, the private Java/JavaFX runtime, and the reviewed 0.2.2 C++ analyzer. It preserves JAR-internal licenses/notices and extracts available copies under `licenses/`; the full Apache 2.0 license is also supplied for Apache artifacts lacking a separate license entry. Analyzer nlohmann-json/PicoSHA2 licenses and notices are copied from the accepted CLI archive. Test-only JUnit/AssertJ and JavaFX web/media are not shipped. `VERSION.json`, `FILES.sha256` and the generated root `THIRD_PARTY_NOTICES.md` describe the actual image, rather than treating this development table as its manifest. This is a local candidate, not a public release.
