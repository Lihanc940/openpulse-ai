package io.github.lihanc940.openpulse.integration.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AnalyzerProcessPropertiesTest {

    @Autowired
    AnalyzerProcessProperties properties;

    @Test
    void applicationConfigurationDefaultsToProtocolV1() {
        assertThat(properties.protocolVersion()).isEqualTo(AnalyzerProtocolVersion.V1_0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0"})
    void bindsSupportedProtocolVersions(String protocolVersion) {
        contextRunner(protocolVersion).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AnalyzerProcessProperties.class).protocolVersion().cliValue())
                    .isEqualTo(protocolVersion);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1", "2", "2.1", "latest", "unknown", " 1.0", "1.0 "})
    void rejectsUnsupportedOrInexactProtocolVersionsDuringBinding(String protocolVersion) {
        contextRunner(protocolVersion).run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner contextRunner(String protocolVersion) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("analyzer-test", Map.of(
                                "openpulse.analyzer.executable", "openpulse-analyzer",
                                "openpulse.analyzer.timeout", "30s",
                                "openpulse.analyzer.protocol-version", protocolVersion
                        ))
                ));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AnalyzerProcessProperties.class)
    static class PropertiesConfiguration {
    }
}
