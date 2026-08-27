package com.adam.server.scan;

import com.adam.server.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ScanCronDefaultsTest {

    static final String ALL_DAY_ALL_WEEK_M15 = "0 1,16,31,46 * * * *";

    @Value("${app.scan.cron}")
    String scanCron;

    @Value("${app.scan.zone}")
    String scanZone;

    @Test
    void resolvedCronIsEveryM15CloseAllWeek() {
        assertThat(scanCron).isEqualTo(ALL_DAY_ALL_WEEK_M15);
        assertThat(scanZone).isEqualTo("Europe/Warsaw");
    }

    @Test
    void appPropertiesDefaultMatchesResolvedCron() {
        assertThat(new AppProperties().getScan().getCron()).isEqualTo(ALL_DAY_ALL_WEEK_M15);
        assertThat(new AppProperties().getScan().getZone()).isEqualTo("Europe/Warsaw");
    }

    @Test
    void schedulerAnnotationDefaultIsAllDayAllWeekWarsaw() throws Exception {
        Method method = ScanScheduler.class.getDeclaredMethod("onM15Close");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.scan.cron:" + ALL_DAY_ALL_WEEK_M15 + "}");
        assertThat(scheduled.zone()).isEqualTo("${app.scan.zone:Europe/Warsaw}");
    }
}
