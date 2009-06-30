package se.sics.kompics.wan.master.ssh;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import se.sics.kompics.Component;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.wan.config.PlanetLabConfiguration;
import se.sics.kompics.wan.master.plab.Credentials;
import se.sics.kompics.wan.master.plab.ExperimentHost;
import se.sics.kompics.wan.master.plab.rpc.RpcFunctions;
import se.sics.kompics.wan.master.scp.FileInfo;
import se.sics.kompics.wan.master.scp.LocalDirMD5Info;
import se.sics.kompics.wan.master.scp.DownloadMgr;
import se.sics.kompics.wan.master.scp.DownloadMgrPort;
import se.sics.kompics.wan.master.scp.RemoteDirMD5Info;
import se.sics.kompics.wan.master.scp.download.DownloadMD5Request;
import se.sics.kompics.wan.master.scp.download.DownloadMD5Response;
import se.sics.kompics.wan.master.scp.download.DownloadManager;
import se.sics.kompics.wan.master.scp.upload.UploadMD5CheckThread;
import se.sics.kompics.wan.master.scp.upload.UploadMD5Response;
import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.ConnectionMonitor;
import ch.ethz.ssh2.HTTPProxyData;
import ch.ethz.ssh2.SCPClient;
import ch.ethz.ssh2.Session;

public class SshComponent extends ComponentDefinition {

	public static final int LOG_ERROR = 1;

	public static final int LOG_FULL = 3;

	public static final int LOG_DEVEL = 2;

	public static final int LOG_LEVEL = 3;

	public static final int SSH_CONNECT_TIMEOUT = 15000;

	public static final int SSH_KEY_EXCHANGE_TIMEOUT = 30000;

	public static final String EXIT_CODE_IDENTIFIER = "=:=:=EXIT STATUS==";

	private Negative<SshPort> sshPort;
	
	private Component md5Checker;

	
	private int sessionCounter = 0;
	
	// (session, status)
	private Map<Integer, SshConn> activeSshConnections = new HashMap<Integer, SshConn>();

	private Map<Integer, Session> sessionObjMap = new HashMap<Integer, Session>();
	
	// private String status = "disconnected";

	private Map<Integer, List<CommandSpec>> sessionCommandsMap = new HashMap<Integer, List<CommandSpec>>();

	private AtomicBoolean quit = new AtomicBoolean(false);

	public class SshConn implements ConnectionMonitor, Comparable<SshConn> {

		private String status;
		private final ExperimentHost hostname;
		private boolean isConnected;
		private boolean wasConnected;
		private final Credentials credentials;
		private Connection connection;

		public SshConn(ExperimentHost host, Credentials credentials,
				Connection connection) {
			super();
			this.status = "created";
			this.hostname = host;
			this.credentials = credentials;
			this.connection = connection;

			if (this.connection.isAuthenticationComplete() == true) {
				isConnected = true;
			}
		}

		public void connectionLost(Throwable reason) {
			// statusChange("connection lost, (HANDLE THIS?)", LOG_ERROR);

			isConnected = false;
		}

		/**
		 * @return the credentials
		 */
		public Credentials getCredentials() {
			return credentials;
		}

		/**
		 * @return the plHost
		 */
		public ExperimentHost getExpHost() {
			return hostname;
		}

		/**
		 * @return the status
		 */
		public String getStatus() {
			return status;
		}

		/**
		 * @return the isConnected
		 */
		public boolean isConnected() {
			return isConnected;
		}

		/**
		 * @return the wasConnected
		 */
		public boolean isWasConnected() {
			return wasConnected;
		}

		/**
		 * @return the connection
		 */
		public Connection getConnection() {
			return connection;
		}

		/**
		 * @param isConnected
		 *            the isConnected to set
		 */
		public void setConnected(boolean isConnected) {
			this.isConnected = isConnected;
		}

		/**
		 * @param wasConnected
		 *            the wasConnected to set
		 */
		public void setWasConnected(boolean wasConnected) {
			this.wasConnected = wasConnected;
		}

