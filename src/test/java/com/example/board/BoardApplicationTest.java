package com.example.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@SpringBootTest
@ActiveProfiles("test")
@TestConstructor(autowireMode = AutowireMode.ALL)
class BoardApplicationTest {

    private final JPAQueryFactory jpaQueryFactory;

    BoardApplicationTest(JPAQueryFactory jpaQueryFactory) {
        this.jpaQueryFactory = jpaQueryFactory;
    }

    @Test
    @DisplayName("컨텍스트가 예외 없이 로딩된다")
    void 컨텍스트가_로딩된다() {
    }

    @Test
    @DisplayName("JPAQueryFactory 빈이 주입된다")
    void JPAQueryFactory_빈이_주입된다() {
        assertThat(jpaQueryFactory).isNotNull();
    }

}
