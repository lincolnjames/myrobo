package com.myrobo.broker;

import com.myrobo.broker.DispatchStore.Mode;
import com.myrobo.collector.MarketCalendar;
import com.myrobo.collector.http.Sleeper;
import com.myrobo.ops.Notifier;
import com.myrobo.ops.Periodic;
import com.myrobo.plan.PlanRepository;
import com.myrobo.plan.PlanService;
import com.myrobo.plan.PlanViews.PlanView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** 증권사 전송 조립. 꺼져 있으면(기본) 키가 없어도 앱이 뜨고 아무것도 보내지 않는다. */
@Configuration
@EnableConfigurationProperties(BrokerProperties.class)
public class BrokerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BrokerConfiguration.class);

    @Bean
    OrderDispatcher orderDispatcher(BrokerProperties p, JdbcClient jdbc, PlanService plans, PlanRepository repo,
                                    Notifier notifier, Clock clock) {
        Mode mode = mode(p.mode());
        Supplier<KisClient> kis = () -> {
            throw new IllegalStateException("증권사 전송이 꺼져 있음");
        };
        if (mode == null) {
            log.info("증권사 전송 꺼짐 (robo.broker.mode=off)");
        } else {
            BrokerProperties.Kis k = p.kis();
            if (!"paper".equalsIgnoreCase(k.env() == null ? "" : k.env().strip())) {
                throw new IllegalStateException("robo.broker.mode=" + p.mode()
                        + "는 모의투자 키(KIS_MODE=paper)로만 켤 수 있어요. 실전은 아직 지원하지 않아요");
            }
            KisClient client = new KisClient(new JdkKisTransport(Duration.ofSeconds(15)), KisClient.Env.PAPER,
                    k.appKey(), k.appSecret(), k.account(), clock, Sleeper.real());
            kis = () -> client;
            log.info("증권사 전송 켜짐: {} (한국투자증권 모의투자, 지정가 여유 {}%, 한 건 ${}, 하루 ${})",
                    mode, p.limitBufferPct(), p.maxOrderUsd(), p.maxDailyUsd());
        }
        OrderDispatcher.PlanSource source = new OrderDispatcher.PlanSource() {
            @Override
            public Optional<PlanView> openPlan() {
                return Optional.ofNullable(plans.overview().open());
            }

            @Override
            public MarketCalendar calendar() {
                return new MarketCalendar(repo.fullDayClosures());
            }
        };
        Map<String, String> exchanges = p.exchanges() == null ? Map.of() : Map.copyOf(p.exchanges());
        return new OrderDispatcher(mode, kis, new JdbcDispatchStore(jdbc), source, notifier,
                new OrderPlanner.Limits(p.limitBufferPct(), p.maxOrderUsd(), p.maxDailyUsd(), exchanges), clock);
    }

    @Bean
    Periodic brokerDispatch(OrderDispatcher dispatcher) {
        return new Periodic() {
            @Override
            public String name() {
                return "증권사 전송";
            }

            @Override
            public void tick() {
                dispatcher.tick();
            }
        };
    }

    /** off(또는 빈 값)면 null. 모르는 값이면 시작을 거부한다 (오타로 실제 전송이 켜지거나 꺼지지 않게). */
    static Mode mode(String v) {
        String s = v == null ? "" : v.strip().toLowerCase();
        return switch (s) {
            case "", "off" -> null;
            case "dry-run" -> Mode.DRY_RUN;
            case "paper" -> Mode.PAPER;
            default -> throw new IllegalStateException("robo.broker.mode는 off, dry-run, paper 중 하나여야 해요: " + v);
        };
    }
}