		/**
		 * @param status
		 *            the status to set
		 */
		public void setStatus(String status) {
			this.status = status;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Comparable#compareTo(java.lang.Object)
		 */
		@Override
		public int compareTo(SshConn that) {
			// we can have several connections to the same host with different
			// usernames
			if ((!credentials.equals(that.credentials))
					|| (hostname.compareTo(that.hostname) != 0)) {
				return -1;
			}

			if (new ConnectionComparator().compare(connection, that.connection) != 0) {
				return -1;
			}

			return 0;

		}
	}

	private class LineReader {
		InputStream inputStream;

		StringBuffer buf;

		public LineReader(InputStream inputStream) {
			this.inputStream = inputStream;
			this.buf = new StringBuffer();
		}

		public String readLine() throws IOException {

			// System.out.println(b)
			int available = inputStream.available();
			if (available > 0) {
				byte[] byteBuffer = new byte[1];
				while (inputStream.read(byteBuffer, 0, 1) > 0) {
					String str = new String(byteBuffer);
					if (str.equals("\n") || str.equals("\r")) {
						if (buf.length() > 0) {
							String ret = buf.toString();
							buf = new StringBuffer();
							return ret;
						} else {
							continue;
						}
					}
					buf.append(str);
				}
			}
			return null;

		}

		public String readRest() throws IOException {
			int available = inputStream.available();
			if (available > 0) {
				byte[] byteBuffer = new byte[available];
				inputStream.read(byteBuffer, 0, available);
				buf.append(new String(byteBuffer));
				return buf.toString();
			}
			return "";
		}

	}

	public SshComponent() {

		md5Checker = create(DownloadMgr.class);
		
		subscribe(handleDownloadMD5Response, md5Checker.getPositive(DownloadMgrPort.class));
		subscribe(handleUploadMD5Response, md5Checker.getPositive(DownloadMgrPort.class));
		
		subscribe(handleSshCommand, sshPort);
		subscribe(handleSshConnectRequest, sshPort);
		subscribe(handleHaltRequest, sshPort);
	}

//	public Handler<InitSsh> handleInitSsh = new Handler<InitSsh>() {
//		public void handle(InitSsh event) {
//
//		}
//	};

	public Handler<SshCommandRequest> handleSshCommand = new Handler<SshCommandRequest>() {
		public void handle(SshCommandRequest event) {
			CommandSpec commandSpec = new CommandSpec(event.getCommand(), event
					.getTimeout(), sessionCommandsMap.size(), event.isStopOnError());

			int sessionId = event.getSessionId();
			
			
			// XXX get sshConn from somewhere
			SshConn conn = activeSshConnections.get(sessionId);
			Session session = sessionObjMap.get(sessionId);
			

			String command = commandSpec.getCommand();
			boolean commandFailed = false;

			String commandResponse = "success";

			if (conn == null || session == null || validSession(session) == false)
			{
				commandResponse = "SshConn or Session obj was null";
			}
			else { // process command
				if (command.startsWith("#")) {
					runSpecialCommand(conn, commandSpec);
				}// run the command in the current session
				else {
					try {
						commandFailed = (runCommand(commandSpec, session, conn) < 0) ? true : false;
					} catch (IOException e) {
						commandResponse = e.getMessage();
						commandFailed = true;
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						commandResponse = e.getMessage();
						commandFailed = true;
					}
				}
			}
			
			trigger(new SshCommandResponse(event, sessionId, commandResponse, !commandFailed), sshPort);
		}
	};

	private boolean validSession(Session session)
	{
		if (session.getExitStatus() == null)
		{
			return true;
		}
		return false;
	}
	
