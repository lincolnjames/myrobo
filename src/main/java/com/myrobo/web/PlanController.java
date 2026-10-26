package com.myrobo.web;

import com.myrobo.plan.ExecutionInput;
import com.myrobo.plan.ExecutionService;
import com.myrobo.plan.PlanService;
import com.myrobo.plan.PlanViews.CreateResult;
import com.myrobo.plan.PlanViews.ExecutionResult;
import com.myrobo.plan.PlanViews.Overview;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문안 화면용 API. 만들기(POST)는 X-Robo-Request 헤더가 있어야 통과한다 (LocalRequestGuard). */
@RestController
@RequestMapping(value = "/api/plans", produces = "application/json;charset=UTF-8")
public class PlanController {

    private final PlanService service;
    private final ExecutionService executions;

    public PlanController(PlanService service, ExecutionService executions) {
        this.service = service;
        this.executions = executions;
    }

    @GetMapping
    public Overview overview() {
        return service.overview();
    }

    /** 체결 입력. 저장되면 200, 입력이 잘못됐으면 400, 받을 수 없는 상태면 409. 본문은 항상 ExecutionResult. */
    @PostMapping("/{id}/executions")
    public ResponseEntity<ExecutionResult> execute(@PathVariable long id, @RequestBody ExecutionInput input) {
        ExecutionResult r = executions.save(id, input);
        return switch (r.status()) {
            case SAVED -> ResponseEntity.ok(r);
            case INVALID -> ResponseEntity.badRequest().body(r);
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(r);
        };
    }

    /** 검증 결과와 상관없이 200을 돌려준다. 결과는 outcome(SENT, REJECTED, NO_ACTION, ...)으로 구분한다. */
    @PostMapping
    public CreateResult create() {
        return service.create();
    }
}
