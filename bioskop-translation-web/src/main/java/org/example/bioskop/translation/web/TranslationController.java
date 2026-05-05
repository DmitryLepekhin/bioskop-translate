package org.example.bioskop.translation.web;

import java.util.UUID;
import org.example.bioskop.translation.core.TranslationRequest;
import org.example.bioskop.translation.core.TranslationResponse;
import org.example.bioskop.translation.core.TranslationService;
import org.example.bioskop.translation.core.TranslationStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TranslationController {
    private final TranslationService translationService;

    public TranslationController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @PostMapping("/translations")
    public TranslationResponse requestTranslation(@RequestBody TranslationRequest request) {
        return translationService.requestTranslation(request);
    }

    @GetMapping("/translations/{sourceId}")
    public TranslationStatusResponse getStatus(
        @PathVariable("sourceId") UUID sourceId,
        @RequestParam("targetLang") String targetLang
    ) {
        return translationService.getStatus(sourceId, targetLang);
    }
}