	public Handler<SshConnectRequest> handleSshConnectRequest = new Handler<SshConnectRequest>() {
		public void handle(SshConnectRequest event) {

			int sessionId = connect(event.getCredentials(), event
					.getHostname(), new CommandSpec("#connect",
					SSH_CONNECT_TIMEOUT, sessionCommandsMap.size(), true));

			trigger(new SshConnectResponse(event, sessionId), sshPort);
		}
	};
	
	
	private int addSession(Session session, CommandSpec command)
	{
		List<CommandSpec> listCommands = sessionCommandsMap.get(sessionCounter);
		if (listCommands == null) {
			listCommands = new ArrayList<CommandSpec>();
		}
		listCommands.add(command);
		sessionCommandsMap.put((int) sessionCounter, listCommands);

		sessionObjMap.put((int) sessionCounter, session);
		
		sessionCounter++;
		
		return sessionCounter;
	}

	public int runCommand(CommandSpec commandSpec, Session session,
			SshConn sshConn) throws IOException, InterruptedException {

		LineReader stdout = new LineReader(session.getStdout());
		LineReader stderr = new LineReader(session.getStderr());
		OutputStream stdin = session.getStdin();

		this.statusChange(sshConn, "executing: '" + commandSpec.getCommand()
				+ "'", LOG_FULL);
		stdin.write(commandSpec.getCommand().getBytes());
		stdin.write(("\necho \"" + EXIT_CODE_IDENTIFIER + "$?\"\n").getBytes());
		commandSpec.started();

		String line;
		String errLine;

		do {

			// session.waitForCondition(ChannelCondition.STDOUT_DATA
			// | ChannelCondition.STDERR_DATA, Math
			// .round(1000.0 / DATA_POLLING_FREQ));
			// XXX why sleep here?
			Thread.sleep(50);
			line = stdout.readLine();
			errLine = stderr.readLine();

			// check if we got any data on stderr
			while (errLine != null) {
				commandSpec.receivedErr(errLine);
				errLine = stderr.readLine();
			}
			// check for data on stdout
			while (line != null) {
				if (line.startsWith(EXIT_CODE_IDENTIFIER)) {
					String[] split = line.split("==");
					commandSpec.setExitCode(Integer.parseInt(split[1]));
					this.statusChange(sshConn, commandSpec.getCommand()
							+ " completed, code=" + commandSpec.getExitCode()
							+ " time=" + commandSpec.getExecutionTime()
							/ 1000.0, LOG_FULL);
					return commandSpec.getExitCode();
				}
				commandSpec.receivedData(line);

				// System.out.println(line);
				line = stdout.readLine();
			}

			if (commandSpec.isTimedOut()) {
				commandSpec.setExitCode(CommandSpec.RETURN_TIMEDOUT,
						"timed out");
				commandSpec
						.recievedControllErr("timeout after "
								+ (Math
										.round(commandSpec.getExecutionTime() * 10.0) / 10.0)
								+ " s");
				if (commandSpec.isStopOnError()) {
					commandSpec
							.recievedControllErr("command is stop on error, halting");
				}
				return commandSpec.getExitCode();
			}

			// handle the case when the command is killed
			if (commandSpec.isKilled()) {
				commandSpec.setExitCode(CommandSpec.RETURN_KILLED, "killed");
				commandSpec
						.recievedControllErr("killed after "
								+ (Math
										.round(commandSpec.getExecutionTime() * 10.0) / 10.0)
								+ " s");
				if (commandSpec.isStopOnError()) {
					commandSpec
							.recievedControllErr("command is stop on error, halting");
				}
				return commandSpec.getExitCode();
			}

			if (line == null) {
				line = "";
			}

		} while (!line.startsWith(EXIT_CODE_IDENTIFIER)
				&& (quit.get() == false));

		// we should never make it down here... unless quiting
		return Integer.MIN_VALUE;
	}

