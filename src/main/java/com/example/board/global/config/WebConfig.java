package com.example.board.global.config;

import com.example.board.global.security.CurrentMemberArgumentResolver;
import com.example.board.global.security.OptionalCurrentMemberArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 확장 지점. 인증 주체를 컨트롤러 파라미터로 채우는 리졸버를 등록한다 —
 * {@code @CurrentMember} 와 선택적 인증의 {@code @OptionalCurrentMember}
 * (sp-docs/conventions.md §1.3).
 *
 * <p><b>CORS 는 여기가 아니라 {@link SecurityConfig} 에 있다.</b> preflight(OPTIONS)는 인가
 * 필터보다 앞에서 처리돼야 하므로 Security 필터 체인의 CORS 설정을 쓴다. MVC 쪽에만 두면
 * preflight 가 401 로 막힌다 (sp-docs/security.md §1.1).
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

	private final CurrentMemberArgumentResolver currentMemberArgumentResolver;

	private final OptionalCurrentMemberArgumentResolver optionalCurrentMemberArgumentResolver;

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this.currentMemberArgumentResolver);
		resolvers.add(this.optionalCurrentMemberArgumentResolver);
	}

}
