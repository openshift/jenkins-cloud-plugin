package hudson.plugins.openshift;

import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.model.Items;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.User;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import net.sf.json.JSONObject;

import org.apache.commons.codec.binary.Base64;
import org.jvnet.hudson.reactor.ReactorException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import com.openshift.express.client.IOpenShiftService;
import com.openshift.express.client.IUser;
import com.openshift.express.client.OpenShiftException;
import com.openshift.express.client.OpenShiftService;
import com.openshift.express.client.configuration.DefaultConfiguration;
import com.openshift.express.client.configuration.SystemConfiguration;
import com.openshift.express.client.configuration.UserConfiguration;
import com.openshift.express.internal.client.ApplicationInfo;
import com.openshift.express.internal.client.InternalUser;
import com.openshift.express.internal.client.UserInfo;
import com.openshift.express.internal.client.utils.StreamUtils;

/**
 * Represents the available cloud of OpenShift instances for building.
 */
public final class OpenShiftCloud extends Cloud {
	private static final Logger LOGGER = Logger.getLogger(OpenShiftCloud.class
			.getName());
	public static final int APP_NAME_MAX_LENGTH = 32;
	public static final String APP_NAME_BUILDER_EXTENSION = "bldr";
	public static final String DEFAULT_LABEL = "raw-build";
	public static final long DEFAULT_TIMEOUT = 300000;
	private String username;
	private String password;
	private String authKey;
	private String authIV;
	private String brokerHost;
	private String brokerPort;
	private String proxyHost;
	private final int proxyPort;
	private final String defaultBuilderSize;
	private boolean ignoreBrokerCertCheck = true;
	private transient File privateKey;
	private String brokerAuthKey;
	private String brokerAuthIV;
	private IOpenShiftService service;

	static {
		javax.net.ssl.HttpsURLConnection
				.setDefaultHostnameVerifier(new javax.net.ssl.HostnameVerifier() {

					public boolean verify(String hostname,
							javax.net.ssl.SSLSession sslSession) {

						if (hostname.equals("localhost")) {
							return true;
						}
						return false;
					}
				});
	}

	@DataBoundConstructor
	public OpenShiftCloud(String username, String password, String brokerHost,
			String brokerPort, String proxyHost, int proxyPort,
			boolean ignoreBrokerCertCheck, String defaultBuilderSize)
			throws IOException {
		super("OpenShift Cloud");
		this.username = username;
		this.password = password;
		this.brokerHost = brokerHost;
		this.brokerPort = brokerPort;
		this.proxyHost = proxyHost;
		this.proxyPort = proxyPort;
		this.defaultBuilderSize = defaultBuilderSize;
		this.ignoreBrokerCertCheck = ignoreBrokerCertCheck;
	}

	public IOpenShiftService getOpenShiftService() throws IOException {
		if (service == null) {
			try {

				UserConfiguration userConfiguration = new UserConfiguration(
						new SystemConfiguration(new DefaultConfiguration()));
				if (username == null)
					username = userConfiguration.getRhlogin();

				authKey = getBrokerAuthKey();
				authIV = getBrokerAuthIV();

				String url = null;
				if (brokerHost == null) {
					brokerHost = userConfiguration.getLibraServer();
					url = "https://" + brokerHost.trim();

					LOGGER.info("Initiating Java Client Service - Configured for default OpenShift Server "
							+ url);
				} else {
					url = "https://" + brokerHost.trim();

					if (brokerPort != null && brokerPort.trim().length() > 0)
						url += ":" + brokerPort.trim();

					LOGGER.info("Initiating Java Client Service - Configured for OpenShift Server "
							+ url);
				}
				service = new OpenShiftService(username, url);

				service.setEnableSSLCertChecks(!ignoreBrokerCertCheck);

				if (proxyHost != null && proxyHost.length() > 0) {
					service.setProxySet(true);
					service.setProxyHost(proxyHost.trim());
					service.setProxyPort(Integer.toString(proxyPort));
				}
			} catch (OpenShiftException e) {
				throw new IOException(e);
			}
		}
		return service;
	}

