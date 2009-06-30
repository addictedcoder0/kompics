package se.sics.kompics.wan.master;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.apache.commons.configuration.ConfigurationException;

import se.sics.kompics.Component;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Kompics;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.timer.ScheduleTimeout;
import se.sics.kompics.timer.Timeout;
import se.sics.kompics.timer.Timer;
import se.sics.kompics.timer.java.JavaTimer;
import se.sics.kompics.wan.config.Configuration;
import se.sics.kompics.wan.config.PlanetLabConfiguration;
import se.sics.kompics.wan.main.DaemonTest;
import se.sics.kompics.wan.master.plab.Credentials;
import se.sics.kompics.wan.master.plab.ExperimentHost;
import se.sics.kompics.wan.master.ssh.HaltRequest;
import se.sics.kompics.wan.master.ssh.HaltResponse;
import se.sics.kompics.wan.master.ssh.SshCommandRequest;
import se.sics.kompics.wan.master.ssh.SshCommandResponse;
import se.sics.kompics.wan.master.ssh.SshConnectRequest;
import se.sics.kompics.wan.master.ssh.SshConnectResponse;
import se.sics.kompics.wan.master.ssh.SshPort;

public class SshTest  {

	public static final int SSH_CONNECT_TIMEOUT = 15000;

	public static final int SSH_KEY_EXCHANGE_TIMEOUT = 15000;

	private static Semaphore semaphore = new Semaphore(0);

	private static final int EVENT_COUNT = 1;
	
	
	public static void setTestObj(SshTest testObj) {
		TestSshComponent.testObj = testObj;
	}
	
	public static class SshConnectTimeout extends Timeout {

		public SshConnectTimeout(ScheduleTimeout request) {
			super(request);
		}
	}


	public static class TestSshComponent extends ComponentDefinition {
		
		private Positive<SshPort> sshPort;

		private Component timer;
		
		private static SshTest testObj = null;
		
		private final HashSet<UUID> outstandingTimeouts = new HashSet<UUID>();
		
		public TestSshComponent() {

			timer = create(JavaTimer.class);
//			connect(daemon.getNegative(Timer.class), timer.getPositive(Timer.class));
			
			subscribe(handleCommandResponse, sshPort);
			subscribe(handleSshConnectResponse, sshPort);
			subscribe(handleHaltResponse, sshPort);
			
			subscribe(handleSshConnectTimeout, timer.getPositive(Timer.class));
		}

		public Handler<Start> handleStart = new Handler<Start>() {
			public void handle(Start event) {

				// TODO Auto-generated method stub
				Credentials cred = new Credentials("jdowling", "password", "/home/jdowling/.ssh/id_rsa", "none");
				ExperimentHost host = new ExperimentHost("lqist.com");
				
				trigger(new SshConnectRequest(cred, host), sshPort);

				ScheduleTimeout st = new ScheduleTimeout(SSH_CONNECT_TIMEOUT);
				SshConnectTimeout connectTimeout = new SshConnectTimeout(st);
				st.setTimeoutEvent(connectTimeout);

				UUID timerId = connectTimeout.getTimeoutId();
				outstandingTimeouts.add(timerId);
				trigger(st, timer.getPositive(Timer.class));

			}
		};
		
		public Handler<SshConnectTimeout> handleSshConnectTimeout = new Handler<SshConnectTimeout>() {
			public void handle(SshConnectTimeout event) {
				
				if (!outstandingTimeouts.contains(event.getTimeoutId())) {
					return;
				}
				outstandingTimeouts.remove(event.getTimeoutId());

				testObj.fail(true);
			}
		};
		
		
		public Handler<SshConnectResponse> handleSshConnectResponse = new Handler<SshConnectResponse>() {
			public void handle(SshConnectResponse event) {

				SshCommandRequest command = new SshCommandRequest(event.getSessionId(), "ls -la", 
						10*1000, true);
				trigger(command, sshPort);
			}
		};
		
		public Handler<SshCommandResponse> handleCommandResponse = new Handler<SshCommandResponse>() {
			public void handle(SshCommandResponse event) {

				trigger(new HaltRequest(event.getSessionId()), sshPort);
			}
		};
		
		public Handler<HaltResponse> handleHaltResponse = new Handler<HaltResponse>() {
			public void handle(HaltResponse event) {
				testObj.pass();

			}
		};
	};

	
	public SshTest() {
		
		/*
		Connection sshConn = new Connection("lqist.com");

		try {

			System.out.println("Connecting");

			sshConn
					.connect(null, SSH_CONNECT_TIMEOUT,
							SSH_KEY_EXCHANGE_TIMEOUT);

			if (sshConn.authenticateWithPublicKey("jdowling", new File(
					"/home/jdowling/.ssh/id_rsa"), "")) {
				System.out.println("Connected");

				Session session = sshConn.openSession();

				StreamGobbler stdout = new StreamGobbler(session.getStdout());

				OutputStream stdin = session.getStdin();

				// session.execCommand("ls /bin");
				session.startShell();
				// session.execCommand("ls");

				stdin.write("ls -la\n".getBytes());
				stdin.write("echo \"=:=:=EXIT STATUS==$?==\"\n".getBytes());

				session.waitForCondition(ChannelCondition.STDOUT_DATA, 5000);

				Thread.sleep(200);
				while (stdout.available() > 0) {
					byte[] response = new byte[stdout.available()];

					stdout.read(response, 0, response.length);
					System.out.println(new String(response));
					Thread.sleep(200);
				}
				int cond = session.waitForCondition(ChannelCondition.EOF, 5000);
				System.out.println( "EOF.. = " + cond);
				session.waitForCondition(ChannelCondition.EXIT_SIGNAL, 5000);
				System.out.println("exit: " + session.getExitStatus());

				session.close();
				sshConn.close();

			}

		} catch (SocketTimeoutException e) {
			System.err.println("connection timeout: " + e.getMessage());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		*/
	}

	@org.junit.Test 
	public void testSsh()
	{
		
		setTestObj(this);
		try {
			Configuration.init(new String[]{}, PlanetLabConfiguration.class);
			Kompics.createAndStart(SshTest.TestSshComponent.class, 1);
		} catch (ConfigurationException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		try {
			SshTest.semaphore.acquire(EVENT_COUNT);
			System.out.println("Exiting unit test....");
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.err.println(e.getMessage());
		}
		
	}
	
	public void pass() {
		org.junit.Assert.assertTrue(true);
		semaphore.release();
	}

	public void fail(boolean release) {
		org.junit.Assert.assertTrue(false);
		if (release == true) {
			semaphore.release();
		}
	}
}