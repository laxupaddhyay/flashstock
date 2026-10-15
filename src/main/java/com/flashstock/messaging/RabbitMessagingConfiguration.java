package com.flashstock.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix="flashstock.messaging", name="enabled", havingValue="true", matchIfMissing=true)
public class RabbitMessagingConfiguration {
    public static final String EXCHANGE = "flashstock.events";
    public static final String LIFECYCLE_QUEUE = "flashstock.lifecycle";
    public static final String DEAD_EXCHANGE = "flashstock.events.dead";
    public static final String DEAD_QUEUE = "flashstock.lifecycle.dead";

    @Bean TopicExchange flashStockExchange() { return new TopicExchange(EXCHANGE, true, false); }
    @Bean DirectExchange flashStockDeadExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }
    @Bean Queue lifecycleQueue() {
        return QueueBuilder.durable(LIFECYCLE_QUEUE).deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(DEAD_QUEUE).build();
    }
    @Bean Queue lifecycleDeadQueue() { return QueueBuilder.durable(DEAD_QUEUE).build(); }
    @Bean Binding lifecycleBinding(Queue lifecycleQueue, TopicExchange flashStockExchange) {
        return BindingBuilder.bind(lifecycleQueue).to(flashStockExchange).with("lifecycle.#");
    }
    @Bean Binding lifecycleDeadBinding(Queue lifecycleDeadQueue, DirectExchange flashStockDeadExchange) {
        return BindingBuilder.bind(lifecycleDeadQueue).to(flashStockDeadExchange).with(DEAD_QUEUE);
    }
    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3).backOffOptions(200, 2.0, 2000)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        return factory;
    }
}