	public String getUsername() {
		return username;
	}

	public String getAuthKey() {
		return authKey;
	}

	public String getAuthIV() {
		return authIV;
	}

	public String getDefaultBuilderSize() {
		return defaultBuilderSize;
	}

	public String getPassword() {
		return password;
	}

	public String getBrokerHost() {
		return brokerHost;
	}

	public String getBrokerPort() {
		return brokerPort;
	}

	public File getPrivateKey() {
		if (privateKey == null) {
			privateKey = locateKey();
		}
		return privateKey;
	}

	private String getBrokerAuthKey() throws IOException {
		if (brokerAuthKey == null) {
			String homeDir = System.getenv("HOME");
			brokerAuthKey = fileToString(homeDir + "/.auth/token");
		}
		return brokerAuthKey;
	}

	private String getBrokerAuthIV() throws IOException {
		if (brokerAuthIV == null) {
			String homeDir = System.getenv("HOME");
			brokerAuthIV = fileToString(homeDir + "/.auth/iv");
		}
		return brokerAuthIV;
	}

	private String fileToString(String filePath) throws IOException {
		File file = new File(filePath);
		String fileString = null;
		if (file.exists()) {
			FileReader fr = new FileReader(file);
			BufferedReader br = new BufferedReader(fr);
			StringBuilder sb = new StringBuilder();
			String s;
			while ((s = br.readLine()) != null) {
				sb.append(s);
			}
			fr.close();
			fileString = sb.toString();
		}
		return fileString;
	}

	public String getProxyHost() {
		return proxyHost;
	}

	public int getProxyPort() {
		return proxyPort;
	}

	public boolean getIgnoreBrokerCertCheck() {
		return ignoreBrokerCertCheck;
	}

	private File locateKey() {
		// Look in the environment variables for the key location
		String dataDir = System.getenv("OPENSHIFT_DATA_DIR");
		if (dataDir == null) {
			LOGGER.warning("Warning OPENSHIFT_DATA_DIR not found, resorting to test value");
			dataDir = System.getenv("HOME");
		}
		// Get the location of the private key
		return new File(dataDir + "/.ssh/jenkins_id_rsa");
	}

	public boolean canProvision(Label label) {
		return true;
	}

	/**
	 * Returns whether a new builder should be provisioned. If an existing build
	 * type exists or the user is out of application capacity, the result will
	 * be false.
	 * 
	 * @return whether a new builder should be provisioned
	 */
	protected boolean hasCapacity(String name, UserInfo userInfo) throws IOException {
		LOGGER.info("Checking capacity");
    	long maxGears = userInfo.getMaxGears();
        long consumedGears = userInfo.getConsumedGears();

		LOGGER.info("User has consumed " + consumedGears + " of " + maxGears
				+ " gears.");
		boolean hasCapacity = consumedGears < maxGears;

		if (!hasCapacity) {
			LOGGER.info("No capacity remaining.  Not provisioning...");
			return false;
		} 
		
		return true;
	}
	
	protected boolean builderExists(String name, UserInfo userInfo) throws IOException {
		List<ApplicationInfo> appInfos = userInfo.getApplicationInfos();
		LOGGER.info("Capacity remaining - checking for existing type...");
		for (@SuppressWarnings("unchecked")
		Iterator<ApplicationInfo> i = appInfos.iterator(); i.hasNext();) {
			ApplicationInfo app = i.next();
			if (app.getName().equals(name)) {
				LOGGER.info("Found an existing builder.  Not provisioning...");
				return true;
			}
		}

		LOGGER.info("No suitable builders found.");
		return false;
	}

