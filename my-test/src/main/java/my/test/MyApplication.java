package my.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MyApplication {

	public static void main(String[] args) {
		/*SpringApplicationBuilder springApplicationBuilder = new SpringApplicationBuilder(MyApplication.class).lazyInitialization(true);
		springApplicationBuilder.run(args);*/
		SpringApplication.run(MyApplication.class, args);
	}
}
