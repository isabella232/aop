/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.amqp.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.streamnative.pulsar.handlers.amqp.AbstractAmqpExchange;
import io.streamnative.pulsar.handlers.amqp.impl.PersistentQueue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.common.naming.NamespaceName;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * RabbitMQ messaging test.
 */
@Slf4j
public class RabbitMQMessagingTest extends RabbitMQTestBase {

    @Test
    public void basic_consume_case() throws IOException, TimeoutException, InterruptedException {
        String exchangeName = "test-exchange";
        String routingKey = "test.key";

        try (Connection conn = getConnection()) {
            try (Channel channel = conn.createChannel()) {

                channel.exchangeDeclare(exchangeName, "direct", true);
                String queueName = channel.queueDeclare().getQueue();
                channel.queueBind(queueName, exchangeName, routingKey);

                List<String> messages = new ArrayList<>();
                channel.basicConsume(queueName, false, "myConsumerTag",
                        new DefaultConsumer(channel) {
                            @Override
                            public void handleDelivery(String consumerTag,
                                                       Envelope envelope,
                                                       AMQP.BasicProperties properties,
                                                       byte[] body) throws IOException {
                                long deliveryTag = envelope.getDeliveryTag();
                                messages.add(new String(body));
                                // (process the message components here ...)
                                channel.basicAck(deliveryTag, false);
                            }
                        });

                byte[] messageBodyBytes = "Hello, world!".getBytes();
                channel.basicPublish(exchangeName, routingKey, null, messageBodyBytes);

                TimeUnit.MILLISECONDS.sleep(200L);
                Assert.assertEquals(messages.get(0), "Hello, world!");
            }
        }
    }

    @Test
    void basic_get_case() throws IOException, TimeoutException {
        String exchangeName = "test-exchange";
        String routingKey = "test.key";

        try (Connection conn = getConnection()) {
            try (Channel channel = conn.createChannel()) {

                channel.exchangeDeclare(exchangeName, "direct", true);
                String queueName = channel.queueDeclare().getQueue();
                channel.queueBind(queueName, exchangeName, routingKey);

                byte[] messageBodyBytes = "Hello, world!".getBytes();
                channel.basicPublish(exchangeName, routingKey, null, messageBodyBytes);

                GetResponse response = channel.basicGet(queueName, false);
                if (response == null) {
                    Assert.fail("AMQP GetReponse must not be null");
                } else {
                    byte[] body = response.getBody();
                    Assert.assertEquals(new String(body), "Hello, world!");
                    long deliveryTag = response.getEnvelope().getDeliveryTag();

                    channel.basicAck(deliveryTag, false);
                }
            }
        }
    }

    @Test
    void basic_consume_nack_case() throws IOException, TimeoutException, InterruptedException {
        String exchangeName = "test-exchange";
        String routingKey = "test.key";

        AtomicInteger atomicInteger = new AtomicInteger();
        final Semaphore waitForAtLeastOneDelivery = new Semaphore(0);
        final Semaphore waitForCancellation = new Semaphore(0);

        try (Connection conn = getConnection()) {
            try (Channel channel = conn.createChannel()) {

                channel.exchangeDeclare(exchangeName, "direct", true);
                String queueName = channel.queueDeclare().getQueue();
                channel.queueBind(queueName, exchangeName, routingKey);

                channel.basicConsume(queueName, false, "myConsumerTag",
                        new DefaultConsumer(channel) {
                            @Override
                            public void handleDelivery(String consumerTag,
                                                       Envelope envelope,
                                                       AMQP.BasicProperties properties,
                                                       byte[] body) throws IOException {
                                waitForAtLeastOneDelivery.release();
                                long deliveryTag = envelope.getDeliveryTag();
                                atomicInteger.incrementAndGet();
                                channel.basicNack(deliveryTag, false, true);
                            }

                            @Override
                            public void handleCancel(String consumerTag) {
                                waitForCancellation.release();
                            }
                        });

                byte[] messageBodyBytes = "Hello, world!".getBytes();
                channel.basicPublish(exchangeName, routingKey, null, messageBodyBytes);
                waitForAtLeastOneDelivery.acquire();
            }
        }

        // WHEN after closing the connection and resetting the counter
        atomicInteger.set(0);

        waitForCancellation.acquire();

        // After connection closed, and Consumer cancellation, no message should be delivered anymore
        Assert.assertEquals(atomicInteger.get(), 0);
    }

