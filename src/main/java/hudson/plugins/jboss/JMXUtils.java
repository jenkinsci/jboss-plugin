package hudson.plugins.jboss;

import hudson.model.BuildListener;

import java.util.Properties;
import java.util.Set;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.jboss.system.ServiceMBean;

/**
 * This utility class contains static methods to help dealing with JBoss Managed Beans. 
 * 
 * @author Juliusz Brzostek
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
	 * Gets {@link InitialContext} from given server and port.
	 * 
	 * @param hostName Name of the server connect to
	 * @param jndiPort Port number of naming service
	 * 
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
     * @param ctx {@link InitialContext} used to lookup.
     * @param listener used only for logging purpose
     * @param timeout timeout of connection in seconds
     * 
     * @return server connection or exception will thrown if failed
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
     * @param server given {@link MBeanServerConnection}
     * 
     * @return true if server is up, false otherwise
     * 
     * @throws Exception A few types of exception can be thrown.
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
     * @param hostName name of the server connect to
     * @param jndiPort port number of naming service
     * @param listener {@link BuildListener} for logging purpose
     * @param timeout how long will we wait for server start
     * @param ignoreErrors if true any connection problems will be ignored
     * 
     * @return true if server is up, false otherwise
     */
	public static boolean checkServerStatus(
			final String hostName, final int jndiPort,
			final BuildListener listener,
			final int timeout, boolean ignoreErrors) {

		boolean started = false;
		try {
			InitialContext ctx = getInitialContext(hostName, jndiPort);
	
			MBeanServerConnection server = getMBeanServer(ctx, listener, timeout);
			
			// Wait until server startup is complete
			long startTime = System.currentTimeMillis();
			while (!started
					&& (System.currentTimeMillis() - startTime < timeout * 1000)) {
				try {
					Thread.sleep(1000);
					started = isServerStarted(server);
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

	/**
	 * Checks if given modules have been correctly deployed.
	 * 
     * @param hostName name of the server connect to
     * @param jndiPort port number of naming service
     * @param listener {@link BuildListener} for logging purpose
     * @param timeout how long will we wait for server start
     * 
	 * @return true if gone fine, false if any module have deployment problem
	 */
	public static boolean checkDeploy(final String hostName,
			final int jndiPort, final BuildListener listener,
			final int timeout, final String[] modules) {
		
		listener.getLogger().println("Verification of deplyed modules started");
				
		InitialContext ctx = getInitialContext(hostName, jndiPort);

		MBeanServerConnection server = getMBeanServer(ctx, listener, timeout);
	
		boolean deployed = true;
		for (String moduleName : modules) {
			if (moduleName.endsWith(".ear")) {
				boolean ok = checkEARDeploymentState(listener, server, moduleName);
				listener.getLogger().println(
						String.format("Verifying deployment of the EAR '%s' ... %s",
								moduleName, ok?"SUCCESS":"FAILED"));
				deployed &= ok;
			} else if (moduleName.endsWith("-ejb.jar")) {
				boolean ok = checkEJBDeploymentState(listener, server, moduleName);
				listener.getLogger().println(
						String.format("Verifying deployment of the EJB '%s' ... %s",
								moduleName, ok?"SUCCESS":"FAILED"));
				deployed &= ok;
			} else if (moduleName.endsWith(".war")) {
				boolean ok = checkWARDeploymentState(listener, server, moduleName);
				listener.getLogger().println(
						String.format("Verifying deployment of the WAR '%s' ... %s",
								moduleName, ok?"SUCCESS":"FAILED"));
				deployed &= ok;
			} else {
				listener.error(
						String.format("Unknown type of the module '%s'. Cannot verify deployment.", moduleName));
				deployed = false;
			}
		}

		listener.getLogger().println("Verification finished.");

		return deployed;
	}

	/**
	 * Checks if single WAR is deployed with no problems.
	 * To check other states take a look on {@link ServiceMBean}.
	 * 
	 * @param listener for logging purpose
     * @param server given {@link MBeanServerConnection}
     * @param warName the name of the WAR to be checked
     * 
	 * @return true if started, false otherwise 
	 */
	public static boolean checkWARDeploymentState(
			final BuildListener listener,
			MBeanServerConnection server, String warName) {
		try {
			String objectPattern = String.format("jboss.web.deployment:*,war=%s", warName);
			@SuppressWarnings("unchecked")
			Set<ObjectName> set = server.queryNames(new ObjectName(objectPattern), null);
			if (set == null || set.size() == 0) {
				return false; // no instance
			}
			ObjectName serverMBeanName = set.iterator().next(); // only first
			return ServiceMBean.STARTED == (Integer) server.getAttribute(serverMBeanName, "State");
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Checks if single EAR is deployed with no problems.
	 * To check other states take a look on {@link ServiceMBean}.
	 * 
	 * @param listener for logging purpose
     * @param server given {@link MBeanServerConnection}
     * @param earName the name of the EAR to be checked
     * 
	 * @return true if started, false otherwise 
	 */
	public static boolean checkEARDeploymentState(
			final BuildListener listener,
			MBeanServerConnection server, String earName) {
		try {
			ObjectName serverMBeanName = new ObjectName(
					String.format("jboss.j2ee:service=EARDeployment,url='%s'", earName));
			return ServiceMBean.STARTED == (Integer) server.getAttribute(serverMBeanName, "State");
		} catch (Exception e) {
			return false;
		}
	}
	
	/**
	 * Checks if single EJB module is deployed with no problems.
	 * To check other states take a look on {@link ServiceMBean}.
	 * 
	 * @param listener for logging purpose
     * @param server given {@link MBeanServerConnection}
     * @param ejbName the name of the EJB module to be checked
     * 
	 * @return true if started, false otherwise 
	 */
	public static boolean checkEJBDeploymentState(
			final BuildListener listener,
			MBeanServerConnection server, String ejbName) {
		try {
			ObjectName serverMBeanName = new ObjectName(
					String.format("jboss.j2ee:service=EjbModule,module=%s", ejbName));
			return ServiceMBean.STARTED == (Integer) server.getAttribute(serverMBeanName, "State");
		} catch (Exception e) {
			return false;
		}
	}
}
