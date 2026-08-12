package io.github.lihanc940.openpulse.analysis.api;

import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerExecutionException;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerExecutionFailure;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerProcessRunner;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "openpulse.analysis.local-api-enabled=true")
class LocalAnalysisApiEnabledTest {

    private static final String ENDPOINT = "/api/v1/analysis/local";

    @Autowired
    WebApplicationContext webApplicationContext;

    @MockitoBean
    AnalyzerProcessRunner analyzerProcessRunner;

    MockMvc mockMvc;

    @TempDir
    Path testDirectory;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void returnsAnalyzerReportForRepositoryPathWithSpaces() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("sample repository"));
        when(analyzerProcessRunner.analyze(repositoryPath)).thenReturn(sampleReport());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(repositoryPath.toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.protocolVersion").value("1.0"))
                .andExpect(jsonPath("$.taskId").value("task_local_api_001"))
                .andExpect(jsonPath("$.summary.totalFiles").value(3));

        verify(analyzerProcessRunner).analyze(repositoryPath);
    }

    @ParameterizedTest
    @MethodSource("invalidRequestBodies")
    void rejectsInvalidRequestBodies(String requestBody) throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is invalid."));
    }

    @Test
    void rejectsPathWithInvalidSyntax() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("invalid\u0000path")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is invalid."));
    }

    @ParameterizedTest
    @MethodSource("executionFailureMappings")
    void mapsEveryAnalyzerExecutionFailure(
            AnalyzerExecutionFailure failure,
            int expectedStatus,
            String expectedMessage
    ) throws Exception {
        when(analyzerProcessRunner.analyze(any(Path.class)))
                .thenThrow(new AnalyzerExecutionException(
                        failure,
                        "secret server path C:\\private; stdout=[secret]; stderr=[secret]; diagnostic tail"
                ));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("sample repository")))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error").value(failure.name()))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("C:\\private")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("stdout")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("stderr")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("diagnostic")
                )));
    }

    @Test
    void mapsUnexpectedExceptionWithoutLeakingDetails() throws Exception {
        when(analyzerProcessRunner.analyze(any(Path.class)))
                .thenThrow(new IllegalStateException("secret stack detail C:\\private"));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("sample repository")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An internal error occurred."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret stack detail")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("C:\\private")
                )));
    }

    private static Stream<String> invalidRequestBodies() {
        return Stream.of(
                "{not-json}",
                "{}",
                "{\"repositoryPath\":null}",
                "{\"repositoryPath\":\"   \"}",
                "null"
        );
    }

    private static Stream<Arguments> executionFailureMappings() {
        return Stream.of(
                Arguments.of(AnalyzerExecutionFailure.INVALID_REPOSITORY_PATH, 400,
                        "Repository path is invalid or unavailable."),
                Arguments.of(AnalyzerExecutionFailure.TEMPORARY_DIRECTORY_CREATION_FAILED, 500,
                        "An internal error occurred."),
                Arguments.of(AnalyzerExecutionFailure.START_FAILED, 503,
                        "Analyzer is temporarily unavailable."),
                Arguments.of(AnalyzerExecutionFailure.TIMEOUT, 504,
                        "Analyzer timed out."),
                Arguments.of(AnalyzerExecutionFailure.INTERRUPTED, 500,
                        "An internal error occurred."),
                Arguments.of(AnalyzerExecutionFailure.INVALID_ARGUMENTS, 502,
                        "Analyzer failed to produce a valid report."),
                Arguments.of(AnalyzerExecutionFailure.REPOSITORY_NOT_FOUND, 502,
                        "Analyzer failed to produce a valid report."),
                Arguments.of(AnalyzerExecutionFailure.SCAN_FAILED, 502,
                        "Analyzer failed to produce a valid report."),
                Arguments.of(AnalyzerExecutionFailure.REPORT_OUTPUT_FAILED, 502,
                        "Analyzer failed to produce a valid report."),
                Arguments.of(AnalyzerExecutionFailure.UNKNOWN_EXIT_CODE, 502,
                        "Analyzer failed to produce a valid report."),
                Arguments.of(AnalyzerExecutionFailure.REPORT_MISSING, 502,
                        "Analyzer failed to produce a valid report."),
                Arguments.of(AnalyzerExecutionFailure.REPORT_INVALID, 502,
                        "Analyzer failed to produce a valid report."),
                Arguments.of(AnalyzerExecutionFailure.CLEANUP_FAILED, 500,
                        "An internal error occurred.")
        );
    }

    private static String requestJson(String repositoryPath) {
        return "{\"repositoryPath\":\""
                + repositoryPath.replace("\\", "\\\\")
                + "\"}";
    }

    private static AnalyzerReport sampleReport() {
        return new AnalyzerReport(
                "1.0",
                "task_local_api_001",
                AnalyzerStatus.SUCCESS,
                new AnalyzerReport.Repository("sample repository", "sample repository"),
                new AnalyzerReport.Summary(3, 2, 1, 0, 0, 30, 20, 5, 5),
                List.of(new AnalyzerReport.Language("Java", 2, 20)),
                new AnalyzerReport.Structure(true, false, false, false, false, false, false, List.of()),
                new AnalyzerReport.Quality(80, 80, 70, 60),
                List.of(),
                new AnalyzerReport.Dependencies(List.of(), List.of()),
                OffsetDateTime.parse("2026-08-13T00:00:00+08:00")
        );
    }
}
