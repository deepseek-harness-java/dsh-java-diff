package cn.xiaofuge.dsh.diff;

import cn.xiaofuge.dsh.diff.config.DiffNarratorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DiffNarratorProperties.class)
public class DshJavaDiffNarratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaDiffNarratorApplication.class, args);
    }
}
