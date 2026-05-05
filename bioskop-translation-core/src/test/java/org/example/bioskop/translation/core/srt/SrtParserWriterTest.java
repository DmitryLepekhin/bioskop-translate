package org.example.bioskop.translation.core.srt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SrtParserWriterTest {
    private final SrtParser parser = new SrtParser();
    private final SrtWriter writer = new SrtWriter();

    @Test
    void parsesSampleInputAndWritesNormalizedMilliseconds() throws IOException {
        String content = Files.readString(sampleInputPath());

        List<SubtitleCue> cues = parser.parse(content);
        String written = writer.write(cues);

        assertEquals(4, cues.size());
        assertEquals(1, cues.get(0).id());
        assertEquals(50, cues.get(0).startMillis());
        assertEquals(2470, cues.get(0).endMillis());
        assertEquals("Look at that guy go. He’s absolutely sending it.", cues.get(0).text());
        assertEquals("00:00:00,050 --> 00:00:02,470", written.split("\n")[1]);
    }

    @Test
    void preservesMultilineCueText() {
        List<SubtitleCue> cues = parser.parse("""
            
            1
            00:00:01,001 --> 00:00:02,010
            first line
            second line
            
            """);

        assertEquals("first line\nsecond line", cues.get(0).text());
        assertEquals("""
            1
            00:00:01,001 --> 00:00:02,010
            first line
            second line
            """, writer.write(cues));
    }

    @Test
    void failsClearlyOnMalformedTiming() {
        MalformedSrtException error = assertThrows(MalformedSrtException.class, () -> parser.parse("""
            1
            wrong
            text
            """));

        assertEquals("Invalid SRT timing line for cue 1: wrong", error.getMessage());
    }

    private Path sampleInputPath() {
        Path rootRelative = Path.of("AI/001-translate-service-general/01-idea/example/input.srt");
        if (Files.exists(rootRelative)) {
            return rootRelative;
        }
        return Path.of("../AI/001-translate-service-general/01-idea/example/input.srt");
    }
}
