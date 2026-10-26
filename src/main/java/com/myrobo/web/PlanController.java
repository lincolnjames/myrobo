package com.myrobo.web;

import com.myrobo.plan.PlanService;
import com.myrobo.plan.PlanViews.CreateResult;
import com.myrobo.plan.PlanViews.Overview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문안 화면용 API. 만들기(POST)는 X-Robo-Request 헤더가 있어야 통과한다 (LocalRequestGuard). */
@RestController
@RequestMapping(value = "/api/plans", produces = "application/json;charset=UTF-8")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @GetMapping
    public Overview overview() {
        return service.overview();
    }

    /** 검증 결과와 상관없이 200을 돌려준다. 결과는 outcome(SENT, REJECTED, NO_ACTION, ...)으로 구분한다. */
    @PostMapping
    public CreateResult create() {
        return service.create();
    }
}
