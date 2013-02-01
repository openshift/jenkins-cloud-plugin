package hudson.plugins.openshift;

import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProvisioner.PlannedNode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jvnet.hudson.test.HudsonTestCase;

import com.openshift.client.IApplication;
import com.openshift.client.ICartridge;
import com.openshift.client.IOpenShiftConnection;
import com.openshift.client.IUser;
import com.openshift.client.OpenShiftException;
import com.openshift.client.configuration.DefaultConfiguration;
import com.openshift.client.configuration.SystemConfiguration;
import com.openshift.client.configuration.UserConfiguration;

public class OpenShiftIntegrationTest extends HudsonTestCase {
    private OpenShiftCloud cloud;
    private String framework;
    private String username;
    private String password;

    public void setUp() throws Exception {
        super.setUp();
        
        username = System.getProperty("RHLOGIN");
        password = System.getProperty("PASSWORD");
        String proxyHost = System.getProperty("proxyHost");
        int proxyPort = -1;
        if (proxyHost != null)
        	proxyPort = Integer.parseInt(System.getProperty("proxyPort"));
        
        // Fake out the Java environment map for testing
        // Add the OPENSHIFT_DATA_DIR to look in the user
        // HOME directory for ~/.ssh/jenkins_id_rsa
        Map<String, String> unomdifiable = System.getenv();
        Class<?> cu = unomdifiable.getClass();
        Field m = cu.getDeclaredField("m");
        m.setAccessible(true);
        Map envMap = (Map<String, String>) m.get(unomdifiable);
        envMap.put("OPENSHIFT_DATA_DIR", System.getenv("HOME"));

        // This server has a copy of slave.jar published in /jnlpJars/slave.jar
        envMap.put("OPENSHIFT_GEAR_DNS", "matt-oncloud.rhcloud.com");

        // Create the new Cloud object
        cloud = new OpenShiftCloud(username, password,
        		System.getProperty("libra_server"), "443", proxyHost, proxyPort, true, 5, 5, "small");

        framework = "jbossas-7";
    }

    public void _testProvisionNoLabel() throws IOException {
        try {
        	Hudson.getInstance().clouds.add(cloud);
        	
            cloud.provision(null, 1);
            fail("Should have thrown an exception");
        }
        catch (UnsupportedOperationException e) {
            // Exception is expected
        }
    }

    public void testProvisionAndLaunch() throws Exception {
    	OpenShiftSlave slave = null;
    	
    	try {
	        Hudson.getInstance().clouds.add(cloud);
	
	        // Terminate any existing nodes
	        for (Node node : Hudson.getInstance().getNodes()) {
	            if (node instanceof AbstractCloudSlave) {
	                System.out.println("Found existing node, terminating...");
	                ((AbstractCloudSlave) node).terminate();
	            }
	        }
	
	        Collection<PlannedNode> nodes = cloud.provision(
	                new LabelAtom(framework), 1);
	   
	        assertNotNull("Slaves null", nodes);
	        assertTrue("Slaves empty", nodes.size() > 0);
	        for (PlannedNode planned : nodes) {
	            slave = (OpenShiftSlave) planned.future.get();
	            assertNotNull(slave.getUuid());
	        }
    	} finally {
    		try {
    			
    			IUser user = OpenShiftCloud.get().getOpenShiftConnection().getUser();
    	      
    	        ICartridge cartridge = getCartridge(OpenShiftCloud.get().getOpenShiftConnection());
    	        user.getDefaultDomain().getApplicationByName("rawbldr").destroy();
        	} catch (Exception e){
        		e.printStackTrace();
        	}
    	}
    }
    
    protected ICartridge getCartridge(IOpenShiftConnection connection) throws OpenShiftException {
    	List<ICartridge> cartridges = connection.getStandaloneCartridges();
    	Iterator<ICartridge> iterator = cartridges.iterator();
    	while (iterator.hasNext()){
    		ICartridge cartridge = iterator.next();
    		if (cartridge.getName().equals(framework))
    			return cartridge;
    	}
    	
    	throw new OpenShiftException("Cartridge for " + framework + " not found");
    }

    /**
     * Cannot be called in tearDown on the Hudson.getInstance() won't be
     * available.
     * 
     * @throws Exception
     */
    public void _testTerminate() throws Exception {
        Hudson.getInstance().clouds.add(cloud);

        // Terminate any created builders
        for (OpenShiftSlave slave : cloud.getSlaves()) {
            System.out.println("Cleaning up slave " + slave.getNodeName());
            slave.terminate();
        }

        assertEquals(0, cloud.getSlaves().size());
    }
}
