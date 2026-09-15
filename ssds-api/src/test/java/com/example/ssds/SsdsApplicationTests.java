package com.example.ssds;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Spring context 能不能組起來。
 *
 * <p><b>Flyway 必須關掉</b>：這支測試連的是共用的 Supabase 資料庫，開著 Flyway 會讓
 * 「跑一次單元測試」變成「對共用資料庫套用 migration」——2026-09-11 就這樣把一支
 * 還在審查中的 migration 套進去了。application.properties 的
 * {@code SSDS_FLYWAY_ENABLED} 預設為 false，但套用 migration 的人會在自己的
 * .env 設成 true，於是那台機器上跑測試就會意外動到共用 schema。
 *
 * <p>在這裡寫死 false 而不是靠環境變數：測試不該因為「誰在跑」而有不同的副作用。
 * 要套用 migration 就明確地跑 migration，不要順便。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class SsdsApplicationTests {

	@Test
	void contextLoads() {
	}

}
