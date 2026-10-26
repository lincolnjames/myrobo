package com.myrobo.common.config;

import java.nio.file.Path;

/** 검증을 통과해 로드된 설정과 그 파일의 지문. 앱 전체에서 이 빈 하나를 주입받아 쓴다. */
public record LoadedConfig(RoboConfig config, String sha256, Path source) {
}
