package io.reconguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages="io.reconguard")
public class AiWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiWorkerApplication.class, args);
    }
}