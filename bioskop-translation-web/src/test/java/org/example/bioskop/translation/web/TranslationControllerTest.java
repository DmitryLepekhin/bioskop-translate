package org.example.bioskop.translation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.example.bioskop.translation.core.TranslationRequest;
import org.example.bioskop.translation.core.TranslationResponse;
import org.example.bioskop.translation.core.TranslationService;
import org.example.bioskop.translation.core.TranslationStatus;
import org.example.bioskop.translation.core.TranslationStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TranslationController.class)
class TranslationControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TranslationService translationService;

    @Test
    void postsTranslationRequest() throws Exception {
        UUID sourceId = UUID.randomUUID();
        when(translationService.requestTranslation(any(TranslationRequest.class))).thenReturn(new TranslationResponse(
            sourceId,
            "/source/exercise-en.srt",
            "/source/exercise-ru.srt",
            "en",
            "ru",
            TranslationStatus.PENDING,
            null
        ));

        mockMvc.perform(post("/translations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceTextId": "%s",
                      "sourcePath": "/source/exercise-en.srt",
                      "targetPath": "/source/exercise-ru.srt",
                      "sourceLang": "en",
                      "targetLang": "ru"
                    }
                    """.formatted(sourceId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sourceTextId").value(sourceId.toString()))
            .andExpect(jsonPath("$.sourcePath").value("/source/exercise-en.srt"))
            .andExpect(jsonPath("$.targetPath").value("/source/exercise-ru.srt"))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getsTranslationStatus() throws Exception {
        UUID sourceId = UUID.randomUUID();
        when(translationService.getStatus(sourceId, "ru")).thenReturn(new TranslationStatusResponse(
            sourceId,
            "/source/exercise-en.srt",
            "/source/exercise-ru.srt",
            "en",
            "ru",
            TranslationStatus.IN_PROGRESS,
            null
        ));

        mockMvc.perform(get("/translations/{sourceId}", sourceId)
                .param("targetLang", "ru"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sourceTextId").value(sourceId.toString()))
            .andExpect(jsonPath("$.targetLang").value("ru"))
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }
}
