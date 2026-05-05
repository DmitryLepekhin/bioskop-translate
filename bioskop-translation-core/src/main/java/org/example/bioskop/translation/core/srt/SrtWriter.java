package org.example.bioskop.translation.core.srt;

import java.util.List;

public class SrtWriter {
    public String write(List<SubtitleCue> cues) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue cue = cues.get(i);
            if (i > 0) {
                result.append('\n');
            }
            result.append(cue.id()).append('\n');
            result.append(formatTimestamp(cue.startMillis()))
                .append(" --> ")
                .append(formatTimestamp(cue.endMillis()))
                .append('\n');
            result.append(cue.text()).append("\n");
        }
        return result.toString();
    }

    static String formatTimestamp(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }

        long hours = millis / 3_600_000;
        millis %= 3_600_000;
        long minutes = millis / 60_000;
        millis %= 60_000;
        long seconds = millis / 1_000;
        long remainingMillis = millis % 1_000;
        return "%02d:%02d:%02d,%03d".formatted(hours, minutes, seconds, remainingMillis);
    }
}
