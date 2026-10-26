package com.myrobo.ops;

import com.myrobo.collector.CollectorJob;
import com.myrobo.collector.CollectorTypes.CollectResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public JobController(CollectorJob collector, DailyJob daily, Notifier notifier) {
        this.collector = collector;
        this.daily = daily;
        this.notifier = notifier;
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