    @Test
    void redelivered_message_should_have_redelivery_marked_as_true() throws IOException, TimeoutException,
            InterruptedException {
        try (Connection conn = getConnection()) {
            CountDownLatch messagesToBeProcessed = new CountDownLatch(2);
            try (Channel channel = conn.createChannel()) {
                channel.queueDeclare("fruits", true, true, false, new HashMap<>());
                AtomicReference<Envelope> redeliveredMessageEnvelope = new AtomicReference();

                channel.basicConsume("fruits", new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String consumerTag,
                                               Envelope envelope,
                                               AMQP.BasicProperties properties,
                                               byte[] body) throws IOException {
                        if (messagesToBeProcessed.getCount() == 1){
                            redeliveredMessageEnvelope.set(envelope);
                            messagesToBeProcessed.countDown();

                        } else {
                            channel.basicNack(envelope.getDeliveryTag(), false, true);
                            messagesToBeProcessed.countDown();
                        }

                    }
                });

                channel.basicPublish("", "fruits", null, "banana".getBytes());

                final boolean finishedProperly = messagesToBeProcessed.await(1000, TimeUnit.SECONDS);
                Assert.assertTrue(finishedProperly);
                Assert.assertNotNull(redeliveredMessageEnvelope.get());
                Assert.assertTrue(redeliveredMessageEnvelope.get().isRedeliver());
            }
        }
    }

    @Test(timeOut = 5000)
    private void inMemoryE2ETest() throws IOException, TimeoutException, InterruptedException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setHost("localhost");
        connectionFactory.setPort(5672);
        connectionFactory.setVirtualHost("vhost1");

        final String exchangeName = "ex1";
        final String queueName1 = "ex1-q1";
        final String queueName2 = "ex1-q2";
        final boolean durable = false;

        @Cleanup
        Connection connection = connectionFactory.newConnection();

        @Cleanup
        Channel channel = connection.createChannel();

        channel.exchangeDeclare(exchangeName, BuiltinExchangeType.FANOUT, durable);
        channel.queueDeclare(queueName1, durable, false, false, null);
        channel.queueDeclare(queueName2, durable, false, false, null);

        channel.queueBind(queueName1, exchangeName, "");
        channel.queueBind(queueName2, exchangeName, "");

        final String str = "Hello AMQP";
        channel.basicPublish(exchangeName, "", null, (str).getBytes(StandardCharsets.UTF_8));

        CountDownLatch countDownLatch = new CountDownLatch(2);
        channel.basicConsume(queueName1, (consumeTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            log.info("{} receive: {}", queueName1, message);
            Assert.assertEquals(message, str);
            countDownLatch.countDown();
        }, consumerTag -> {
        });

        channel.basicConsume(queueName2, (consumeTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            log.info("{} receive: {}", queueName2, message);
            Assert.assertEquals(message, str);
            countDownLatch.countDown();
        }, consumerTag -> {
        });
        countDownLatch.await();
    }

    @Test
    private void basicPublishTest() throws IOException, TimeoutException {
        final String queueName = "testQueue";
        final String message = "Hello AOP!";
        final int messagesNum = 10;

        @Cleanup
        PulsarAdmin pulsarAdmin = PulsarAdmin.builder().serviceHttpUrl("http://127.0.0.1:"
                + brokerWebservicePort).build();
        log.info("topics: {}", pulsarAdmin.topics());

        try (Connection connection = getConnection();
             Channel channel = connection.createChannel()) {
            channel.queueDeclare(queueName, true, false, false, null);
            for (int i = 0; i < messagesNum; i++) {
                channel.basicPublish("", queueName, null, message.getBytes());
                System.out.println("send message: " + message);
            }
        }

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder()
                .serviceUrl("pulsar://localhost:" + brokerPort).build();
        @Cleanup
        org.apache.pulsar.client.api.Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic("persistent://public/vhost1/" + AbstractAmqpExchange.DEFAULT_EXCHANGE_DURABLE)
                .subscriptionName("test")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        for (int i = 0; i < messagesNum; i++) {
            Message<byte[]> msg = consumer.receive();
            System.out.println("receive msg: " + new String(msg.getData()));
            Assert.assertEquals(new String(msg.getData()), message);
        }
    }

    @Test
    private void persistentExchangeAndQueueWriteTest() throws IOException, TimeoutException {
        final String vhost = "vhost1";
        final String exchangeName = "ex";
        final String queueName1 = "ex-q1";
        final String queueName2 = "ex-q2";

        @Cleanup
        Connection connection = getConnection();
        @Cleanup
        Channel channel = connection.createChannel();

        channel.exchangeDeclare(exchangeName, BuiltinExchangeType.FANOUT, true);
        channel.queueDeclare(queueName1, true, false, false, null);
        channel.queueBind(queueName1, exchangeName, "");
        channel.queueDeclare(queueName2, true, false, false, null);
        channel.queueBind(queueName2, exchangeName, "");

        String contentMsg = "Hello AOP!";
        channel.basicPublish(exchangeName, "", null, contentMsg.getBytes());

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl("pulsar://localhost:" + brokerPort).build();
        String exchangeTopic = "persistent://public/vhost1/ex";
        NamespaceName namespaceName = NamespaceName.get("public", vhost);
        String queueIndexTopic1 = PersistentQueue.getIndexTopicName(namespaceName, queueName1);
        String queueIndexTopic2 = PersistentQueue.getIndexTopicName(namespaceName, queueName2);

        @Cleanup
        org.apache.pulsar.client.api.Consumer<byte[]> exchangeConsumer =
                pulsarClient.newConsumer()
                        .topic(exchangeTopic)
                        .subscriptionName("test-sub")
                        .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                        .subscribe();
        @Cleanup
        org.apache.pulsar.client.api.Consumer<byte[]> queueIndexConsumer1 =
                pulsarClient.newConsumer()
                        .topic(queueIndexTopic1)
                        .subscriptionName("test-sub")
                        .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                        .subscribe();
        @Cleanup
        org.apache.pulsar.client.api.Consumer<byte[]> queueIndexConsumer2 =
                pulsarClient.newConsumer()
                        .topic(queueIndexTopic2)
                        .subscriptionName("test-sub")
                        .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                        .subscribe();

        Message<byte[]> message = exchangeConsumer.receive();
        final long ledgerId = ((MessageIdImpl) message.getMessageId()).getLedgerId();
        final long entryId = ((MessageIdImpl) message.getMessageId()).getEntryId();
        log.info("[{}] receive messageId: {}, msg: {}",
                exchangeTopic, message.getMessageId(), new String(message.getData()));
        Assert.assertEquals(new String(message.getData()), contentMsg);

        message = queueIndexConsumer1.receive();
        ByteBuf byteBuf1 = Unpooled.wrappedBuffer(message.getData());
        Assert.assertEquals(ledgerId, byteBuf1.readLong());
        Assert.assertEquals(entryId, byteBuf1.readLong());

        message = queueIndexConsumer2.receive();
        ByteBuf byteBuf2 = Unpooled.wrappedBuffer(message.getData());
        Assert.assertEquals(ledgerId, byteBuf2.readLong());
        Assert.assertEquals(entryId, byteBuf2.readLong());
    }

    @Test
    private void fanoutConsumeTest() throws IOException, TimeoutException, InterruptedException {

        final String vhost = "vhost1";
        final String exchangeName = "ex1";
        final String queueName1 = "ex1-q1";
        final String queueName2 = "ex1-q2";

        @Cleanup
        Connection connection = getConnection();
        @Cleanup
        Channel channel = connection.createChannel();

        channel.exchangeDeclare(exchangeName, BuiltinExchangeType.FANOUT, true);
        channel.queueDeclare(queueName1, true, false, false, null);
        channel.queueBind(queueName1, exchangeName, "");
        channel.queueDeclare(queueName2, true, false, false, null);
        channel.queueBind(queueName2, exchangeName, "");

        String contentMsg = "Hello AOP!";
        channel.basicPublish(exchangeName, "", null, contentMsg.getBytes());

        final AtomicInteger count = new AtomicInteger(0);

        @Cleanup
        Channel channel1 = connection.createChannel();
        Consumer consumer1 = new DefaultConsumer(channel1) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                                       byte[] body) throws IOException {
                String message = new String(body, "UTF-8");
                System.out.println(" [x] Received Consumer '" + message + "'");
                count.incrementAndGet();
            }
        };
        channel1.basicConsume(queueName1, true, consumer1);

        @Cleanup
        Channel channel2 = connection.createChannel();
        Consumer consumer2 = new DefaultConsumer(channel2) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                                       byte[] body) throws IOException {
                String message = new String(body, "UTF-8");
                System.out.println(" [x] Received Consumer '" + message + "'");
                count.incrementAndGet();
            }
        };
        channel2.basicConsume(queueName2, true, consumer2);
        Thread.sleep(1000);
        //Assert.assertTrue(count.get() == 2);

    }
}