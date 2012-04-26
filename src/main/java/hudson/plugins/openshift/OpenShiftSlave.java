package hudson.plugins.openshift;

import hudson.Extension;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.NodeProperty;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

import com.openshift.express.client.IApplication;
import com.openshift.express.client.ICartridge;
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

public class OpenShiftSlave extends AbstractCloudSlave {
    private static final long serialVersionUID = 8486485671018263774L;
    private static final Logger LOGGER = Logger.getLogger(OpenShiftSlave.class
            .getName());
    
    private String framework;
    private final String builderSize;
    private final long builderTimeout;
    private String uuid;

    /**
     * The name of the slave should be the 'sanitized version of the framework
     * that is used, removing all '.' and '-' characters (i.e. jbossas70, php53)
     * 
     * The framework should be the exact OpenShift framework used (i.e.
     * jbossas-7)
     */
    @DataBoundConstructor
    public OpenShiftSlave(String name, String framework, String builderSize,
            String label, long builderTimeout, int executors) throws FormException, IOException {
        super(name, "Builder for " + label, name + "/ci/jenkins", executors, Mode.NORMAL,
                label, new OpenShiftComputerLauncher(),
                new CloudRetentionStrategy(15), Collections
                        .<NodeProperty<?>> emptyList());
           
        this.framework = framework;
        this.builderSize = builderSize;
        this.builderTimeout = builderTimeout;
        
        mapLegacyFrameworks();
    }
    
    protected void mapLegacyFrameworks(){
    	if (framework.equals("rack-1.1"))
    		framework = "ruby-1.8";
    	else if (framework.equals("wsgi-3.2"))
    		framework = "python-2.6";
    }

    @SuppressWarnings("unchecked")
    @Override
    public AbstractCloudComputer<OpenShiftSlave> createComputer() {
        return new OpenShiftComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException,
            InterruptedException {
        LOGGER.info("Terminating slave " + name + " (uuid: " + uuid + ")");

        if (getComputer() != null && getComputer().getChannel() != null) {
            LOGGER.info("Closing the SSH channel...");
            getComputer().getChannel().close();
        }

        LOGGER.info("Terminating OpenShift application...");
        terminateApp();
    }

    private void terminateApp() throws IOException {
    	
    	try {
	        IOpenShiftService service = OpenShiftCloud.get().getOpenShiftService();
	        IUser user = OpenShiftCloud.get().getIUser();
	        ICartridge cartridge = user.getCartridgeByName(framework);
	        service.destroyApplication(name, cartridge, user);
    	} catch (Exception e){
    		e.printStackTrace();
    	}
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        public String getDisplayName() {
            return "OpenShift Slave";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    public String getHostName() throws IOException {
        UserInfo userInfo = OpenShiftCloud.get().getUserInfo();
        String namespace = userInfo.getNamespace();
        String domain = userInfo.getRhcDomain();
        StringBuilder sb = new StringBuilder(200);
        sb.append(name);
        sb.append("-");
        sb.append(namespace);
        sb.append(".");
        sb.append(domain);
        return sb.toString();
    }

    public void connect(boolean delayDNS) throws IOException {
        LOGGER.info("Connecting to slave " + name + "...");

        // Force a refresh of the user info to get the application UUID
        UserInfo userInfo = OpenShiftCloud.get().getUserInfo(true);
        ApplicationInfo appInfo = userInfo.getApplicationInfoByName(name);
        
        if (appInfo == null)
        	throw new IOException("Failed to connect to application " + name);
    
        uuid = appInfo.getUuid();

        LOGGER.info("Established UUID = " + uuid);

        // Sleep for 5 seconds for DNS to propagate to minimize cache penalties
        if (delayDNS) {
            try {
                Thread.sleep(5000);
            }
            catch (InterruptedException e) {
                // Ignore
            }
        }

        long startTime = System.currentTimeMillis();
        long currentTime = startTime;
        // Wait until DNS is resolvable
        while (isBuildRunning() && (builderTimeout == -1 || currentTime - startTime < builderTimeout)) {
            try {
            	String hostname = getHostName();
                LOGGER.info("Checking to see if slave DNS for " + hostname + " is resolvable ...");
                InetAddress address = InetAddress.getByName(hostname);
                LOGGER.info("Slave DNS resolved - " + address);
                break;
            }
            catch (UnknownHostException e) {
                LOGGER.info("Slave DNS not propagated yet, retrying...");
                try {
                    Thread.sleep(5000);
                }
                catch (InterruptedException ie) {
                    // Ignore interruptions
                }
                currentTime = System.currentTimeMillis();
            }
        }
        
        if (builderTimeout >= 0 && currentTime - startTime >= builderTimeout){
        	LOGGER.warning("Slave DNS not propagated. Timing out.");
        	throw new IOException("Slave DNS not propagated. Timing out.");
        }
    }
    
    protected boolean isBuildRunning() {	
    	boolean running = true;
		Queue queue = Hudson.getInstance().getQueue();
		if (queue != null){
			Queue.Item[] items = queue.getItems();
			if (items.length == 0)
				running = false;
		}
		
		return running;
		   
	}

    public void provision() throws Exception {
        // Create a new application of the right type
        createApp();

        // Now stop the application to free up resources
        stopApp();

        // Force a connection to establish the UUID
        connect(true);
    }

    private void createApp() throws Exception {
    	
    	try {
	        IOpenShiftService service = OpenShiftCloud.get().getOpenShiftService();
	        IUser user = OpenShiftCloud.get().getIUser();
	     
	        ICartridge cartridge = user.getCartridgeByName(framework);
	     
	        IApplication app = service.createApplication(name, cartridge, user, builderSize);
    	} catch (Exception e){
    		e.printStackTrace();
    		throw e;
    	}
        // Provision the new application in OpenShift
    	

    }

    private void stopApp() throws IOException {
        LOGGER.info("Slave stopping application...");
        
        try {
	        IOpenShiftService service = OpenShiftCloud.get().getOpenShiftService();
	        IUser user = OpenShiftCloud.get().getIUser();
	        ICartridge cartridge = user.getCartridgeByName(framework);
	        IApplication app = service.stopApplication(name, cartridge, user);
    	} catch (Exception e){
    		e.printStackTrace();
    	}
    }

    public String getUuid() {
        return uuid;
    }
}