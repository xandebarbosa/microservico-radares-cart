package com.coruja;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableCaching
@EntityScan(basePackages = {"com.coruja.entities"})
public class MicroservicoRadaresCartApplication {

	public static void main(String[] args) {
		SpringApplication.run(MicroservicoRadaresCartApplication.class, args);
	}

}
