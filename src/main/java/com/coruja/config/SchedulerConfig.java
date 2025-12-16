package com.coruja.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulerConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Define 5 threads disponíveis. Assim o FTP pode usar uma
        // e a atualização de localizações usa outra simultaneamente.
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("agendador-pool-cart-");
        scheduler.initialize();
        return scheduler;
    }
}
