package org.tanzu.goose.cf.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tanzu.goose.cf.GooseExecutor;
import org.tanzu.goose.cf.GooseExecutorImpl;

/**
 * Spring Boot auto-configuration for Goose CLI integration.
 * <p>
 * This auto-configuration is activated when:
 * </p>
 * <ul>
 *   <li>The {@link GooseExecutor} class is on the classpath</li>
 *   <li>The property {@code goose.enabled} is {@code true} (default)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>
 * Add the goose-cf-wrapper dependency to your Spring Boot application:
 * </p>
 * <pre>{@code
 * <dependency>
 *     <groupId>org.tanzu.goose</groupId>
 *     <artifactId>goose-cf-wrapper</artifactId>
 *     <version>1.0.0</version>
 * </dependency>
 * }</pre>
 * <p>
 * Then inject the {@link GooseExecutor} bean:
 * </p>
 * <pre>{@code
 * @Autowired
 * private GooseExecutor gooseExecutor;
 * 
 * public void processWithGoose(String prompt) {
 *     String result = gooseExecutor.execute(prompt);
 *     // ...
 * }
 * }</pre>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
@Configuration
@ConditionalOnClass(GooseExecutor.class)
@ConditionalOnProperty(name = "goose.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GooseProperties.class)
public class GooseAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(GooseAutoConfiguration.class);

    /**
     * Creates the default {@link GooseExecutor} bean.
     * <p>
     * The executor is configured using environment variables:
     * </p>
     * <ul>
     *   <li>GOOSE_CLI_PATH - Path to the Goose CLI executable</li>
     *   <li>Provider API keys (ANTHROPIC_API_KEY, OPENAI_API_KEY, etc.)</li>
     * </ul>
     *
     * @param properties the Goose configuration properties
     * @return a configured GooseExecutor instance
     */
    @Bean
    @ConditionalOnMissingBean
    public GooseExecutor gooseExecutor(GooseProperties properties) {
        logger.info("Creating GooseExecutor bean");

        try {
            GooseExecutorImpl executor = new GooseExecutorImpl();

            if (executor.isAvailable()) {
                String version = executor.getVersion();
                logger.info("Goose CLI available, version: {}", version);
            } else {
                logger.warn("Goose CLI not available - check GOOSE_CLI_PATH and provider credentials");
            }

            return executor;
        } catch (IllegalStateException e) {
            logger.error("Failed to create GooseExecutor: {}", e.getMessage());
            throw e;
        }
    }
}

