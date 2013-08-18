package hudson.plugins.openshift;

import java.io.InputStream;
import java.util.Properties;

import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;


@RunWith(Suite.class)
@Suite.SuiteClasses({
	OpenShiftIntegrationTest.class
})

public class JenkinsIntegrationTestSuite {
	
	static {
	    javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier(
	    new javax.net.ssl.HostnameVerifier(){

	        public boolean verify(String hostname,
	                javax.net.ssl.SSLSession sslSession) {

	            if (System.getProperty("proxiedHost") != null && hostname.contains(System.getProperty("proxiedHost"))) {
	                return true;
	            }
	            return false;
	        }
	    });
	}

	@BeforeClass
	public static void setUp() throws Exception {
		InputStream propsStream = null;

		try {
			propsStream = JenkinsIntegrationTestSuite.class.getClassLoader().getResourceAsStream("integrationTest.properties");
			if (propsStream != null){
				Properties props = new Properties();
			    props.load(propsStream);

			    if (props.get("libra_server") != null)
			    	System.setProperty("libra_server", (String)props.get("libra_server"));

			    if (props.get("proxyHost") != null)
			    	System.setProperty("proxyHost", (String)props.get("proxyHost"));
			    if (props.get("proxyPort") != null)
			    	System.setProperty("proxyPort", (String)props.get("proxyPort"));
			    if (props.get("proxySet") != null)
			    	System.setProperty("proxySet", (String)props.get("proxySet"));
			    if (props.get("proxiedHost") != null)
			    	System.setProperty("proxiedHost", (String)props.get("proxiedHost"));

			    if (props.get("RHLOGIN") != null)
			    	System.setProperty("RHLOGIN", (String)props.get("RHLOGIN"));
			    if (props.get("PASSWORD") != null)
			    	System.setProperty("PASSWORD", (String)props.get("PASSWORD"));
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		} finally {
			if (propsStream != null)
				propsStream.close();
		}
	}

}