	private void runSpecialCommand(SshConn conn, CommandSpec commandSpec) {
		// handle these in a special way...
		String[] command = this.parseParameters(commandSpec.getCommand());
		if (command.length > 0) {
			if (command[0].equals(RpcFunctions.SPECIAL_COMMAND_UPLOAD_DIR)) {
				if (command.length == 2) {
					File fileOrDir = new File(command[1]);
					if (fileOrDir.exists()) {
						this.upload(conn, fileOrDir, commandSpec);
					}
				}
			} else if (command[0]
					.startsWith(RpcFunctions.SPECIAL_COMMAND_DOWNLOAD_DIR)) {
				if (command.length == 5) {
					String remotePath = command[1];
					File localFileOrDir = new File(command[2]);
					String fileFilter = command[3];
					String localNameType = command[4];
					if (localFileOrDir.exists()) {
						download(conn, remotePath, localFileOrDir, fileFilter,
								localNameType, commandSpec);
					}
				} else {
					System.err.println("parse error '"
							+ commandSpec.getCommand() + "'" + "length="
							+ command.length);
				}
			} else {
				System.err.println("unknown command '" + command[0] + "'");
			}
		} else {
			System.out.println("parameter parsing problem: '"
					+ commandSpec.getCommand() + "'");
		}
	}

	public boolean upload(SshConn conn, File fileOrDir, CommandSpec commandSpec) {
		return uploadDir(conn, fileOrDir, commandSpec);
	}

	public boolean uploadDir(SshComponent.SshConn conn, File baseDir,
			CommandSpec commandSpec) {
		try {
			UploadMD5CheckThread md5Check;

			md5Check = new UploadMD5CheckThread(conn, LocalDirMD5Info
					.getInstance().getFileInfo(baseDir), commandSpec);

			// no need to fire it up as a thread, just call the run method
			md5Check.run();

			return true;

		} catch (InterruptedException e) {
			commandSpec.receivedErr("local i/o error, " + e.getMessage());
		}
		return false;
	}

	public boolean download(SshConn conn, String remotePath, File localBaseDir,
			String fileFilter, String localNamingType, CommandSpec commandSpec) {
		// sanity checks
		if (fileFilter == null || fileFilter.length() == 0) {
			// match everything
			fileFilter = ".";
		}
		return downloadDir(conn, remotePath, localBaseDir, fileFilter,
				localNamingType, commandSpec);
	}

	private String[] parseParameters(String parameters) {
		String[] split = new String[0];
		if (parameters.contains("\"") && parameters.contains("'")) {
			System.err
					.println("sorry... arguments can only contain either \" or ', not both");
			return split;
		}

		if (parameters.contains("\"") || parameters.contains("'")) {
			// handle specially

			ArrayList<String> params = new ArrayList<String>();
			boolean withinQuotes = false;
			StringBuffer tmpBuffer = new StringBuffer();
			for (int i = 0; i < parameters.length(); i++) {

				char c = parameters.charAt(i);
				// System.out.println("processing: " + c);
				if (c == '"' || c == '\'') {
					withinQuotes = !withinQuotes;
					// System.out.println("w=" + withinQuotes);
					// continue to the next character
				} else {
					if (c == ' ' && !withinQuotes) {
						// we reached a space, and we are not between quoutes,
						// add to list and flush buffer
						params.add(tmpBuffer.toString());

						// System.out.println("found: " + tmpBuffer.toString()
						// + "(" + params.size() + ")");
						tmpBuffer = new StringBuffer();
					} else {
						// if the char is not ' ' or '"' or '\'', append to
						// stringbuffer

						tmpBuffer.append(c);
						// System.out.println("adding: " +
						// tmpBuffer.toString());
					}
				}
			}
			if (tmpBuffer.length() > 0) {
				params.add(tmpBuffer.toString());
			}
			split = params.toArray(split);
		} else {
			split = parameters.split(" ");
		}

		return split;
	}

