package com.example.board.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Swagger UI 는 /swagger-ui.html 에 있다 (sp-docs/tech-stack.md §4.4, sp-docs/nfr.md).
// /swagger-ui/** 와 /v3/api-docs/** 를 permitAll 로 여는 것은 B-04 의 SecurityConfig 다.
// prod 프로파일에서는 springdoc 자체를 꺼서 경로를 없애며, 그 확인은 B-09 가 한다
// (sp-docs/security.md §5.1.1).
@Configuration
public class SwaggerConfig {

	@Bean
	public OpenAPI boardServiceOpenApi() {
		return new OpenAPI().info(new Info()
				.title("board-service API")
				.description("게시글과 댓글. 작성자 닉네임은 스냅샷으로 복제 저장한다.")
				.version("v1"));
	}

}
