package edu.bistu.campusmarket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CampusMarketApplication {
    public static void main(String[] args) {
        if (args.length == 1 && ("--init-admin".equals(args[0]) || "--reset-admin-password".equals(args[0]))) {
            System.exit(AdminBootstrap.run(System.console(), "--reset-admin-password".equals(args[0])));
            return;
        }
        SpringApplication.run(CampusMarketApplication.class, args);
    }
}
