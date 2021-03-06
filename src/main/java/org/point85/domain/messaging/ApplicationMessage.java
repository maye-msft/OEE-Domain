package org.point85.domain.messaging;

public abstract class ApplicationMessage {
	// type of message for deserialization
	private MessageType messageType;

	// sender host name
	private String senderHostName;

	// sender host IP address
	private String senderHostAddress;

	// time message sent
	private String timestamp;

	protected ApplicationMessage(MessageType messageType) {
		this.messageType = messageType;
	}

	protected ApplicationMessage(String senderHostName, String senderHostAddress, MessageType messageType) {
		this.messageType = messageType;
		this.senderHostName = senderHostName;
		this.senderHostAddress = senderHostAddress;
	}

	public MessageType getMessageType() {
		return messageType;
	}

	public void setMessageType(MessageType documentType) {
		this.messageType = documentType;
	}

	public String getSenderHostName() {
		return senderHostName;
	}

	public void setSenderHostName(String senderHostName) {
		this.senderHostName = senderHostName;
	}

	public void validate() throws Exception {
		if (messageType == null) {
			throw new Exception("The message type cannot be null");
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Type: ").append(messageType.toString()).append(", Sender Host: ").append(senderHostName);

		return sb.toString();
	}

	public String getSenderHostAddress() {
		return senderHostAddress;
	}

	public void setSenderHostAddress(String senderHostAddress) {
		this.senderHostAddress = senderHostAddress;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(String timestamp) {
		this.timestamp = timestamp;
	}
}
