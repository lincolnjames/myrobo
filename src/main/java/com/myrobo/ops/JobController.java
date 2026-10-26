package com.myrobo.ops;

import com.myrobo.collector.CollectorJob;
import com.myrobo.collector.CollectorTypes.CollectResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배치를 손으로 돌리는 내부용 엔드포인트. 서버가 127.0.0.1에만 열려 있어 외부에서는 접근할 수 없다.
 * 예) curl -X POST http://localhost:8080/internal/jobs/collect
 */
@RestController
@RequestMapping("/internal/jobs")
public class JobController {

    private final CollectorJob collector;

    public JobController(CollectorJob collector) {
        this.collector = collector;
    }

    /** charset을 명시해야 Windows PowerShell(Invoke-RestMethod)이 한글을 깨지 않고 읽는다. */
    @PostMapping(value = "/collect", produces = "application/json;charset=UTF-8")
    public CollectResult collect() {
        return collector.run();
    }
}