	/*
	 * public HttpClient getHttpClient() throws IOException { HttpClient client
	 * = new HttpClient();
	 * 
	 * // Use a proxy if specified if (proxyHost != null &&
	 * !proxyHost.trim().isEmpty()) {
	 * client.getHostConfiguration().setProxy(proxyHost, proxyPort); }
	 * 
	 * // Ignore SSL if specified if (ignoreBrokerCertCheck) { try {
	 * TrustManager easyTrustManager = new X509TrustManager() { public
	 * X509Certificate[] getAcceptedIssuers() { return null; }
	 * 
	 * public void checkServerTrusted(X509Certificate[] chain, String authType)
	 * throws CertificateException { }
	 * 
	 * public void checkClientTrusted(X509Certificate[] chain, String authType)
	 * throws CertificateException { } }; SSLContext ctx =
	 * SSLContext.getInstance("TLS"); ctx.init(null, new TrustManager[] {
	 * easyTrustManager }, null); SSLContext.setDefault(ctx); } catch (Exception
	 * e) { throw new IOException(e); } }
	 * 
	 * return client; }
	 */

	public UserInfo getUserInfo() throws IOException {
		return getUserInfo(false);
	}

	/*
	 * private void setupLoginInfo(PostMethod method, JSONObject jsonObject)
	 * throws IOException { if (getBrokerAuthKey() != null) {
	 * method.addParameter("broker_auth_key", getBrokerAuthKey());
	 * method.addParameter("broker_auth_iv", getBrokerAuthIV()); } else {
	 * jsonObject.put("rhlogin", username); method.addParameter("password",
	 * password); } }
	 */

	public IUser getIUser() throws IOException {
		InternalUser user;

		service = getOpenShiftService();

		if (authKey != null)
			user = new InternalUser(username, authKey, authIV, service);
		else
			user = new InternalUser(username, OpenShiftCloud.get()
					.getPassword(), service);

		return user;
	}

	public UserInfo getUserInfo(boolean force) throws IOException {
		try {
			service = getOpenShiftService();

			IUser user = getIUser();
			if (authKey != null)
				user = new InternalUser(username, authKey, authIV, service);
			else
				user = new InternalUser(username, OpenShiftCloud.get()
						.getPassword(), service);

			UserInfo userInfo = service.getUserInfo(user);

			return userInfo;
		} catch (OpenShiftException e) {
			e.printStackTrace();
			throw new IOException(e);
		}

		/*
		 * if (jsonData == null || force) { HttpClient client = getHttpClient();
		 * PostMethod method = new PostMethod(url + "/userinfo"); JSONObject
		 * jsonObject = new JSONObject(); setupLoginInfo(method, jsonObject);
		 * method.addParameter("json_data", jsonObject.toString());
		 * client.executeMethod(method); byte[] responseBody =
		 * method.getResponseBody(); method.releaseConnection(); JSONObject
		 * strResponse = (JSONObject) JSONSerializer .toJSON(new
		 * String(responseBody)); String strData = (String)
		 * strResponse.get("data");
		 * 
		 * // You need to remove the quotes and re-parse after getting the //
		 * 'data' value strData = strData.substring(1, strData.length() - 1);
		 * jsonData = (JSONObject) JSONSerializer.toJSON(strData); }
		 * 
		 * LOGGER.info("Data = " + jsonData.toString());
		 * 
		 * return jsonData;
		 */
	}

