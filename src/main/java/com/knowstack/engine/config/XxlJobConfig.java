package com.knowstack.engine.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(
            @Value("${xxl.job.admin.addresses}") String adminAddresses,
            @Value("${xxl.job.executor.appname}") String appname,
            @Value("${xxl.job.executor.port}") int port,
            @Value("${xxl.job.executor.ip:}") String ip,
            @Value("${xxl.job.executor.logpath}") String logPath,
            @Value("${xxl.job.accessToken}") String accessToken) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setPort(port);
        if (!ip.isBlank()) {
            executor.setIp(ip);
        }
        executor.setLogPath(logPath);
        executor.setAccessToken(accessToken);
        return executor;
    }
}
