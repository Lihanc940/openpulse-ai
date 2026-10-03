package io.github.lihanc940.openpulse.analysis.api;

import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class LocalAnalysisApiDisabledTest {

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    WebApplicationContext webApplicationContext;

    @MockitoBean
    AnalyzerProcessRunner analyzerProcessRunner;

    MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void localAnalysisApiIsNotRegisteredByDefault() throws Exception {
        assertThat(applicationContext.getBeansOfType(LocalAnalysisController.class)).isEmpty();

        mockMvc.perform(post("/api/v1/analysis/local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryPath\":\"sample repository\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(analyzerProcessRunner);
    }
}