	public Collection<PlannedNode> provision(Label label, int excessWorkload) {
        List<PlannedNode> result = new ArrayList<PlannedNode>();
        LOGGER.info("Provisioning new node for workload = " + excessWorkload
                + " and label = " + label);
       
        // First, sync the state of the running applications and the Jenkins
        // slaves
        List<OpenShiftSlave> slaves = null;
        try {
        	slaves = getSlaves();
            for (OpenShiftSlave slave : slaves) {
                Hudson.getInstance().addNode(slave);
            }
        }
        catch (IOException e) {
            LOGGER.log(Level.SEVERE,
                    "Exception caught trying to load existing slaves", e);
        }

        String builderType = "diy-0.1";
        String builderName = "raw" + APP_NAME_BUILDER_EXTENSION;
        String builderSize = OpenShiftCloud.get().getDefaultBuilderSize();
        long builderTimeout = DEFAULT_TIMEOUT;
        
        String labelStr = DEFAULT_LABEL;

        // Derive the builderType
        if (label != null) {
            labelStr = label.toString();
            
            AbstractProject job = Hudson.getInstance().getItemByFullName(
                    labelStr, AbstractProject.class);
            if (job != null) {
                OpenShiftBuilderSizeJobProperty osbsjp = ((OpenShiftBuilderSizeJobProperty) job
                        .getProperty(OpenShiftBuilderSizeJobProperty.class));
                builderSize = osbsjp.builderSize;

                OpenShiftBuilderTypeJobProperty osbtjp = ((OpenShiftBuilderTypeJobProperty) job
                        .getProperty(OpenShiftBuilderTypeJobProperty.class));
                builderType = osbtjp.builderType;
                
                OpenShiftBuilderTimeoutJobProperty timeoutJobProperty = ((OpenShiftBuilderTimeoutJobProperty) job
                        .getProperty(OpenShiftBuilderTimeoutJobProperty.class));
                if (timeoutJobProperty != null)
                	builderTimeout = timeoutJobProperty.builderTimeout;
                else
                	builderTimeout = -1;
                
                
                if (labelStr.endsWith("-build")) {
                    builderName = labelStr.substring(0, labelStr.indexOf("-build"));
                }
                
                if (builderName.length() > (APP_NAME_MAX_LENGTH - APP_NAME_BUILDER_EXTENSION.length())) {
                    builderName = builderName.substring(0, APP_NAME_MAX_LENGTH - APP_NAME_BUILDER_EXTENSION.length());
                }
                builderName = builderName + APP_NAME_BUILDER_EXTENSION;

            }
        } else {
        	LOGGER.info("Cancelling build - Label is null");
        	
        	cancelBuild(builderName);
        	
        	throw new UnsupportedOperationException( "No Label");
        }        

        final String framework = builderType;
        final String name = builderName;
        final String size = builderSize;
        final String plannedNodeName = labelStr;
        final long timeout = builderTimeout;
        final int executors = excessWorkload;
        
        try {
            // Create builders for any excess workload
              
            if (excessWorkload > 0){
            	OpenShiftSlave slave = getSlave(slaves, builderName);
            	UserInfo userInfo = getUserInfo();
            	
            	if (slave != null && builderExists(builderName, userInfo)) {
            		LOGGER.info("Slave exists without corresponding builder. Deleting slave");
            		slave.terminate();
            		return result;
            	}
            
            	if (!hasCapacity(name, userInfo)) {
                    LOGGER.info("Not provisioning new builder...");
                } else {
                	reloadConfig(label);
                	PlannedNode node = new PlannedNode(plannedNodeName,
                        Computer.threadPoolForRemoting
                                .submit(new Callable<Node>() {
                                    public Node call() throws Exception {
                                        // Provision a new slave builder
                                        OpenShiftSlave slave = new OpenShiftSlave(
                                                name, framework, size,
                                                plannedNodeName, timeout, executors);
                                        
                                        try {
	                                        slave.provision();
	                                        Hudson.getInstance().addNode(slave);
	                                        return slave;
                                        } catch (Exception e){
                                        	LOGGER.warning("Unable to provision node " + e);
                                        	cancelBuild(name, plannedNodeName);
                                        	throw e;
                                        }
                                    }
                                }), executors);

                	result.add(node);
                }
                
                LOGGER.info("Provisioned " + result.size() + " new nodes");
                
                if (result.size() == 0) {
                	cancelBuild(builderName, label.getName());
                }
            }
        }
        catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Exception caught trying to provision", e);
        }
        
