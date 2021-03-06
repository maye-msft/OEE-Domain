package org.point85.domain.opc.ua;

import static com.google.common.collect.Lists.newArrayList;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.eclipse.milo.opcua.stack.core.util.ConversionUtil.toList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.api.UaSession;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.X509IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.api.nodes.VariableNode;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription;
import org.eclipse.milo.opcua.sdk.client.model.nodes.objects.ServerNode;
import org.eclipse.milo.opcua.sdk.client.model.nodes.variables.ServerStatusNode;
import org.eclipse.milo.opcua.stack.client.UaTcpStackClient;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.BuiltinDataType;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.Stack;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExpandedNodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExtensionObject;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.IdType;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.ServerState;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo;
import org.eclipse.milo.opcua.stack.core.types.structured.CallMethodRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoredItemCreateRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoringParameters;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

public class UaOpcClient implements SessionActivityListener {
	// logging utility
	private static final Logger logger = LoggerFactory.getLogger(UaOpcClient.class);

	private static final String APP_NAME = "Point85 OEE OPC UA Client";

	private static final String APP_URI = "urn:point85:oee:client";

	// request timeout (msec)
	private static final int REQUEST_TIMEOUT = 20000;

	private static final TimeUnit REQUEST_TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

	private static final double MAX_AGE = 0.0d;

	private static final int SUBSCRIPTION_QUEUE_SIZE = 1;

	private static final double SAMPLING_INTERVAL = 0.0d;

	// session timeout (msec)
	private static final int SESSION_TIMEOUT = 24 * 3600 * 1000;

	// wrapped UA client
	private OpcUaClient opcUaClient;

	// asynch callback listeners
	private final List<OpcUaAsynchListener> asynchListeners = new ArrayList<>();

	// client handle counter for subscriptions
	private final AtomicLong clientHandles = new AtomicLong(1L);

	// registry of subscriptions
	private final ConcurrentMap<NodeId, UaSubscription> subscriptionMap = new ConcurrentHashMap<>();

	private final AtomicReference<BiConsumer<Boolean, Throwable>> listener = new AtomicReference<>();

	private OpcUaSource connectedSource;

	public UaOpcClient() {
		// nothing to initialize
	}

	public OpcUaClient getNativeClient() {
		return this.opcUaClient;
	}

	private UaSubscription getSubscription(NodeId nodeId) {
		return subscriptionMap.get(nodeId);
	}

	private void registerSubscription(NodeId nodeId, UaSubscription subscription) {
		this.subscriptionMap.putIfAbsent(nodeId, subscription);
	}

	private void unregisterSubscription(NodeId nodeId) {
		this.subscriptionMap.remove(nodeId);
	}

	public boolean isSubscribed(NodeId nodeId) {
		return this.subscriptionMap.containsKey(nodeId);
	}

	public void registerAsynchListener(OpcUaAsynchListener listener) {
		if (!asynchListeners.contains(listener)) {
			asynchListeners.add(listener);
		}
	}

	public void unregisterAsynchListener(OpcUaAsynchListener listener) {
		asynchListeners.remove(listener);
	}

