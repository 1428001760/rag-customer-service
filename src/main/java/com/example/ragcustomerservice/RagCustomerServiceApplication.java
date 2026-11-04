package com.example.ragcustomerservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.ragcustomerservice.mapper") // 加这行，扫描Mapper接口
public class RagCustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagCustomerServiceApplication.class, args);
    }
}
