package hudson.plugins.openshift;

import com.openshift.client.IApplication;
import com.openshift.client.IDomain;
import com.openshift.client.IOpenShiftConnection;

import java.io.IOException;

public class Util {
    public static IApplication getApplicationFromUuid(String uuid) {
        IOpenShiftConnection connection;
        try {
            connection = OpenShiftCloud.get().getOpenShiftConnection();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for(IDomain domain : connection.getDomains()) {
            for(IApplication app : domain.getApplications()) {
                if(app.getUUID().equals(uuid)) {
                    return app;
                }
            }
        }
        return null;
    }
}
