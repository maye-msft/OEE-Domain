package org.point85.domain.messaging;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * Class to connect to RMQ, publish and subscribe to message
 *
 */
public class MessagingClient extends BaseMessagingClient {
	// logger
	private static final Logger logger = LoggerFactory.getLogger(MessagingClient.class);

	// auto-ack on consume
	private static final boolean AUTO_ACK = false;

	// multiple acknowledgement of messages
	public static final boolean ACK_MULTIPLE = false;

	// use a topic exchange
	private static final String EXCHANGE_TYPE = "topic";

	private static final String EXCHANGE_NAME = "Point85";

	// durable exchange
	private static final boolean DURABLE_EXCHANGE = true;

	private String bindingKey;

	// RMQ objects
	private ConnectionFactory factory;
	private Connection connection;
	protected Channel channel;
	private String consumerTag;

	// for blocking (RPC) style calls
	private String replyQueueName;

	// listener to call back when message received
	private MessageListener listener;

	public MessagingClient() {
		// nothing to initialize
	}

	public String getBindingKey() {
		return bindingKey;
	}

	public void setBindingKey(String bindingKey) {
		this.bindingKey = bindingKey;
	}

	public Channel getChannel() {
		return this.channel;
	}

	public void registerListener(MessageListener listener) {
		this.listener = listener;
	}

	public void unregisterListener(MessageListener listener) {
		this.listener = null;
	}

	public void startUp(String hostName, int port, String userName, String password, String queueName,
			List<RoutingKey> routingKeys, MessageListener listener) throws Exception {
		// connect to broker
		connect(hostName, port, userName, password);

		// add listener
		registerListener(listener);

		// subscribe to messages
		subscribe(queueName, routingKeys);
	}

	public void connect(String hostName, int port, String userName, String password) throws Exception {
		if (logger.isInfoEnabled()) {
			logger.info("Connecting to RMQ broker host " + hostName + " on port " + port + ", user " + userName
					+ ", exchange " + EXCHANGE_NAME);
		}

		// factory
		factory = new ConnectionFactory();
		factory.setHost(hostName);
		factory.setPort(port);
		factory.setUsername(userName);
		factory.setPassword(password);

		// connection
		connection = factory.newConnection();

		// channel
		channel = connection.createChannel();

		// durable exchange
		channel.exchangeDeclare(EXCHANGE_NAME, EXCHANGE_TYPE, DURABLE_EXCHANGE);

		if (logger.isInfoEnabled()) {
			logger.info(
					"Connected to broker host " + hostName + " on port " + port + ", exchange " + EXCHANGE_NAME);
		}
	}

	public void shutDown() throws Exception {
		if (channel != null) {
			if (consumerTag != null) {
				try {
					channel.basicCancel(consumerTag);
				} catch (Exception e) {
				}
			}

			if (channel.isOpen()) {
				try {
					channel.close();
				} catch (Exception e) {
				}
			}
			channel = null;
		}

		if (connection != null) {
			if (connection.isOpen()) {
				connection.close();
			}
			connection = null;
		}

		if (logger.isInfoEnabled()) {
			logger.info("Disconnected from " + factory.getHost() + " on port " + factory.getPort());
		}
	}

	private void sendMessage(ApplicationMessage message, String routingKey, BasicProperties properties)
			throws Exception {
		if (channel == null) {
			logger.error("Can't send message: " + message + ".  The channel is null.");
			return;
		}

		// payload is JSON string
		String payload = serialize(message);

		// publish with this routing key
		channel.basicPublish(EXCHANGE_NAME, routingKey, properties, payload.getBytes());
	}

	public void publish(ApplicationMessage message, RoutingKey routingKey, int ttlSec) throws Exception {
		// validate
		message.validate();

		// use type field to ID the message
		BasicProperties properties = new BasicProperties.Builder().type(message.getMessageType().toString())
				.correlationId(UUID.randomUUID().toString()).build();

		// TTL in msec
		properties.builder().expiration(String.valueOf(ttlSec * 1000));

		// send the message with these properties
		sendMessage(message, routingKey.getKey(), properties);
	}

