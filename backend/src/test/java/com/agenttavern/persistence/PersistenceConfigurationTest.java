package com.agenttavern.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agenttavern.tournament.port.TournamentStore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PersistenceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JdbcClientAutoConfiguration.class,
                    PersistenceConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withUserConfiguration(SafeJdbcInfrastructure.class);

    @Test
    void autoConfigurationCreatesTheStoreAfterJdbcAndActivatesItsTransactionProxy() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JdbcClient.class);
            assertThat(context).hasSingleBean(JdbcTournamentStore.class);
            assertThat(context).hasSingleBean(TournamentStore.class);

            JdbcTournamentStore store = context.getBean(JdbcTournamentStore.class);
            assertThat(context.getBean(TournamentStore.class)).isSameAs(store);
            assertThat(AopUtils.isCglibProxy(store)).isTrue();
            assertThat(((Advised) store).getAdvisors())
                    .anySatisfy(advisor -> assertThat(advisor.getAdvice())
                            .isInstanceOf(TransactionInterceptor.class));

            assertThatThrownBy(() -> store.commit(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("commit");
            CountingTransactionManager transactionManager = context.getBean(CountingTransactionManager.class);
            assertThat(transactionManager.started()).isEqualTo(1);
            assertThat(transactionManager.rolledBack()).isEqualTo(1);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class SafeJdbcInfrastructure {

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
            return new NamedParameterJdbcTemplate(mock(DataSource.class));
        }

        @Bean
        CountingTransactionManager transactionManager() {
            return new CountingTransactionManager();
        }
    }

    static final class CountingTransactionManager implements PlatformTransactionManager {
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger rolledBack = new AtomicInteger();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            started.incrementAndGet();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // The regression invokes a failing method, so commit is intentionally not expected.
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack.incrementAndGet();
        }

        int started() {
            return started.get();
        }

        int rolledBack() {
            return rolledBack.get();
        }
    }
}
