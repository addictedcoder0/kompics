package se.sics.kompics.p2p.application;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.sics.kompics.api.Channel;
import se.sics.kompics.api.Component;
import se.sics.kompics.api.ComponentMembrane;
import se.sics.kompics.api.Event;
import se.sics.kompics.api.Priority;
import se.sics.kompics.api.annotation.ComponentCreateMethod;
import se.sics.kompics.api.annotation.ComponentInitializeMethod;
import se.sics.kompics.api.annotation.ComponentSpecification;
import se.sics.kompics.api.annotation.EventHandlerMethod;
import se.sics.kompics.api.annotation.MayTriggerEventTypes;
import se.sics.kompics.p2p.application.events.DoNextOperation;
import se.sics.kompics.p2p.application.events.StartApplication;
import se.sics.kompics.p2p.peer.events.FailPeer;
import se.sics.kompics.p2p.peer.events.JoinPeer;
import se.sics.kompics.p2p.peer.events.LeavePeer;
import se.sics.kompics.timer.events.SetTimerEvent;
import se.sics.kompics.timer.events.TimerSignalEvent;

/**
 * The <code>Application</code> class
 * 
 * @author Cosmin Arad
 * @version $Id$
 */
@ComponentSpecification
public class Application {

	private static final Logger logger = LoggerFactory
			.getLogger(Application.class);

	private final Component component;

	// PeerCluster channel
	private Channel peerClusterCommandChannel;

	// timer channels
	private Channel timerSetChannel, timerSignalChannel;

	// the operations to execute
	private String[] operations;
	private int lastOperationIndex;

	public Application(Component component) {
		this.component = component;
	}

	@ComponentCreateMethod
	public void create(Channel startChannel, Channel peerClusterCommandChannel) {
		logger.debug("Create");
		// use shared timer component
		ComponentMembrane timerMembrane = component
				.getSharedComponentMembrane("se.sics.kompics.Timer");
		timerSetChannel = timerMembrane.getChannel(SetTimerEvent.class);
		timerSignalChannel = timerMembrane.getChannel(TimerSignalEvent.class);

		this.peerClusterCommandChannel = peerClusterCommandChannel;

		component.subscribe(startChannel, "handleStartApplication");
		component.subscribe(timerSignalChannel, "handleDoNextOperation");
	}

	@ComponentInitializeMethod
	public void init() {
		logger.debug("Init");
	}

	@EventHandlerMethod
	public void handleStartApplication(StartApplication event) {
		operations = event.getOperations();
		lastOperationIndex = -1;
		doNextOperation();
	}

	@EventHandlerMethod
	@MayTriggerEventTypes(SetTimerEvent.class)
	public void handleDoNextOperation(DoNextOperation event) {
		logger.info("DONE WAITING");
		doNextOperation();
	}

	private void doNextOperation() {
		lastOperationIndex++;

		if (lastOperationIndex == operations.length) {
			logger.info("DONE ALL OPERATIONS");
			return;
		}
		String op = operations[lastOperationIndex];

		if (op.startsWith("J")) {
			doJoinPeer(op);
		} else if (op.startsWith("F")) {
			doFailPeer(op);
		} else if (op.startsWith("L")) {
			doLeavePeer(op);
		} else if (op.startsWith("NAVBF")) {
			doNavigateBF(op);
		} else if (op.startsWith("NAVDF")) {
			doNavigateDF(op);
		} else if (op.startsWith("NOP")) {
			doNop(op);
		} else if (op.startsWith("X")) {
			doShutdown();
		} else {
			logger.info("BAD OPEARTION: {}.", op);
		}
	}

	private void doJoinPeer(String op) {
		BigInteger peerId = new BigInteger(op.substring(1));

		logger.debug("Sending JoinPeer {}", peerId);

		JoinPeer command = new JoinPeer(peerId);
		component.triggerEvent(command, peerClusterCommandChannel);

		doNextOperation();
	}

