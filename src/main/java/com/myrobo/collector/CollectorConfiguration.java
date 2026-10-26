package com.myrobo.collector;

import com.myrobo.collector.http.HttpFetcher;
import com.myrobo.collector.http.JdkHttpFetcher;
import com.myrobo.collector.http.RateLimiter;
import com.myrobo.collector.http.Sleeper;
import com.myrobo.collector.source.FinnhubClient;
import com.myrobo.collector.source.FrankfurterClient;
import com.myrobo.collector.source.KoreaEximClient;
import com.myrobo.collector.source.TiingoClient;
import com.myrobo.collector.store.JdbcCollectorStores;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

/** 수집 모듈 조립. 클래스들은 Spring을 모르고, 여기서만 엮는다. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorConfiguration {

    @Bean
    HttpFetcher httpFetcher() {
        return new JdkHttpFetcher(Duration.ofSeconds(30), Sleeper.real());
    }

    @Bean
    CollectorService collectorService(CollectorProperties p, HttpFetcher http, JdbcClient jdbc, JdbcTemplate template) {
        var tiingoLimit = new RateLimiter("Tiingo", p.tiingo().perHour(), p.tiingo().perDay(),
                Clock.systemUTC(), Sleeper.real());
        var tiingo = new TiingoClient(http, tiingoLimit, p.tiingo().baseUrl(), p.tiingo().key());
        var prices = new PriceCollector(tiingo, new JdbcCollectorStores.Prices(jdbc, template), p.backfillStart());
        var fx = new FxResolver(
                new KoreaEximClient(http, p.koreaexim().baseUrl(), p.koreaexim().key()),
                new FrankfurterClient(http, p.frankfurter().baseUrl()));
        return new CollectorService(
                new FinnhubClient(http, p.finnhub().baseUrl(), p.finnhub().key()),
                new JdbcCollectorStores.Holidays(jdbc, template),
                prices, fx, new JdbcCollectorStores.Fx(jdbc));
    }
}
