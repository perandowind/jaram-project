package com.jaram.be.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jaram.notifications.enabled", havingValue = "true")
class NotificationMessaging {
    static final String EVENT_EXCHANGE_NAME = "jaram.notifications.events";
    static final String NOTIFICATION_QUEUE_NAME = "jaram.notifications";
    static final String DEAD_LETTER_EXCHANGE_NAME = "jaram.notifications.dead";
    static final String DEAD_LETTER_QUEUE_NAME = "jaram.notifications.dlq";

    private static final int CONSUMER_PREFETCH_COUNT = 10;
    private static final int MAX_CONSUMER_ATTEMPTS = 3;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 250;
    private static final double RETRY_DELAY_MULTIPLIER = 2;
    private static final long MAX_RETRY_DELAY_MILLIS = 1000;

    @Bean
    Declarables notificationTopology() {
        DirectExchange eventExchange = new DirectExchange(EVENT_EXCHANGE_NAME);
        DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE_NAME);
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE_NAME).quorum().build();
        Queue notificationQueue = QueueBuilder.durable(NOTIFICATION_QUEUE_NAME).quorum()
                .deadLetterExchange(DEAD_LETTER_EXCHANGE_NAME)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE_NAME)
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .build();
        List<Declarable> declarations = new ArrayList<>(List.of(
                eventExchange, deadLetterExchange, notificationQueue, deadLetterQueue,
                BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_QUEUE_NAME)));
        for (NotificationType type : NotificationType.values()) {
            declarations.add(BindingBuilder.bind(notificationQueue).to(eventExchange).with(type.routingKey()));
        }
        return new Declarables(declarations);
    }

    @Bean
    SimpleRabbitListenerContainerFactory notificationListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(CONSUMER_PREFETCH_COUNT);
        factory.setAdviceChain(notificationRetryAdvice());
        return factory;
    }

    private Advice notificationRetryAdvice() {
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(MAX_CONSUMER_ATTEMPTS)
                .exponentialBackoff(INITIAL_RETRY_DELAY_MILLIS, RETRY_DELAY_MULTIPLIER, MAX_RETRY_DELAY_MILLIS)
                .retryOn(TransientDataAccessException.class)
                .retryOn(DataAccessResourceFailureException.class)
                .retryOn(CannotCreateTransactionException.class)
                .traversingCauses()
                .build();
        return RetryInterceptorBuilder.stateless()
                .retryOperations(retry)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler notificationScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("notification-publisher-");
        return scheduler;
    }

    @Bean
    NotificationPublisher notificationPublisher(
            NotificationOutbox outbox, RabbitTemplate rabbitTemplate, TransactionTemplate transactionTemplate) {
        return new NotificationPublisher(outbox, rabbitTemplate, transactionTemplate);
    }

    @Bean
    NotificationConsumer notificationConsumer(ObjectMapper objectMapper, NotificationStore notificationStore) {
        return new NotificationConsumer(objectMapper, notificationStore);
    }
}
