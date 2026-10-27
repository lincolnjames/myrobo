package com.myrobo.ops;

import com.myrobo.collector.CollectorJob;
import com.myrobo.collector.CollectorTypes.CollectResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 배치를 손으로 돌리는 내부용 엔드포인트. 서버가 127.0.0.1에만 열려 있어 외부에서는 접근할 수 없다.
 * 변경 요청이라 X-Robo-Request: 1 헤더가 필요하다 (LocalRequestGuard).
 * 예) Invoke-RestMethod -Method Post -Headers @{'X-Robo-Request'='1'} http://localhost:8080/internal/jobs/collect
 */
@RestController
@RequestMapping("/internal/jobs")
public class JobController {

    private final CollectorJob collector;
    private final DailyJob daily;
    private final Notifier notifier;
    private final BackupJob backup;
    private final BackupRestore restore;

    public JobController(CollectorJob collector, DailyJob daily, Notifier notifier, BackupJob backup,
                         BackupRestore restore) {
        this.collector = collector;
        this.daily = daily;
        this.notifier = notifier;
        this.backup = backup;
        this.restore = restore;
    }

    /** 백업을 지금 실행한다. 결과 파일은 백업 폴더(robo.backup.dir)에 생긴다. */
    @PostMapping(value = "/backup", produces = "application/json;charset=UTF-8")
    public BackupJob.Summary backup() {
        return backup.run();
    }

    /**
     * 백업 폴더의 zip을 빈 데이터베이스에 되돌린다 (재해 복구용). 실수 방지로 confirm=RESTORE가 있어야 하고,
     * 직접 입력한 기록이 있는 데이터베이스에는 거부한다.
     * 예) ...jobs/restore?file=myrobo-2026-11-01.zip&amp;confirm=RESTORE
     */
    @PostMapping(value = "/restore", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> restore(@RequestParam String file, @RequestParam String confirm) {
        if (!"RESTORE".equals(confirm)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "confirm=RESTORE 가 필요함"));
        }
        try {
            return ResponseEntity.ok(Map.of("ok", true, "rows", restore.run(file).rows()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /** charset을 명시해야 Windows PowerShell(Invoke-RestMethod)이 한글을 깨지 않고 읽는다. */
    @PostMapping(value = "/collect", produces = "application/json;charset=UTF-8")
    public CollectResult collect() {
        return collector.run();
    }

    /** 매일 작업 전체(수집 → 주문안 → 알림)를 지금 실행. 알림이 켜져 있으면 실제로 보낸다. */
    @PostMapping(value = "/daily", produces = "application/json;charset=UTF-8")
    public DailyJob.Summary daily() {
        return daily.run();
    }

    /** 알림 설정이 맞는지 확인하는 테스트 메시지 한 건. */
    @PostMapping(value = "/notify-test", produces = "application/json;charset=UTF-8")
    public Map<String, Object> notifyTest() {
        boolean enabled = notifier.enabled();
        boolean sent = enabled && notifier.send("[myrobo] 테스트 알림이에요. 이 메시지가 보이면 알림 설정이 맞아요.");
        return Map.of("enabled", enabled, "sent", sent);
    }
}
