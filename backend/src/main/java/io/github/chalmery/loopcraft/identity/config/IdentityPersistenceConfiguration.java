package io.github.chalmery.loopcraft.identity.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import io.github.chalmery.loopcraft.identity.mapper.UserMapper;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
@MapperScan(basePackageClasses = UserMapper.class)
public class IdentityPersistenceConfiguration {
    /** 使用框架生成 PostgreSQL 分页语句。 */
    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        var pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(100L);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
