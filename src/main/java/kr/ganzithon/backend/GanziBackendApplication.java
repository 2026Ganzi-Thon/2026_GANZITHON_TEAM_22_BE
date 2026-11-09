package kr.ganzithon.backend;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GanziBackendApplication {
    public static void main(String[] args) { SpringApplication.run(GanziBackendApplication.class, args); }
    @Bean public Clock clock() { return Clock.systemUTC(); }
}