        return result;
    }
	
	protected void reloadConfig(Label label) throws IOException, InterruptedException, ReactorException {
		LOGGER.info("Reloading configuration for " + label.toString() + "...");
    	
    	String ip = System.getenv("OPENSHIFT_INTERNAL_IP");
    	String port = System.getenv("OPENSHIFT_INTERNAL_PORT");
    	String username = System.getenv("JENKINS_USERNAME");
    	String password = System.getenv("JENKINS_PASSWORD");
    	
    	String name = label.toString();
    	//if (name.endsWith("-build")) {
        //    name = name.substring(0, name.indexOf("-build"));
        //}
    	
    	URL url = new URL("http://" + ip + ":" + port + "/job/" + name + "/config.xml");
    	HttpURLConnection connection = createConnection(url, username, password);
    	connection.setRequestMethod("GET");
    	String get = readToString(connection.getInputStream());
    	LOGGER.info("Reload config " + get);
    	
    	connection = createConnection(url, username, password);
    	connection.setRequestMethod("POST");
    	connection.setDoOutput(true);
    	writeTo(get.getBytes(), connection.getOutputStream());
    	String result = readToString(connection.getInputStream());
    	LOGGER.info("Reload result " + result);
	}
	
	protected void writeTo(byte[] data, OutputStream outputStream) throws IOException {
		outputStream.write(data);
		outputStream.flush();
		outputStream.close();
	}
	
	protected HttpURLConnection createConnection(URL url, String username, String password) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
	
