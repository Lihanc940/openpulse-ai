# Desktop development dependencies

This module uses the following unmodified third-party dependencies. Versions are pinned in the desktop/client POMs; Maven resolves their transitive dependencies. This file records sources and license identities for stage 2 development, not an app-image distribution manifest.

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

The JavaFX artifacts above are OpenJFX artifacts from Maven Central, not Oracle JavaFX binary downloads. No Java runtime or desktop distribution is bundled in stage 2. Stage 3 must assemble the actual runtime/analyzer license files, relevant OpenJFX native-library notices and source links alongside the reviewed app-image; this development inventory does not claim that packaging work is finished.
