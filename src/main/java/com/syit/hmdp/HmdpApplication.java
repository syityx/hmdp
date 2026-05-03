package com.syit.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.syit.hmdp.mapper")
public class HmdpApplication {

	public static void main(String[] args) {
		System.out.println("JDK version: " + System.getProperty("java.version"));
		SpringApplication.run(HmdpApplication.class, args);
	}

}
