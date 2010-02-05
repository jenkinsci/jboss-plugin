package hudson.plugins.jboss;

import hudson.model.BuildListener;

import java.util.Properties;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * This utility class contains static methods to help dealing with JBoss Managed Beans. 
 * 
 * @author juliuszb
 *
 */
public class JMXUtils {

	/**
	 * Default constructor.
	 */
	private JMXUtils() {
		// utility class cannot be instantiated
	}

	/**
	 * Gets {@link InitialContet} from given server and port.
	 * 
	 * @param hostName, Name of the server connect to
	 * @param jndiPort, Port number of naming service
	 * @return Obtained InitialContext, or RuntimeException will thrown.
	 */
	public static InitialContext getInitialContext(final String hostName, final int jndiPort) {
		Properties env = new Properties();
		try {
			env.put("java.naming.factory.initial",
					"org.jnp.interfaces.NamingContextFactory");
			env.put("java.naming.factory.url.pkgs",
					"org.jboss.naming:org.jnp.interfaces");
			env.put("java.naming.provider.url", hostName + ":" + jndiPort);
			return new InitialContext(env);
		} catch (NamingException e) {
			throw new RuntimeException(
					"Unable to instantiate naming context: " + e.getMessage(),
					e);
		}
	}

    /**
     * Gets Managed Beans server for given naming context.
     * 
     * @param ctx, {@link InitialContext} used to lookup.
     * @param listener, used only for logging purpose
     * @param timeout, timeout of connection in seconds
     * @return, server connection or exception will thrown if failed
     */
    public static MBeanServerConnection getMBeanServer(
    		final InitialContext ctx, final BuildListener listener, final int timeout) {
    	
		MBeanServerConnection server = null;
		NamingException ne = null;
		int retryWait = 1000;
		for (int i = 0; i < timeout; ++i) {
			try {
				Thread.sleep(retryWait);
				server = (MBeanServerConnection) ctx
						.lookup("jmx/invoker/RMIAdaptor");
				break;
			} catch (NamingException e) {
				ne = e;
			} catch (InterruptedException e) {
				listener.getLogger().println(
						"Thread interrupted while waiting for MBean connection: "
								+ e.getMessage());
				return server;
			}
		}

		if (server == null) {
			throw new RuntimeException(new StringBuilder().append(
					"Unable to get JBoss JMX MBean connection ").append("in ")
					.append(timeout).append(" seconds.").toString(), ne);
		}

		return server;
	}

    /**
     * Checks if Server is up using given MBean server connection.
     * 
     * @param server, given {@link MBeanServerConnection}
     * @return true if server is up, false otherwise
     * @throws Exception, a few types of exception can be thrown.
     */
	public static boolean isServerStarted(MBeanServerConnection server) throws Exception {
		ObjectName serverMBeanName = new ObjectName("jboss.system:type=Server");
		return ((Boolean) server.getAttribute(serverMBeanName, "Started"))
				.booleanValue();
	}

    /**
     * Waits for server status.
     * 
     * If server is not started checks status every second for 'timeout'. 
     * 
     * @param hostName, name of the server connect to
     * @param jndiPort, port number of naming service
     * @param listener, {@BuildListener} for logging purpose
     * @param timeout, how long will we wait for server start
     * @param ignoreErrors, if true any connection problems will be ignored, flase otherwise
     */
	public static boolean checkStatus(
			final String hostName, final int jndiPort,
			final BuildListener listener,
			final int timeout, boolean ignoreErrors) {

		boolean started = false;
		try {
			InitialContext ctx = JMXUtils.getInitialContext(hostName, jndiPort);
	
			MBeanServerConnection server = JMXUtils.getMBeanServer(ctx, listener, timeout);
			
			// Wait until server startup is complete
			long startTime = System.currentTimeMillis();
			while (!started
					&& (System.currentTimeMillis() - startTime < timeout * 1000)) {
				try {
					Thread.sleep(1000);
					started = JMXUtils.isServerStarted(server);
				} catch (Exception e) {
					throw new RuntimeException("Unable to wait: " + e.getMessage(),
						e);
				}
			}
		} catch (RuntimeException e) {
			if (!ignoreErrors) {
				throw e;
			}
		}
		return started;
    }

}
