package org.example.bioskop.translation.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.example.bioskop.translation.core.srt.SubtitleCue;
import org.example.bioskop.translation.core.storage.LocalFileTranslationStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranslationContextLoaderIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsConventionContextAndAttachesSpeakers() {
        LocalFileTranslationStorage storage = new LocalFileTranslationStorage(tempDir);
        storage.writeText("/source/notes.md", "Keep jokes dry.");
        storage.writeText("/source/characters.json", """
            {
              "characters": [
                {
                  "id": "Andy",
                  "displayName": "Andy",
                  "gender": "male",
                  "description": "Senior-aged, Kansas accent."
                }
              ]
            }
            """);
        storage.writeText("/source/speakers.csv", """
            cue,character
            1,Andy
            """);

        TranslationContextLoader loader = new TranslationContextLoader(storage);
        TranslationContext context = loader.loadForSourcePath("/source/input-en.srt");
        List<SubtitleCue> cues = loader.attachSpeakers(
            List.of(new SubtitleCue(1, 0, 1000, "Hello", null)),
            context.speakersByCueId()
        );

        assertEquals("Keep jokes dry.", context.notes());
        assertEquals(1, context.characters().size());
        assertEquals("Andy", context.characters().get(0).id());
        assertEquals("Andy", cues.get(0).speaker());
    }
}
