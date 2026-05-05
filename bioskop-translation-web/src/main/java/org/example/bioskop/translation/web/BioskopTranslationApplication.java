package org.example.bioskop.translation.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.example.bioskop.translation")
public class BioskopTranslationApplication {
    public static void main(String[] args) {
        SpringApplication.run(BioskopTranslationApplication.class, args);
    }
}