	private void subscribe(String queueName, List<RoutingKey> routingKeys) throws Exception {
		// TTL
		Map<String, Object> args = new HashMap<String, Object>();
		args.put("x-message-ttl", QUEUE_TTL_SEC * 1000);

		// not durable, non-exclusive queue, autodelete with TTL
		channel.queueDeclare(queueName, false, false, true, args);

		for (RoutingKey routingKey : routingKeys) {
			// bind to key
			channel.queueBind(queueName, EXCHANGE_NAME, routingKey.getKey());
		}

		// create a message receiver
		Receiver consumer = new Receiver();

		// start consuming messages
		consumerTag = channel.basicConsume(queueName, AUTO_ACK, consumer);

		if (logger.isInfoEnabled()) {
			String keys = "";
			for (RoutingKey routingKey : routingKeys) {
				if (keys.length() > 0) {
					keys += ", ";
				}
				keys += routingKey;
			}
			logger.info("Subscribed to queue " + queueName + " with routing key(s) " + keys);
		}
	}

	public void createRpcQueue() throws IOException {
		replyQueueName = channel.queueDeclare().getQueue();

		Consumer consumer = new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
					byte[] body) throws IOException {
				AMQP.BasicProperties replyProps = new AMQP.BasicProperties.Builder()
						.correlationId(properties.getCorrelationId()).build();

				String response = new String(body, "UTF-8");

				channel.basicPublish(EXCHANGE_NAME, properties.getReplyTo(), replyProps, response.getBytes("UTF-8"));

				channel.basicAck(envelope.getDeliveryTag(), false);
			}
		};

		channel.basicConsume(replyQueueName, true, consumer);
	}

	// RPC-style blocking call. Publish message, then wait for response.
	public ApplicationMessage call(ApplicationMessage message, String routingKey) throws Exception {
		ApplicationMessage replyMessage = null;
		String correlationId = UUID.randomUUID().toString();

		// use type field to ID the message, reply on the reply queue
		BasicProperties properties = new BasicProperties.Builder().type(message.getMessageType().toString())
				.correlationId(correlationId).replyTo(replyQueueName).build();

		// send the message with these properties
		sendMessage(message, routingKey, properties);

		// wait for response
		final BlockingQueue<String> response = new ArrayBlockingQueue<String>(1);

		channel.basicConsume(replyQueueName, true, new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
					byte[] body) throws IOException {
				if (properties.getCorrelationId().equals(correlationId)) {
					// put body into the blocking queue
					response.offer(new String(body, "UTF-8"));
				}
			}
		});

		// take the body off of the blocking queue
		String body = response.take();

		// message type
		MessageType type = MessageType.fromString(properties.getType());
		replyMessage = deserialize(type, body);

		return replyMessage;
	}

	@Override
	public int hashCode() {
		return Objects.hash(factory.getHost(), factory.getPort());
	}

	@Override
	public boolean equals(Object other) {

		if (!(other instanceof MessagingClient)) {
			return false;
		}
		MessagingClient otherPubSub = (MessagingClient) other;

		return factory.getHost().equals(otherPubSub.factory.getHost())
				&& factory.getPort() == otherPubSub.factory.getPort();
	}

	@Override
	public String toString() {
		return factory != null ? factory.getHost() + ":" + factory.getPort() : "";
	}

	// ************************* Message Receiver ***************************
	private class Receiver extends DefaultConsumer {
		Receiver() {
			super(channel);
		}

		@Override
		public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body)
				throws java.io.IOException {

			// message type
			MessageType type = MessageType.fromString(properties.getType());

			ApplicationMessage message = deserialize(type, new String(body));

			if (logger.isInfoEnabled()) {
				logger.info("Received message of type " + type + " from sender " + message.getSenderHostName() + " ("
						+ message.getSenderHostAddress() + ")");
			}

			if (listener != null) {
				try {
					listener.onMessage(channel, envelope, message);
				} catch (Exception e) {
					logger.error(e.getMessage());
				}
			}
		}
	}
}