	private int connect(Credentials credentials, ExperimentHost expHost,
			CommandSpec commandSpec) {

		Connection connection = new Connection(expHost.getHostname());

		SshConn sshConnection = new SshConn(expHost, credentials, connection);

		// SshConn sshConnection = activeSshConnections.get(session);

		List<SshConn> listActiveConns = new ArrayList<SshConn>(
				activeSshConnections.values());

		
		if (listActiveConns.contains(sshConnection) == true) {
			
			Set<Integer> sessions = activeSshConnections.keySet();
			for (Integer sId: sessions) {
				if (activeSshConnections.get(sId).compareTo(sshConnection) == 0) {
					return sId;
				}
			}
			throw new IllegalStateException("Found active connection, but no session object.");
		}

		commandSpec.started();

		sshConnection = new SshConn(expHost, credentials, connection);

		connection.addConnectionMonitor(sshConnection);

		// if (Main.getConfig(Constants.HTTP_PROXY_HOST) != null
		// && Main.getConfig(Constants.HTTP_PROXY_PORT) != null) {

		if (PlanetLabConfiguration.getHttpProxyHost().compareTo(
				PlanetLabConfiguration.DEFAULT_HTTP_PROXY_HOST) != 0
				&& PlanetLabConfiguration.getHttpProxyPort() != PlanetLabConfiguration.DEFAULT_HTTP_PROXY_PORT) {
			int port = PlanetLabConfiguration.getHttpProxyPort();
			String hostname = PlanetLabConfiguration.getHttpProxyHost();
			String username = PlanetLabConfiguration.getHttpProxyUsername();
			String password = PlanetLabConfiguration.getHttpProxyPassword();
			// if username AND password is specified
			if (username != PlanetLabConfiguration.DEFAULT_HTTP_PROXY_USERNAME
					&& password != PlanetLabConfiguration.DEFAULT_HTTP_PROXY_PASSWORD) {
				connection.setProxyData(new HTTPProxyData(hostname, port,
						username, password));
				System.out
						.println("ssh connect with http proxy and auth, host="
								+ hostname + " port=" + port + "user="
								+ username);
			} else {
				// ok, only hostname and port
				connection.setProxyData(new HTTPProxyData(
						PlanetLabConfiguration.getHttpProxyHost(), port));
				System.out.println("ssh connect with http proxy, host="
						+ hostname + " port=" + port);
			}
		}
		// try to open the connection

		int sessionId;
		try {
			// try to connect
			connection.connect(null, SSH_CONNECT_TIMEOUT,
					SSH_KEY_EXCHANGE_TIMEOUT);

			// try to authenticate
			// if (sshConn.authenticateWithPublicKey(controller
			// .getCredentials().getSlice(), new File(controller
			// .getCredentials().getKeyPath()), controller
			// .getCredentials().getKeyFilePassword())) {

			if (connection.authenticateWithPublicKey(credentials.getUsername(),
					new File(credentials.getKeyPath()), credentials
							.getKeyFilePassword())) {

				// ok, authentiaction succesfull, return the connection
				commandSpec.recievedControllData("connect successful");
				// isConnected = true;
				sshConnection.setConnected(true);

				Session session = startShell(sshConnection);

//				activeSshConnections.put(session, sshConnection);
//				return session;
				sessionId = addSession(session, new CommandSpec("#connect", SSH_CONNECT_TIMEOUT, sessionCommandsMap.size(), true));

			} else {
				// well, authentication failed
				statusChange(sshConnection, "auth failed", LOG_DEVEL);
				commandSpec.setExitCode(1, "auth failed");
				commandSpec.recievedControllErr("auth failed");
				sessionId = -1;
			}

			// handle errors...
		} catch (SocketTimeoutException e) {
			this.statusChange(sshConnection, "connection timeout: "
					+ e.getMessage(), LOG_DEVEL);
			if (e.getMessage().contains("kex")) {
				commandSpec.setExitCode(4, "kex timeout");
			} else {
				commandSpec.setExitCode(3, "conn timeout");
			}
			commandSpec.recievedControllErr(e.getMessage());
			sessionId = -1;
		} catch (IOException e) {

			if (e.getCause() != null) {
				commandSpec.recievedControllErr(e.getCause().getMessage());
				if (e.getCause().getMessage().contains("Connection reset")) {
					statusChange(sshConnection, e.getCause().getMessage(),
							LOG_DEVEL);
					commandSpec.setExitCode(2, "conn reset");

				} else if (e.getCause().getMessage().contains(
						"Connection refused")) {
					statusChange(sshConnection, e.getCause().getMessage(),
							LOG_DEVEL);
					commandSpec.setExitCode(2, "conn refused");

				} else if (e.getCause().getMessage().contains(
						"Premature connection close")) {
					statusChange(sshConnection, e.getCause().getMessage(),
							LOG_DEVEL);
					commandSpec.setExitCode(2, "prem close");

				} else if (e.getCause() instanceof java.net.UnknownHostException) {
					statusChange(sshConnection, e.getCause().getMessage(),
							LOG_DEVEL);
					commandSpec.setExitCode(2, "dns unknown");

				} else if (e.getCause() instanceof NoRouteToHostException) {
					statusChange(sshConnection, e.getCause().getMessage(),
							LOG_DEVEL);
					commandSpec.setExitCode(2, "no route");
				} else if (e.getMessage().contains("Publickey")) {
					statusChange(sshConnection, e.getMessage(), LOG_DEVEL);
					commandSpec.setExitCode(2, "auth error");
				} else {
					System.err.println("NEW EXCEPTION TYPE, handle...");

					e.printStackTrace();
				}
			} else {
				commandSpec.receivedErr(e.getMessage());
				commandSpec.setExitCode(255, "other");

				statusChange(sshConnection, e.getMessage(), LOG_DEVEL);
			}

			sessionId = -1;
		}

		return sessionId;

	}

