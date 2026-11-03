package com.knowstack.engine;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan({
    "com.knowstack.engine.document.mapper",
    "com.knowstack.engine.chat.mapper",
    "com.knowstack.engine.business.mapper",
    "com.knowstack.engine.rag.mapper"
})
@SpringBootApplication
public class KnowEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowEngineApplication.class, args);
    }
}
