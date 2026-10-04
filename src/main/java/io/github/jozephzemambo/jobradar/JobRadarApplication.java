package io.github.jozephzemambo.jobradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class JobRadarApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(JobRadarApplication.class, args);
        // One-shot modes exit with their runner's code so schedulers and scripts can detect failure.
        if (context.getEnvironment().matchesProfiles("crawl | export")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
