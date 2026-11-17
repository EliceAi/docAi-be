package com.team.docai.global.config;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.*;
@Configuration
public class TimeConfig {
 @Bean public Clock applicationClock(){return Clock.system(ZoneId.of("Asia/Seoul"));}
}