	// log into the server and connect
	public synchronized void connect(OpcUaSource source) throws Exception {
		String endpointUrl = source.getEndpointUrl();

		if (logger.isInfoEnabled()) {
			logger.info("Connecting to: " + endpointUrl);
		}

		// identity provider
		IdentityProvider identityProvider = new AnonymousProvider();
		X509KeyStoreLoader loader = null;

		if (source.getUserName() != null && source.getUserName().length() > 0) {
			// user name and password authentication
			identityProvider = new UsernameProvider(source.getUserName(), source.getUserPassword());
		}

		if (source.getKeystore() != null && source.getKeystore().length() > 0) {
			// load X509 certificate from a keystore
			loader = new X509KeyStoreLoader();
			loader.load(source.getKeystore(), source.getKeystorePassword());
			identityProvider = new X509IdentityProvider(loader.getClientCertificate(),
					loader.getClientKeyPair().getPrivate());
		}

		if (logger.isInfoEnabled()) {
			logger.info("Identity provider: " + identityProvider.getClass().getSimpleName());
		}

		// get the server's endpoints
		EndpointDescription[] endpointDescriptions = UaTcpStackClient.getEndpoints(endpointUrl).get();

		// security settings
		String policyUri = source.getSecurityPolicy().getSecurityPolicyUri();
		MessageSecurityMode messageSecurityMode = source.getMessageSecurityMode();

		if (logger.isInfoEnabled()) {
			logger.info("Configured policy: " + policyUri + ", mode: " + messageSecurityMode);
		}

		EndpointDescription endpointDescription = null;

		for (EndpointDescription description : endpointDescriptions) {
			String uri = description.getSecurityPolicyUri();
			MessageSecurityMode mode = description.getSecurityMode();

			if (logger.isInfoEnabled()) {
				logger.info("Checking URL: " + description.getEndpointUrl() + ",  Policy: " + uri + ", Mode: " + mode);
			}

			if (uri.equals(policyUri) && mode.equals(messageSecurityMode)) {
				endpointDescription = description;
				break;
			}
		}

		if (endpointDescription == null) {
			String msg = "Unable to find a matching endpoint for security policy " + policyUri + " and mode "
					+ messageSecurityMode;
			logger.error(msg);
			throw new Exception(msg);
		}

		// make sure the URL has the requested host name
		EndpointDescription updatedEndpoint = EndpointUtilExt.updateUrl(endpointDescription, source.getHost());

		if (logger.isInfoEnabled()) {
			logger.info("Using endpoint: {} [{}, {}]", updatedEndpoint.getEndpointUrl(),
					updatedEndpoint.getSecurityPolicyUri(), updatedEndpoint.getSecurityMode());
		}

		// build the configuration
		OpcUaClientConfigBuilder configBuilder = new OpcUaClientConfigBuilder();

		configBuilder.setApplicationName(LocalizedText.english(APP_NAME)).setApplicationUri(APP_URI)
				.setEndpoint(updatedEndpoint).setIdentityProvider(identityProvider)
				.setRequestTimeout(uint(REQUEST_TIMEOUT));

		if (logger.isInfoEnabled()) {
			logger.info("App name: " + APP_NAME + ", app URI: " + APP_URI);
		}

		if (loader != null) {
			// get the configured certificate
			configBuilder.setCertificate(loader.getClientCertificate()).setKeyPair(loader.getClientKeyPair());

			if (logger.isInfoEnabled()) {
				logger.info("Client certificate alg " + loader.getClientCertificate().getSigAlgName());
			}
		}

		// session timeout
		configBuilder.setSessionTimeout(uint(SESSION_TIMEOUT));

		// create the client
		opcUaClient = new OpcUaClient(configBuilder.build());

		// synchronous connect
		opcUaClient.connect().get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);

