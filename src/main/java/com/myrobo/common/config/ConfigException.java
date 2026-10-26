package com.myrobo.common.config;

import java.util.List;

/** 설정 파일을 읽거나 검증하다 발견한 문제 전부. 앱은 이 예외로 시작을 멈춘다. */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public ConfigException(List<String> problems) {
        super("robo-config.yml에 문제가 있어 시작할 수 없습니다:\n - " + String.join("\n - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
