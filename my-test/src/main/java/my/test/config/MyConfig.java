package my.test.config;

import my.test.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MyConfig {

	@Autowired
	UserService userService;


}
