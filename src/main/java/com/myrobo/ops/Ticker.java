package com.myrobo.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 앱이 켜져 있는 동안 주기적으로(기본 10분): 놓친 매일 작업 따라잡기 → 그 밖의 주기 작업(증권사 전송 등).
 *
 * <p>PC가 08:00에 절전 중이어도, 깨어난 뒤 다음 틱에 그날 작업이 돈다. 따라잡기가 먼저라
 * 같은 틱에서 새로 만든 주문안을 바로 보낼 수 있다. 한 작업이 실패해도 다음 작업은 돈다.
 */
public class Ticker {

    private static final Logger log = LoggerFactory.getLogger(Ticker.class);

    private final CatchUp catchUp;
    private final List<Periodic> tasks;

    public Ticker(CatchUp catchUp, List<Periodic> tasks) {
        this.catchUp = catchUp;
        this.tasks = List.copyOf(tasks);
    }

    @Scheduled(initialDelayString = "${robo.daily.catch-up-delay-seconds:60}",
            fixedDelayString = "${robo.daily.check-interval-seconds:600}", timeUnit = TimeUnit.SECONDS)
    public void tick() {
        try {
            catchUp.runIfMissed();
        } catch (RuntimeException e) {
            log.error("놓친 작업 따라잡기 실패", e);
        }
        for (Periodic p : tasks) {
            try {
                p.tick();
            } catch (RuntimeException e) {
                log.error("주기 작업 실패: {}", p.name(), e);
            }
        }
    }
}
