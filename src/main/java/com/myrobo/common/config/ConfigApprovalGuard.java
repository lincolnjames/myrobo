package com.myrobo.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 승인되지 않은 설정 파일로는 실행하지 않는다 (규칙 변경 절차의 강제 장치).
 *
 * <ul>
 *   <li>승인 기록이 하나도 없으면: 지금 파일을 최초 승인으로 기록하고 진행 (첫 실행)</li>
 *   <li>지금 파일의 해시가 승인된 기록에 있으면: 진행</li>
 *   <li>그 외: 시작 거부. 파일을 손으로 고쳐 절차를 건너뛰는 것을 막는다</li>
 * </ul>
 */
@Component
public class ConfigApprovalGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConfigApprovalGuard.class);

    private final JdbcClient jdbc;
    private final LoadedConfig loaded;
    private final boolean requireApproval;

    public ConfigApprovalGuard(JdbcClient jdbc, LoadedConfig loaded,
                               @Value("${robo.config.require-approval:true}") boolean requireApproval) {
        this.jdbc = jdbc;
        this.loaded = loaded;
        this.requireApproval = requireApproval;
    }

    @Override
    public void run(ApplicationArguments args) {
        RoboConfig c = loaded.config();
        if (!requireApproval) {
            log.warn("설정 승인 검사가 꺼져 있음 (robo.config.require-approval=false). 개발용으로만 쓸 것.");
            return;
        }
        long approvals = jdbc.sql("select count(*) from rule_change where status = 'APPROVED'")
                .query(Long.class).single();
        if (approvals == 0) {
            jdbc.sql("""
                    insert into rule_change (version, kind, reason, status, config_hash, approved_at)
                    values (:version, :kind, :reason, 'APPROVED', :hash, now())
                    """)
                    .param("version", c.version())
                    .param("kind", c.changeKind().name())
                    .param("reason", "최초 실행 시 자동 승인: " + c.changeReason())
                    .param("hash", loaded.sha256())
                    .update();
            log.warn("승인 기록이 없어 현재 설정(version {}, hash {})을 최초 승인으로 기록함",
                    c.version(), shortHash());
            return;
        }
        boolean approved = jdbc.sql("""
                        select count(*) from rule_change
                        where status = 'APPROVED' and config_hash = :hash
                        """)
                .param("hash", loaded.sha256())
                .query(Long.class).single() > 0;
        if (!approved) {
            throw new IllegalStateException("승인되지 않은 설정 파일입니다 (version " + c.version()
                    + ", hash " + shortHash() + "). 규칙 변경 절차로 승인한 뒤 다시 실행하세요.");
        }
        log.info("설정 확인 완료: version {}, hash {}", c.version(), shortHash());
    }

    private String shortHash() {
        return loaded.sha256().substring(0, 12);
    }
}