		if (logger.isInfoEnabled()) {
			logger.info("Connected to server.");
		}
		connectedSource = source;
	}

	public synchronized void disconnect() throws Exception {
		if (opcUaClient != null) {
			opcUaClient.disconnect().get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);
			opcUaClient = null;
		}
		Stack.releaseSharedResources();
		connectedSource = null;
	}

	private void checkPreconditions() throws Exception {
		if (opcUaClient == null) {
			throw new Exception("Not connected to an OPC UA server.");
		}
	}

	public synchronized List<DataValue> readSynch(List<NodeId> nodeIds) throws Exception {
		checkPreconditions();

		// synchronous read request
		return opcUaClient.readValues(MAX_AGE, TimestampsToReturn.Both, nodeIds).get(REQUEST_TIMEOUT,
				REQUEST_TIMEOUT_UNIT);
	}

	public synchronized List<StatusCode> writeSynch(List<NodeId> nodeIds, List<Variant> values) throws Exception {
		checkPreconditions();

		List<DataValue> dataValues = new ArrayList<>(values.size());

		for (Variant variant : values) {
			dataValues.add(new DataValue(variant, null, null));
		}
		return opcUaClient.writeValues(nodeIds, dataValues).get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);
	}

	public synchronized void writeAsynch(List<NodeId> nodeIds, List<Variant> values) throws Exception {
		checkPreconditions();

		List<DataValue> dataValues = new ArrayList<>(values.size());

		for (Variant variant : values) {
			dataValues.add(new DataValue(variant, null, null));
		}

		CompletableFuture<List<StatusCode>> cf = opcUaClient.writeValues(nodeIds, dataValues);
		cf.thenAccept(statusCodes -> {
			for (OpcUaAsynchListener listener : asynchListeners) {
				listener.onOpcUaWrite(statusCodes);
			}
		});
	}

	public synchronized void readAsynch(NodeId nodeId) throws Exception {
		checkPreconditions();

		CompletableFuture<DataValue> cf = opcUaClient.readValue(MAX_AGE, TimestampsToReturn.Both, nodeId);
		cf.thenAccept(value -> {
			List<DataValue> values = new ArrayList<>(1);
			values.add(value);

			for (OpcUaAsynchListener listener : asynchListeners) {
				listener.onOpcUaRead(values);
			}
		});
	}

	public synchronized void readAsynch(List<NodeId> nodeIds) throws Exception {
		checkPreconditions();

		CompletableFuture<List<DataValue>> cf = opcUaClient.readValues(MAX_AGE, TimestampsToReturn.Both, nodeIds);
		cf.thenAccept(values -> {
			for (OpcUaAsynchListener listener : asynchListeners) {
				listener.onOpcUaRead(values);
			}
		});
	}

	public UInteger[] getArrayDimensions(NodeId nodeId) throws Exception {
		VariableNode node = opcUaClient.getAddressSpace().createVariableNode(nodeId);
		return node.getArrayDimensions().get();
	}

	public synchronized DataValue readSynch(NodeId nodeId) throws Exception {
		checkPreconditions();

		VariableNode node = opcUaClient.getAddressSpace().createVariableNode(nodeId);
		return node.readValue().get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);
	}

	public synchronized StatusCode writeSynch(NodeId nodeId, Variant newValue) throws Exception {
		checkPreconditions();

		CompletableFuture<StatusCode> cf = opcUaClient.writeValue(nodeId, new DataValue(newValue, null, null));
		return cf.get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);
	}

	public synchronized void writeAsynch(NodeId nodeId, Variant value) throws Exception {
		checkPreconditions();

		DataValue dataValue = new DataValue(value, null, null);
		CompletableFuture<StatusCode> cf = opcUaClient.writeValue(nodeId, dataValue);

		cf.thenAccept(statusCode -> {
			List<StatusCode> statusCodes = new ArrayList<>(1);
			statusCodes.add(statusCode);

			for (OpcUaAsynchListener listener : asynchListeners) {
				listener.onOpcUaWrite(statusCodes);
			}
		});
	}

	public static Object getJavaObject(Variant value) {
		Object uaValue = value.getValue();
		Object javaObject = null;
		NodeId nodeId = value.getDataType().get();
		Class<?> clazz = BuiltinDataType.getBackingClass(nodeId);

		if (nodeId.getType().equals(IdType.Numeric)) {
			if (clazz.equals(Integer.class)) {
				javaObject = (Integer) uaValue;
			} else if (clazz.equals(UInteger.class)) {
				javaObject = ((UInteger) uaValue).longValue();
			} else if (clazz.equals(Short.class)) {
				javaObject = (Short) uaValue;
			} else if (clazz.equals(UShort.class)) {
				javaObject = ((UShort) uaValue).intValue();
			} else if (clazz.equals(Boolean.class)) {
				javaObject = (Boolean) uaValue;
			} else if (clazz.equals(Byte.class)) {
				javaObject = (Byte) uaValue;
			} else if (clazz.equals(UByte.class)) {
				javaObject = ((UByte) uaValue).shortValue();
			} else if (clazz.equals(Long.class)) {
				javaObject = (Long) uaValue;
			} else if (clazz.equals(ULong.class)) {
				javaObject = ((ULong) uaValue).doubleValue();
			} else if (clazz.equals(Float.class)) {
				javaObject = (Float) uaValue;
				;
			} else if (clazz.equals(Double.class)) {
				javaObject = (Double) uaValue;
			} else if (clazz.equals(DateTime.class)) {
				javaObject = (DateTime) uaValue;
			} else if (clazz.equals(String.class)) {
				javaObject = (String) uaValue;
			}
		} else if (nodeId.getType().equals(IdType.String)) {
			javaObject = (String) uaValue;
		} else if (nodeId.getType().equals(IdType.Guid)) {
			javaObject = (UUID) uaValue;
		}
		return javaObject;
	}

	public static NodeId getNodeId(ExpandedNodeId expandedId) {
		UShort ns = expandedId.getNamespaceIndex();
		Class<?> clazz = expandedId.getIdentifier().getClass();
		Object identifier = expandedId.getIdentifier();

		NodeId nodeId = NodeId.NULL_VALUE;

		if (clazz.equals(UInteger.class)) {
			nodeId = new NodeId(ns, (UInteger) identifier);
		} else if (clazz.equals(String.class)) {
			nodeId = new NodeId(ns, (String) identifier);
		} else if (clazz.equals(UUID.class)) {
			nodeId = new NodeId(ns, (UUID) identifier);
		} else if (clazz.equals(ByteString.class)) {
			nodeId = new NodeId(ns, (ByteString) identifier);
		}
		return nodeId;
	}

	public synchronized List<ReferenceDescription> browseSynch(NodeId parentNode) throws Exception {
		BrowseDescription browseDescription = new BrowseDescription(parentNode, BrowseDirection.Forward,
				Identifiers.References, true, uint(NodeClass.Object.getValue() | NodeClass.Variable.getValue()),
				uint(BrowseResultMask.All.getValue()));

		BrowseResult browseResult = opcUaClient.browse(browseDescription).get();

		return toList(browseResult.getReferences());
	}

	public synchronized List<BrowseResult> browse(List<NodeId> nodeIds) throws Exception {
		List<BrowseDescription> nodesToBrowse = new ArrayList<>(nodeIds.size());

		for (NodeId nodeId : nodeIds) {
			BrowseDescription browseDescription = new BrowseDescription(nodeId, BrowseDirection.Forward,
					Identifiers.References, true, uint(NodeClass.Object.getValue() | NodeClass.Variable.getValue()),
					uint(BrowseResultMask.All.getValue()));
			nodesToBrowse.add(browseDescription);
		}

		return opcUaClient.browse(nodesToBrowse).get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);
	}

	private void onSubscriptionValue(UaMonitoredItem item, DataValue value) {
		for (OpcUaAsynchListener listener : this.asynchListeners) {
			listener.onOpcUaSubscription(value, item);
		}
	}

	private UaSubscription establishSubscription(NodeId nodeId, double publishingInterval, ExtensionObject filter)
			throws Exception {
		// node to read
		ReadValueId readValueId = new ReadValueId(nodeId, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE);

		// client handle must be unique per item
		UInteger clientHandle = uint(clientHandles.getAndIncrement());

		// discard oldest value
		MonitoringParameters parameters = new MonitoringParameters(clientHandle, SAMPLING_INTERVAL, filter,
				uint(SUBSCRIPTION_QUEUE_SIZE), true);

		MonitoredItemCreateRequest request = new MonitoredItemCreateRequest(readValueId, MonitoringMode.Reporting,
				parameters);

		// consumer
		BiConsumer<UaMonitoredItem, Integer> onItemCreated = (item, id) -> item
				.setValueConsumer(this::onSubscriptionValue);

		// create a subscription
		UaSubscription subscription = opcUaClient.getSubscriptionManager().createSubscription(publishingInterval)
				.get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);

		List<UaMonitoredItem> items = subscription
				.createMonitoredItems(TimestampsToReturn.Both, newArrayList(request), onItemCreated)
				.get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);

		for (UaMonitoredItem item : items) {
			if (item.getStatusCode().isGood()) {
				logger.info("Monitored item created for nodeId: " + item.getReadValueId().getNodeId());
			} else {
				throw new Exception("Failed to create monitored item for nodeId: " + item.getReadValueId().getNodeId()
						+ ", code: " + item.getStatusCode());
			}
		}

		return subscription;
	}

	public synchronized void subscribe(NodeId nodeId, double publishingInterval, ExtensionObject filter)
			throws Exception {
		checkPreconditions();

		// check to see if modified
		UaSubscription subscription = getSubscription(nodeId);

		if (subscription != null) {
			// modify it by publishing interval
			opcUaClient.getSubscriptionManager().modifySubscription(subscription.getSubscriptionId(),
					publishingInterval);
		}

		// create subscription
		subscription = establishSubscription(nodeId, publishingInterval, filter);

		// save in map
		registerSubscription(nodeId, subscription);
	}

	public synchronized void unsubscribe(NodeId nodeId) throws Exception {
		if (nodeId == null) {
			return;
		}

		// from the registry
		UaSubscription subscription = getSubscription(nodeId);

		if (subscription == null) {
			return;
		}

		ImmutableList<UaMonitoredItem> items = subscription.getMonitoredItems();

		List<StatusCode> codes = subscription.deleteMonitoredItems(items).get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);

		for (StatusCode code : codes) {
			if (!code.isGood()) {
				throw new Exception("Unable to unsubscribe from node " + nodeId + ": " + code);
			}
		}

		opcUaClient.getSubscriptionManager().deleteSubscription(subscription.getSubscriptionId());

		// remove from registry
		unregisterSubscription(nodeId);
	}

	public synchronized List<Object> callMethodSynch(NodeId objectId, NodeId methodId, List<Object> inputArguments)
			throws Exception {

		Variant[] variants = new Variant[inputArguments.size()];
		for (int i = 0; i < inputArguments.size(); i++) {
			variants[i] = new Variant(inputArguments.get(i));
		}

		CallMethodRequest request = new CallMethodRequest(objectId, methodId, variants);

		CompletableFuture<List<Object>> cf = opcUaClient.call(request).thenCompose(result -> {
			StatusCode statusCode = result.getStatusCode();

			if (statusCode.isGood()) {
				Variant[] outputArguments = result.getOutputArguments();

				List<Object> outputs = new ArrayList<>(outputArguments.length);

				for (int i = 0; i < outputArguments.length; i++) {
					outputs.add(outputArguments[i].getValue());
				}

				return CompletableFuture.completedFuture(outputs);
			} else {
				CompletableFuture<List<Object>> f = new CompletableFuture<>();
				f.completeExceptionally(new org.eclipse.milo.opcua.stack.core.UaException(statusCode));
				return f;
			}
		});

		return cf.get(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT);
	}

	public DateTime getServerCurrentTime() throws Exception {
		ServerNode serverNode = opcUaClient.getAddressSpace().getObjectNode(Identifiers.Server, ServerNode.class).get();

		ServerStatusNode serverStatusNode = serverNode.getServerStatusNode().get();
		return serverStatusNode.getCurrentTime().get();
	}

	public OpcUaServerStatus getServerStatus() throws Exception {
		OpcUaServerStatus serverStatus = new OpcUaServerStatus();

		// get build info separately
		VariableNode buildInfoNode = opcUaClient.getAddressSpace()
				.createVariableNode(Identifiers.Server_ServerStatus_BuildInfo);

		BuildInfo buildInfo = buildInfoNode.getValue().thenApply(ExtensionObject.class::cast)
				.thenApply(ExtensionObject::<BuildInfo>decode).get();

		// Get a typed reference to the Server object: ServerNode
		ServerNode serverNode = opcUaClient.getAddressSpace().getObjectNode(Identifiers.Server, ServerNode.class).get();

		// Get a typed reference to the ServerStatus variable
		// component and read value attributes individually
		ServerStatusNode serverStatusNode = serverNode.getServerStatusNode().get();

		DateTime startTime = serverStatusNode.getStartTime().get();
		ServerState state = serverStatusNode.getState().get();

		serverStatus.setState(state);
		serverStatus.setBuildInfo(buildInfo);
		serverStatus.setStartTime(startTime);

		return serverStatus;
	}

	public boolean isConnected() {
		return opcUaClient != null ? true : false;
	}

	@Override
	public void onSessionActive(UaSession session) {
		logger.info("active session id: {}", session.getSessionId());
		BiConsumer<Boolean, Throwable> consumer = listener.get();
		if (consumer != null) {
			consumer.accept(Boolean.TRUE, null);
		}
	}

	@Override
	public void onSessionInactive(UaSession session) {
		logger.info("inactive session id: {}", session.getSessionId());
		BiConsumer<Boolean, Throwable> consumer = listener.get();
		if (consumer != null) {
			consumer.accept(Boolean.FALSE, null);
		}
	}

	@Override
	public int hashCode() {
		return connectedSource != null ? Objects.hash(connectedSource.getHost(), connectedSource.getPort()) : 17;
	}

	@Override
	public boolean equals(Object other) {

		if (!(other instanceof UaOpcClient)) {
			return false;
		}
		UaOpcClient otherClient = (UaOpcClient) other;

		return connectedSource.getHost().equals(otherClient.connectedSource.getHost())
				&& connectedSource.getPort().equals(otherClient.connectedSource.getPort());
	}

	@Override
	public String toString() {
		String value = getClass().getSimpleName();

		if (connectedSource != null) {
			value = "Host: " + connectedSource.getHost() + ":" + connectedSource.getPort();

			if (connectedSource.getEndpointPath() != null) {
				value += "/" + connectedSource.getEndpointPath();
			}
		}
		return value;
	}
}
