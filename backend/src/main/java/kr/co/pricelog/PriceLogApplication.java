package kr.co.pricelog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 프라이스로그 백엔드의 Spring Boot 진입점. */
@SpringBootApplication
public class PriceLogApplication {

  /** 명령줄 인자를 Spring Boot에 전달해 서버를 시작한다. */
  public static void main(String[] args) {
    SpringApplication.run(PriceLogApplication.class, args);
  }
}
