package com.morawski.dev.falcon;

import org.springframework.boot.SpringApplication;

public class TestFalconApplication {

	public static void main(String[] args) {
		SpringApplication.from(FalconApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
