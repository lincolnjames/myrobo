package com.myrobo.web;

import com.myrobo.holdings.HoldingsService;
import com.myrobo.holdings.HoldingsService.PortfolioResponse;
import com.myrobo.holdings.SnapshotInput;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 잔고 입력 화면(static/index.html)이 쓰는 API. */
@RestController
@RequestMapping(value = "/api/portfolio", produces = "application/json;charset=UTF-8")
public class PortfolioController {

    private final HoldingsService service;

    public PortfolioController(HoldingsService service) {
        this.service = service;
    }

    @GetMapping
    public PortfolioResponse get() {
        return service.view();
    }

    /** 성공하면 갱신된 상태를, 입력이 잘못됐으면 400과 문제 목록 전체를 돌려준다. */
    @PutMapping("/snapshot")
    public ResponseEntity<Object> save(@RequestBody SnapshotInput input) {
        SnapshotInput.Result r = service.save(input);
        if (!r.ok()) {
            return ResponseEntity.badRequest().body(Map.<String, List<String>>of("errors", r.errors()));
        }
        return ResponseEntity.ok(service.view());
    }
}
