package hudson.plugins.openshift;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
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
import java.net.HttpURLConnection;
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
import java.util.concurrent.Future;
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

import com.openshift.client.IApplication;
import com.openshift.client.IOpenShiftConnection;
import com.openshift.client.IUser;
import com.openshift.client.OpenShiftConnectionFactory;
import com.openshift.client.OpenShiftException;
import com.openshift.client.configuration.DefaultConfiguration;
import com.openshift.client.configuration.SystemConfiguration;
import com.openshift.client.configuration.UserConfiguration;
import com.openshift.client.NoopSSLCertificateCallback;

/**
 * Represents the available cloud of OpenShift instances for building.
 */
public final class OpenShiftCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(OpenShiftCloud.class
            .getName());
    public static final int APP_NAME_MAX_LENGTH = 32;
    public static final String APP_NAME_BUILDER_EXTENSION = "bldr";
    public static final String DEFAULT_LABEL = "raw-build";
    public static final String DEFAULT_PLATFORM = "Linux";
    public static final long DEFAULT_TIMEOUT = 300000;
    private static final int FAILURE_LIMIT = 5;
    private static final int RETRY_DELAY = 5000;

    private static final int DEFAULT_CONNECT_TIMEOUT = 10 * 1024;
    private static final int DEFAULT_READ_TIMEOUT = 60 * 1024;
    private static final String SYSPROP_OPENSHIFT_CONNECT_TIMEOUT = "com.openshift.httpclient.timeout";
    private static final String SYSPROP_DEFAULT_CONNECT_TIMEOUT = "sun.net.client.defaultConnectTimeout";
    private static final String SYSPROP_DEFAULT_READ_TIMEOUT = "sun.net.client.defaultReadTimeout";
    private static final String SYSPROP_ENABLE_SNI_EXTENSION = "jsse.enableSNIExtension";
    private static final String SYSPROPERTY_PROXY_PORT = "proxyPort";
    private static final String SYSPROPERTY_PROXY_HOST = "proxyHost";
    private static final String SYSPROPERTY_PROXY_SET = "proxySet";

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
    private int slaveIdleTimeToLive = 15;
    private int maxSlaveIdleTimeToLive = 15;
    private transient File privateKey;
    private String brokerAuthKey;
    private String brokerAuthIV;
    private transient IOpenShiftConnection service;

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
                          boolean ignoreBrokerCertCheck, int slaveIdleTimeToLive,
                          int maxSlaveIdleTimeToLive, String defaultBuilderSize)
            throws IOException {
        super("OpenShift Cloud");
        this.username = username;
        this.password = password;
        this.brokerHost = brokerHost;
        this.brokerPort = brokerPort;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.maxSlaveIdleTimeToLive = maxSlaveIdleTimeToLive;
        this.slaveIdleTimeToLive = limitSlaveIdleTimeToLive(
                slaveIdleTimeToLive, maxSlaveIdleTimeToLive);
        this.defaultBuilderSize = defaultBuilderSize;
        this.ignoreBrokerCertCheck = ignoreBrokerCertCheck;
    }

    private String getNamespace() {
        return System.getenv("OPENSHIFT_NAMESPACE");
    }

    public IOpenShiftConnection getOpenShiftConnection() throws IOException {
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

                service = new OpenShiftConnectionFactory().getConnection(
                        username, username, password, authKey, authIV, null, url, new NoopSSLCertificateCallback());


                if (proxyHost != null && proxyHost.length() > 0) {
                    System.setProperty(SYSPROPERTY_PROXY_SET, "true");
                    System.setProperty(SYSPROPERTY_PROXY_HOST, proxyHost.trim());
                    System.setProperty(SYSPROPERTY_PROXY_PORT, Integer.toString(proxyPort));
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
        String homeDir = System.getenv("HOME");
        brokerAuthKey = fileToString(homeDir + "/.auth/token");
        return brokerAuthKey;
    }

    private String getBrokerAuthIV() throws IOException {
        String homeDir = System.getenv("HOME");
        brokerAuthIV = fileToString(homeDir + "/.auth/iv");
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

    public int getSlaveIdleTimeToLive() {
        return slaveIdleTimeToLive;
    }

    public int getMaxSlaveIdleTimeToLive() {
        return maxSlaveIdleTimeToLive;
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
        return label!=null;
    }

    /**
     * Returns whether a new builder should be provisioned. If an existing build
     * type exists or the user is out of application capacity, the result will
     * be false.
     *
     * @return whether a new builder should be provisioned
     */
    protected boolean hasCapacity(String name, IUser user) throws IOException {
        LOGGER.info("Checking capacity");
        long maxGears = user.getMaxGears();
        long consumedGears = user.getConsumedGears();

        LOGGER.info("User has consumed " + consumedGears + " of " + maxGears
                + " gears.");
        boolean hasCapacity = consumedGears < maxGears;

        if (!hasCapacity) {
            LOGGER.info("No capacity remaining.  Not provisioning...");
            return false;
        }

        return true;
    }

    protected boolean builderExists(String name, IUser userInfo)
            throws IOException, OpenShiftException {
        LOGGER.info("Capacity remaining - checking for existing type...");
        for (IApplication app : userInfo.getDomain(getNamespace()).getApplications()) {
            if (app.getName().equals(name)) {
                LOGGER.info("Found an existing builder.  Not provisioning...");
                return true;
            }
        }

        LOGGER.info("No suitable builders found.");
        return false;
    }

    public Collection<PlannedNode> provision(Label label, int excessWorkload) {

        System.setProperty("com.openshift.httpclient.timeout", "300000");
        System.setProperty("sun.net.client.defaultConnectTimeout", "300000");
        System.setProperty("sun.net.client.defaultReadTimeout", "300000");

        service = null;

        LOGGER.info("Provisioning new node for workload = " + excessWorkload
                + " and label = " + label + " in domain " + getNamespace());

        if (slaveIdleTimeToLive == 0)
            slaveIdleTimeToLive = 15;

        String applicationUUID = null;
        String builderType = "diy-0.1";
        String builderName = "raw" + APP_NAME_BUILDER_EXTENSION;
        String builderSize = OpenShiftCloud.get().getDefaultBuilderSize();
        String region = null;
        String builderPlatform = DEFAULT_PLATFORM;
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

                OpenShiftRegionJobProperty osrjp = ((OpenShiftRegionJobProperty) job
                        .getProperty(OpenShiftRegionJobProperty.class));
                region = osrjp==null?null:osrjp.region;

                OpenShiftApplicationUUIDJobProperty osappuidjp = ((OpenShiftApplicationUUIDJobProperty) job
                        .getProperty(OpenShiftApplicationUUIDJobProperty.class));
                applicationUUID = osappuidjp==null?null:osappuidjp.applicationUUID;

                OpenShiftBuilderTypeJobProperty osbtjp = ((OpenShiftBuilderTypeJobProperty) job
                        .getProperty(OpenShiftBuilderTypeJobProperty.class));
                builderType = osbtjp.builderType;

                OpenShiftPlatformJobProperty ospjp = ((OpenShiftPlatformJobProperty) job
                        .getProperty(OpenShiftPlatformJobProperty.class));
                if(ospjp!=null) {
                    builderPlatform = ospjp.platform;
                }

                OpenShiftBuilderTimeoutJobProperty timeoutJobProperty = ((OpenShiftBuilderTimeoutJobProperty) job
                        .getProperty(OpenShiftBuilderTimeoutJobProperty.class));
                if (timeoutJobProperty != null)
                    builderTimeout = timeoutJobProperty.builderTimeout;
                else
                    builderTimeout = -1;

                if (labelStr.endsWith("-build")) {
                    builderName = labelStr.substring(0,
                            labelStr.indexOf("-build"));
                }

                if (builderName.length() > (APP_NAME_MAX_LENGTH - APP_NAME_BUILDER_EXTENSION
                        .length())) {
                    builderName = builderName.substring(0, APP_NAME_MAX_LENGTH
                            - APP_NAME_BUILDER_EXTENSION.length());
                }
                builderName = builderName + APP_NAME_BUILDER_EXTENSION;

            }
        } else {
            LOGGER.info("Cancelling build - Label is null");

            cancelBuild(builderName);

            throw new UnsupportedOperationException("No Label");
        }

        Queue.Item item = getItem(builderName, labelStr);

        List<PlannedNode> result = new ArrayList<PlannedNode>();

        int failures = 0;
        Exception exception = null;
        while (failures < FAILURE_LIMIT) {
            try {
                provisionSlave(result, applicationUUID, builderType, builderName, builderSize, region, builderPlatform,
                        label, labelStr, builderTimeout, excessWorkload);

                LOGGER.info("Provisioned " + result.size() + " new nodes");

                if (result.size() == 0) {
                    cancelItem(item, builderName, labelStr);
                }

                return result;
            } catch (Exception e) {
                ++failures;
                exception = e;

                LOGGER.warning("Caught " + e + ". Will retry "
                        + (FAILURE_LIMIT - failures)
                        + " more times before canceling build.");


                try {
                    Thread.sleep(RETRY_DELAY);
                } catch (Exception e1) {
                }
            }
        }

        LOGGER.warning("Cancelling build due to earlier exceptions");
        if (null != exception) {
            exception.printStackTrace();
        }

        this.cancelItem(item, builderName, labelStr);

        return result;
    }

    protected void provisionSlave(List<PlannedNode> result, String appUUID, String builderType, String builderName, String builderSize, String region, String builderPlatform, Label label, String labelStr, long builderTimeout, int excessWorkload)
            throws Exception {
        List<OpenShiftSlave> slaves = getSlaves();
        for (OpenShiftSlave slave : slaves) {
            Hudson.getInstance().addNode(slave);
        }

        final String applicationUUID = appUUID;
        final String type = builderType;
        final String name = builderName;
        final String size = builderSize;
        final String platform = builderPlatform;
        final String plannedNodeName = labelStr;
        final long timeout = builderTimeout;
        final int executors = excessWorkload;

        if (excessWorkload <= 0) return;

        OpenShiftSlave slave = getSlave(slaves, builderName);

        IUser user = this.getOpenShiftConnection().getUser();

        if (slave != null && builderExists(builderName, user)) {
            LOGGER.info("Slave exists. Not provisioning");
            return;
        }

        if (!hasCapacity(name, user)) {
            LOGGER.info("Not provisioning new builder due to lack of capacity");
            return;
        }

        reloadConfig(label);

        // Provision a new slave builder
        final OpenShiftSlave newSlave = new OpenShiftSlave(
                name, applicationUUID, type, size, region, platform,
                plannedNodeName, timeout,
                executors, slaveIdleTimeToLive);

        newSlave.provision();

        Future<Node> future = Computer.threadPoolForRemoting.submit(new Callable<Node>() {
            public Node call() throws Exception {
                Hudson.getInstance().addNode(newSlave);
                return newSlave;
            }
        });

        PlannedNode node = new PlannedNode(plannedNodeName, future, executors);
        result.add(node);
    }

    protected void reloadConfig(Label label) throws IOException,
            InterruptedException, ReactorException {
        LOGGER.info("Reloading configuration for " + label.toString() + "...");

        String ip = System.getenv("OPENSHIFT_JENKINS_IP");
        if (ip == null) {
            ip = System.getenv("OPENSHIFT_INTERNAL_IP");
        }
        String port = System.getenv("OPENSHIFT_JENKINS_PORT");
        if (port == null) {
            port = System.getenv("OPENSHIFT_INTERNAL_PORT");
        }
        String username = System.getenv("JENKINS_USERNAME");
        String password = System.getenv("JENKINS_PASSWORD");

        String name = label.toString();

        URL url = new URL("http://" + ip + ":" + port + "/job/" + name
                + "/config.xml");
        HttpURLConnection connection = null;
        String config = null;

        try {
            LOGGER.info("Retrieving config XML from " + url.toString());

            connection = createConnection(url, username, password);
            connection.setRequestMethod("GET");
            config = readToString(connection.getInputStream());

            if (config == null || config.trim().length() == 0) {
                throw new RuntimeException(
                        "Received empty config XML from API call to "
                                + url.toString());
            }

            config = config.trim();

        } catch (IOException e) {
            LOGGER.warning("Reload GET:");
            if (config != null) {
                for (char c : config.toCharArray()) {
                    System.out.printf("U+%04x ", (int) c);
                }
            }
            throw e;
        } finally {
            if (connection != null)
                connection.disconnect();
        }

        try {
            LOGGER.info("Reloading config from XML: " + config);

            connection = createConnection(url, username, password);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);

            writeTo(config.getBytes(), connection.getOutputStream());
            String result = readToString(connection.getInputStream());

            int code = connection.getResponseCode();

            LOGGER.info("Reload ResponseCode: " + code);

            if (code != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("Received an invalid response ("
                        + code + ") while updating config XML. "
                        + "Server response: " + result);
            }

            LOGGER.info("Config reload result: " + result);
        } catch (IOException e) {
            LOGGER.warning("Reload POST:");
            if (config != null) {
                for (char c : config.toCharArray()) {
                    System.out.printf("U+%04x ", (int) c);
                }
            }
            throw e;
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    protected void writeTo(byte[] data, OutputStream outputStream)
            throws IOException {
        outputStream.write(data);
        outputStream.flush();
        outputStream.close();
    }

    protected HttpURLConnection createConnection(URL url, String username,
                                                 String password) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setAllowUserInteraction(false);
        connection.setRequestProperty("Content-Type", "text/plain");
        connection.setInstanceFollowRedirects(true);
        setConnectTimeout(connection);
        setReadTimeout(connection);

        LOGGER.info("Using credentials " + username + ":" + password);

        String basicAuth = "Basic "
                + new String(Base64.encodeBase64(new String(username + ":"
                + password).getBytes()));
        connection.setRequestProperty("Authorization", basicAuth);

        return connection;
    }

    private void setConnectTimeout(URLConnection connection) {
        int timeout = getSystemPropertyInteger(SYSPROP_OPENSHIFT_CONNECT_TIMEOUT);
        if (timeout > -1) {
            connection.setConnectTimeout(timeout);
            return;
        }
        timeout = getSystemPropertyInteger(SYSPROP_DEFAULT_CONNECT_TIMEOUT);
        if (timeout == -1) {
            connection.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        }
    }

    private void setReadTimeout(URLConnection connection) {
        int timeout = getSystemPropertyInteger(SYSPROP_DEFAULT_READ_TIMEOUT);
        if (timeout == -1) {
            connection.setReadTimeout(DEFAULT_READ_TIMEOUT);
        }
    }

    private int getSystemPropertyInteger(String key) {
        try {
            return Integer.parseInt(System.getProperty(key));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void setPermissiveSSLSocketFactory(HttpsURLConnection service) {
        try {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(new KeyManager[0],
                    new TrustManager[]{new PermissiveTrustManager()},
                    new SecureRandom());
            SSLSocketFactory socketFactory = sslContext.getSocketFactory();
            ((HttpsURLConnection) service).setSSLSocketFactory(socketFactory);
        } catch (KeyManagementException e) {
            // ignore
        } catch (NoSuchAlgorithmException e) {
            // ignore
        }
    }

    public static String readToString(InputStream inputStream)
            throws IOException {
        if (inputStream == null) {
            return null;
        }
        return readToString(new InputStreamReader(inputStream));
    }

    public static String readToString(Reader reader) throws IOException {
        if (reader == null) {
            return null;
        }
        BufferedReader bufferedReader = new BufferedReader(reader);
        StringWriter writer = new StringWriter();
        String line = null;
        while ((line = bufferedReader.readLine()) != null) {
            writer.write(line);
            writer.write('\n');
        }
        return writer.toString();
    }

    protected boolean isBuildRunning(Label label) {
        boolean running = false;
        Job job = (Job) Hudson.getInstance().getItem(label.getName());
        if (job != null) {
            Queue.Item item = job.getQueueItem();
            if (item != null)
                running = true;
        }

        return running;
    }

    protected OpenShiftSlave getSlave(List<OpenShiftSlave> slaves,
                                      String builderName) {

        if (slaves != null) {
            for (OpenShiftSlave slave : slaves) {
                LOGGER.info("slaveExists " + slave.getDisplayName() + " "
                        + builderName);
                if (slave.getDisplayName().equals(builderName))
                    return slave;
            }
        }
        return null;
    }

    protected void cancelBuild(String builderName) {
        cancelBuild(builderName, null);
    }

    protected Queue.Item getItem(String builderName, String label) {
        try {
            Job job = null;

            if (label != null)
                job = (Job) Hudson.getInstance().getItem(label);

            if (job != null) {
                Queue.Item item = job.getQueueItem();
                if (item != null) {
                    return item;
                }
            } else {
                Queue queue = Hudson.getInstance().getQueue();
                Queue.Item[] items = queue.getItems();
                if (items != null && items.length > 0) {
                    return items[0];
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Exception caught trying to terminate slave", e);
        }
        return null;
    }

    protected void cancelBuild(String builderName, String label) {
        LOGGER.info("Cancelling build");
        try {
            Node existingNode = Hudson.getInstance().getNode(builderName);
            if (existingNode != null && existingNode instanceof OpenShiftSlave) {
                ((OpenShiftSlave) existingNode).terminate();
            }

            Queue.Item item = getItem(builderName, label);

            cancelItem(item, builderName, label);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Exception caught trying to terminate slave", e);
        }
    }

    protected void cancelItem(Queue.Item item, String builderName, String label) {
        LOGGER.info("Cancelling Item ");
        try {

            if (item != null) {
                Queue queue = Queue.getInstance();
                boolean canceled = queue.cancel(item);
                LOGGER.warning("Build " + label + " " + builderName
                        + " has been canceled");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Exception caught trying to terminate slave", e);
        }
    }

    protected int limitSlaveIdleTimeToLive(int slaveTimeToLive,
                                           int maxSlaveIdleTimeToLive) {

        if (slaveTimeToLive == 0 || maxSlaveIdleTimeToLive == 0) {
            return 15;
        }

        if (maxSlaveIdleTimeToLive > 0
                && (slaveTimeToLive < 0 || slaveTimeToLive > maxSlaveIdleTimeToLive)) {
            LOGGER.warning("Slave Idle Time to Live  " + slaveTimeToLive
                    + " is greater than the max allowed "
                    + maxSlaveIdleTimeToLive + ". Using max.");
            return maxSlaveIdleTimeToLive;
        }

        return slaveTimeToLive;
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
        private int slaveIdleTimeToLive = 15;
        private int maxSlaveIdleTimeToLive = 15;

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
            slaveIdleTimeToLive = o.getInt("slaveIdleTimeToLive");
            maxSlaveIdleTimeToLive = o.getInt("maxSlaveIdleTimeToLive");
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

        public int getSlaveIdleTimeToLive() {
            return slaveIdleTimeToLive;
        }

        public int getMaxSlaveIdleTimeToLive() {
            return maxSlaveIdleTimeToLive;
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
    protected List<OpenShiftSlave> getSlaves() throws IOException,
            OpenShiftException {

        List<OpenShiftSlave> slaveList = new ArrayList<OpenShiftSlave>();

        for (IApplication appInfo : this.getOpenShiftConnection().getUser()
                .getDomain(getNamespace()).getApplications()) {
            String appName = appInfo.getName();
            if (appName.endsWith(APP_NAME_BUILDER_EXTENSION) && !appName.equals(APP_NAME_BUILDER_EXTENSION)) {
                Node node = Hudson.getInstance().getNode(appName);
                OpenShiftSlave slave = null;
                if (node == null || !(node instanceof OpenShiftSlave)) {
                    LOGGER.info("Didn't find existing slave for: " + appName);
                    try {
                        String framework = appInfo.getCartridge().getName();

                        slave = new OpenShiftSlave(appName, appInfo.getUUID(),framework,
                                OpenShiftCloud.get().getDefaultBuilderSize(), null, DEFAULT_PLATFORM,
                                DEFAULT_LABEL, DEFAULT_TIMEOUT, 1,
                                slaveIdleTimeToLive);
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

        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
        }
    }
}