	private void doLeavePeer(String op) {
		BigInteger peerId = new BigInteger(op.substring(1));

		logger.debug("Sending LeavePeer {}", peerId);

		LeavePeer command = new LeavePeer(peerId);
		component.triggerEvent(command, peerClusterCommandChannel);

		doNextOperation();
	}

	private void doFailPeer(String op) {
		BigInteger peerId = new BigInteger(op.substring(1));

		logger.debug("Sending FailPeer {}", peerId);

		FailPeer command = new FailPeer(peerId);
		component.triggerEvent(command, peerClusterCommandChannel);

		doNextOperation();
	}

	private void doNavigateBF(String op) {
		logger.info("Navigating architecture BF...");

		Component root = component;
		while (root.getSuperComponent() != null) {
			root = root.getSuperComponent();
		}

		navigateBF(root);

		doNextOperation();
	}

	private void doNavigateDF(String op) {
		logger.info("Navagating architecture DF...");

		Component root = component;
		while (root.getSuperComponent() != null) {
			root = root.getSuperComponent();
		}

		navigateDF(root, 0);

		doNextOperation();
	}

	private void doNop(String op) {
		int delay = Integer.parseInt(op.substring(3));
		logger.info("WAITING {} milliseconds...", delay);

		SetTimerEvent setTimerEvent = new SetTimerEvent(0,
				new DoNextOperation(), timerSignalChannel, component, delay);

		component.triggerEvent(setTimerEvent, timerSetChannel, Priority.HIGH);
	}

	private void doShutdown() {
		// TODO shutdown
		System.exit(0);
	}

	private void navigateDF(Component comp, int level) {
		String space = "";
		String indent = "\t";
		for (int i = 0; i < level; i++) {
			space += indent;
		}

		logger.info("{}Navigating component {} (parent is {})", new Object[] {
				space,
				comp.getName(),
				(comp.getSuperComponent() == null ? "[null]" : comp
						.getSuperComponent().getName()) });
		List<Component> subcomponents = comp.getSubComponents();
		List<Channel> channels = comp.getLocalChannels();

		if (channels.size() == 0) {
			logger.info("{}{}No local channels", space, indent);
		} else {
			for (Channel ch : channels) {
				logger.info("{}{}Local channel@{} is {}", new Object[] { space,
						indent, ch.hashCode(), channelToString(ch) });
			}
		}
		if (subcomponents.size() == 0) {
			logger.info("{}{}No Children components", space, indent);
		} else {
			for (Component c : subcomponents) {
				logger.info("{}{}Child is {}", new Object[] { space, indent,
						c.getName() });
				navigateDF(c, level + 1);
			}
		}
	}

	private void navigateBF(Component comp) {
		String indent = "\t";

		logger.info("Navigating component {} (parent is {})", comp.getName(),
				(comp.getSuperComponent() == null ? "[null]" : comp
						.getSuperComponent().getName()));
		List<Component> subcomponents = comp.getSubComponents();
		List<Channel> channels = comp.getLocalChannels();

		if (subcomponents.size() == 0) {
			logger.info("{}No Children components", indent);
		} else {
			for (Component c : subcomponents) {
				logger.info("{}Child is {}", indent, c.getName());
			}
		}
		if (channels.size() == 0) {
			logger.info("{}No local channels", indent);
		} else {
			for (Channel ch : channels) {
				logger.info("{}Local channel@{} is {}", new Object[] { indent,
						ch.hashCode(), channelToString(ch) });
			}
		}
		for (Component c : subcomponents) {
			navigateBF(c);
		}
	}

	private String channelToString(Channel c) {
		Set<Class<? extends Event>> types = c.getEventTypes();
		String ret = "[";
		for (Class<? extends Event> type : types) {
			ret += " " + type.getSimpleName() + " ";
		}
		return ret + "]";
	}
}