//		HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
//		httpsConnection.setHostnameVerifier(new NoopHostnameVerifier());
//		setPermissiveSSLSocketFactory(httpsConnection);
		
		connection.setUseCaches(false);
		connection.setDoInput(true);
		connection.setAllowUserInteraction(true);
		connection.setRequestProperty("Content-Type", "text/plain");
		connection.setInstanceFollowRedirects(true);
		
		LOGGER.info("Using credentials " + username + ":" + password);
	
		String basicAuth = "Basic " + new String(Base64.encodeBase64(new String(username + ":" + password).getBytes()));
		connection.setRequestProperty("Authorization", basicAuth);
		
		return connection;
	}
	
	private void setPermissiveSSLSocketFactory(HttpsURLConnection connection) {
		try {
			SSLContext sslContext = SSLContext.getInstance("SSL");
			sslContext.init(new KeyManager[0], new TrustManager[] { new PermissiveTrustManager() }, new SecureRandom());
			SSLSocketFactory socketFactory = sslContext.getSocketFactory();
			((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
		} catch (KeyManagementException e) {
			// ignore
		} catch (NoSuchAlgorithmException e) {
			// ignore
		}
	}
	
	private String readToString(InputStream inputStream) throws IOException {
		if (inputStream == null) {
			return null;
		}
		byte[] data = new byte[inputStream.available()];
		inputStream.read(data);
		return new String(data);
	}
	
	protected boolean isBuildRunning(Label label) {	
    	boolean running = false;
    	Job job = (Job)Hudson.getInstance().getItem(label.getName());
    	if (job != null){
	    	Queue.Item item = job.getQueueItem();
	    	if (item != null)
	    		running = true;
    	}
		
		return running;
	}
	
	protected OpenShiftSlave getSlave(List<OpenShiftSlave> slaves, String builderName) {
		for (OpenShiftSlave slave : slaves) {
			LOGGER.info("slaveExists " + slave.getDisplayName() + " " + builderName);
			if (slave.getDisplayName().equals(builderName))
				return slave;
		}
		return null;
	}
	
	protected void cancelBuild(String builderName) {
		cancelBuild(builderName, null);
	}
	
	protected void cancelBuild(String builderName, String label) {
		LOGGER.info("Cancelling build");
		try {			
			Node existingNode = Hudson.getInstance().getNode(builderName);
	    	if (existingNode != null && existingNode instanceof OpenShiftSlave){
	    		((OpenShiftSlave)existingNode).terminate();
	    	}
	    
	    	Job job = null;
	    	
	    	if (label != null)
	    		job = (Job)Hudson.getInstance().getItem(label);
	    	
	    	if (job != null){
		    	Queue.Item item = job.getQueueItem();
		    	if (item != null){
		    		Queue queue = Queue.getInstance();
		    		boolean cancelled = queue.cancel(item);
		    		LOGGER.warning("Build for job " + job.getName() + " has been cancelled");
		    	}
	    	} else {
	    		Queue queue = Hudson.getInstance().getQueue();
	    		Queue.Item[] items = queue.getItems();
		    	if (items != null && items.length > 0){
		    		boolean cancelled = queue.cancel(items[0]);
		    		LOGGER.warning("Build for label/builderName " + label + "/" + builderName + " has been cancelled");
		    	}
	    	}
		} catch (Exception e){
			LOGGER.log(Level.SEVERE, "Exception caught trying to terminate slave", e);
		} 
	}

	@Extension
	public static final class DescriptorImpl extends Descriptor<Cloud> {
		private String username;
		private String password;
		private String brokerHost;
		private String brokerPort;
		private String proxyHost;
		private String defaultBuilderSize;
		private int proxyPort;
		private boolean ignoreBrokerCertCheck;

		public String getDisplayName() {
			return "OpenShift Cloud";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject o)
				throws FormException {

			username = o.getString("username");
			password = o.getString("password");
			brokerHost = o.getString("brokerHost");
			brokerPort = o.getString("brokerPort");
			proxyHost = o.getString("proxyHost");
			proxyPort = o.getInt("proxyPort");
			ignoreBrokerCertCheck = o.getBoolean("ignoreBrokerCertCheck");
			defaultBuilderSize = o.getString("defaultBuilderSize");
			save();

			return super.configure(req, o);
		}

		public String getUsername() {
			return username;
		}

		public String getPassword() {
			return password;
		}

		public String getBrokerHost() {
			return brokerHost;
		}

		public String getBrokerPort() {
			return brokerPort;
		}

		public String getProxyHost() {
			return proxyHost;
		}

		public int getProxyPort() {
			return proxyPort;
		}

		public boolean getIgnoreBrokerCertCheck() {
			return ignoreBrokerCertCheck;
		}

		public String getDefaultBuilderSize() {
			return defaultBuilderSize;
		}
	}

	/**
	 * Gets the first {@link OpenShiftCloud} instance configured or null
	 */
	public static OpenShiftCloud get() {
		return Hudson.getInstance().clouds.get(OpenShiftCloud.class);
	}

	@SuppressWarnings("unchecked")
	protected List<OpenShiftSlave> getSlaves() throws IOException {

		List<ApplicationInfo> appInfos = getUserInfo(true)
				.getApplicationInfos();
		List<OpenShiftSlave> slaveList = new ArrayList<OpenShiftSlave>();

		for (Iterator<ApplicationInfo> i = appInfos.iterator(); i.hasNext();) {
			ApplicationInfo appInfo = i.next();
			String appName = appInfo.getName();
			if (appName.endsWith(APP_NAME_BUILDER_EXTENSION)) {
				Node node = Hudson.getInstance().getNode(appName);
				OpenShiftSlave slave = null;
				if (node == null || !(node instanceof OpenShiftSlave)) {
					LOGGER.info("Didn't find existing slave for: " + appName);
					try {
						String framework = appInfo.getCartridge().getName();

						slave = new OpenShiftSlave(appName, framework,
								OpenShiftCloud.get().getDefaultBuilderSize(),
								DEFAULT_LABEL, DEFAULT_TIMEOUT, 1);
					} catch (Exception e) {
						throw new IOException(e);
					}
				} else {
					slave = (OpenShiftSlave) node;
					LOGGER.info("Found existing slave for: " + appName);
				}
				slaveList.add(slave);
			}
		}
		return slaveList;
	}
	
	private static class NoopHostnameVerifier implements HostnameVerifier {

		public boolean verify(String hostname, SSLSession sslSession) {
			return true;
		}
	}

	private static class PermissiveTrustManager implements X509TrustManager {

		public X509Certificate[] getAcceptedIssuers() {
			return null;
		}

		public void checkServerTrusted(X509Certificate[] chain,
				String authType) throws CertificateException {
		}

		public void checkClientTrusted(X509Certificate[] chain,
				String authType) throws CertificateException {
		}
	}
}