	public Session startShell(SshConn conn) {
		Session session = null;
		if (conn.isConnected()) {
			try {
				session = conn.getConnection().openSession();
			} catch (IOException e) {
				statusChange(conn, "could not open session: " + e.getMessage(),
						LOG_ERROR);
				return null;
			}

			try {
				session.startShell();
			} catch (IOException e) {
				statusChange(conn, "could not start shell: " + e.getMessage(),
						LOG_ERROR);
				return null;
			}
		}
		return session;
	}

	private void statusChange(SshConn connection, String status, int level) {

		connection.setStatus(status);
		if (level <= LOG_LEVEL) {
			System.out.println(connection.getExpHost() + ": " + status);
		}
	}

	private static final String FLAT = "flat";

	private static final String HIERARCHY = "hierarchy";

	private static final String[] NAMING_TYPES = { HIERARCHY, FLAT };

	// private volatile String downloadDirectoryType = HIERARCHY;

	public boolean downloadDir(SshComponent.SshConn conn, String remotePath,
			File localBaseDir, String fileFilter, String localNamingType,
			CommandSpec commandSpec) {

		createLocalDir(localBaseDir);

		String downloadDirectoryType = getLocalFilenameType(localNamingType);

		RemoteDirMD5Info remoteMD5 = new RemoteDirMD5Info(conn);
		System.out.println("Getting file list");
		List<FileInfo> fileList;
		try {
			fileList = remoteMD5.getRemoteFileList(remotePath, fileFilter,
					commandSpec);
			for (FileInfo info : fileList) {
				if (downloadDirectoryType.equals(FLAT)) {
					info.setLocalFlatFile(localBaseDir);
				} else if (downloadDirectoryType.equals(HIERARCHY)) {
					info.setLocalHierarchicalFile(localBaseDir);
				}
			}
			System.out.println("starting md5 check thread");
//			MD5Check md5Check = new DownloadMD5CheckThread(conn, fileList,
//					commandSpec);
//			md5Check.run();
			
			// XXX send this as event to another component that will return the result asynchronously
			// XXX other component will launch a thread that returns the result
			
			Session s1 = null;
			SCPClient scpClient = conn.getConnection().createSCPClient();
			if (null != (s1 = startShell(conn))) {
				trigger(new DownloadMD5Request(scpClient, fileList, commandSpec), 
						md5Checker.getPositive(DownloadMgrPort.class));
//				downloadMD5Checker(conn, fileList, commandSpec);
			}
			
			return true;
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return false;
	}

	private boolean createLocalDir(File baseDir) {
		if (baseDir.isDirectory()) {
			return true;
		} else if (baseDir.mkdirs()) {
			return true;
		} else {
			System.err
					.println("could not create local directory for downloads: "
							+ baseDir);
			return false;
		}
	}

	public String getLocalFilenameType(String type) {
		if (FLAT.equals(type)) {
			return FLAT;
		} else if (HIERARCHY.equals(type)) {
			return HIERARCHY;
		} else {
			System.out.println("unknown local naming type: '" + type
					+ "', using default '" + DownloadManager.HIERARCHY + "'");
			return HIERARCHY;
		}
	}

	public List<FileInfo> getRemoteFileList(SshConn sshConn, String remoteDir,
			String filter, CommandSpec baseCommand) throws IOException,
			InterruptedException {
		baseCommand.started();
		baseCommand.receivedData("getting remote file list");
		CommandSpec command = this.generateCommand(remoteDir, filter);
		Session session = null;
		ArrayList<FileInfo> remoteFiles = new ArrayList<FileInfo>();
		// System.out.println("Starting shell");

		if (null != (session = startShell(sshConn))) {
			// System.out.println("Running command: " + command.getCommand());
			runCommand(sshConn, command, session);
			int numFiles = command.getLineNum();
			// System.out.println("got " + numFiles + " lines");
			for (int i = 1; i < numFiles; i++) {
				String line = command.getProcLine(i);
				int index = line.indexOf(" ");

				if (index > 0) {
					String md5 = line.substring(0, index);
					String path = line.substring(index + 2);
					// System.out.println(line);
					// System.out.println(md5 + "." + path);
					remoteFiles.add(new FileInfo(path, md5, sshConn
							.getExpHost().getHostname()));
				}

			}
			session.close();
		}
		baseCommand.receivedData("calculated md5 of " + remoteFiles.size()
				+ " files");
		baseCommand.setExitCode(0);

		return remoteFiles;
	}

	public boolean checkRemoteFile(Session session, CommandSpec commandResults,
			FileInfo file) throws IOException, InterruptedException {

		SshConn sshConn = activeSshConnections.get(session);
		if (sshConn == null)
		{
			throw new IOException("No connection for session");
		}
		
		CommandSpec commandSpec = this.md5CheckCommand(file);
		if (runCommand(sshConn, commandSpec, session) < 0) {
			// timeout or killed...

			return false;
		}
		boolean md5match = false;
		// does the file exists? md5sum returns 0 on success
		if (commandSpec.getExitCode() == 0) {
			// does the md5 match?
			String localMD5 = file.getMd5();
			String remoteMD5 = commandSpec.getProcLine(1).split(" ")[0];
			// System.out.println("checking "
			// + file.getRemoteFileName());
			if (localMD5.equals(remoteMD5)) {
				md5match = true;
				// System.out.println("passed");
				commandResults.recievedControllData("passed: "
						+ file.getFullRemotePath());
			} else {
				commandResults.recievedControllErr("copying (md5 failed):"
						+ file.getFullRemotePath());
			}
			// System.out.println("size: "
			// + commandSpec.getProcOutput(0).size());
		} else {
			commandResults.recievedControllErr("copying (missing):"
					+ file.getFullRemotePath());
		}
		return md5match;

	}

	private CommandSpec md5CheckCommand(FileInfo file) {
		return new CommandSpec("md5sum " + file.getFullRemotePath(), 0, 0,
				false);
	}

	private CommandSpec generateCommand(String remoteDir, String filter) {
		if (filter != null && filter != "") {
			return new CommandSpec("md5sum `find " + remoteDir + " | grep "
					+ filter + "` 2> /dev/null", 0, -1, false);
		} else {
			return new CommandSpec("md5sum `find " + remoteDir
					+ "` 2> /dev/null", 0, -1, false);
		}

	}

	public int runCommand(SshConn sshConn, CommandSpec commandSpec, Session session)
			throws IOException, InterruptedException {

		LineReader stdout = new LineReader(session.getStdout());
		LineReader stderr = new LineReader(session.getStderr());
		OutputStream stdin = session.getStdin();

		statusChange(sshConn, "executing: '" + commandSpec.getCommand() + "'",
				LOG_FULL);
		stdin.write(commandSpec.getCommand().getBytes());
		stdin.write(("\necho \"" + EXIT_CODE_IDENTIFIER + "$?\"\n").getBytes());
		commandSpec.started();

		String line;
		String errLine;

		do {

			// session.waitForCondition(ChannelCondition.STDOUT_DATA
			// | ChannelCondition.STDERR_DATA, Math
			// .round(1000.0 / DATA_POLLING_FREQ));
			Thread.sleep(50);
			line = stdout.readLine();
			errLine = stderr.readLine();

			// check if we got any data on stderr
			while (errLine != null) {
				commandSpec.receivedErr(errLine);
				errLine = stderr.readLine();
			}
			// check for data on stdout
			while (line != null) {
				if (line.startsWith(EXIT_CODE_IDENTIFIER)) {
					String[] split = line.split("==");
					commandSpec.setExitCode(Integer.parseInt(split[1]));
					this.statusChange(sshConn, commandSpec.getCommand()
							+ " completed, code=" + commandSpec.getExitCode()
							+ " time=" + commandSpec.getExecutionTime()
							/ 1000.0, LOG_FULL);
					return commandSpec.getExitCode();
				}
				commandSpec.receivedData(line);

				// System.out.println(line);
				line = stdout.readLine();
			}

			if (commandSpec.isTimedOut()) {
				commandSpec.setExitCode(CommandSpec.RETURN_TIMEDOUT,
						"timed out");
				commandSpec
						.recievedControllErr("timeout after "
								+ (Math
										.round(commandSpec.getExecutionTime() * 10.0) / 10.0)
								+ " s");
				if (commandSpec.isStopOnError()) {
					commandSpec
							.recievedControllErr("command is stop on error, halting");
				}
				return commandSpec.getExitCode();
			}

			// handle the case when the command is killed
			if (commandSpec.isKilled()) {
				commandSpec.setExitCode(CommandSpec.RETURN_KILLED, "killed");
				commandSpec
						.recievedControllErr("killed after "
								+ (Math
										.round(commandSpec.getExecutionTime() * 10.0) / 10.0)
								+ " s");
				if (commandSpec.isStopOnError()) {
					commandSpec
							.recievedControllErr("command is stop on error, halting");
				}
				return commandSpec.getExitCode();
			}

			if (line == null) {
				line = "";
			}

		} while (!line.startsWith(EXIT_CODE_IDENTIFIER) && !quit.get());

		// we should never make it down here... unless quiting
		return Integer.MIN_VALUE;
	}

	
	public void checkFile(FileInfo file) {
		try {
			fileMD5Hashes.put(file);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public Handler<DownloadMD5Response> handleDownloadMD5Response = new Handler<DownloadMD5Response>()
	{
		public void handle(DownloadMD5Response event) {
			
			
			
			
		}
	};
	
	
	public Handler<UploadMD5Response> handleUploadMD5Response = new Handler<UploadMD5Response>()
	{
		public void handle(UploadMD5Response event) {
			
			
		}
	};
	
	
	public Handler<HaltRequest> handleHaltRequest = new Handler<HaltRequest>()
	{
		public void handle(HaltRequest event) {
			
//			Session session = event.getSession();
			int sessionId = event.getSessionId();
			
//			this.isConnected = false;
//			commandQueue.clear();
//			this.quit = true;
//			this.interrupt();
//			this.disconnect();

			removeSession(sessionId);
			
			trigger(new HaltResponse(event, true), sshPort);
		}
	};

	
	private boolean removeSession(int sessionId)
	{
		// XXX check semantics of close()
		
		Session session = this.sessionObjMap.get(sessionId);
		
		if (session == null)
		{
			return false;
		}
		
		session.close();
		boolean status = (activeSshConnections.remove(session) == null) ? false : true; 
		
		sessionCommandsMap.remove(session);
		sessionObjMap.remove(sessionId);

		return status;
	}
}