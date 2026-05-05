package org.example.bioskop.translation.core.srt;

public record SubtitleCue(
    int id,
    long startMillis,
    long endMillis,
    String text,
    String speaker
) {
    public SubtitleCue withText(String text) {
        return new SubtitleCue(id, startMillis, endMillis, text, speaker);
    }

    public SubtitleCue withSpeaker(String speaker) {
        return new SubtitleCue(id, startMillis, endMillis, text, speaker);
    }
}
