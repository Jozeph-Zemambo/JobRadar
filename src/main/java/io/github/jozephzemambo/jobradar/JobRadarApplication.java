package io.github.jozephzemambo.jobradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class JobRadarApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(JobRadarApplication.class, args);
        // In one-shot crawl mode, exit with CrawlRunner's code so schedulers can detect a failed crawl.
        if (context.getEnvironment().matchesProfiles("crawl")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
