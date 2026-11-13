package com.example.board;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @ConfigurationPropertiesScan} 은 설정 바인딩 클래스를 그 값을 쓰는 패키지에 두게 한다
 * (sp-docs/plan/phase2-board.md B-16 의 산출물 — 예: {@code attachment/storage},
 * {@code post/support}). 등록을 한 설정 클래스에 모으면 {@code global} 이 기능 패키지를 알게 된다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BoardApplication {

    public static void main(String[] args) {
        SpringApplication.run(BoardApplication.class, args);
    }
}
