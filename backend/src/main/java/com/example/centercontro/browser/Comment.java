package com.example.centercontro.browser;

import java.time.Instant;

public record Comment(
        long seq,
        String id,
        String nickname,
        String text,
        String rawText,
        boolean system,
        Instant at
) {
    public String display() {
        if (nickname == null || nickname.isBlank()) {
            return text;
        }
        return nickname + "：" + text;
    }
}
