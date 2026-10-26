package com.myrobo.common.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 파일 읽기 → YAML 파싱 → 매핑 → 검증 → 지문 계산. Spring 없이도 쓸 수 있다 (테스트, 백테스트 도구). */
public final class RoboConfigFiles {

    private RoboConfigFiles() {
    }

    public static LoadedConfig load(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        // SafeConstructor: YAML 태그로 임의 클래스를 만들지 못하게 막는다
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> root = yaml.load(new String(bytes, StandardCharsets.UTF_8));

        RoboConfig config = RoboConfigMapper.fromMap(root);
        List<String> problems = RoboConfigValidator.validate(config);
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new LoadedConfig(config, ConfigHash.sha256(bytes), path.toAbsolutePath());
    }
}
