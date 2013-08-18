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
}
