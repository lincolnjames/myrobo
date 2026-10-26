package com.myrobo.common.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 설정 파일의 지문(SHA-256). 승인 기록의 해시와 다르면 앱은 실행을 거부한다.
 * 윈도우·맥에서 git이 줄바꿈을 바꿔도 같은 해시가 나오도록 CRLF와 BOM을 정규화한다.
 */
public final class ConfigHash {

    private ConfigHash() {
    }

    public static String sha256(byte[] fileBytes) {
        String text = new String(fileBytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
