package com.hexghost.apod;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(ApodProperties.class)
public class ApodApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(ApodApplication.class, args);
        int code = context.getBean(Pipeline.class).exitCode();
        System.exit(SpringApplication.exit(context, () -> code));
    }
    @Bean(destroyMethod = "close") Pipeline pipeline(ApodProperties config) { return new Pipeline(config); }
    @Bean ApplicationRunner run(Pipeline pipeline) { return args -> pipeline.run(); }
}
