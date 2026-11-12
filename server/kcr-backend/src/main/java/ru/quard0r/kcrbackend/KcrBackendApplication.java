package ru.quard0r.kcrbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KcrBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(KcrBackendApplication.class, args);
    }

}
