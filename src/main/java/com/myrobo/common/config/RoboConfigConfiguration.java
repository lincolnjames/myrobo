package com.myrobo.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/** 시작할 때 robo-config.yml을 읽고 검증한다. 문제가 있으면 여기서 앱이 멈춘다. */
@Configuration
public class RoboConfigConfiguration {

    @Bean
    public LoadedConfig loadedConfig(@Value("${robo.config-path}") String configPath) throws IOException {
        return RoboConfigFiles.load(Path.of(configPath));
    }
}
