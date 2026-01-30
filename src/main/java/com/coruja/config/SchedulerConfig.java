package com.coruja.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
public class SchedulerConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // Força o registrar a usar o nosso Bean customizado
        taskRegistrar.setTaskScheduler(taskScheduler());
    }

    // A mágica acontece aqui: Expor como @Bean garante que o Spring
    // substitua o agendador padrão em todo o contexto da aplicação.
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5); // 5 Threads garantidas
        scheduler.setThreadNamePrefix("agendador-pool-cart-");
        scheduler.initialize();
        return scheduler;
    }
}