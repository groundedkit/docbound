package com.groundedkit.docbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocboundApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocboundApplication.class, args);
	}

}
